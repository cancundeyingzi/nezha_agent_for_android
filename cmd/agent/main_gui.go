//go:build gui || android

package main

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"

	"github.com/hashicorp/go-uuid"

	"github.com/nezhahq/agent/model"
	"github.com/nezhahq/agent/pkg/logger"
	"github.com/nezhahq/agent/pkg/monitor"
)

var (
	isAgentRunning bool               // Agent 是否正在运行
	agentCancel    context.CancelFunc // Agent 上下文取消函数
)

// GUILogger 实现服务日志系统并将其实时输出于 Fyne 列表组件中
type GUILogger struct {
	writer func(msg string)
}

func (l *GUILogger) Error(v ...interface{}) error {
	l.writer(fmt.Sprint(v...))
	return nil
}
func (l *GUILogger) Warning(v ...interface{}) error {
	l.writer(fmt.Sprint(v...))
	return nil
}
func (l *GUILogger) Info(v ...interface{}) error {
	l.writer(fmt.Sprint(v...))
	return nil
}
func (l *GUILogger) Errorf(format string, a ...interface{}) error {
	l.writer(fmt.Sprintf(format, a...))
	return nil
}
func (l *GUILogger) Warningf(format string, a ...interface{}) error {
	l.writer(fmt.Sprintf(format, a...))
	return nil
}
func (l *GUILogger) Infof(format string, a ...interface{}) error {
	l.writer(fmt.Sprintf(format, a...))
	return nil
}

