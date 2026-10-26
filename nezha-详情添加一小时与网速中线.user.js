// ==UserScript==
// @name         Nezha Detail Enhancer: 1 小时 + 网速中线 + 在线月卡
// @namespace    https://nz.ccdyz.top/
// @version      0.5.0
// @description  为 Nezha 详情页增加“1 小时”伪历史视图、网速图中间刻度线，并在“在线”页增加 1 年 / 3 年 / 10 年周期（大周期按月汇总）。
// @match        https://nz.ccdyz.top/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const HOUR_MS = 60 * 60 * 1000;
  // 在“1 小时”请求中保留完整 1d 原始响应：切回“1 天”无需再次等待网络。
  const DAY_CACHE_TTL_MS = 5 * 60 * 1000;
  const DAY_CACHE_CAP = 40;
  const DAY_MS = 24 * 60 * 60 * 1000;
  const ENHANCE_ATTR = "data-nezha-detail-enhancer";
  const HOUR_TAB_ATTR = "data-nezha-hour-tab";
  const MIDLINE_ATTR = "data-nezha-net-midline";
  const MIDLABEL_ATTR = "data-nezha-net-midlabel";
  const ONLINE_DAYS_ATTR = "data-nezha-online-days";
  const ONLINE_ACTIVE_ATTR = "data-nezha-online-active";
  const ONLINE_CARRIER_ATTR = "data-nezha-online-carrier";
  const ONLINE_LOCKED_ATTR = "data-nezha-online-locked";
  const MONTH_GRID_ATTR = "data-nezha-online-month-grid";
  const NATIVE_PERIOD_LABELS = ["实时", "1 天", "7 天", "30 天"];
  const HOUR_LABEL = "1 小时";

  // 在线页原生只有 1 / 7 / 30 天；这里追加长周期，单位仍是“天”。
  const ONLINE_NATIVE_LABELS = { "1 天": 1, "7 天": 7, "30 天": 30 };
  const ONLINE_OPTIONS = [
    { days: 365, label: "1 年" },
    { days: 1095, label: "3 年" },
    { days: 3650, label: "10 年" }
  ];

  // 超过该天数改用“月卡”展示，并把交给 React 的明细截断，避免渲染上千个日卡。
  const ONLINE_MONTH_THRESHOLD = 60;
  const ONLINE_DETAIL_DAYS = 30;

  const DEBUG = false;

  const state = {
    hourMode: false,
    suppressNativeReset: false,
    // 历史图切换：序列号用于取消尚未完成的旧切换
    periodSwitchId: 0,
    periodTarget: null,
    periodServerId: null,
    syntheticPeriodClick: false,
    oneDayMetricsStarted: 0,
    oneDayMetricsDelivered: 0,
    dayRawCache: new Map(),
    // 与 TanStack Query 的 1d 缓存对应的完整原始时间序列（不带一小时裁剪）。
    dayQueryFullPoints: new Map(),
    metricQueryClient: null,
    dayRestoreUntil: 0,
    enhanceFrame: 0,
    clickListenerInstalled: false,
    observerStarted: false,
    fetchPatched: false,

    // 在线页自定义周期（365 / 1095 / 3650），null 表示使用原生周期
    onlineDays: null,
    // 为触发 React 重新请求而点击的原生 tab，只能是 1 或 7
    onlineCarrier: null,
    onlineMaxDays: null,
    onlineLocked: false,
    onlineServerId: null,
    onlineData: null,
    monthRenderedFor: 0,
    syntheticClick: false
  };

  const log = (...args) => {
    if (DEBUG) console.log("[Nezha Detail Enhancer]", ...args);
  };

  const warn = (...args) => {
    console.warn("[Nezha Detail Enhancer]", ...args);
  };

  const normalizeText = (value) => (value || "").replace(/\s+/g, " ").trim();
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const isFiniteNumber = (value) => Number.isFinite(Number(value));

  function patchFetch() {
    if (state.fetchPatched || window.__nezhaDetailEnhancerFetchPatched) return;
    if (typeof window.fetch !== "function") return;

    const originalFetch = window.fetch.bind(window);

    window.fetch = async function enhancedFetch(input, init) {
      // 在线历史：自定义长周期需要改写 days 参数（后端上限 3650，超过后台保留天数会被裁剪）
      const onlineUrl = resolveOnlineHistoryUrl(input);
      const metricsUrl = getRequestUrl(input);
      const isOneDayMetrics = metricsUrl && shouldFilterMetricsResponse(metricsUrl) &&
        (!init || !init.method || String(init.method).toUpperCase() === "GET") &&
        (!input || !input.method || String(input.method).toUpperCase() === "GET");
      const metricsKey = isOneDayMetrics ? metricsCacheKey(metricsUrl) : "";
      if (isOneDayMetrics) {
        state.oneDayMetricsStarted++;

        // “一小时”显示的是裁剪后的 1d 数据，但完整数据已经在内存中。
        // 切回“一天”时直接给 React 完整响应，避免因网络 / TSDB 延迟仍显示一小时。
        if (!state.hourMode && (state.periodTarget === "day" || Date.now() < state.dayRestoreUntil)) {
          const cached = readDayMetricsCache(metricsKey);
          if (cached) {
            state.oneDayMetricsDelivered++;
            log("instant day cache hit", metricsKey);
            return cachedResponse(cached);
          }
        }
      }
      if (onlineUrl && state.onlineDays) {
        const targetId = getServerIdFromUrl(onlineUrl);

        // 已经切到另一台服务器：丢弃上一个节点的自定义周期，按原生请求走
        if (
          targetId !== null &&
          state.onlineServerId !== null &&
          targetId !== state.onlineServerId
        ) {
          resetOnlineState();
          return originalFetch(input, init);
        }

        try {
          const rewritten = new URL(onlineUrl.toString());
          rewritten.searchParams.set("days", String(state.onlineDays));
          const response = await originalFetch(rewritten.toString(), init);
          return await buildOnlineHistoryResponse(response);
        } catch (error) {
          warn("获取自定义在线周期失败，已回退原始请求。", error);
          return originalFetch(input, init);
        }
      }

      const response = await originalFetch(input, init);

      try {
        if (onlineUrl) {
          rememberOnlineServerId(onlineUrl);
          return await buildOnlineHistoryResponse(response);
        }

        if (!isOneDayMetrics || !response.ok) return response;

        // 无论 1 小时 / 1 天，都先缓存完整数据，绝不保存过滤后的响应。
        // 过滤要以响应交付时的模式为准，防止在途旧请求覆盖新模式。
        const rawPayload = await response.clone().json();
        cacheFullDayMetrics(metricsKey, rawPayload, response);
        state.oneDayMetricsDelivered++;
        if (!state.hourMode) return response;

        return await buildFilteredHourResponse(response, rawPayload);
      } catch (error) {
        warn("处理 1d metrics 响应失败，已回退原始响应。", error);
        return response;
      }
    };

    Object.defineProperty(window, "__nezhaDetailEnhancerFetchPatched", {
      value: true,
      configurable: false,
      enumerable: false,
      writable: false
    });

    state.fetchPatched = true;
    log("fetch patched");
  }

  function getRequestUrl(input) {
    try {
      const rawUrl =
        typeof input === "string"
          ? input
          : input && typeof input === "object" && "url" in input
            ? input.url
            : String(input || "");

      if (!rawUrl) return null;
      return new URL(rawUrl, window.location.href);
    } catch {
      return null;
    }
  }

  function shouldFilterMetricsResponse(url) {
    return (
      url.origin === window.location.origin &&
      /^\/api\/v1\/server\/\d+\/metrics$/.test(url.pathname) &&
      url.searchParams.get("period") === "1d"
    );
  }

  function resolveOnlineHistoryUrl(input) {
    const url = getRequestUrl(input);
    if (!url || url.origin !== window.location.origin) return null;
    if (!/^\/api\/v1\/server\/\d+\/online-history$/.test(url.pathname)) return null;

    const currentId = getServerIdFromPath();
    const targetId = getServerIdFromUrl(url);
    if (currentId !== null && targetId !== null && currentId !== targetId) return null;

    return url;
  }

  function getServerIdFromUrl(url) {
    const match = String(url && url.pathname).match(/^\/api\/v1\/server\/(\d+)\//);
    return match ? Number(match[1]) : null;
  }

  function getServerIdFromPath() {
    const match = String(window.location.pathname).match(/^\/server\/(\d+)/);
    return match ? Number(match[1]) : null;
  }

  function rememberOnlineServerId(url) {
    const id = getServerIdFromUrl(url);
    if (id !== null) state.onlineServerId = id;
  }

  function metricsCacheKey(url) {
    const canonical = new URL(url.toString());
    canonical.searchParams.sort();
    return canonical.pathname + "?" + canonical.searchParams.toString();
  }

  function cacheFullDayMetrics(key, payload, response) {
    if (!key || !payload || !payload.data || !Array.isArray(payload.data.data_points)) return;
    const headers = new Headers(response.headers);
    headers.set("content-type", "application/json; charset=utf-8");
    headers.delete("content-length");
    headers.delete("content-encoding");
    // 与原响应隔离，之后只按模式生成新的响应，不允许修改缓存本身。
    const item = {
      body: JSON.stringify(payload),
      headers: [...headers.entries()],
      status: response.status,
      statusText: response.statusText,
      savedAt: Date.now()
    };
    state.dayRawCache.delete(key);
    state.dayRawCache.set(key, item);
    state.dayQueryFullPoints.set(key, payload.data.data_points);
    while (state.dayRawCache.size > DAY_CACHE_CAP) {
      const oldestKey = state.dayRawCache.keys().next().value;
      state.dayRawCache.delete(oldestKey);
      state.dayQueryFullPoints.delete(oldestKey);
    }
  }

  function readDayMetricsCache(key) {
    const item = state.dayRawCache.get(key);
    if (!item) return null;
    if (Date.now() - item.savedAt > DAY_CACHE_TTL_MS) {
      state.dayRawCache.delete(key);
      return null;
    }
    return item;
  }

  function cachedResponse(item) {
    return new Response(item.body, {
      status: item.status,
      statusText: item.statusText,
      headers: new Headers(item.headers)
    });
  }

  async function buildFilteredHourResponse(response, sourcePayload) {
    // clone：过滤一小时数据不会破坏原始缓存中的 24 小时数据。
    const data = structuredClone(sourcePayload);
    const points = data && data.data && data.data.data_points;

    if (!Array.isArray(points) || points.length === 0) {
      return response;
    }

    const latestTs = points.reduce((latest, point) => {
      const ts = Number(point && point.ts);
      return Number.isFinite(ts) && ts > latest ? ts : latest;
    }, 0);

    if (!latestTs) return response;

    const cutoff = latestTs - HOUR_MS;
    data.data.data_points = points.filter((point) => Number(point && point.ts) >= cutoff);
    // 解析/序列化可能跨越模式切换，届时必须交付完整原始响应。
    if (!state.hourMode) return response;

    const headers = new Headers(response.headers);
    headers.set("content-type", "application/json; charset=utf-8");
    headers.delete("content-length");
    headers.delete("content-encoding");

    log("filtered metrics response", {
      before: points.length,
      after: data.data.data_points.length
    });

    return new Response(JSON.stringify(data), {
      status: response.status,
      statusText: response.statusText,
      headers: headers
    });
  }

  /**
   * 在线历史的响应处理：
   * 1) 记录完整 daily（月卡要按自然月聚合，必须用全量数据）；
   * 2) 大周期把 daily / periods 截断到最近 30 天再交给 React，避免上千个日卡；
   * 3) 记录 max_days，后台保留天数不足时把按钮标灰。
   */
  async function buildOnlineHistoryResponse(response) {
    const payload = await response.clone().json().catch(() => null);

    if (!payload) return response;

    if (payload.error) {
      const message = String(payload.error);
      if (message.includes("only 1d") || /unauthorized/i.test(message)) {
        state.onlineLocked = true;
        state.onlineDays = null;
        state.onlineCarrier = null;
      }
      scheduleEnhance();
      return response;
    }

    const data = payload.data;
    if (!data || !Array.isArray(data.daily)) return response;

    const days = Number(data.days) || 0;
    const maxDays = Number(data.max_days);

    // 后台把窗口裁剪到 max_days：以它为准，超出范围的按钮标灰并说明原因
    if (Number.isFinite(maxDays) && maxDays > 0) {
      state.onlineMaxDays = maxDays;
    } else if (days > 0) {
      state.onlineMaxDays = Math.max(days, state.onlineMaxDays || 0);
    }
    state.onlineLocked = false;

    const windowStart = Date.parse(data.window_start);
    const windowEnd = Date.parse(data.window_end);

    state.onlineData = {
      days: days,
      maxDays: state.onlineMaxDays,
      windowStart: Number.isFinite(windowStart) ? windowStart : 0,
      windowEnd: Number.isFinite(windowEnd) ? windowEnd : 0,
      tzOffset: Number(data.tz_offset) || 0,
      daily: data.daily.map((item) => ({
        date: String((item && item.date) || ""),
        seconds: Number(item && item.online_seconds) || 0
      })),
      monthKey: Date.now()
    };
    state.monthRenderedFor = 0;

    if (days > ONLINE_MONTH_THRESHOLD) {
      data.daily = data.daily.slice(-ONLINE_DETAIL_DAYS);

      if (Array.isArray(data.periods) && Number.isFinite(windowEnd)) {
        const cutoff = windowEnd - ONLINE_DETAIL_DAYS * DAY_MS;
        data.periods = data.periods.filter((period) => {
          const end = Date.parse((period && (period.end || period.start)) || "");
          return Number.isFinite(end) && end >= cutoff;
        });
      }
    }

    const headers = new Headers(response.headers);
    headers.set("content-type", "application/json; charset=utf-8");
    headers.delete("content-length");
    headers.delete("content-encoding");

    scheduleEnhance();

    return new Response(JSON.stringify(payload), {
      status: response.status,
      statusText: response.statusText,
      headers: headers
    });
  }

  function ensureStyles() {
    if (document.getElementById("nezha-detail-enhancer-style")) return;

    const style = document.createElement("style");
    style.id = "nezha-detail-enhancer-style";
    style.textContent =
      "\n[" + HOUR_TAB_ATTR + "] {\n" +
      "  user-select: none;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"true\"] {\n" +
      "  color: hsl(var(--foreground)) !important;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"false\"],\n" +
      ".nezha-hour-native-muted {\n" +
      "  color: hsl(var(--muted-foreground)) !important;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"false\"]:hover {\n" +
      "  color: hsl(var(--foreground)) !important;\n" +
      "}\n" +
      "\n[" + ONLINE_DAYS_ATTR + "] {\n" +
      "  user-select: none;\n" +
      "}\n" +
      "\n[" + ONLINE_DAYS_ATTR + "][" + ONLINE_ACTIVE_ATTR + "=\"true\"] {\n" +
      "  color: hsl(var(--foreground)) !important;\n" +
      "}\n" +
      "\n[" + ONLINE_CARRIER_ATTR + "=\"true\"] {\n" +
      "  color: hsl(var(--muted-foreground)) !important;\n" +
      "}\n" +
      "\n[" + ONLINE_DAYS_ATTR + "][" + ONLINE_LOCKED_ATTR + "=\"true\"] {\n" +
      "  cursor: not-allowed !important;\n" +
      "  opacity: 0.4;\n" +
      "  filter: grayscale(1);\n" +
      "}\n";

    (document.head || document.documentElement).appendChild(style);
  }

  function installClickListener() {
    if (state.clickListenerInstalled) return;

    document.addEventListener(
      "click",
      (event) => {
        const target = event.target;
        if (!target || !target.closest) return;

        // 1) 在线页：脚本注入的 1 年 / 3 年 / 10 年
        const customDay = target.closest("[" + ONLINE_DAYS_ATTR + "]");
        if (customDay) {
          event.preventDefault();
          event.stopPropagation();

          if (customDay.getAttribute(ONLINE_LOCKED_ATTR) === "true") return;

          activateOnlineCustomDays(Number(customDay.getAttribute(ONLINE_DAYS_ATTR)));
          return;
        }

        // 2) 在线页：原生 1 / 7 / 30 天
        const onlineNativeDay = target.closest(
          '[data-testid="days-selector"] > div:not([' + ONLINE_DAYS_ATTR + "])"
        );
        if (onlineNativeDay) {
          if (state.syntheticClick) return;
          handleNativeOnlineDayClick(event, onlineNativeDay);
          return;
        }

        const periodButton =
          target.closest && target.closest("div[class*='rounded-full']")
            ? target.closest("div[class*='rounded-full']")
            : null;

        if (!periodButton) return;

        if (periodButton.hasAttribute(HOUR_TAB_ATTR)) {
          event.preventDefault();
          event.stopPropagation();
          activateHourPeriod();
          return;
        }

        const label = normalizeText(periodButton.textContent);
        if (NATIVE_PERIOD_LABELS.includes(label)) {
          // 自己发送的原生点击由切换流程控制，不能反过来取消自己。
          if (state.syntheticPeriodClick || state.suppressNativeReset) return;
          if (isDisabledPeriodButton(periodButton)) return;

          if (label === "1 天" && (state.hourMode || state.periodTarget === "hour")) {
            event.preventDefault();
            event.stopPropagation();
            activateNativeDayPeriod();
            return;
          }

          // 用户手动改选其它原生周期时，取消所有在途的异步桥接动作。
          state.periodSwitchId++;
          state.periodTarget = null;
          if (state.hourMode) {
            // 离开一小时视图时，先修正 1d 的 Query 缓存。
            restoreFullDayQueryCache();
          }
          state.hourMode = false;
          scheduleEnhance();
          scheduleDelayedEnhance();
        }
      },
      true
    );

    state.clickListenerInstalled = true;
  }

  /* ------------------------------------------------------------------ *
   * 在线页长周期
   * ------------------------------------------------------------------ */

  function findOnlineDaysSelector() {
    const byTestId = document.querySelector('[data-testid="days-selector"]');
    if (byTestId) return byTestId;

    const candidates = Array.from(document.querySelectorAll("div[class*='rounded-full']"));
    return (
      candidates.find((element) => {
        const className = String(element.getAttribute("class") || "");
        if (!className.includes("bg-muted")) return false;

        const text = normalizeText(element.textContent);
        return (
          text.includes("1 天") &&
          text.includes("7 天") &&
          text.includes("30 天") &&
          !text.includes("实时")
        );
      }) || null
    );
  }

  function findOnlineNativeOptions(container) {
    const map = new Map();
    if (!container) return map;

    Array.from(container.children).forEach((element) => {
      if (!element || element.nodeType !== 1) return;
      if (element.hasAttribute(ONLINE_DAYS_ATTR)) return;

      const days = ONLINE_NATIVE_LABELS[normalizeText(element.textContent)];
      if (days) map.set(days, element);
    });

    return map;
  }

  function isDisabledPeriodButton(button) {
    const className = String(button.getAttribute("class") || "");
    return className.includes("cursor-not-allowed") || className.includes("opacity-40");
  }

  function findActiveOnlineNativeDays(container) {
    const natives = findOnlineNativeOptions(container);

    for (const [days, button] of natives) {
      if (button.classList.contains("text-foreground")) return days;
    }

    return null;
  }

  function ensureOnlineDayButtons() {
    const container = findOnlineDaysSelector();
    if (!container) return;

    const natives = findOnlineNativeOptions(container);
    const template = natives.get(30) || natives.get(7) || natives.get(1);
    if (!template) return;

    ONLINE_OPTIONS.forEach((option) => {
      let button = container.querySelector(
        "[" + ONLINE_DAYS_ATTR + '="' + option.days + '"]'
      );

      if (!button) {
        button = template.cloneNode(true);
        button.setAttribute(ONLINE_DAYS_ATTR, String(option.days));
        button.setAttribute(ENHANCE_ATTR, "online-days");
        button.removeAttribute("aria-disabled");
        button.className = buildOnlineButtonClass(template.getAttribute("class"));

        const label = button.querySelector("p, span");
        if (label) {
          label.textContent = option.label;
        } else {
          button.textContent = option.label;
        }

        container.appendChild(button);
      }

      updateOnlineButtonState(button, option);
    });
  }

  function buildOnlineButtonClass(rawClass) {
    const classes = String(rawClass || "")
      .split(/\s+/)
      .filter(Boolean)
      .filter(
        (token) =>
          ![
            "cursor-not-allowed",
            "opacity-40",
            "grayscale",
            "text-foreground",
            "text-muted-foreground",
            "hover:text-foreground"
          ].includes(token)
      );

    classes.push("cursor-pointer", "text-muted-foreground", "hover:text-foreground");
    return classes.join(" ");
  }

  function updateOnlineButtonState(button, option) {
    const locked =
      state.onlineLocked ||
      (Number.isFinite(state.onlineMaxDays) && option.days > state.onlineMaxDays);

    button.setAttribute(ONLINE_ACTIVE_ATTR, state.onlineDays === option.days ? "true" : "false");
    button.setAttribute(ONLINE_LOCKED_ATTR, locked ? "true" : "false");

    if (locked) {
      button.title = state.onlineLocked
        ? "需要登录后才能查看更长周期"
        : "后台在线统计仅保留 " + state.onlineMaxDays + " 天";
    } else {
      button.title = "查看最近 " + option.days + " 天";
    }
  }

  function activateOnlineCustomDays(days) {
    const option = ONLINE_OPTIONS.find((item) => item.days === days);
    if (!option) return;

    const container = findOnlineDaysSelector();
    if (!container) return;

    const natives = findOnlineNativeOptions(container);
    const current = findActiveOnlineNativeDays(container);

    // 载体必须是“未被锁定”的原生项，否则 React 不会 setState，也就不会发请求
    const carrier = [1, 7, 30].find(
      (candidate) =>
        candidate !== current &&
        natives.has(candidate) &&
        !isDisabledPeriodButton(natives.get(candidate))
    );

    if (!carrier) {
      state.onlineLocked = true;
      scheduleEnhance();
      return;
    }

    state.onlineDays = days;
    state.onlineCarrier = carrier;

    applyOnlineDaysVisual();
    clickOnlineNativeDay(carrier);

    scheduleEnhance();
    scheduleDelayedEnhance();
  }

  function clickOnlineNativeDay(days) {
    const container = findOnlineDaysSelector();
    if (!container) return false;

    const button = findOnlineNativeOptions(container).get(days);
    if (!button) return false;

    state.suppressNativeReset = true;
    state.syntheticClick = true;

    button.dispatchEvent(
      new MouseEvent("click", {
        bubbles: true,
        cancelable: true,
        view: window
      })
    );

    state.syntheticClick = false;

    window.setTimeout(() => {
      state.suppressNativeReset = false;
    }, 0);

    return true;
  }

  function handleNativeOnlineDayClick(event, button) {
    const days = ONLINE_NATIVE_LABELS[normalizeText(button.textContent)];
    if (!days) return;

    if (!state.onlineDays) return;

    const carrier = state.onlineCarrier;
    state.onlineDays = null;
    state.onlineCarrier = null;
    applyOnlineDaysVisual();

    // 点的正是“载体”时，React 的 selectedDays 没变，不会重新请求，
    // 必须借另一个值把请求逼出来，再切回目标值。
    if (days === carrier) {
      event.preventDefault();
      event.stopPropagation();
      forceOnlineNativeDay(days);
    }
  }

  async function forceOnlineNativeDay(target) {
    const bridge = target === 1 ? 7 : 1;

    if (!clickOnlineNativeDay(bridge)) return;

    await sleep(90);
    clickOnlineNativeDay(target);
  }

  function applyOnlineDaysVisual() {
    const container = findOnlineDaysSelector();
    if (!container) return;

    const natives = findOnlineNativeOptions(container);
    natives.forEach((button, days) => {
      if (state.onlineDays && days === state.onlineCarrier) {
        button.setAttribute(ONLINE_CARRIER_ATTR, "true");
      } else {
        button.removeAttribute(ONLINE_CARRIER_ATTR);
      }
    });

    ONLINE_OPTIONS.forEach((option) => {
      const button = container.querySelector(
        "[" + ONLINE_DAYS_ATTR + '="' + option.days + '"]'
      );
      if (button) updateOnlineButtonState(button, option);
    });

    if (!state.onlineDays) return;

    const active = container.querySelector(
      "[" + ONLINE_DAYS_ATTR + '="' + state.onlineDays + '"]'
    );

    if (active) {
      moveActiveIndicator(container, active);
    }
  }

  /* ------------------------------------------------------------------ *
   * 大周期月卡
   * ------------------------------------------------------------------ */

  function findOnlineDailyCard() {
    const titles = Array.from(document.querySelectorAll("p"));

    const title = titles.find((element) => {
      const text = normalizeText(element.textContent);
      return text === "每日在线时长" || text === "每月在线时长";
    });

    if (!title) return null;

    const headerRow = title.parentElement;
    if (!headerRow) return null;

    // 月卡容器会插在标题行与日卡之间，这里必须跳过它继续往后找
    let grid = headerRow.nextElementSibling;
    while (grid) {
      const className = String(grid.getAttribute("class") || "");
      if (grid.hasAttribute(MONTH_GRID_ATTR)) {
        grid = grid.nextElementSibling;
        continue;
      }
      if (className.includes("grid")) break;
      grid = grid.nextElementSibling;
    }

    if (!grid) return null;

    return { title: title, headerRow: headerRow, grid: grid };
  }

  function renderOnlineMonthGrid() {
    const card = findOnlineDailyCard();
    if (!card) return;

    const data = state.onlineData;
    const useMonth = Boolean(data && data.days > ONLINE_MONTH_THRESHOLD);
    const grid = card.grid;

    if (!useMonth) {
      if (grid.style.display === "none") grid.style.display = "";
      if (normalizeText(card.title.textContent) === "每月在线时长") {
        card.title.textContent = "每日在线时长";
      }

      const stale = grid.parentElement
        ? grid.parentElement.querySelector("[" + MONTH_GRID_ATTR + "]")
        : null;
      if (stale) stale.remove();

      state.monthRenderedFor = 0;
      return;
    }

    if (grid.style.display !== "none") grid.style.display = "none";
    if (normalizeText(card.title.textContent) !== "每月在线时长") {
      card.title.textContent = "每月在线时长";
    }

    const existing = grid.parentElement
      ? grid.parentElement.querySelector("[" + MONTH_GRID_ATTR + "]")
      : null;

    if (existing && state.monthRenderedFor === data.monthKey) return;

    const container = existing || document.createElement("div");
    container.setAttribute(MONTH_GRID_ATTR, "true");
    container.className = "mt-3 flex flex-col gap-2";
    container.textContent = "";

    const note = document.createElement("div");
    note.className = "text-[11px] leading-4 text-muted-foreground tabular-nums";
    note.textContent =
      "按自然月汇总 · 实际窗口 " +
      data.days +
      " 天" +
      (Number.isFinite(data.maxDays) ? "（后台保留上限 " + data.maxDays + " 天）" : "") +
      " · 每日明细仅保留最近 " +
      Math.min(ONLINE_DETAIL_DAYS, data.days) +
      " 天";
    container.appendChild(note);

    const rows = buildMonthRows(data);

    if (rows.length === 0) {
      const empty = document.createElement("p");
      empty.className = "py-6 text-center text-sm text-muted-foreground";
      empty.textContent = "暂无在线记录";
      container.appendChild(empty);
    } else {
      const list = document.createElement("div");
      list.className = "grid grid-cols-1 gap-2 md:grid-cols-2";

      rows.forEach((row) => {
        list.appendChild(buildMonthCard(row));
      });

      container.appendChild(list);
    }

    if (!existing && grid.parentElement) {
      grid.parentElement.insertBefore(container, grid);
    }

    state.monthRenderedFor = data.monthKey;
  }

  function buildMonthRows(data) {
    const groups = new Map();

    data.daily.forEach((item) => {
      const key = String(item.date || "").slice(0, 7);
      if (!/^\d{4}-\d{2}$/.test(key)) return;

      const group = groups.get(key) || { key: key, seconds: 0, days: 0 };
      group.seconds += item.seconds;
      group.days += 1;
      groups.set(key, group);
    });

    const windowStartWall = data.windowStart + data.tzOffset * 1000;
    const windowEndWall = data.windowEnd + data.tzOffset * 1000;
    const rows = [];

    groups.forEach((group) => {
      const parts = group.key.split("-");
      const year = Number(parts[0]);
      const month = Number(parts[1]);

      const monthStart = Date.UTC(year, month - 1, 1);
      const monthEnd = Date.UTC(year, month, 1);

      const overlap =
        Math.max(
          0,
          Math.min(monthEnd, windowEndWall) - Math.max(monthStart, windowStartWall)
        ) / 1000;

      rows.push({
        key: group.key,
        seconds: group.seconds,
        days: group.days,
        total: overlap,
        rate: overlap > 0 ? (group.seconds / overlap) * 100 : 0
      });
    });

    return rows.sort((a, b) => (a.key < b.key ? 1 : a.key > b.key ? -1 : 0));
  }

  function buildMonthCard(row) {
    const card = document.createElement("div");
    card.className =
      "flex min-w-0 flex-col gap-2.5 rounded-lg bg-muted/40 px-2.5 py-2 dark:bg-muted/20";

    const head = document.createElement("div");
    head.className = "flex items-baseline justify-between gap-2 text-xs";

    const month = document.createElement("span");
    month.className = "text-foreground tabular-nums";
    month.textContent = row.key;

    const duration = document.createElement("span");
    duration.className = "text-muted-foreground tabular-nums";
    duration.textContent = "在线 " + formatOnlineDuration(row.seconds);

    head.appendChild(month);
    head.appendChild(duration);

    const bar = document.createElement("div");
    bar.className =
      "relative h-2 overflow-hidden rounded-full bg-stone-200/70 dark:bg-stone-700";

    const fill = document.createElement("div");
    fill.className = "h-full rounded-full bg-green-500/90";
    fill.style.width = Math.min(100, Math.max(0, row.rate)).toFixed(1) + "%";

    bar.appendChild(fill);

    const foot = document.createElement("div");
    foot.className =
      "flex justify-between text-[9px] leading-none text-muted-foreground tabular-nums";

    const rate = document.createElement("span");
    rate.textContent = "在线率 " + row.rate.toFixed(1) + "%";

    const coverage = document.createElement("span");
    coverage.textContent = row.days + " 天";

    foot.appendChild(rate);
    foot.appendChild(coverage);

    card.appendChild(head);
    card.appendChild(bar);
    card.appendChild(foot);

    return card;
  }

  function formatOnlineDuration(seconds) {
    const total = Math.max(0, Math.round(Number(seconds) || 0));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);

    if (days > 0) return hours > 0 ? days + " 天 " + hours + " 小时" : days + " 天";
    if (hours > 0) return minutes > 0 ? hours + " 小时 " + minutes + " 分" : hours + " 小时";
    return minutes + " 分";
  }

  /* ------------------------------------------------------------------ *
   * 详情页 1 小时 / 网速中线（原有逻辑）
   * ------------------------------------------------------------------ */

  /*
   * 直接同步 TanStack Query 里的 1d 原始查询缓存。
   *
   * 该前端 useMetricHistory 的 key 为
   * ["server-metrics", serverId, metric, "1d"]，staleTime=30_000。
   * 旧版向 fetch 返回裁剪数据，Query 随之缓存只有一小时的数据；
   * 仅模拟“实时 -> 1 天”并不能保证刷新这份仍然新鲜的查询缓存。
   *
   * 现在原生选中周期保持为 1d，切换 1 小时/1 天时直接对 Query 缓存
   * setQueryData，React Query 通知订阅的图表更新。无需等待 React 桥接及
   * 下一次 TSDB 请求。若目标页没有 QueryClient，才退回旧兼容流程。
   */
  function activateNativeDayPeriod() {
    void switchMetricPeriodFast("day");
  }

  function activateHourPeriod() {
    void switchMetricPeriodFast("hour");
  }

  function isQueryClient(candidate) {
    return candidate && typeof candidate.getQueriesData === "function" &&
      typeof candidate.setQueryData === "function" &&
      typeof candidate.invalidateQueries === "function";
  }

  function findMetricQueryClient() {
    if (isQueryClient(state.metricQueryClient)) return state.metricQueryClient;
    const group = findPeriodGroup();
    if (!group) return null;
    const nodes = [group.buttons.get("1 天"), group.container].filter(Boolean);
    for (const start of nodes) {
      // React 把内部 Fiber 挂在真实 DOM 节点上；QueryClientProvider 是其祖先。
      for (let element = start; element; element = element.parentElement) {
        const reactFiberKeys = Object.keys(element).filter((k) =>
          k.startsWith("__reactFiber$") || k.startsWith("__reactContainer$")
        );
        for (const fiberKey of reactFiberKeys) {
          let fiber = element[fiberKey];
          // __reactContainer$ 指向 HostRoot，可沿 child 找到 provider。
          if (fiberKey.startsWith("__reactContainer$") && fiber && fiber.current) fiber = fiber.current;
          let hops = 0;
          while (fiber && hops++ < 150) {
            for (const props of [fiber.memoizedProps, fiber.pendingProps]) {
              if (!props) continue;
              const candidate = props.client || props.value;
              if (isQueryClient(candidate)) {
                state.metricQueryClient = candidate;
                log("located TanStack QueryClient through React Fiber");
                return candidate;
              }
            }
            fiber = fiber.return;
          }
        }
      }
    }
    return null;
  }

  function metricKeyFor(serverId, metric) {
    const url = new URL(`/api/v1/server/${serverId}/metrics`, window.location.origin);
    url.searchParams.set("metric", metric);
    url.searchParams.set("period", "1d");
    return metricsCacheKey(url);
  }

  function slicePointsForHour(points) {
    if (!Array.isArray(points) || points.length === 0) return points;
    const latestTs = points.reduce((max, item) => {
      const ts = Number(item && item.ts);
      return Number.isFinite(ts) ? Math.max(max, ts) : max;
    }, 0);
    if (!latestTs) return points;
    return points.filter((point) => Number(point && point.ts) >= latestTs - HOUR_MS);
  }

  function metricPointsEqual(left, right) {
    if (left === right) return true;
    if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false;
    for (let i = 0; i < left.length; i++) {
      if (left[i]?.ts !== right[i]?.ts || left[i]?.value !== right[i]?.value) return false;
    }
    return true;
  }

  function patchMetricQueryCache(mode, client = findMetricQueryClient()) {
    const serverId = getServerIdFromPath();
    if (!client || serverId === null) return { available: false, patched: 0, missing: 0 };

    const entries = client.getQueriesData({ queryKey: ["server-metrics", serverId] });
    let patched = 0;
    let missing = 0;
    for (const [queryKey, currentPoints] of entries) {
      if (!Array.isArray(queryKey) || queryKey.length !== 4 || queryKey[3] !== "1d") continue;
      if (!Array.isArray(currentPoints)) continue;

      const cacheKey = metricKeyFor(serverId, queryKey[2]);
      let fullPoints = state.dayQueryFullPoints.get(cacheKey);
      if (!fullPoints && mode === "hour") {
        // 1d 已从 Query 缓存命中、并未发起 fetch：当前 points 仍是完整数据。
        fullPoints = currentPoints;
        state.dayQueryFullPoints.set(cacheKey, fullPoints);
      }
      if (!fullPoints) {
        missing++;
        continue;
      }
      const expected = mode === "hour" ? slicePointsForHour(fullPoints) : fullPoints;
      if (!metricPointsEqual(currentPoints, expected)) {
        client.setQueryData(queryKey, expected);
        patched++;
      }
    }
    return { available: true, patched, missing };
  }

  function restoreFullDayQueryCache() {
    const client = findMetricQueryClient();
    if (!client) return false;
    const result = patchMetricQueryCache("day", client);
    if (result.missing) {
      // 缺少完整原始值时，显式使查询过期，避免继续命中被截断的 30 秒缓存。
      const serverId = getServerIdFromPath();
      void client.invalidateQueries({ queryKey: ["server-metrics", serverId,], refetchType: "active" });
    }
    return true;
  }

  function scheduleCacheReconciliation(id, target) {
    // 处理切换边缘同时结束的旧 fetch / React Query 写入。
    [0, 40, 140, 450, 1000].forEach((ms) => {
      window.setTimeout(() => {
        if (state.periodSwitchId !== id || state.periodServerId !== getServerIdFromPath()) return;
        if (target === "day" && !state.hourMode) patchMetricQueryCache("day");
        if (target === "hour" && state.hourMode) patchMetricQueryCache("hour");
      }, ms);
    });
  }

  async function switchMetricPeriodFast(target) {
    const group = findPeriodGroup();
    if (!group) return;
    const client = findMetricQueryClient();
    if (!client) {
      warn("尚未定位 React QueryClient，改用兼容切换流程。", target);
      return fallbackSwitchHourDayPeriod(target);
    }

    const id = ++state.periodSwitchId;
    const wantHour = target === "hour";
    state.periodTarget = target;
    // 在修改 Query 缓存前设置模式，使后续并发 fetch 返回目标模式。
    state.hourMode = wantHour;
    state.dayRestoreUntil = wantHour ? 0 : Date.now() + 8000;

    const result = patchMetricQueryCache(target, client);
    log("direct query cache period change", { target, ...result });

    // 如果用户本来在实时、7 天或 30 天，则只需点一次原生 1 天。
    // 如果原生早已是 1 天，绝不额外桥接，更不会再等待下一次 fetch。
    const currentNative = findActiveNativeLabel(group);
    if (currentNative !== "1 天") clickNativePeriod("1 天");

    if (!wantHour && result.missing) {
      const serverId = getServerIdFromPath();
      void client.invalidateQueries({ queryKey: ["server-metrics", serverId], refetchType: "active" });
    }
    scheduleCacheReconciliation(id, target);
    scheduleEnhance();
    state.periodTarget = null;
  }

  // 旧版 React 或 QueryClient 不可访问时的保底逻辑，正常不经过这里。
  async function fallbackSwitchHourDayPeriod(target) {
    return switchHourDayPeriod(target);
  }

  function isCurrentPeriodSwitch(id) {
    return state.periodSwitchId === id;
  }

  async function waitForNativePeriod(label, id, timeout = 1800) {
    const started = performance.now();
    while (isCurrentPeriodSwitch(id) && performance.now() - started < timeout) {
      const group = findPeriodGroup();
      if (group && findActiveNativeLabel(group) === label) return true;
      await sleep(25);
    }
    return false;
  }

  async function waitForOneDayMetrics(previous, id, timeout = 1200) {
    const started = performance.now();
    while (isCurrentPeriodSwitch(id) && performance.now() - started < timeout) {
      if (state.oneDayMetricsDelivered > previous) return true;
      await sleep(30);
    }
    return false;
  }

  async function switchHourDayPeriod(target) {
    const id = ++state.periodSwitchId;
    state.periodTarget = target;
    const wantHour = target === "hour";

    // 切回整天时必须从这一刻起停止裁剪 1d 数据。
    if (!wantHour) {
      state.hourMode = false;
      // 有些图表会在主请求结束后分批加载；继续短暂提供完整 1d 缓存。
      state.dayRestoreUntil = Date.now() + 8000;
    } else {
      state.dayRestoreUntil = 0;
    }

    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        if (!isCurrentPeriodSwitch(id)) return;

        let group = findPeriodGroup();
        if (!group) {
          await sleep(100);
          group = findPeriodGroup();
        }
        if (!group || !group.buttons.has("1 天")) return;

        const currentNative = findActiveNativeLabel(group);

        // 如果原生已选 1 天，先切到实时，使 React 接受后续 1d 点击为状态变化。
        // 第二轮无论如何都强制走一次桥接，避免出现缓存数据未被刷新。
        if (currentNative === "1 天" || attempt > 0) {
          if (!clickNativePeriod("实时")) return;
          const bridged = await waitForNativePeriod("实时", id);
          if (!isCurrentPeriodSwitch(id)) return;
          if (!bridged) {
            warn("未确认 React 已切换到实时，停止本次操作以避免状态错乱。");
            return;
          }
        }

        // 给 React 一次绘制机会；这不是用时间猜 React 是否提交。
        await new Promise((resolve) => requestAnimationFrame(() => resolve()));
        if (!isCurrentPeriodSwitch(id)) return;

        // 最终点击 1 天之前设置模式，确保请求回包可按目标模式处理。
        state.hourMode = wantHour;
        const requestedBefore = state.oneDayMetricsDelivered;
        if (!clickNativePeriod("1 天")) return;

        const selected = await waitForNativePeriod("1 天", id);
        if (!isCurrentPeriodSwitch(id)) return;
        const requested = await waitForOneDayMetrics(requestedBefore, id);
        if (!isCurrentPeriodSwitch(id)) return;

        log("period switch", { target, attempt, selected, requested });
        if (selected && requested) return;
      }
      warn("1 小时 / 1 天切换后未观察到完整的 1d 请求；可开启 DEBUG 检查。");
    } catch (error) {
      warn("1 小时 / 1 天切换失败。", error);
    } finally {
      if (isCurrentPeriodSwitch(id)) {
        state.periodTarget = null;
        scheduleEnhance();
        scheduleDelayedEnhance();
      }
    }
  }

  function clickNativePeriod(label) {
    const group = findPeriodGroup();
    const button = group && group.buttons && group.buttons.get(label);
    if (!button || isDisabledPeriodButton(button)) return false;

    // React 的原生 click 是同步派发；只在该事件的捕获阶段屏蔽自拦截。
    const priorSuppress = state.suppressNativeReset;
    state.suppressNativeReset = true;
    state.syntheticPeriodClick = true;
    try {
      button.dispatchEvent(
        new MouseEvent("click", {
          bubbles: true,
          cancelable: true,
          view: window
        })
      );
    } finally {
      state.syntheticPeriodClick = false;
      state.suppressNativeReset = priorSuppress;
    }
    return true;
  }

  function scheduleDelayedEnhance() {
    [60, 180, 450, 900, 1500].forEach((delay) => {
      window.setTimeout(scheduleEnhance, delay);
    });
  }

  function scheduleEnhance() {
    if (state.enhanceFrame) return;

    state.enhanceFrame = window.requestAnimationFrame(() => {
      state.enhanceFrame = 0;

      try {
        enhancePage();
      } catch (error) {
        warn("页面增强失败。", error);
      }
    });
  }

  function enhancePage() {
    ensureStyles();
    syncOnlineContext();
    ensureHourTab();
    patchNetworkMidline();
    ensureOnlineDayButtons();
    applyOnlineDaysVisual();
    renderOnlineMonthGrid();
  }

  function syncOnlineContext() {
    const id = getServerIdFromPath();

    // Next/React 的客户端路由不会重建 userscript：跨服务器时需要清除旧状态。
    if (id !== state.periodServerId) {
      state.periodServerId = id;
      state.periodSwitchId++;
      state.periodTarget = null;
      state.hourMode = false;
      state.dayRawCache.clear();
      state.dayQueryFullPoints.clear();
      state.metricQueryClient = null;
      state.dayRestoreUntil = 0;
    }

    if (id !== state.onlineServerId) {
      state.onlineServerId = id;
      resetOnlineState();
    }
  }

  function resetOnlineState() {
    state.onlineDays = null;
    state.onlineCarrier = null;
    state.onlineData = null;
    state.monthRenderedFor = 0;
    state.onlineLocked = false;
  }

  function findPeriodGroup() {
    const containers = Array.from(document.querySelectorAll("div[class*='rounded-full']"))
      .filter((element) => {
        // 避免误选其它 Tab 内仍留在 DOM 中的隐藏历史周期选择器。
        if (!element.isConnected || element.getClientRects().length === 0) return false;
        const text = normalizeText(element.textContent);
        const className = String(element.getAttribute("class") || "");
        return (
          className.includes("bg-muted") &&
          NATIVE_PERIOD_LABELS.every((label) => text.includes(label))
        );
      })
      .sort((a, b) => normalizeText(a.textContent).length - normalizeText(b.textContent).length);

    for (const container of containers) {
      const buttons = new Map();
      const slots = new Map();

      NATIVE_PERIOD_LABELS.forEach((label) => {
        const matches = Array.from(container.querySelectorAll("div"))
          .filter((element) => !element.hasAttribute(HOUR_TAB_ATTR))
          .filter((element) => normalizeText(element.textContent) === label)
          .filter((element) =>
            String(element.getAttribute("class") || "").includes("rounded-full")
          );

        const button =
          matches.find((element) =>
            String(element.getAttribute("class") || "").includes("px-3")
          ) || matches[0];

        if (button) {
          buttons.set(label, button);
          slots.set(label, getDirectChildWithin(container, button));
        }
      });

      if (NATIVE_PERIOD_LABELS.every((label) => buttons.has(label))) {
        return {
          container: container,
          buttons: buttons,
          slots: slots
        };
      }
    }

    return null;
  }

  function getDirectChildWithin(container, element) {
    let node = element;

    while (node && node.parentElement !== container) {
      node = node.parentElement;
    }

    return node || element;
  }

  function ensureHourTab() {
    const group = findPeriodGroup();
    if (!group) return;

    const hourButton = getOrCreateHourButton(group);
    if (!hourButton) return;

    applyHourVisualState(group, hourButton);
  }

  function getOrCreateHourButton(group) {
    const existing = group.container.querySelector("[" + HOUR_TAB_ATTR + "]");
    if (existing) return existing;

    const dayButton = group.buttons.get("1 天");
    if (!dayButton) return null;

    const hourButton = dayButton.cloneNode(true);
    hourButton.setAttribute(HOUR_TAB_ATTR, "true");
    hourButton.setAttribute(ENHANCE_ATTR, "hour-tab");
    hourButton.setAttribute("data-nezha-hour-active", "false");
    replaceButtonLabel(hourButton, HOUR_LABEL);

    const daySlot = group.slots && group.slots.get("1 天");
    group.container.insertBefore(hourButton, daySlot || dayButton);
    return hourButton;
  }

  function replaceButtonLabel(button, label) {
    const candidates = Array.from(button.querySelectorAll("p, span, div")).reverse();
    const textNode = candidates.find((element) => normalizeText(element.textContent) === "1 天");

    if (textNode) {
      textNode.textContent = label;
    } else {
      button.textContent = label;
    }
  }

  function applyHourVisualState(group, hourButton) {
    const oneDayButton = group.buttons.get("1 天");
    const activeTarget = state.hourMode ? hourButton : findActiveNativeButton(group);

    hourButton.setAttribute("data-nezha-hour-active", state.hourMode ? "true" : "false");

    if (oneDayButton) {
      oneDayButton.classList.toggle("nezha-hour-native-muted", state.hourMode);
    }

    if (activeTarget) {
      moveActiveIndicator(group.container, activeTarget);
    }
  }

  function findActiveNativeButton(group) {
    const activeLabel = findActiveNativeLabel(group);
    return activeLabel ? group.buttons.get(activeLabel) : null;
  }

  function findActiveNativeLabel(group) {
    const enabled = [...group.buttons.entries()].filter(([, button]) => !isDisabledPeriodButton(button));

    // 优先读取组件明确标记的选中态，避免未选中按钮也没有 muted class。
    for (const [label, button] of enabled) {
      if (
        button.getAttribute("aria-pressed") === "true" ||
        button.getAttribute("aria-selected") === "true" ||
        button.getAttribute("data-state") === "active"
      ) return label;
    }

    for (const [label, button] of enabled) {
      if (button.classList.contains("text-foreground") &&
          !button.classList.contains("text-muted-foreground")) return label;
    }

    // 兼容旧版前端：选中按钮不带 muted，非选中按钮带 muted。
    for (const [label, button] of enabled) {
      if (!button.classList.contains("text-muted-foreground")) return label;
    }
    return null;
  }

  function moveActiveIndicator(container, target) {
    const indicator = container.querySelector(".active-indicator-fade-in");
    if (!indicator || !target) return;

    const containerRect = container.getBoundingClientRect();
    const targetRect = target.getBoundingClientRect();

    if (!containerRect.width || !targetRect.width) return;

    const x = targetRect.left - containerRect.left + container.scrollLeft;
    const y = targetRect.top - containerRect.top + container.scrollTop;

    indicator.style.width = String(targetRect.width) + "px";
    indicator.style.height = String(targetRect.height) + "px";
    indicator.style.transform = "translate(" + x + "px, " + y + "px)";
  }

  function patchNetworkMidline() {
    const card = findNetworkSpeedCard();
    if (!card) return;

    const svg = card.querySelector("svg.recharts-surface");
    if (!svg) return;

    const nativeTickTexts = getNativeSpeedTickTexts(svg);
    if (nativeTickTexts.length >= 3) {
      removeNetworkMidline(card);
      return;
    }
    if (nativeTickTexts.length < 2) return;

    const tickInfos = nativeTickTexts
      .map((element) => ({
        element: element,
        y: readSvgNumber(element, "y"),
        value: parseSpeedTick(element.textContent)
      }))
      .filter((item) => isFiniteNumber(item.y) && isFiniteNumber(item.value))
      .sort((a, b) => a.y - b.y);

    if (tickInfos.length < 2) return;

    const topTick = tickInfos[0];
    const bottomTick = tickInfos[tickInfos.length - 1];
    const midLabelY = (topTick.y + bottomTick.y) / 2;
    const midValue = (topTick.value + bottomTick.value) / 2;

    const gridLines = getNativeHorizontalGridLines(svg);
    const lineInfo = getMidlineGeometry(svg, gridLines, topTick, bottomTick);
    if (!lineInfo) return;

    const existingLine = card.querySelector("[" + MIDLINE_ATTR + "]");
    const existingLabel = card.querySelector("[" + MIDLABEL_ATTR + "]");
    const line = existingLine || createMidline(gridLines[0]);
    const label = existingLabel || createMidLabel(topTick.element);

    const gridGroup =
      svg.querySelector(".recharts-cartesian-grid-horizontal") ||
      svg.querySelector(".recharts-cartesian-grid") ||
      svg;
    const labelGroup =
      svg.querySelector(".recharts-yAxis .recharts-cartesian-axis-tick-labels") ||
      svg.querySelector(".recharts-yAxis") ||
      svg;

    applyMidlineGeometry(line, lineInfo);
    applyMidLabel(label, midLabelY, midValue);

    if (!existingLine) gridGroup.appendChild(line);
    if (!existingLabel) labelGroup.appendChild(label);
  }

  function findNetworkSpeedCard() {
    return Array.from(document.querySelectorAll(".server-charts > *")).find((card) => {
      const text = normalizeText(card.textContent);
      return text.includes("上传") && text.includes("下载") && /M\/s/.test(text);
    });
  }

  function removeNetworkMidline(card) {
    card
      .querySelectorAll("[" + MIDLINE_ATTR + "], [" + MIDLABEL_ATTR + "]")
      .forEach((element) => element.remove());
  }

  function getNativeSpeedTickTexts(svg) {
    return Array.from(
      svg.querySelectorAll(".recharts-yAxis text, text.recharts-cartesian-axis-tick-value")
    )
      .filter((element) => !element.hasAttribute(MIDLABEL_ATTR))
      .filter((element) => /M\/s$/.test(normalizeText(element.textContent)));
  }

  function getNativeHorizontalGridLines(svg) {
    return Array.from(svg.querySelectorAll(".recharts-cartesian-grid-horizontal line"))
      .filter((line) => !line.hasAttribute(MIDLINE_ATTR))
      .filter((line) => isFiniteNumber(readSvgNumber(line, "y1")));
  }

  function getMidlineGeometry(svg, gridLines, topTick, bottomTick) {
    if (gridLines.length >= 2) {
      const sortedLines = gridLines
        .map((line) => ({
          line: line,
          y: readSvgNumber(line, "y1")
        }))
        .filter((item) => isFiniteNumber(item.y))
        .sort((a, b) => a.y - b.y);

      const topLine = sortedLines[0];
      const bottomLine = sortedLines[sortedLines.length - 1];

      if (topLine && bottomLine) {
        return {
          x1: readSvgNumber(topLine.line, "x1"),
          x2: readSvgNumber(topLine.line, "x2"),
          y: (topLine.y + bottomLine.y) / 2
        };
      }
    }

    const width =
      readSvgNumber(svg, "width") ||
      (svg.viewBox && svg.viewBox.baseVal && svg.viewBox.baseVal.width);
    const left = readSvgNumber(topTick.element, "x") || 0;

    if (!width) return null;

    return {
      x1: left + 12,
      x2: width - 8,
      y: (topTick.y + bottomTick.y) / 2
    };
  }

  function createMidline(templateLine) {
    const line = templateLine
      ? templateLine.cloneNode(false)
      : document.createElementNS("http://www.w3.org/2000/svg", "line");

    line.setAttribute(MIDLINE_ATTR, "true");
    line.setAttribute(ENHANCE_ATTR, "network-midline");

    if (!line.getAttribute("stroke")) line.setAttribute("stroke", "#ccc");
    if (!line.getAttribute("fill")) line.setAttribute("fill", "none");

    return line;
  }

  function applyMidlineGeometry(line, geometry) {
    line.setAttribute("x1", String(geometry.x1));
    line.setAttribute("x2", String(geometry.x2));
    line.setAttribute("y1", String(geometry.y));
    line.setAttribute("y2", String(geometry.y));
  }

  function createMidLabel(templateText) {
    const label = templateText.cloneNode(true);
    label.setAttribute(MIDLABEL_ATTR, "true");
    label.setAttribute(ENHANCE_ATTR, "network-midlabel");
    return label;
  }

  function applyMidLabel(label, y, value) {
    label.setAttribute("y", String(y));
    label.textContent = formatSpeedTick(value);
  }

  function readSvgNumber(element, attr) {
    const value = Number(element && element.getAttribute && element.getAttribute(attr));
    return Number.isFinite(value) ? value : NaN;
  }

  function parseSpeedTick(text) {
    const match = normalizeText(text).match(/^(-?\d+(?:\.\d+)?)M\/s$/);
    return match ? Number(match[1]) : NaN;
  }

  function formatSpeedTick(value) {
    if (!Number.isFinite(value)) return "";
    if (Math.abs(value) < 0.0001) return "0M/s";

    const fixed =
      Math.abs(value) < 10 && Math.abs(value % 1) > 0.0001
        ? value.toFixed(1)
        : value.toFixed(0);
    return fixed.replace(/\.0$/, "") + "M/s";
  }

  function startObserver() {
    if (state.observerStarted) return;

    const root = document.documentElement || document;
    const observer = new MutationObserver(scheduleEnhance);
    observer.observe(root, {
      childList: true,
      subtree: true,
      characterData: true
    });

    window.addEventListener("resize", scheduleDelayedEnhance, { passive: true });
    window.addEventListener("popstate", scheduleDelayedEnhance, { passive: true });
    document.addEventListener("visibilitychange", scheduleDelayedEnhance, { passive: true });

    state.observerStarted = true;
    scheduleEnhance();
    scheduleDelayedEnhance();
  }

  function boot() {
    patchFetch();
    installClickListener();
    ensureStyles();
    startObserver();
  }

  if (document.documentElement) {
    boot();
  } else {
    document.addEventListener("DOMContentLoaded", boot, { once: true });
  }
})();
