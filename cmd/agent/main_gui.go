//go:build gui || android

package main

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"

	"github.com/hashicorp/go-uuid"

	"github.com/nezhahq/agent/model"
	"github.com/nezhahq/agent/pkg/monitor"
)

var (
	isAgentRunning bool               // Agent 是否正在运行
	agentCancel    context.CancelFunc // Agent 上下文取消函数
)

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
	scriptEntry.SetPlaceHolder("Paste curl installation script here...")
	scriptEntry.Wrapping = fyne.TextWrapWord

	statusLabel := widget.NewLabel("Status: Stopped")

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

			// 生成或复用设备 UUID
			// 修复 Bug: 之前直接使用 secretEntry.Text 作为 UUID，
			// 但 ValidateConfig 要求 UUID 必须为合法 UUID 格式（uuid.ParseUUID），
			// 而 Client Secret 不一定是合法 UUID，会导致 Agent 启动失败。
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
				DisableCommandExecute: true, // 移动端默认禁用命令执行（安全考虑）
				DisableAutoUpdate:     true, // Android 端禁用自动更新（避免 os.Exit 崩溃）
				DisableForceUpdate:    true, // Android 端禁用强制更新
				DisableNat:            true, // 移动端默认禁用 NAT 穿透
				ReportDelay:           3,    // 设置默认上报间隔（秒），避免 ValidateConfig 校验失败
				IPReportPeriod:        1800, // 默认 IP 上报周期（秒）
				SkipConnectionCount:   true, // Android 上连接数统计可能受权限限制
				SkipProcsCount:        true, // Android 上进程数统计可能受权限限制
			}

			// 在移动端由于安全性限制以及 SElinux/seccomp 的影响，
			// 不应该尝试使用 exec.Command 执行外部命令（如 "su"），因为这可能导致 SIGSYS 从而让进程强制崩溃。
			// 移动端始终禁用 Command Execute，即使以 root 运行也不应在此执行外部探测。

			// 预运行环境初始化
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
						statusLabel.SetText(fmt.Sprintf("Status: Crashed (%v)", r))
						isAgentRunning = false
						startStopBtn.SetText("Start Agent")
					}
				}()
				run(ctx)
			}()

			isAgentRunning = true
			startStopBtn.SetText("Stop Agent")
			statusLabel.SetText("Status: Running")
		}
	})

	// "从脚本自动填充"按钮：解析 curl 安装脚本中的参数
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
	form := container.NewVBox(
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
		layout.NewSpacer(),
		statusLabel,
		startStopBtn,
	)

	w.SetContent(container.NewPadded(form))
	w.Resize(fyne.NewSize(400, 600))
	w.ShowAndRun()
}