func main() {
	a := app.NewWithID("com.nezhahq.agent")
	w := a.NewWindow("Nezha Agent")

	// Preferences（从持久化存储中读取上次保存的配置）
	prefs := a.Preferences()
	savedServer := prefs.StringWithFallback("server", "")
	savedSecret := prefs.StringWithFallback("secret", "")
	savedTLS := prefs.BoolWithFallback("tls", false)

	// UI Elements（构建用户界面元素）
	serverEntry := widget.NewEntry()
	serverEntry.SetText(savedServer)
	serverEntry.SetPlaceHolder("IP:Port or Domain:Port")

	secretEntry := widget.NewEntry()
	secretEntry.SetText(savedSecret)
	secretEntry.SetPlaceHolder("UUID (Client Secret)")

	tlsCheck := widget.NewCheck("Enable TLS", nil)
	tlsCheck.SetChecked(savedTLS)

	scriptEntry := widget.NewMultiLineEntry()
	scriptEntry.SetPlaceHolder("Paste curl installation script...")
	scriptEntry.Wrapping = fyne.TextWrapWord

	statusLabel := widget.NewLabel("Status: Stopped")

	// 实时日志系统列表视图配置
	var logLines []string
	var logMutex sync.Mutex

	logList := widget.NewList(
		func() int {
			logMutex.Lock()
			defer logMutex.Unlock()
			return len(logLines)
		},
		func() fyne.CanvasObject {
			// Wrapping 需要使用 RichText 或支持换行的 Label，设置好 Wrapping
			lbl := widget.NewLabel("Template log line")
			lbl.Wrapping = fyne.TextWrapWord
			return lbl
		},
		func(i widget.ListItemID, o fyne.CanvasObject) {
			logMutex.Lock()
			defer logMutex.Unlock()
			if int(i) < len(logLines) {
				o.(*widget.Label).SetText(logLines[i])
			}
		},
	)

	appendLog := func(msg string) {
		logMutex.Lock()
		// 加前缀
		nowTxt := time.Now().Format("15:04:05")
		logLines = append(logLines, fmt.Sprintf("[%s] %s", nowTxt, msg))
		// 为了防止吃内存，最多保留 100 条
		if len(logLines) > 100 {
			logLines = logLines[len(logLines)-100:]
		}
		logMutex.Unlock()

		// 异步刷新 UI 并卷动到底部
		if logList != nil {
			logList.Refresh()
			logList.ScrollToBottom()
		}
	}

	guiLogObj := &GUILogger{writer: appendLog}
	// 将默认 Logger 初始化为指向 Fyne GUI
	logger.InitDefaultLogger(true, guiLogObj)

	var startStopBtn *widget.Button
	startStopBtn = widget.NewButton("Start Agent", func() {
		if isAgentRunning {
			// 停止 Agent：取消上下文
			if agentCancel != nil {
				agentCancel()
				agentCancel = nil
			}
			isAgentRunning = false
			startStopBtn.SetText("Start Agent")
			statusLabel.SetText("Status: Stopped")
			appendLog("Agent stopped by user action.")
		} else {
			// 输入校验：Server 和 Client Secret 不能为空
			if serverEntry.Text == "" || secretEntry.Text == "" {
				dialog.ShowError(
					errors.New("Server address and Client Secret are required"),
					w,
				)
				return
			}

			// 保存配置到 Preferences
			prefs.SetString("server", serverEntry.Text)
			prefs.SetString("secret", secretEntry.Text)
			prefs.SetBool("tls", tlsCheck.Checked)

			deviceUUID := prefs.StringWithFallback("device_uuid", "")
			if deviceUUID == "" {
				var err error
				deviceUUID, err = uuid.GenerateUUID()
				if err != nil {
					dialog.ShowError(err, w)
					return
				}
				prefs.SetString("device_uuid", deviceUUID)
			}

			// 构建全局 Agent 配置
			agentConfig = model.AgentConfig{
				Server:                serverEntry.Text,
				ClientSecret:          secretEntry.Text,
				UUID:                  deviceUUID,
				TLS:                   tlsCheck.Checked,
				DisableCommandExecute: true, // 移动端默认禁用命令执行
				DisableAutoUpdate:     true, // Android 端禁用自动更新
				DisableForceUpdate:    true, // Android 端禁用强制更新
				DisableNat:            true, // 移动端默认禁用 NAT 穿透
				ReportDelay:           3,
				IPReportPeriod:        1800,
				SkipConnectionCount:   true, // Android 上连接数统计可能受权限限制
				SkipProcsCount:        true, // Android 上进程数统计可能受权限限制
			}

			setEnv()
			monitor.InitConfig(&agentConfig)
			initialized = false

			// 启动 Agent（携带可取消的上下文）
			ctx, cancel := context.WithCancel(context.Background())
			agentCancel = cancel
			go func() {
				// 保护 Agent 运行时的 panic，防止闪退
				defer func() {
					if r := recover(); r != nil {
						errMsg := fmt.Sprintf("Crashed: %v", r)
						statusLabel.SetText("Status: " + errMsg)
						appendLog(errMsg)
						isAgentRunning = false
						startStopBtn.SetText("Start Agent")
					}
				}()
				appendLog(fmt.Sprintf("Starting connection to %s...", agentConfig.Server))
				run(ctx)
			}()

			isAgentRunning = true
			startStopBtn.SetText("Stop Agent")
			statusLabel.SetText("Status: Running")
		}
	})

	// "从脚本自动填充"按钮
	parseBtn := widget.NewButton("Auto Fill from Script", func() {
		script := scriptEntry.Text
		if script == "" {
			dialog.ShowInformation("Empty", "Please paste the curl script first.", w)
			return
		}

		reServer := regexp.MustCompile(`NZ_SERVER=([\w\.:-]+)`)
		reSecret := regexp.MustCompile(`NZ_CLIENT_SECRET=([\w\-]+)`)
		reTLS := regexp.MustCompile(`NZ_TLS=(true|false)`)

		mServer := reServer.FindStringSubmatch(script)
		if len(mServer) > 1 {
			serverEntry.SetText(mServer[1])
		}

		mSecret := reSecret.FindStringSubmatch(script)
		if len(mSecret) > 1 {
			secretEntry.SetText(mSecret[1])
		}

		mTLS := reTLS.FindStringSubmatch(script)
		if len(mTLS) > 1 {
			tlsCheck.SetChecked(strings.ToLower(mTLS[1]) == "true")
		}

		dialog.ShowInformation("Success", "Fields populated from script", w)
	})

	// 构建界面布局
	configContainer := container.NewVBox(
		widget.NewLabelWithStyle("One-Click Setup", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		scriptEntry,
		parseBtn,
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Manual Configuration", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		widget.NewLabel("Server:"),
		serverEntry,
		widget.NewLabel("Client Secret (UUID):"),
		secretEntry,
		tlsCheck,
	)

	// 日志区域（允许滚动占据其余空间）
	logPanel := container.NewVBox(
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Runtime Logs", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
	)

	logsScroll := container.NewBorder(nil, nil, nil, nil, logList)

	content := container.NewBorder(
		configContainer, // 顶部表单
		container.NewVBox(statusLabel, startStopBtn), // 底部按钮
		nil, // 左边
		nil, // 右边
		container.NewBorder(logPanel, nil, nil, nil, logsScroll), // 中部主要为日志
	)

	w.SetContent(container.NewPadded(content))
	w.Resize(fyne.NewSize(400, 750))
	w.ShowAndRun()
}
