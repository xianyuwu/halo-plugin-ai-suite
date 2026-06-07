/**
 * AI 聊天浮窗 — 访客端 v3
 *
 * 对齐 ardot 设计稿：SVG 图标、消息前小头像、快捷问题 chip。
 * 视觉令牌由 CSS 变量管理（chat-widget.css），主题色由配置注入。
 */
(function () {
  "use strict";

  // 容器模式：由外部通过 window.__AI_CHAT_CONTAINER__ 指定挂载目标
  var container = window.__AI_CHAT_CONTAINER__ || null;
  var isContainerMode = !!container;
  // 防重复注入（容器模式由 Vue 组件负责清理，不做此检查）
  if (!isContainerMode && document.getElementById("ai-chat-trigger")) return;

  var configApiBase = "/apis/api.halo.run/v1alpha1";

  var config = {
    apiBase: configApiBase,
    position: "right-bottom",
    color: "#7C3BED",
    theme: "auto", // auto / light / dark
    welcome: "Hi! 我是你的 AI 博客助手，有什么我可以帮你的吗？",
    shortcuts: [],
    width: 400,
    height: 600,
    triggerAlign: "auto",
    triggerOffsetY: 24,
    stream: true,
    allowGuest: true,
  };

  var posSide = "right";
  var history = [];
  var isOpen = false;
  var isStreaming = false;

  // ===== SVG 图标库（仅 sparkles 用作博客没装 Remix Icon 时的 fallback；其它给弹窗内部用） =====

  var ICON = {
    // sparkles 星光 — trigger 按钮 / 头像 / AI 消息小头像通用
    sparkles:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' +
             '<path d="M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6z"/>' +
             '<path d="M18 4l.7 2 .7-2-2 .7 2 .7-.7-2-.7 2zM5 16l.7 2 .7-2-2 .7 2 .7-.7-2-.7 2z"/>' +
             '</svg>',
    // 关闭
    close:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
             '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>' +
             '</svg>',
    // 发送（paper-plane）
    send:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
             '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>' +
             '</svg>',
    // 点赞（thumbs-up）
    thumbUp: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
             '<path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"/>' +
             '<path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>' +
             '</svg>',
    // 点踩（thumbs-down）
    thumbDown:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
             '<path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"/>' +
             '<path d="M17 2h3a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-3"/>' +
             '</svg>',
    // 重试（refresh-cw）
    refresh: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
             '<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>' +
             '<path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10"/><path d="M20.49 15a9 9 0 0 1-14.85 3.36L1 14"/>' +
             '</svg>',
    // 复制（copy）
    copy:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
             '<rect x="9" y="9" width="13" height="13" rx="2"/>' +
             '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>' +
             '</svg>',
    // 已复制（check）
    check:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
             '<polyline points="20 6 9 17 4 12"/>' +
             '</svg>',
    // 折叠/展开箭头（chevron-down）
    chevronDown: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
             '<polyline points="6 9 12 15 18 9"/>' +
             '</svg>'
  };

  function getAvatarHTML() {
    if (config.triggerLabel) {
      return '<span class="ai-avatar-label">' + escapeHtml(config.triggerLabel) + '</span>';
    }
    return '<i class="' + (config.icon || "ri-sparkling-2-line") + '"></i>';
  }

  // ===== 创建 DOM =====

  var trigger = document.createElement("button");
  trigger.id = "ai-chat-trigger";
  // Remix Icon 字体图标（与博客主题图标库一致）；若博客未加载 Remix，<i> 不会渲染出图标，
  // 此时 fallback 到 sparkles SVG。检测在 applyConfig 里做。
  trigger.innerHTML = '<i class="ri-sparkling-2-line"></i>';
  trigger.title = "AI 助手";
  trigger.addEventListener("click", toggleChat);

  var chatWindow = document.createElement("div");
  chatWindow.id = "ai-chat-window";
  chatWindow.innerHTML =
    (isContainerMode ? "" :
      '<div class="ai-chat-resize-handle ai-chat-resize-left" data-resize="left"></div>' +
      '<div class="ai-chat-resize-handle ai-chat-resize-top" data-resize="top"></div>' +
      '<div class="ai-chat-resize-handle ai-chat-resize-corner" data-resize="corner"></div>'
    ) +
    '<div class="ai-chat-header">' +
      '<div class="ai-chat-header-avatar">' + getAvatarHTML() + '</div>' +
      '<div class="ai-chat-header-info">' +
        '<h3>AI 助手</h3>' +
      '</div>' +
      '<button class="ai-chat-close" aria-label="关闭">' + ICON.close + '</button>' +
    '</div>' +
    '<div class="ai-chat-messages"></div>' +
    '<div class="ai-chat-clear-area"><button class="ai-chat-clear-btn" type="button">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="ai-chat-clear-icon">' +
        '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>' +
        '<line x1="2" y1="2" x2="22" y2="22"/>' +
      '</svg>清除对话上下文</button></div>' +
    '<div class="ai-chat-input-area">' +
      '<textarea class="ai-chat-input" placeholder="输入你的问题…" rows="1"></textarea>' +
      '<button class="ai-chat-send" aria-label="发送">' + ICON.send + '</button>' +
    '</div>' +
    '<div class="ai-chat-disclaimer">内容由 AI 生成，请仔细甄别</div>' +
    '<div class="ai-chat-privacy-banner" style="display:none"><span>🔒 对话内容可能被记录，请勿输入个人隐私信息</span><button class="ai-chat-privacy-close" aria-label="关闭">✕</button></div>' +
    '<div class="ai-feedback-modal" hidden>' +
      '<div class="ai-feedback-modal-backdrop" data-feedback-modal-dismiss></div>' +
      '<div class="ai-feedback-modal-card" role="dialog" aria-modal="true" aria-labelledby="ai-feedback-modal-title">' +
        '<div id="ai-feedback-modal-title" class="ai-feedback-modal-title">这条回答哪里不准确？</div>' +
        '<div class="ai-feedback-modal-hint">你的反馈会帮助我们改进（可选，最多 200 字）</div>' +
        '<textarea class="ai-feedback-modal-input" maxlength="200" placeholder="例如：回答过时了 / 答非所问 / 信息有误..."></textarea>' +
        '<div class="ai-feedback-modal-footer">' +
          '<span class="ai-feedback-modal-count"><span class="ai-feedback-modal-count-num">0</span> / 200</span>' +
          '<div class="ai-feedback-modal-actions">' +
            '<button type="button" class="ai-feedback-modal-btn ai-feedback-modal-cancel" data-feedback-modal-dismiss>取消</button>' +
            '<button type="button" class="ai-feedback-modal-btn ai-feedback-modal-submit">提交</button>' +
          '</div>' +
        '</div>' +
      '</div>' +
    '</div>';

  var messagesEl = chatWindow.querySelector(".ai-chat-messages");
  var inputEl = chatWindow.querySelector(".ai-chat-input");
  var sendBtn = chatWindow.querySelector(".ai-chat-send");
  var closeBtn = chatWindow.querySelector(".ai-chat-close");

  // 反馈评论弹窗（替换 window.prompt，提供更友好的输入体验）
  var feedbackModal = chatWindow.querySelector(".ai-feedback-modal");
  var feedbackInput = chatWindow.querySelector(".ai-feedback-modal-input");
  var feedbackCountNum = chatWindow.querySelector(".ai-feedback-modal-count-num");
  var feedbackSubmit = chatWindow.querySelector(".ai-feedback-modal-submit");
  var feedbackCancel = chatWindow.querySelector(".ai-feedback-modal-cancel");
  var feedbackResolve = null;
  var feedbackLastFocus = null;

  function updateFeedbackCount() {
    if (feedbackCountNum && feedbackInput) {
      feedbackCountNum.textContent = String(feedbackInput.value.length);
    }
  }

  function closeFeedbackModal(result) {
    if (!feedbackModal) return;
    feedbackModal.hidden = true;
    if (feedbackResolve) {
      feedbackResolve(result);
      feedbackResolve = null;
    }
    if (feedbackInput) feedbackInput.value = "";
    updateFeedbackCount();
    if (feedbackLastFocus && typeof feedbackLastFocus.focus === "function") {
      feedbackLastFocus.focus();
      feedbackLastFocus = null;
    }
  }

  /**
   * 打开反馈评论弹窗 — resolve(comment 字符串 或 null).
   * null 表示用户取消. 空字符串表示用户提交了空评论.
   */
  function openFeedbackModal() {
    if (!feedbackModal) return Promise.resolve(null);
    feedbackLastFocus = document.activeElement;
    feedbackModal.hidden = false;
    if (feedbackInput) {
      feedbackInput.value = "";
      updateFeedbackCount();
      // 下一帧聚焦, 否则 autofocus 会被 hidden 抢掉
      requestAnimationFrame(function () { feedbackInput.focus(); });
    }
    return new Promise(function (resolve) { feedbackResolve = resolve; });
  }

  // 清除上下文按钮
  var clearBtn = chatWindow.querySelector(".ai-chat-clear-btn");
  if (clearBtn) {
    clearBtn.addEventListener("click", function () {
      history = [];
      // 清空消息区域，重新显示欢迎语和快捷问题
      while (messagesEl.firstChild) {
        messagesEl.removeChild(messagesEl.firstChild);
      }
      addMessage("assistant", config.welcome);
      renderShortcuts(config.shortcuts);
    });
  }

  // 隐私提示关闭按钮
  var privacyClose = chatWindow.querySelector(".ai-chat-privacy-close");
  if (privacyClose) {
    privacyClose.addEventListener("click", function () {
      var banner = chatWindow.querySelector(".ai-chat-privacy-banner");
      if (banner) banner.style.display = "none";
      sessionStorage.setItem("ai-privacy-dismissed", "1");
    });
  }

  /** 根据配置更新外观 */
  function applyConfig() {
    // 主题色注入 CSS 变量，整个 widget 内的紫色调（trigger/header/user 气泡/发送按钮/链接等）自动跟随
    chatWindow.style.setProperty("--ai-chat-color", config.color);
    trigger.style.setProperty("--ai-chat-color", config.color);

    // 更新悬浮按钮大小
    var triggerSize = config.triggerSize || 35;
    trigger.style.width = triggerSize + "px";
    trigger.style.height = triggerSize + "px";
    trigger.style.fontSize = (triggerSize * 0.55) + "px";

    // 更新悬浮按钮内容：文字标签优先于图标
    if (config.triggerLabel) {
      trigger.innerHTML = '<span class="ai-trigger-label">' + escapeHtml(config.triggerLabel) + '</span>';
    } else {
      trigger.innerHTML = '<i class="' + (config.icon || "ri-sparkling-2-line") + '"></i>';
    }

    // 更新所有 AI 头像为当前配置图标
    var avatars = chatWindow.querySelectorAll(".ai-chat-header-avatar, .ai-chat-row-avatar");
    var avatarHTML = getAvatarHTML();
    for (var ai = 0; ai < avatars.length; ai++) {
      avatars[ai].innerHTML = avatarHTML;
    }

    // 检测 Remix Icon 是否加载（仅在非文字模式时检测）
    if (!config.triggerLabel && !isRemixIconAvailable()) {
      trigger.innerHTML = ICON.sparkles;
    }

    // 悬浮按钮位置：按 admin 选择的对齐策略
    //   - manual: 永远用 config.triggerOffsetY（admin 自己指定）
    //   - auto  : 优先 calcTriggerBottom()（自动对齐博客 .actions 容器顶端 + 5px）
    //             若没有 .actions 容器（非 Dream 主题）→ fallback 到 triggerOffsetY
    var bottom;
    if (config.triggerAlign === "manual") {
      bottom = (typeof config.triggerOffsetY === "number" && config.triggerOffsetY > 0)
        ? config.triggerOffsetY : 120;
    } else {
      bottom = calcTriggerBottom();
      if (bottom == null) {
        bottom = (typeof config.triggerOffsetY === "number" && config.triggerOffsetY > 0)
          ? config.triggerOffsetY : 120;
      }
    }
    trigger.style.setProperty("--ai-chat-trigger-bottom", bottom + "px");
    // 弹窗底部 = trigger bottom + 35（按钮高）+ 8（间距）
    chatWindow.style.setProperty("--ai-chat-window-bottom", (bottom + 43) + "px");

    applyTheme();

    posSide = config.position.indexOf("left") >= 0 ? "left" : "right";
    trigger.classList.remove("position-left", "position-right");
    trigger.classList.add("position-" + posSide);
    chatWindow.classList.remove("position-left", "position-right");
    chatWindow.classList.add("position-" + posSide);
    chatWindow.style.width = config.width + "px";
    chatWindow.style.height = config.height + "px";

    if (!config.allowGuest) {
      chatWindow.querySelector(".ai-chat-input-area").style.display = "none";
    }
  }

  /**
   * 计算并应用 data-theme：
   *   - light / dark：admin 锁死，直接用
   *   - auto：先尝试探测博客主题（类名 / 背景色），探测不到再 fallback 到 prefers-color-scheme
   *
   * 抽成单独函数，方便 watchPageTheme 在监听到变化时复用
   */
  function applyTheme() {
    var theme = (config.theme === "light" || config.theme === "dark") ? config.theme : "auto";
    if (theme === "auto") {
      var detected = detectPageTheme();
      if (detected) theme = detected;
      // 探测不到 → 仍标记 "auto"，由 CSS @media prefers-color-scheme 接管
    }
    if (chatWindow.getAttribute("data-theme") !== theme) {
      chatWindow.setAttribute("data-theme", theme);
      trigger.setAttribute("data-theme", theme);
    }
  }

  /**
   * 监听博客主题切换 — 仅 auto 模式启用
   *
   * 三路监听 → applyTheme（用 rAF 合并多次同帧触发）：
   *   1. MutationObserver: <html>/<body> 的 class / data-theme / style 变化
   *      （覆盖类名 toggle、属性切换、inline style 改 background-color 三种主流实现）
   *   2. matchMedia change: 系统 prefers-color-scheme 切换
   *   3. (不监听 light/dark) admin 锁死时不需要跟随，节省性能
   */
  function watchPageTheme() {
    if (config.theme !== "auto") return;

    var pending = false;
    function schedule() {
      if (pending) return;
      pending = true;
      requestAnimationFrame(function () {
        pending = false;
        applyTheme();
      });
    }

    if (typeof MutationObserver !== "undefined" && document.documentElement && document.body) {
      var observer = new MutationObserver(schedule);
      var opts = { attributes: true, attributeFilter: ["class", "data-theme", "style"] };
      observer.observe(document.documentElement, opts);
      observer.observe(document.body, opts);
    }

    if (window.matchMedia) {
      var mql = window.matchMedia("(prefers-color-scheme: dark)");
      if (mql.addEventListener) {
        mql.addEventListener("change", schedule);
      } else if (mql.addListener) {
        mql.addListener(schedule); // 老 Safari fallback
      }
    }
  }

  /**
   * 构造 chat / chat-stream 的 GET URL —— 含 URL 长度守卫
   *
   * 历史问题：原本 Halo Netty 默认 maxInitialLineLength = 4KB，中文一字 URL-encode 9 字节，
   * 第二轮 history 就超限。现已要求生产环境在 application.yaml 配 `server.netty.max-initial-line-length: 64KB`，
   * 这里的 URL_SOFT_LIMIT 留出 4KB 安全余量。万一未配置，前端仍按 60KB 兜底——超就砍最旧 history。
   *
   * 策略：
   *   1. history 最多保留 12 条（约 6 轮）
   *   2. 单条 > 2000 字符头尾截断
   *   3. 兜底：URL > 60000 字符时按 2 条砍最旧
   */
  function buildChatUrl(path, text) {
    var MAX_HISTORY = 12;
    var MAX_MSG_LEN = 2000;
    var URL_SOFT_LIMIT = 60000;

    function truncate(s) {
      if (!s || s.length <= MAX_MSG_LEN) return s;
      return s.substring(0, 1400) + "\n…[内容已截断]…\n" + s.substring(s.length - 500);
    }

    var pruned = history.slice(-MAX_HISTORY).map(function (m) {
      return { role: m.role, content: truncate(m.content || "") };
    });

    function build() {
      var u = config.apiBase + path + "?message=" + encodeURIComponent(text);
      if (pruned.length > 0) u += "&history=" + encodeURIComponent(JSON.stringify(pruned));
      return u;
    }

    var url = build();
    while (url.length > URL_SOFT_LIMIT && pruned.length >= 2) {
      pruned = pruned.slice(2); // 砍掉最旧的一轮（user + assistant）
      url = build();
    }
    return url;
  }

  /**
   * 检测博客是否已加载 Remix Icon 字体库 — 决定 trigger 用 <i> 还是 SVG fallback
   */
  function isRemixIconAvailable() {
    var links = document.querySelectorAll('link[rel="stylesheet"], link[rel="preload stylesheet"]');
    for (var i = 0; i < links.length; i++) {
      if ((links[i].href || "").toLowerCase().indexOf("remixicon") >= 0) return true;
    }
    return false;
  }

  /**
   * 计算 trigger 按钮的 bottom 像素 — 对齐到博客 .actions 容器顶端 + 5px 间距
   *
   * Dream 主题 .actions 默认 right:-48px 隐藏，滚动 > 100px 加 .show 出现。
   * AI 按钮独立于 .actions（始终可见），但位置 y 跟 .actions 顶端对齐。
   * .actions { bottom: 40 } + 每子 35x35 + margin-bottom:5 → 堆叠单元 40。
   *
   * 返回 null 表示页面无 .actions 容器（非 Dream），由 caller fallback 到 widgetTriggerOffsetY 配置。
   */
  function calcTriggerBottom() {
    var actions = document.querySelector(".actions");
    if (!actions) return null;

    // 数当前可见的子元素（响应式：is-hidden-mobile / is-hidden-desktop / is-hidden-all 会改 display）
    var visibleCount = 0;
    for (var i = 0; i < actions.children.length; i++) {
      var s = window.getComputedStyle(actions.children[i]);
      if (s.display !== "none") visibleCount++;
    }
    if (visibleCount === 0) return null;

    return 40 + visibleCount * 40 + 5;
  }

  /**
   * 探测当前博客页面深浅 — 返回 "dark" / "light" / null（无可靠信号）
   *
   * 优先级：
   *   1. <html> 或 <body> 上的明显信号 — 主流深色主题约定的类名或 data-theme 属性
   *   2. <html> 或 <body> 的实际背景色亮度（YIQ luminance < 128 算深色）
   *   3. 都没有 → 返回 null，由调用方 fallback 到 prefers-color-scheme
   */
  function detectPageTheme() {
    var html = document.documentElement;
    var body = document.body;
    if (!html || !body) return null;

    // 信号 1：类名 / data-theme（Tailwind 等约定）
    var classStr = ((html.className || "") + " " + (body.className || "")).toLowerCase();
    var dataTheme = (html.getAttribute("data-theme") || body.getAttribute("data-theme") || "").toLowerCase();
    if (/\b(dark|dark-mode|theme-dark|night)\b/.test(classStr) || dataTheme === "dark") {
      return "dark";
    }
    if (/\b(light|light-mode|theme-light|day)\b/.test(classStr) || dataTheme === "light") {
      return "light";
    }

    // 信号 2：实际背景色亮度
    // getComputedStyle 返回 rgb(R, G, B) 或 rgba(R, G, B, A)；透明视为无效信号
    for (var i = 0; i < 2; i++) {
      var bg = window.getComputedStyle(i === 0 ? body : html).backgroundColor;
      var m = (bg || "").match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?\s*\)$/);
      if (!m) continue;
      var alpha = m[4] === undefined ? 1 : parseFloat(m[4]);
      if (alpha < 0.5) continue; // 透明 → 不可靠，下一个候选
      // YIQ 加权亮度（人眼感知公式）
      var lum = 0.299 * (+m[1]) + 0.587 * (+m[2]) + 0.114 * (+m[3]);
      return lum < 128 ? "dark" : "light";
    }

    return null;
  }

  /**
   * 初始化浮窗左/上/左上角拖拽改尺寸
   *
   * 浮窗是 position: fixed + 右下角定位（right + bottom）。
   * 因此「向左拉」= 增加 width，「向上拉」= 增加 height，corner = 同时两个。
   *
   * mousedown 在 handle 上记录起点，mousemove/mouseup 监听在 document（避免
   * 鼠标快速移出 handle 时丢失事件）。手机端 handle 已用 CSS 隐藏。
   * 左下角浮窗位置同样隐藏 handle（CSS 中处理），所以这里只需要处理 right 定位。
   */
  function initResize() {
    var MIN_W = 320, MIN_H = 420;
    var startX, startY, startW, startH, mode;

    function onMove(e) {
      if (mode === "left" || mode === "corner") {
        var dx = startX - e.clientX; // 向左拉 dx > 0
        var maxW = window.innerWidth - 48;
        var newW = Math.max(MIN_W, Math.min(maxW, startW + dx));
        chatWindow.style.width = newW + "px";
      }
      if (mode === "top" || mode === "corner") {
        var dy = startY - e.clientY; // 向上拉 dy > 0
        var maxH = window.innerHeight - 120;
        var newH = Math.max(MIN_H, Math.min(maxH, startH + dy));
        chatWindow.style.height = newH + "px";
      }
    }
    function onUp() {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      if (!isContainerMode) {
        document.body.style.userSelect = "";
        document.body.style.cursor = "";
      }
    }

    var handles = chatWindow.querySelectorAll(".ai-chat-resize-handle");
    for (var i = 0; i < handles.length; i++) {
      handles[i].addEventListener("mousedown", function (e) {
        mode = e.currentTarget.getAttribute("data-resize");
        startX = e.clientX;
        startY = e.clientY;
        startW = chatWindow.offsetWidth;
        startH = chatWindow.offsetHeight;
        if (!isContainerMode) {
          document.body.style.userSelect = "none";
          // 拖拽期间锁定全局 cursor，避免离开 handle 时光标抖动
          document.body.style.cursor =
            mode === "left" ? "ew-resize" :
            mode === "top"  ? "ns-resize" : "nwse-resize";
        }
        document.addEventListener("mousemove", onMove);
        document.addEventListener("mouseup", onUp);
        e.preventDefault();
      });
    }
  }

  // ===== 事件绑定 =====

  closeBtn.addEventListener("click", toggleChat);
  sendBtn.addEventListener("click", function () { sendMessage(); });
  inputEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  inputEl.addEventListener("input", function () {
    this.style.height = "auto";
    this.style.height = Math.min(this.scrollHeight, 100) + "px";
  });

  // 反馈弹窗事件：字符计数、Enter 提交、Esc 取消、点击遮罩取消
  if (feedbackInput) {
    feedbackInput.addEventListener("input", updateFeedbackCount);
    feedbackInput.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        closeFeedbackModal(feedbackInput.value || "");
      } else if (e.key === "Escape") {
        e.preventDefault();
        closeFeedbackModal(null);
      }
    });
  }
  if (feedbackSubmit) {
    feedbackSubmit.addEventListener("click", function () {
      closeFeedbackModal(feedbackInput && feedbackInput.value || "");
    });
  }
  // 点 [data-feedback-modal-dismiss] 的元素（取消按钮、遮罩）关弹窗
  var dismissEls = chatWindow.querySelectorAll("[data-feedback-modal-dismiss]");
  for (var di = 0; di < dismissEls.length; di++) {
    dismissEls[di].addEventListener("click", function () { closeFeedbackModal(null); });
  }

  // ===== 核心逻辑 =====

  // 事件委托：点击 [N] inline 角标 → 找到本回复气泡内对应的 citation，滚动并高亮
  messagesEl.addEventListener("click", function (e) {
    var target = e.target;

    // 4 按钮工具条(like / dislike / retry / copy) — 走事件委托
    var btn = target.closest && target.closest(".ai-chat-msg-btn");
    if (btn) {
      var bar = btn.parentElement;
      if (bar && bar.classList.contains("ai-chat-msg-actions")) {
        var action = btn.getAttribute("data-action");
        if (action === "like") {
          submitFeedback(bar, "like", "");
        } else if (action === "dislike") {
          // 弹自定义评论框 — 用户取消返回 null, 空评论返回空串, 有内容返回字符串
          openFeedbackModal().then(function (comment) {
            if (comment === null) return;  // 用户取消
            submitFeedback(bar, "dislike", (comment || "").substring(0, 200));
          });
        } else if (action === "retry") {
          var contentEl = bar.parentElement;
          var row = contentEl ? contentEl.closest(".ai-chat-row") : null;
          if (row) handleRetry(row);
        } else if (action === "copy") {
          handleCopy(bar);
        }
        return;
      }
    }

    // 引用 section header 折叠/展开
    var citeHeader = target.closest && target.closest(".ai-chat-msg-cites-header");
    if (citeHeader) {
      var section = citeHeader.parentElement;
      if (section && section.classList.contains("ai-chat-msg-cites")) {
        section.classList.toggle("expanded");
      }
      return;
    }

    if (!target.classList || !target.classList.contains("ai-cite-inline")) return;
    var num = parseInt(target.getAttribute("data-num"), 10);
    if (!num) return;

    // 引用 section 已在气泡内部 — 找 .ai-chat-msg-cites-body > .ai-cite-link[data-num=N]
    var row = target.closest(".ai-chat-row");
    if (!row) return;
    var bubble = row.querySelector(".ai-chat-msg.assistant");
    if (!bubble) return;
    var citesSection = bubble.querySelector(".ai-chat-msg-cites");
    var citesBody = bubble.querySelector(".ai-chat-msg-cites-body");
    if (!citesBody) return;

    // 折叠状态时自动展开，让用户能看到目标
    if (citesSection && !citesSection.classList.contains("expanded")) {
      citesSection.classList.add("expanded");
    }

    var chip = citesBody.querySelector('.ai-cite-link[data-num="' + num + '"]');
    if (!chip) return;

    chip.scrollIntoView({ behavior: "smooth", block: "nearest" });
    chip.classList.add("ai-cite-highlight");
    setTimeout(function () { chip.classList.remove("ai-cite-highlight"); }, 1200);
  });

  function toggleChat() {
    isOpen = !isOpen;
    chatWindow.classList.toggle("open", isOpen);
    if (isOpen) {
      inputEl.focus();
      // 首次打开时显示隐私提示
      if (config.showPrivacyTip && !sessionStorage.getItem("ai-privacy-dismissed")) {
        var banner = chatWindow.querySelector(".ai-chat-privacy-banner");
        if (banner) banner.style.display = "flex";
      }
    }
  }

  function sendMessage(presetText) {
    var text = (typeof presetText === "string" ? presetText : inputEl.value).trim();
    if (!text || isStreaming) return;

    inputEl.value = "";
    inputEl.style.height = "auto";

    // 一旦用户发了消息，移除快捷问题区（一次性引导）
    var shortcutsEl = messagesEl.querySelector(".ai-chat-shortcuts");
    if (shortcutsEl) shortcutsEl.remove();

    addMessage("user", text);
    history.push({ role: "user", content: text });

    isStreaming = true;
    sendBtn.disabled = true;

    if (config.stream) {
      streamChat(text);
    } else {
      normalChat(text);
    }
  }

  /**
   * 通用 SSE 流式解析器 — 把 fetch + ReadableStream 的字节流按 \n\n 切事件块,
   * 识别 event: / data: 字段, 调对应回调.
   *
   * 回调约定:
   *   onCitation(citations)  —— event:citations 触发, 入参是已 JSON.parse 的数组
   *   onToken(text)          —— 普通 token 事件触发, 入参是单 token 字符串
   *                            (若是 {"content":"..."} 包装, 会自动解包取 content)
   *   onDone()               —— 流结束时 (reader.read done) 触发一次
   *   onError(err)           —— 流读出错时触发
   *
   * 之所以抽这个函数, 是为了让 streamChat 和搜索结果页 AI 卡片共用同一套
   * SSE 解析逻辑 —— 避免在两个地方复制大段 fetch+read+split 代码.
   */
  function parseSseStream(response, callbacks) {
    callbacks = callbacks || {};
    var onCitation = callbacks.onCitation || function () {};
    var onToken = callbacks.onToken || function () {};
    var onLogId = callbacks.onLogId || function () {};
    var onDone = callbacks.onDone || function () {};
    var onError = callbacks.onError || function () {};

    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var buffer = "";

    function read() {
      reader.read().then(function (result) {
        if (result.done) {
          onDone();
          return;
        }
        buffer += decoder.decode(result.value, { stream: true });
        var parts = buffer.split("\n\n");
        buffer = parts.pop() || "";

        for (var i = 0; i < parts.length; i++) {
          var eventBlock = parts[i].trim();
          if (!eventBlock) continue;

          var eventType = "message";
          var eventData = "";
          var lines = eventBlock.split("\n");
          for (var j = 0; j < lines.length; j++) {
            if (lines[j].indexOf("event:") === 0) {
              eventType = lines[j].substring(6).trim();
            } else if (lines[j].indexOf("data:") === 0) {
              eventData = lines[j].substring(5).trim();
            }
          }
          if (!eventData) continue;

          if (eventType === "citations") {
            try { onCitation(JSON.parse(eventData)); } catch (e) { /* ignore */ }
          } else if (eventType === "logId") {
            // 后端在 [DONE] 之前推一个 logId 事件 — 用于后续反馈关联
            onLogId(eventData);
          } else if (eventData === "[DONE]") {
            // 流结束标记, 等 reader.read() 真正 done 时再调 onDone
          } else {
            try {
              var token;
              if (eventData.charAt(0) === "{") {
                var parsed = JSON.parse(eventData);
                token = typeof parsed.content === "string" ? parsed.content : "";
              } else {
                token = eventData;
              }
              if (token) onToken(token);
            } catch (e) {
              onToken(eventData);
            }
          }
        }
        read();
      }, function (err) {
        onError(err);
      });
    }
    read();
  }

  function streamChat(text) {
    // 思考中三点动画（独立元素，首 token 到达时移除）
    var typingEl = config.showRetrievalStatus
      ? addTypingIndicator("🔍 正在检索文章...")
      : addTypingIndicator();
    var assistantEl = null;
    var content = "";
    var citations = [];
    var pendingLogId = null;   // logId 可能先于 assistantEl 到达，用闭包暂存
    var url = buildChatUrl("/chat/stream", text);

    function ensureAssistantEl() {
      if (assistantEl) return;
      if (typingEl) { typingEl.remove(); typingEl = null; }
      assistantEl = addMessage("assistant", "");
      // logId 先于首 token 到达时，此时补上
      if (pendingLogId && !assistantEl._logId) {
        assistantEl._logId = pendingLogId;
      }
    }

    fetch(url)
      .then(function (response) {
        if (!response.ok) throw new Error("服务器错误: " + response.status);
        parseSseStream(response, {
          onCitation: function (c) {
            citations = c;
            if (config.showRetrievalStatus && typingEl && c.length > 0) {
              var titles = c.slice(0, 3).map(function (x) { return x.title || ""; }).filter(Boolean).join("、");
              var more = c.length > 3 ? " 等" : "";
              typingEl.textContent = "📄 已找到 " + c.length + " 篇相关文章" + (titles ? "：" + titles + more : "");
            }
          },
          onLogId: function (id) {
            pendingLogId = id;
            if (assistantEl) assistantEl._logId = id;
          },
          onToken: function (token) {
            content += token;
            ensureAssistantEl();
            renderAssistant(assistantEl, content);
            scrollToBottom();
          },
          onDone: function () {
            ensureAssistantEl();
            finishStream(assistantEl, content, citations);
          },
          onError: function () {
            ensureAssistantEl();
            assistantEl.textContent = "⚠️ AI 助手暂时不可用，请稍后再试。";
            finishStream(assistantEl, "", []);
          }
        });
      })
      .catch(function () {
        ensureAssistantEl();
        assistantEl.textContent = "⚠️ AI 助手暂时不可用，请稍后再试。";
        finishStream(assistantEl, "", []);
      });
  }

  function normalChat(text) {
    var url = buildChatUrl("/chat", text);
    fetch(url)
      .then(function (res) { return res.json(); })
      .then(function (data) {
        var reply = data.reply || "⚠️ AI 助手暂时无法回答，请稍后再试。";
        addMessage("assistant", reply);
        history.push({ role: "assistant", content: reply });
      })
      .catch(function () {
        addMessage("assistant", "⚠️ AI 助手暂时不可用，请稍后再试。");
      })
      .finally(function () {
        isStreaming = false;
        sendBtn.disabled = false;
        inputEl.focus();
      });
  }

  function finishStream(el, content, citations) {
    isStreaming = false;
    sendBtn.disabled = false;
    inputEl.focus();

    if (content) history.push({ role: "assistant", content: content });

    var contentWrap = el.closest ? el.closest(".ai-chat-content") : null;
    var target = contentWrap || el;

    // 4 按钮工具条 — 气泡外的 content wrapper 内
    appendMsgActions(target, el._logId, content);

    if (!config.showReferences) return;
    if (citations && citations.length > 0) {
      // 只显示 AI 正文里实际引用过的文章（匹配 [N] 角标）
      var usedNums = {};
      var inlineCites = el.querySelectorAll(".ai-cite-inline");
      for (var ci = 0; ci < inlineCites.length; ci++) {
        usedNums[inlineCites[ci].getAttribute("data-num")] = true;
      }
      var filteredCites = citations.filter(function (_, i) {
        return usedNums[String(i + 1)];
      });
      if (filteredCites.length === 0) { filteredCites = citations; }

      // 引用 section：分隔线 + header（可点击折叠）+ body — 全部插到气泡内部
      var hr = document.createElement("hr");
      hr.className = "ai-chat-msg-divider";

      var section = document.createElement("div");
      section.className = "ai-chat-msg-cites";  // 默认折叠（不写 expanded class）

      var header = document.createElement("div");
      header.className = "ai-chat-msg-cites-header";
      header.setAttribute("role", "button");
      header.setAttribute("tabindex", "0");
      header.innerHTML =
        '<span class="ai-chat-msg-cites-label">参考 <span class="ai-chat-msg-cites-count">' +
        filteredCites.length + '</span> 篇文章</span>' +
        '<span class="ai-chat-msg-cites-toggle">' + ICON.chevronDown + '</span>';

      var body = document.createElement("div");
      body.className = "ai-chat-msg-cites-body";
      body.innerHTML = filteredCites.map(function (c) {
        var originalIdx = citations.indexOf(c);
        var num = String(originalIdx + 1);
        var title = escapeHtml(c.title || "文章");
        var cUrl = c.url || "";
        if (cUrl) {
          return '<a class="ai-cite-link" data-num="' + num + '" href="' + escapeAttr(cUrl) +
            '" target="_blank"><span class="ai-cite-link-num">' + num + '</span><span class="ai-cite-link-text">' + title + '</span></a>';
        }
        return '<span class="ai-cite-link ai-cite-disabled" data-num="' + num + '"><span class="ai-cite-link-num">' + num + '</span><span class="ai-cite-link-text">' + title + '</span></span>';
      }).join("");

      section.appendChild(header);
      section.appendChild(body);

      el.appendChild(hr);
      el.appendChild(section);
      scrollToBottom();
    }
  }

  /**
   * 在 AI 消息 row 末尾追加 4 按钮工具条(点赞/点踩/重生成/复制).
   * 重复调用幂等 — 若 row 上已有 .ai-chat-msg-actions 则只更新 logId / 启用按钮.
   */
  function appendMsgActions(row, logId, content) {
    if (!row) return;
    var bar = row.querySelector(".ai-chat-msg-actions");
    if (!bar) {
      bar = document.createElement("div");
      bar.className = "ai-chat-msg-actions";
      bar.innerHTML =
        '<button class="ai-chat-msg-btn" data-action="like" title="回答有帮助">' + ICON.thumbUp + '</button>' +
        '<button class="ai-chat-msg-btn" data-action="dislike" title="回答不准确">' + ICON.thumbDown + '</button>' +
        '<button class="ai-chat-msg-btn" data-action="retry" title="重新生成">' + ICON.refresh + '</button>' +
        '<button class="ai-chat-msg-btn" data-action="copy" title="复制全文">' + ICON.copy + '</button>';
      row.appendChild(bar);
    }
    bar.setAttribute("data-log-id", logId || "");
    bar.setAttribute("data-content", content || "");
    // 流式已完成,启用所有按钮
    var btns = bar.querySelectorAll(".ai-chat-msg-btn");
    for (var i = 0; i < btns.length; i++) btns[i].disabled = false;
  }

  /**
   * 添加消息 — 返回气泡元素（不是 row 包装层），方便上游 innerHTML
   *
   * AI 消息会带左侧小头像，user 消息靠右无头像
   */
  function addMessage(role, text) {
    var row = document.createElement("div");
    row.className = "ai-chat-row " + role;

    if (role === "assistant") {
      var avatar = document.createElement("div");
      avatar.className = "ai-chat-row-avatar";
      avatar.innerHTML = getAvatarHTML();
      row.appendChild(avatar);
    }

    var msgEl = document.createElement("div");
    msgEl.className = "ai-chat-msg " + role;
    if (role === "assistant") {
      renderAssistant(msgEl, text);
    } else {
      msgEl.textContent = text;
    }
    // 气泡 + 操作区的纵向包装层，让 actions 按钮垂直排列在气泡下方
    var contentWrap = document.createElement("div");
    contentWrap.className = "ai-chat-content";
    contentWrap.appendChild(msgEl);

    row.appendChild(contentWrap);

    messagesEl.appendChild(row);
    scrollToBottom();
    return msgEl;
  }

  /** 插入「思考中」三点跳动动画元素 */
  function addTypingIndicator(text) {
    var el = document.createElement("div");
    el.className = "ai-chat-typing";
    if (text) {
      el.classList.add("ai-typing-text");
      el.textContent = text;
    } else {
      el.innerHTML = "<span></span><span></span><span></span>";
    }
    messagesEl.appendChild(el);
    scrollToBottom();
    return el;
  }

  /**
   * 渲染 assistant 消息内容 — 优先 Markdown + sanitize，库未就绪降级为纯文本
   *
   * 渲染后扫描 [N] 引用标记（LLM 按 system prompt 指令插入），替换成可点击的
   * 紫色上标小角标。点击会滚动并高亮下方参考文章对应的 chip（见 messagesEl 的事件委托）。
   */
  function renderAssistant(el, content) {
    if (!content) { el.innerHTML = ""; return; }
    var html;
    if (typeof window.marked !== "undefined" && typeof window.DOMPurify !== "undefined") {
      try {
        html = window.marked.parse(content, { breaks: true, gfm: true });
        html = window.DOMPurify.sanitize(html);
      } catch (e) {
        html = escapeHtml(content);
      }
    } else {
      html = escapeHtml(content);
    }
    // 把正文里的 [N] 替换成 <sup class="ai-cite-inline" data-num="N">N</sup>
    // 严格匹配「[ + 1~2 位数字 + ]」，避免误伤 [DONE] / Markdown 链接的 [text]
    if (config.showReferences) {
      html = html.replace(/\[(\d{1,2})\]/g, function (_, num) {
        return '<sup class="ai-cite-inline" data-num="' + num + '">' + num + '</sup>';
      });
    } else {
      html = html.replace(/\[(\d{1,2})\]/g, ""); // 关闭引用时不显示编号
    }
    el.innerHTML = html;
    // 检测热门文章卡片模式并重组 DOM
    restructureHotCards(el);
  }

  /**
   * 热门文章卡片 DOM 重组
   * 检测 Markdown 渲染后的 hr + p>strong>a + blockquote 模式
   * 重组为带排名徽章、渐变色条的卡片结构（匹配 Ardot 设计稿）
   */
  function restructureHotCards(el) {
    var nodes = Array.prototype.slice.call(el.children);

    // 扫描连续的 [hr, p>strong>a, blockquote?] 序列
    var groups = [];
    var idx = 0;
    while (idx < nodes.length) {
      if (nodes[idx].tagName === 'HR'
          && idx + 1 < nodes.length
          && nodes[idx + 1].tagName === 'P'
          && nodes[idx + 1].querySelector(':scope > strong > a')) {
        var bq = (idx + 2 < nodes.length && nodes[idx + 2].tagName === 'BLOCKQUOTE')
          ? nodes[idx + 2] : null;
        groups.push([nodes[idx], nodes[idx + 1], bq]);
        idx += bq ? 3 : 2;
      } else {
        idx++;
      }
    }

    // 至少 2 张卡片才认为是热门文章模式
    if (groups.length < 2) return;

    groups.forEach(function(g, i) {
      var hr = g[0], p = g[1], bq = g[2];
      var link = p.querySelector(':scope > strong > a');
      var raw = link.textContent;
      var m = raw.match(/^(\d+)[.、．\s]+(.*)/);
      var rank = m ? parseInt(m[1]) : (i + 1);
      var title = m ? m[2] : raw;

      var card = document.createElement('div');
      card.className = 'ai-hot-card';
      card.setAttribute('data-rank', rank);

      // 左侧渐变色条
      var bar = document.createElement('div');
      bar.className = 'ai-hot-card-bar';

      // 内容区
      var body = document.createElement('div');
      body.className = 'ai-hot-card-body';

      // 排名徽章 + 标题
      var headerRow = document.createElement('div');
      headerRow.className = 'ai-hot-card-header';

      var badge = document.createElement('span');
      badge.className = 'ai-hot-card-badge';
      badge.textContent = rank;

      var titleLink = document.createElement('a');
      titleLink.className = 'ai-hot-card-title';
      titleLink.href = link.href;
      titleLink.target = '_blank';
      titleLink.textContent = title;

      headerRow.appendChild(badge);
      headerRow.appendChild(titleLink);
      body.appendChild(headerRow);

      // 描述行
      if (bq) {
        var desc = document.createElement('div');
        desc.className = 'ai-hot-card-desc';
        desc.textContent = bq.textContent.trim();
        body.appendChild(desc);
      }

      card.appendChild(bar);
      card.appendChild(body);

      hr.parentNode.insertBefore(card, hr);
      hr.remove();
      p.remove();
      if (bq) bq.remove();
    });
  }

  /**
   * 渲染快捷问题区 — 仅在欢迎语之后、用户首次发言之前显示
   */
  function renderShortcuts(items) {
    if (!items || !items.length) return;
    var wrap = document.createElement("div");
    wrap.className = "ai-chat-shortcuts";
    // 简单的 emoji 映射
    var icons = ["📝", "🤖", "🔍", "💡", "🌍", "📖"];
    var cards = items.map(function (q, i) {
      var icon = icons[i % icons.length];
      return '<button class="ai-shortcut-card" type="button">' +
        '<span class="ai-shortcut-card-icon">' + icon + '</span>' +
        '<span class="ai-shortcut-card-text">' + escapeHtml(q) + '</span>' +
        '</button>';
    }).join("");
    wrap.innerHTML = cards;
    // 绑定点击事件
    var buttons = wrap.querySelectorAll(".ai-shortcut-card");
    buttons.forEach(function (btn, i) {
      btn.addEventListener("click", function () { sendMessage(items[i]); });
    });
    messagesEl.appendChild(wrap);
    scrollToBottom();
  }

  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function escapeHtml(text) {
    if (!text) return "";
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/\n/g, "<br>");
  }

  function escapeAttr(text) {
    if (!text) return "";
    return text.replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  /**
   * 简易 toast — 在 body 底部居中显示一条提示, 1.5s 后自动消失.
   * 用于 "已复制"、"反馈已提交" 等轻量反馈.
   */
  function showToast(msg) {
    try {
      var t = document.createElement("div");
      t.className = "ai-chat-toast";
      t.textContent = msg;
      document.body.appendChild(t);
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 1500);
    } catch (e) { /* ignore */ }
  }

  /**
   * 提交反馈(点赞 / 点踩) — GET /chat/feedback?logId=...&type=...&comment=...
   *
   * <p>走 GET 是为了绕开 Halo 对 api.halo.run 组的 POST RBAC 拦截（实测 302 跳登录）。
   * 跟 /chat/stream 的设计保持一致。200 字内中文 URL 编码约 600 字节，远低于上限。
   *
   * sessionStorage 记忆本次会话的反馈状态, 避免刷新重复提交.
   */
  function submitFeedback(bar, type, comment) {
    var logId = bar.getAttribute("data-log-id");
    if (!logId) {
      showToast("反馈失败: 缺少 logId");
      return;
    }
    var key = "ai-feedback-" + logId;
    try {
      if (sessionStorage.getItem(key)) {
        showToast("已反馈过");
        return;
      }
    } catch (e) { /* sessionStorage 不可用时仍允许提交 */ }

    // 禁用按钮防重复点击（请求期间）
    var likeBtn = bar.querySelector('[data-action="like"]');
    var disBtn = bar.querySelector('[data-action="dislike"]');
    if (likeBtn) likeBtn.disabled = true;
    if (disBtn) disBtn.disabled = true;

    var apiBase = (config && config.apiBase) || "/apis/api.halo.run/v1alpha1";
    var params = "logId=" + encodeURIComponent(logId) +
      "&type=" + encodeURIComponent(type) +
      "&comment=" + encodeURIComponent(comment || "");
    var url = apiBase + "/chat/feedback?" + params;
    fetch(url)
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data && data.success) {
          // 成功后才写 sessionStorage，防止失败时锁死重试
          try { sessionStorage.setItem(key, type); } catch (e) {}
          if (type === "like" && likeBtn) {
            likeBtn.classList.add("liked");
          } else if (type === "dislike" && disBtn) {
            disBtn.classList.add("disliked");
          }
          showToast(type === "like" ? "感谢你的反馈 👍" : "已记录,谢谢反馈 👎");
        } else {
          // 失败：恢复按钮，允许重试
          if (likeBtn) likeBtn.disabled = false;
          if (disBtn) disBtn.disabled = false;
          showToast("反馈失败: " + (data && data.error ? data.error : "未知错误"));
        }
      })
      .catch(function () {
        // 网络错误：恢复按钮，允许重试
        if (likeBtn) likeBtn.disabled = false;
        if (disBtn) disBtn.disabled = false;
        showToast("反馈失败: 网络错误");
      });
  }

  /**
   * 重新生成 — 找到上一条 user message, 删本 row + 上一个 user row,
   * 截断 history 到最后一条 user, 重发同一问题.
   */
  function handleRetry(row) {
    // 找本 row 之前的最近一个 .ai-chat-row.user
    var prevUserRow = null;
    var prev = row.previousSibling;
    while (prev) {
      if (prev.classList && prev.classList.contains("ai-chat-row")
          && prev.classList.contains("user")) {
        prevUserRow = prev;
        break;
      }
      prev = prev.previousSibling;
    }
    if (!prevUserRow) {
      showToast("找不到上一条用户消息");
      return;
    }
    var userMsgEl = prevUserRow.querySelector(".ai-chat-msg.user");
    var text = userMsgEl ? userMsgEl.textContent : "";
    if (!text) return;

    // 删本 row + 上一个 user row
    if (row.parentNode) row.parentNode.removeChild(row);
    if (prevUserRow.parentNode) prevUserRow.parentNode.removeChild(prevUserRow);

    // 截断 history 到最后一条 user message 之前
    var lastUserIdx = -1;
    for (var i = history.length - 1; i >= 0; i--) {
      if (history[i].role === "user") { lastUserIdx = i; break; }
    }
    if (lastUserIdx >= 0) history = history.slice(0, lastUserIdx);

    sendMessage(text);
  }

  /**
   * 复制 AI 回答全文到剪贴板.
   */
  function handleCopy(bar) {
    var content = bar.getAttribute("data-content") || "";
    if (!content) {
      showToast("无可复制内容");
      return;
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(content)
        .then(function () { showToast("已复制"); })
        .catch(function () { fallbackCopy(content); });
    } else {
      fallbackCopy(content);
    }
  }

  function fallbackCopy(text) {
    try {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
      showToast("已复制");
    } catch (e) {
      showToast("复制失败");
    }
  }

  /**
   * 搜索结果页 AI 综合回答卡片 — 在 /search?keyword=xxx 页面顶部注入流式回答.
   *
   * 流程:
   *   1. 判 pathname === '/search' 且 query 含 keyword
   *   2. 创建 <div class="ai-search-answer-card"> 插入到 main / .search-results / body 顶部
   *   3. fetch /search/answer?keyword=... → 复用 parseSseStream 解析流
   *   4. 用现有 renderAssistant 渲染 Markdown + 引用角标
   *   5. 流结束后追加引用 chip 列表 (复用 finishStream 里的过滤 + 渲染规则)
   *   6. 失败 → 隐藏卡片, 不破坏原搜索结果
   *
   * popstate 监听 (浏览器后退/前进): URL keyword 变化时移除旧卡片并重新注入.
   * 不劫持 pushState — 避免破坏博客自身 SPA 路由, popstate 已覆盖 99% 场景.
   */
  function tryInjectSearchAnswer() {
    try {
      var path = window.location.pathname;
      if (path !== "/search") return;
      var url = new URL(window.location.href);
      var keyword = url.searchParams.get("keyword");
      if (!keyword) return;
      keyword = keyword.trim();
      if (!keyword) return;

      // 避免重复注入 (popstate 触发时先移除旧的)
      var existing = document.querySelector(".ai-search-answer-card");
      if (existing) existing.remove();

      // 探测当前主题 → 给卡片设 data-theme, 让 CSS 走对应 token 副本
      var theme = detectPageTheme();
      if (!theme && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) {
        theme = "dark";
      }
      if (!theme) theme = "light";

      // 创建卡片 DOM
      var card = document.createElement("div");
      card.className = "ai-search-answer-card";
      card.setAttribute("data-theme", theme);
      card.innerHTML =
        '<div class="ai-search-answer-header">' +
          '<span class="ai-search-answer-title">AI 综合回答</span>' +
          '<span class="ai-search-answer-badge">AI</span>' +
        '</div>' +
        '<div class="ai-search-answer-body">' +
          '<div class="ai-search-answer-loading">AI 正在思考...</div>' +
        '</div>';

      // 插入位置: main → .search-results → body 顶部
      var target = document.querySelector("main")
                || document.querySelector(".search-results")
                || document.body;
      if (target.firstChild) {
        target.insertBefore(card, target.firstChild);
      } else {
        target.appendChild(card);
      }

      // 取 apiBase: 与浮窗共用 (config 可能在 init 前为空, 走默认值)
      var apiBase = (config && config.apiBase) || "/apis/api.halo.run/v1alpha1";
      var reqUrl = apiBase + "/search/answer?keyword=" + encodeURIComponent(keyword);

      var body = card.querySelector(".ai-search-answer-body");
      var content = "";
      var citations = [];
      var loaded = false;

      function clearLoading() {
        if (loaded) return;
        loaded = true;
        if (body) body.innerHTML = "";
      }

      function renderCitations() {
        if (!card.isConnected || citations.length === 0) return;
        var oldCites = card.querySelector(".ai-search-answer-citations");
        if (oldCites) oldCites.remove();

        // 过滤: 只显示 AI 正文里实际引用的 (复用 finishStream 的 [N] 扫描)
        var usedNums = {};
        var inlineCites = body.querySelectorAll(".ai-cite-inline");
        for (var ci = 0; ci < inlineCites.length; ci++) {
          usedNums[inlineCites[ci].getAttribute("data-num")] = true;
        }
        var filtered = citations.filter(function (_, i) {
          return usedNums[String(i + 1)];
        });
        if (filtered.length === 0) filtered = citations;

        var citeEl = document.createElement("div");
        citeEl.className = "ai-search-answer-citations";
        var chipHtml = '<span class="ai-cite-label">参考文章：</span>' +
          filtered.map(function (c) {
            var originalIdx = citations.indexOf(c);
            var num = String(originalIdx + 1);
            var title = escapeHtml(c.title || "文章");
            var cUrl = c.url || "";
            if (cUrl) {
              return '<a class="ai-cite-link" data-num="' + num + '" href="' + escapeAttr(cUrl) +
                '" target="_blank">' + title + '</a>';
            }
            return '<span class="ai-cite-link ai-cite-disabled" data-num="' + num + '">' + title + '</span>';
          }).join("");
        citeEl.innerHTML = chipHtml;
        card.appendChild(citeEl);
      }

      fetch(reqUrl)
        .then(function (response) {
          if (!response.ok) throw new Error("HTTP " + response.status);
          parseSseStream(response, {
            onCitation: function (c) { citations = c; },
            onToken: function (token) {
              if (!card.isConnected) return;
              clearLoading();
              content += token;
              renderAssistant(body, content);
            },
            onDone: function () {
              if (!card.isConnected) return;
              clearLoading();
              renderAssistant(body, content);
              renderCitations();
            },
            onError: function () {
              if (card.isConnected) card.style.display = "none";
            }
          });
        })
        .catch(function () {
          // 网络失败 / 后端 5xx → 静默隐藏卡片, 不破坏原搜索
          if (card.isConnected) card.style.display = "none";
        });
    } catch (e) {
      // 防御: 任何异常都静默吞掉, 不影响页面其他功能
    }
  }

  // ===== 初始化：拉取配置 → 渲染 UI =====

  function init() {
    fetch(config.apiBase + "/widget-config")
      .then(function (res) { return res.json(); })
      .then(function (data) {
        if (data) {
          config.position = data.position || config.position;
          config.color = data.color || config.color;
          config.icon = data.icon || config.icon;
          config.triggerSize = typeof data.triggerSize === "number" ? data.triggerSize : 35;
          config.triggerLabel = data.triggerLabel || "";
          config.theme = data.theme || config.theme;
          config.welcome = data.welcome || config.welcome;
          config.shortcuts = Array.isArray(data.shortcuts) ? data.shortcuts : [];
          config.width = data.width || config.width;
          config.height = data.height || config.height;
          if (data.triggerAlign === "manual" || data.triggerAlign === "auto") config.triggerAlign = data.triggerAlign;
          if (typeof data.triggerOffsetY === "number") config.triggerOffsetY = data.triggerOffsetY;
          config.stream = data.stream !== false;
          config.allowGuest = data.allowGuest !== false;
          config.showReferences = data.showReferences !== false;
          config.showRetrievalStatus = data.showRetrievalStatus === true;
          config.showPrivacyTip = data.showPrivacyTip === true;
        }
      })
      .catch(function () { /* 用默认配置 */ })
      .finally(function () {
        applyConfig();
        var isEmbed = window.location.search.indexOf("ai-embed=1") >= 0;
        if (isContainerMode) {
          // 容器模式：直接挂载到外部指定的容器，等同于 embed 模式
          chatWindow.classList.add("ai-embed");
          chatWindow.classList.add("open");
          isOpen = true;
          container.appendChild(chatWindow);
        } else if (isEmbed) {
          chatWindow.classList.add("ai-embed");
          chatWindow.classList.add("open");
          isOpen = true;
          document.body.appendChild(chatWindow);
        } else {
          document.body.appendChild(trigger);
          document.body.appendChild(chatWindow);
        }
        addMessage("assistant", config.welcome);
        var oldShortcuts = messagesEl.querySelector(".ai-chat-shortcuts");
        if (oldShortcuts) oldShortcuts.remove();
        renderShortcuts(config.shortcuts);
        watchPageTheme();
        initResize();
        // 搜索结果页 AI 综合回答卡片 — 与 widget 浮窗独立, 即使 widget-config 拉取失败也能跑
        tryInjectSearchAnswer();
        // 避让主题「返回顶部」按钮（Dream 主题等 bottom-right 按钮常见）
        // 默认 120/165px 间距与「返回顶部」太近会视觉挤，自动上移
        if (!isContainerMode) {
          var backToTop = document.querySelector(
            '.back-to-top, .go-top, .to-top, [class*="back-to-top"]'
          );
          if (backToTop) {
            trigger.style.setProperty("bottom", "80px", "important");
            chatWindow.style.setProperty("bottom", "125px", "important");
          }
        }
        // 预览模式：URL 含 ?ai-preview=1 时自动打开浮窗
        if (!isEmbed && window.location.search.indexOf("ai-preview=1") >= 0) {
          setTimeout(function () { toggleChat(); }, 300);
        }
        // 监听 popstate (浏览器后退/前进) — URL keyword 变化时重置卡片
        window.addEventListener("popstate", function () {
          var oldCard = document.querySelector(".ai-search-answer-card");
          if (oldCard) oldCard.remove();
          tryInjectSearchAnswer();
        });
      });
  }

  // ===== Console 预览：接收 postMessage 实时更新外观 =====
  window.addEventListener("message", function (e) {
    if (!e.data || e.data.type !== "ai-preview-config") return;
    var data = e.data.payload;
    if (!data) return;

    // 视觉属性 → 重新 applyConfig
    var needApply = false;
    if (data.color) { config.color = data.color; needApply = true; }
    if (data.width) { config.width = data.width; needApply = true; }
    if (data.height) { config.height = data.height; needApply = true; }
    if (data.theme) { config.theme = data.theme; needApply = true; }
    if (data.icon) { config.icon = data.icon; needApply = true; }
    if (data.triggerLabel !== undefined) { config.triggerLabel = data.triggerLabel; needApply = true; }
    if (needApply) applyConfig();

    // 欢迎语：仅在无对话历史时更新第一条 assistant 消息
    if (data.welcome !== undefined && history.length === 0) {
      config.welcome = data.welcome;
      var firstMsg = messagesEl.querySelector(
        ".ai-chat-row.assistant .ai-chat-msg.assistant"
      );
      if (firstMsg) renderAssistant(firstMsg, data.welcome);
    }

    // 快捷问题：仅在无对话历史时重建
    if (data.shortcuts !== undefined && history.length === 0) {
      config.shortcuts = data.shortcuts;
      var oldShortcuts = messagesEl.querySelector(".ai-chat-shortcuts");
      if (oldShortcuts) oldShortcuts.remove();
      if (data.shortcuts.length) renderShortcuts(data.shortcuts);
    }

    // 游客访问：切换输入区显隐
    if (data.allowGuest !== undefined) {
      config.allowGuest = data.allowGuest;
      var inputArea = chatWindow.querySelector(".ai-chat-input-area");
      if (inputArea) inputArea.style.display = data.allowGuest ? "" : "none";
    }
  });

  init();
})();
