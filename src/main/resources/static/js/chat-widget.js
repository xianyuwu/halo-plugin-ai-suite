/**
 * AI 聊天浮窗 — 访客端 v3
 *
 * 对齐 ardot 设计稿：SVG 图标、消息前小头像、快捷问题 chip。
 * 视觉令牌由 CSS 变量管理（chat-widget.css），主题色由配置注入。
 */
(function () {
  "use strict";

  // 防重复注入
  if (document.getElementById("ai-chat-trigger")) return;

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
             '</svg>'
  };

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
    '<div class="ai-chat-resize-handle ai-chat-resize-left" data-resize="left"></div>' +
    '<div class="ai-chat-resize-handle ai-chat-resize-top" data-resize="top"></div>' +
    '<div class="ai-chat-resize-handle ai-chat-resize-corner" data-resize="corner"></div>' +
    '<div class="ai-chat-header">' +
      '<div class="ai-chat-header-avatar">' + ICON.sparkles + '</div>' +
      '<div class="ai-chat-header-info">' +
        '<h3>AI 助手</h3>' +
      '</div>' +
      '<button class="ai-chat-close" aria-label="关闭">' + ICON.close + '</button>' +
    '</div>' +
    '<div class="ai-chat-messages"></div>' +
    '<div class="ai-chat-input-area">' +
      '<textarea class="ai-chat-input" placeholder="输入你的问题…" rows="1"></textarea>' +
      '<button class="ai-chat-send" aria-label="发送">' + ICON.send + '</button>' +
    '</div>' +
    '<div class="ai-chat-disclaimer">内容由 AI 生成，请仔细甄别</div>';

  var messagesEl = chatWindow.querySelector(".ai-chat-messages");
  var inputEl = chatWindow.querySelector(".ai-chat-input");
  var sendBtn = chatWindow.querySelector(".ai-chat-send");
  var closeBtn = chatWindow.querySelector(".ai-chat-close");

  /** 根据配置更新外观 */
  function applyConfig() {
    // 主题色注入 CSS 变量，整个 widget 内的紫色调（trigger/header/user 气泡/发送按钮/链接等）自动跟随
    chatWindow.style.setProperty("--ai-chat-color", config.color);
    trigger.style.setProperty("--ai-chat-color", config.color);

    // 检测 Remix Icon 是否加载；未加载就把 trigger 内容换成 SVG fallback
    if (!isRemixIconAvailable()) {
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
    trigger.className = "position-" + posSide;
    chatWindow.className = "position-" + posSide;
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
      document.body.style.userSelect = "";
      document.body.style.cursor = "";
    }

    var handles = chatWindow.querySelectorAll(".ai-chat-resize-handle");
    for (var i = 0; i < handles.length; i++) {
      handles[i].addEventListener("mousedown", function (e) {
        mode = e.currentTarget.getAttribute("data-resize");
        startX = e.clientX;
        startY = e.clientY;
        startW = chatWindow.offsetWidth;
        startH = chatWindow.offsetHeight;
        document.body.style.userSelect = "none";
        // 拖拽期间锁定全局 cursor，避免离开 handle 时光标抖动
        document.body.style.cursor =
          mode === "left" ? "ew-resize" :
          mode === "top"  ? "ns-resize" : "nwse-resize";
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

  // ===== 核心逻辑 =====

  // 事件委托：点击 [N] inline 角标 → 找到本回复下方对应的 citation chip，滚动并高亮
  messagesEl.addEventListener("click", function (e) {
    var target = e.target;
    if (!target.classList || !target.classList.contains("ai-cite-inline")) return;
    var num = parseInt(target.getAttribute("data-num"), 10);
    if (!num) return;

    // 从角标所在的 row 往后找最近的 .ai-chat-citations
    var row = target.closest(".ai-chat-row");
    if (!row) return;
    var next = row.nextSibling;
    while (next && !(next.classList && next.classList.contains("ai-chat-citations"))) {
      next = next.nextSibling;
    }
    if (!next) return;
    var chips = next.querySelectorAll(".ai-cite-link");
    var target2 = chips[num - 1]; // num 是 1-indexed
    if (!target2) return;

    target2.scrollIntoView({ behavior: "smooth", block: "nearest" });
    // 闪烁高亮 1.2s 提示用户
    target2.classList.add("ai-cite-highlight");
    setTimeout(function () { target2.classList.remove("ai-cite-highlight"); }, 1200);
  });

  function toggleChat() {
    isOpen = !isOpen;
    chatWindow.classList.toggle("open", isOpen);
    if (isOpen) inputEl.focus();
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

  function streamChat(text) {
    // 思考中三点动画（独立元素，首 token 到达时移除）
    var typingEl = addTypingIndicator();
    var assistantEl = null;
    var content = "";
    var citations = [];

    // 流式端点用 GET：Halo 对 SSE 类型的匿名 POST 触发 CSRF 拦截会 302；GET 不被 CSRF 检查。
    // URL 长度由 Halo Netty 的 max-initial-line-length 控制（默认 4KB；需要在 application.yaml 配 64KB）。
    var url = buildChatUrl("/chat/stream", text);

    function ensureAssistantEl() {
      if (assistantEl) return;
      if (typingEl) { typingEl.remove(); typingEl = null; }
      assistantEl = addMessage("assistant", "");
    }

    fetch(url)
      .then(function (response) {
        if (!response.ok) throw new Error("服务器错误: " + response.status);
        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        var buffer = "";

        function read() {
          reader.read().then(function (result) {
            if (result.done) {
              ensureAssistantEl();
              finishStream(assistantEl, content, citations);
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
                try { citations = JSON.parse(eventData); } catch (e) {}
              } else if (eventData === "[DONE]") {
                continue;
              } else {
                try {
                  if (eventData.charAt(0) === "{") {
                    var parsed = JSON.parse(eventData);
                    if (typeof parsed.content === "string") content += parsed.content;
                  } else {
                    content += eventData;
                  }
                } catch (e) {
                  content += eventData;
                }
                ensureAssistantEl();
                renderAssistant(assistantEl, content);
                scrollToBottom();
              }
            }
            read();
          });
        }
        read();
      })
      .catch(function () {
        ensureAssistantEl();
        assistantEl.textContent = "请求失败，请稍后重试";
        finishStream(assistantEl, "", []);
      });
  }

  function normalChat(text) {
    var url = buildChatUrl("/chat", text);
    fetch(url)
      .then(function (res) { return res.json(); })
      .then(function (data) {
        var reply = data.reply || "抱歉，生成回答失败";
        addMessage("assistant", reply);
        history.push({ role: "assistant", content: reply });
      })
      .catch(function () {
        addMessage("assistant", "请求失败，请稍后重试");
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

      // citations 列表：每个 chip 前缀编号
      var citeEl = document.createElement("div");
      citeEl.className = "ai-chat-citations";
      citeEl.innerHTML =
        '<span class="ai-cite-label">参考文章：</span>' +
        filteredCites.map(function (c, fi) {
          // 重新编号：过滤后的序号
          var originalIdx = citations.indexOf(c);
          var num = String(originalIdx + 1);
          var title = escapeHtml(c.title || "文章");
          var url = c.url || "";
          if (url) {
            return '<a class="ai-cite-link" data-num="' + num + '" href="' + escapeAttr(url) +
              '" target="_blank">' + title + '</a>';
          }
          return '<span class="ai-cite-link ai-cite-disabled" data-num="' + num + '">' + title + '</span>';
        }).join("");
      // citations 要插在 row 之后（不是气泡之后），保持与气泡左对齐
      var row = el.closest ? (el.closest(".ai-chat-row") || el) : el;
      row.parentNode.insertBefore(citeEl, row.nextSibling);
      scrollToBottom();
    }
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
      avatar.innerHTML = ICON.sparkles;
      row.appendChild(avatar);
    }

    var msgEl = document.createElement("div");
    msgEl.className = "ai-chat-msg " + role;
    if (role === "assistant") {
      renderAssistant(msgEl, text);
    } else {
      msgEl.textContent = text;
    }
    row.appendChild(msgEl);

    messagesEl.appendChild(row);
    scrollToBottom();
    return msgEl;
  }

  /** 插入「思考中」三点跳动动画元素 */
  function addTypingIndicator() {
    var el = document.createElement("div");
    el.className = "ai-chat-typing";
    el.innerHTML = "<span></span><span></span><span></span>";
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
    html = html.replace(/\[(\d{1,2})\]/g, function (_, num) {
      return '<sup class="ai-cite-inline" data-num="' + num + '">' + num + '</sup>';
    });
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
    wrap.innerHTML = '<div class="ai-chat-shortcuts-label">你可以试试问我：</div>';

    var row = document.createElement("div");
    row.className = "ai-chat-shortcuts-row";
    items.forEach(function (q) {
      var btn = document.createElement("button");
      btn.className = "ai-chat-shortcut";
      btn.type = "button";
      btn.textContent = q;
      btn.addEventListener("click", function () { sendMessage(q); });
      row.appendChild(btn);
    });
    wrap.appendChild(row);

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

  // ===== 初始化：拉取配置 → 渲染 UI =====

  function init() {
    fetch(config.apiBase + "/widget-config")
      .then(function (res) { return res.json(); })
      .then(function (data) {
        if (data) {
          config.position = data.position || config.position;
          config.color = data.color || config.color;
          config.theme = data.theme || config.theme;
          config.welcome = data.welcome || config.welcome;
          config.shortcuts = Array.isArray(data.shortcuts) ? data.shortcuts : [];
          config.width = data.width || config.width;
          config.height = data.height || config.height;
          if (data.triggerAlign === "manual" || data.triggerAlign === "auto") config.triggerAlign = data.triggerAlign;
          if (typeof data.triggerOffsetY === "number") config.triggerOffsetY = data.triggerOffsetY;
          config.stream = data.stream !== false;
          config.allowGuest = data.allowGuest !== false;
        }
      })
      .catch(function () { /* 用默认配置 */ })
      .finally(function () {
        applyConfig();
        document.body.appendChild(trigger);
        document.body.appendChild(chatWindow);
        addMessage("assistant", config.welcome);
        renderShortcuts(config.shortcuts);
        // auto 模式：监听博客主题切换，实时跟随（无需刷新）
        watchPageTheme();
        // 浮窗左/上/角拖拽改尺寸
        initResize();
      });
  }

  init();
})();
