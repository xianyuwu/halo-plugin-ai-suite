/**
 * 思维导图沉浸式区块 — 访客端（B 方案，v3 默认折叠 + 3-4 级 + 居中）
 *
 * 形态：在「正文容器最上方」注入一个 `<section class="halo-mindmap-block">` 专属区块，
 * 内嵌 markmap 渲染的思维导图，节点点击可跳原文锚点。
 *
 * v2 → v3 变更点：
 *   1) 默认折叠：localStorage 无记录时默认折叠（v2 是默认展开）；
 *      key 保持 "halo-mindmap-collapsed:<postName>" 不变，老用户偏好保留。
 *   2) 节点层级 3-4 级：markmap initialExpandLevel 改为 4，配 color() 加 L3/L4 配色；
 *      后端 markdown 已含 #/##/###/####，前端不重新 parse。
 *   3) 展开后内容居中：fit() 后手动调整 svg viewBox 加 padding +
 *      preserveAspectRatio="xMidYMid meet" + CSS 给 body 固定高度（60vh，
 *      max 480-560px），让 markmap 真正视觉居中。
 *
 * 位置变更（v2 继承）：
 *   区块插在正文容器（.post-content / article body / 等）的第一个子元素位置
 *   （即 H1 / 文章元信息之后，正文第一段之前）
 *
 * 与最初版本 (快捷入口 + bottom sheet 弹层) 的区别：
 * - 标题旁快捷按钮已移除，入口收敛到正文顶部区块
 * - 弹层/遮罩整套移除
 * - 默认折叠（v3 新行为），用户展开后状态 per-post 写 localStorage
 *
 * API：/apis/api.ai-suite.halo.run/v1alpha1/mindmap?postName=xxx
 * markmap bundle 路径：/plugins/ai-suite/assets/res/js/markmap-bundle.min.js
 */
(function () {
  "use strict";

  // ===== 配置 =====
  var API_BASE = "/apis/api.ai-suite.halo.run/v1alpha1";
  var MARKMAP_BUNDLE = "/plugins/ai-suite/assets/res/js/markmap-bundle.min.js";
  var BLOCK_CLASS = "halo-mindmap-block";
  var mindmapConfig = {
    theme: "inherit",
    color: "#4F46E5"
  };
  var mindmapConfigLoaded = false;
  var mindmapConfigLoading = false;

  // SVG 图标：脑图（tree structure）
  var ICON_MINDMAP =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
    '<circle cx="12" cy="5" r="2.5"/>' +
    '<circle cx="5" cy="18" r="2.5"/>' +
    '<circle cx="19" cy="18" r="2.5"/>' +
    '<path d="M12 7.5v4M9.5 11.5L7 15.5M14.5 11.5L17 15.5"/>' +
    "</svg>";

  // ▼ 展开 / ▶ 折叠
  var ICON_CHEVRON_DOWN =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M6 9l6 6 6-6"/></svg>';
  // 重新生成
  var ICON_REFRESH =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><path d="M21 3v5h-5"/></svg>';
  // 居中/适配视图
  var ICON_FIT =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M16 3h3a2 2 0 0 1 2 2v3"/>' +
    '<path d="M8 21H5a2 2 0 0 1-2-2v-3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/>' +
    '<circle cx="12" cy="12" r="3"/></svg>';

  // ===== 工具：文章检测 / 主题检测 / 脚本加载 =====

  // 文章正文容器选择器（按优先级排序的降级链）。
  // isArticlePage() 和 findArticleContainer() 共用此列表，保证"识别文章"和"注入位置"用同一套认知。
  // 修复 v2 bug：Dream 1.3.2 主题的正文容器 class = .main-content.article
  // （v2 原版 findArticleContainer 漏了这个选择器 → 链全 miss → 区块静默退出）
  var ARTICLE_CONTAINER_SELECTORS = [
    ".post-content",
    ".article-content",
    ".entry-content",
    ".markdown-body",
    "article#content",
    "article.post",
    "article .content",
    "[class*='post-content']",
    ".main-content.article",
    ".joe_detail__article",
    "#post-inner"
  ];

  function isArticlePage() {
    var path = location.pathname;
    if (isListPagePath(path)) return false;
    if (getInjectedPageContext()) return true;
    var postEl = document.querySelector("[data-target='Post'][data-id]");
    if (postEl && isPostContentContainer(postEl)) return true;
    if (document.querySelector("[data-target='Post'].main-content.article[data-id]")) return true;
    if (/\/page\/\d+/.test(path)) return false;
    if (path.startsWith("/search")) return false;
    return !!findArticleContainer();
  }

  function isListPagePath(path) {
    var normalized = (path || "/").replace(/\/+$/, "") || "/";
    if (normalized === "/") return true;
    if (normalized === "/archives" || /^\/archives\/page\/\d+(\/|$)/.test(normalized)) return true;
    return /^\/(categories|tags)(\/|$)/.test(normalized)
      || /^\/(search|links|about)(\/|$)/.test(normalized)
      || /\/page\/\d+(\/|$)/.test(normalized);
  }

  function isPostContentContainer(el) {
    if (!el) return false;
    if (el.matches && el.matches(".main-content.article")) return true;
    return !!(el.querySelector && el.querySelector(".main-content.article,[class*='post-content'],article .content"));
  }

  function getInjectedPageContext() {
    var contextEl = document.getElementById("ai-suite-page-context");
    if (contextEl && contextEl.textContent) {
      try {
        var parsed = JSON.parse(contextEl.textContent);
        if (parsed && parsed.type === "post" && parsed.postName) return parsed;
      } catch (e) {
        console.debug("[MindMap] 页面上下文 JSON 解析失败", e);
      }
    }
    var ctx = window.__AI_SUITE_PAGE__;
    return ctx && ctx.type === "post" && ctx.postName ? ctx : null;
  }

  function getPostName() {
    var postEl = document.querySelector("[data-target='Post'][data-id]");
    if (postEl) return postEl.getAttribute("data-id");
    var ctx = getInjectedPageContext();
    if (ctx) return ctx.postName;
    var commentEl = document.querySelector("[id^='comment-content-halo-run-Post-']");
    if (commentEl && commentEl.id) {
      return commentEl.id.replace(/^comment-content-halo-run-Post-/, "");
    }
    // 不降级到 canonical/URL slug：/mindmap 端点只认 Post 的 metadata.name（UUID），
    // 传 slug 会被判"文章不存在"→ 显示"生成失败"。拿不到 UUID 时返回空，由
    // mountMindmapBlock 放弃本次注入，靠重试机制等 data-id 就绪后再取（pjax 后很快出现）。
    return "";
  }

  function detectPageTheme() {
    var html = document.documentElement;
    var body = document.body;
    var classStr = ((html.className || "") + " " + (body.className || "")).toLowerCase();
    var dataTheme = (
      html.getAttribute("data-theme") ||
      body.getAttribute("data-theme") ||
      ""
    ).toLowerCase();
    // 显式 data-theme 优先，避免切到 light 后页面上仍残留 dark class 被误判。
    if (dataTheme === "dark") return "dark";
    if (dataTheme === "light") return "light";
    if (/\b(light|light-mode|theme-light|day)\b/.test(classStr)) return "light";
    if (/\b(dark|dark-mode|theme-dark|night)\b/.test(classStr)) return "dark";
    for (var i = 0; i < 2; i++) {
      var bg = window.getComputedStyle(i === 0 ? body : html).backgroundColor;
      var m = (bg || "").match(
        /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?\s*\)$/
      );
      if (!m) continue;
      var alpha = m[4] === undefined ? 1 : parseFloat(m[4]);
      if (alpha < 0.5) continue;
      var lum = 0.299 * +m[1] + 0.587 * +m[2] + 0.114 * +m[3];
      return lum < 128 ? "dark" : "light";
    }
    return "auto";
  }

  function detectTheme() {
    var componentTheme = mindmapConfig.theme || "inherit";
    if (componentTheme === "light" || componentTheme === "dark") return componentTheme;
    if (componentTheme === "system") {
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light";
    }
    var pageTheme = detectPageTheme();
    if (componentTheme === "auto" || componentTheme === "inherit") {
      if (pageTheme === "light" || pageTheme === "dark") return pageTheme;
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light";
    }
    return pageTheme;
  }

  function loadMindmapConfig(callback) {
    if (mindmapConfigLoaded) {
      callback();
      return;
    }
    if (mindmapConfigLoading) {
      setTimeout(function () { loadMindmapConfig(callback); }, 50);
      return;
    }
    mindmapConfigLoading = true;
    fetch(API_BASE + "/widget-config")
      .then(function (res) { return res.ok ? res.json() : {}; })
      .then(function (data) {
        mindmapConfig.theme = data.mindmapTheme || "inherit";
        mindmapConfig.color = data.mindmapColor || data.color || "#4F46E5";
      })
      .catch(function () {
        mindmapConfig.theme = "inherit";
        mindmapConfig.color = "#4F46E5";
      })
      .finally(function () {
        mindmapConfigLoaded = true;
        mindmapConfigLoading = false;
        callback();
      });
  }

  function hexToRgbParts(hex) {
    var value = (hex || "").trim();
    if (!/^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value)) return null;
    if (value.length === 4) {
      value = "#" + value[1] + value[1] + value[2] + value[2] + value[3] + value[3];
    }
    var n = parseInt(value.slice(1), 16);
    return ((n >> 16) & 255) + ", " + ((n >> 8) & 255) + ", " + (n & 255);
  }

  function applyMindmapColor(block) {
    if (!block) return;
    var color = mindmapConfig.color || "#4F46E5";
    block.style.setProperty("--ai-mindmap-color", color);
    var rgb = hexToRgbParts(color);
    if (rgb) block.style.setProperty("--ai-mindmap-color-rgb", rgb);
  }

  function syncMindmapTheme(theme) {
    var nextTheme = theme || detectTheme();
    var block = document.querySelector("." + BLOCK_CLASS);

    if (block) {
      var oldTheme = block.getAttribute("data-theme") || "";
      block.setAttribute("data-theme", nextTheme);
      applyMindmapColor(block);
      block.classList.toggle("halo-mindmap-block--dark", nextTheme === "dark");
      block.classList.toggle("halo-mindmap-block--light", nextTheme !== "dark");

      if (currentMarkmapSvg) {
        currentMarkmapSvg.style.color = nextTheme === "dark" ? "#E8E8EE" : "#172033";
      }

      var md = block.getAttribute("data-markdown");
      var expanded = !block.classList.contains("halo-mindmap-block--collapsed");
      if (oldTheme && oldTheme !== nextTheme && md && expanded) {
        currentMarkmap = null;
        currentMarkmapSvg = null;
        renderAfterExpand(block);
      }
    }

  }

  function watchThemeChanges() {
    if (typeof MutationObserver === "undefined") return;
    var scheduled = false;
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(function () {
        scheduled = false;
        syncMindmapTheme();
      });
    }

    var observer = new MutationObserver(schedule);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["class", "style", "data-theme"]
    });
    if (document.body) {
      observer.observe(document.body, {
        attributes: true,
        attributeFilter: ["class", "style", "data-theme"]
      });
    }
    try {
      var media = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)");
      if (media) {
        if (typeof media.addEventListener === "function") {
          media.addEventListener("change", schedule);
        } else if (typeof media.addListener === "function") {
          media.addListener(schedule);
        }
      }
    } catch (e) { /* 容错 */ }
  }

  var loadedScripts = {};
  function loadScript(src) {
    if (loadedScripts[src]) return loadedScripts[src];
    loadedScripts[src] = new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = src;
      s.defer = true;
      s.onload = resolve;
      s.onerror = function () {
        loadedScripts[src] = null;
        reject(new Error("加载失败: " + src));
      };
      document.head.appendChild(s);
    });
    return loadedScripts[src];
  }

  var markmapReady = null;
  function ensureMarkmap() {
    if (markmapReady) return markmapReady;
    markmapReady = loadScript(MARKMAP_BUNDLE)
      .then(function () {
        if (!window.markmap || !window.markmap.Markmap || !window.markmap.Transformer) {
          throw new Error("markmap 加载完成但全局对象不完整");
        }
      });
    return markmapReady;
  }

  // 模块级持有当前 markmap 实例 / svg 节点 — 折叠/重建/居中时复用 + 检查
  // 必须放在所有用到 currentMarkmap / currentMarkmapSvg 的函数之前，
  // 否则 fitAndCenterMarkmap 在加载阶段会有 TDZ 引用错。
  var currentMarkmap = null;
  var currentMarkmapSvg = null;
  // 每次加载或销毁都会递增，用于阻止上一篇文章的异步响应回写到新页面。
  var mindmapRequestSerial = 0;
  var expandRenderTimer = null;

  function isElementAttached(el) {
    return el && el.parentNode && document.body.contains(el.parentNode);
  }

  function renderAfterExpand(block, scrollAfterRender) {
    if (!block) return;
    if (expandRenderTimer) clearTimeout(expandRenderTimer);

    var done = false;
    var body = block.querySelector(".halo-mindmap-body");
    function run() {
      if (done) return;
      done = true;
      if (body) body.removeEventListener("transitionend", onEnd);
      requestAnimationFrame(function () {
        try { ensureMarkmapRendered(); } catch (e) { console.warn("[MindMap] 展开渲染失败:", e); }
        // 用户主动展开时，渲染完成后平滑滚动让脑图进入视口居中，避免手动下滑
        if (scrollAfterRender) {
          try {
            var b = document.querySelector("." + BLOCK_CLASS);
            if (b) b.scrollIntoView({ behavior: "smooth", block: "center" });
          } catch (e) { /* 容错 */ }
        }
      });
    }
    function onEnd(event) {
      if (!event || event.target === body) run();
    }

    if (body) body.addEventListener("transitionend", onEnd, { once: true });
    expandRenderTimer = setTimeout(run, 380);
  }

  function expandMindmapBlock(block, postName) {
    if (!block) return;
    block.classList.remove("halo-mindmap-block--collapsed");
    setCollapsed(postName || block.getAttribute("data-post"), false);
    // 第二个参数 true：用户主动展开，渲染完成后触发滚动到脑图
    renderAfterExpand(block, true);
  }

  // ===== H1 探测：降级链 =====
  //
  // 顺序（按 Dream 主题结构由稳到散）：
  //   1. <article> 内的 <h1>         — 主流文章页
  //   2. role="banner" 内的 <h1>     — 一些主题的 banner 容器
  //   3. <main> 内的 <h1>             — 通用回退
  //   4. body 内第一个 <h1>          — 最后兜底
  // 全部失败 → console.warn + 静默退出
  function findH1() {
    var selectors = [
      "article h1",
      "[role='banner'] h1",
      "main h1",
      "body h1"
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) return { el: el, usedSelector: selectors[i] };
    }
    return null;
  }

  // ===== 正文容器探测：降级链 =====
  //
  // 顺序（按 Dream 主题结构由稳到散），找到一个就停：
  //   a. .post-content                       — Halo / Dream 主流
  //   b. article .content | .post-body | .entry-content
  //   c. <article> 本身                       — 兜底；正文为空时直接 prepend 到 article
  //   d. <main> 的第一个文章容器              — 最后兜底
  //   e. 全部失败 → console.debug（噪音小）后让主流程静默退出
  //
  // 找到容器后，区块插入位置：
  //   - 容器第一个子元素是 H1 自身 → 用 H1.nextSibling 作锚点（prepend 到 H1 之后）
  //   - 否则 → container.insertBefore(block, container.firstChild)
  function findArticleContainer() {
    // 1) 精确正文选择器 — 命中即信任，跳过 rect 尺寸校验
    //    Why: pjax 首次挂载（pjax:end +30ms）时布局未稳定，getBoundingClientRect
    //    返回的尺寸可能暂时低于阈值，导致 .post-content 这类主题明确的容器被
    //    isUsableArticleContainer 否决、降级到启发式，把脑图区块插到页面顶部
    //    （表现为“打开文章脑图在页面顶部，刷新后恢复正常”）。这些选择器本身
    //    已是强信号，只需排除评论区等即可信任。
    var precise = ARTICLE_CONTAINER_SELECTORS.concat([
      "article .post-body",
      "article .entry-content"
    ]);
    for (var i = 0; i < precise.length; i++) {
      var el = document.querySelector(precise[i]);
      if (el && !isExcludedContainer(el)) {
        return { container: el, usedSelector: precise[i] };
      }
    }
    // 2) <article> 语义兜底 — 仍走完整校验（含 rect 尺寸 + 内容判定）
    var articleEl = document.querySelector("article");
    if (isUsableArticleContainer(articleEl)) {
      return { container: articleEl, usedSelector: "article" };
    }
    // 3) 启发式打分（内部仍用 isUsableArticleContainer 含 rect 校验）
    return findArticleContainerByHeuristics();
  }

  function isUsableArticleContainer(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width < 280 || rect.height < 80) return false;
    if (isExcludedContainer(el)) return false;
    var text = (el.innerText || "").trim();
    return text.length >= 120 || el.querySelector("p,h1,h2,h3,h4,blockquote,pre,ul,ol");
  }

  function isExcludedContainer(el) {
    if (!el) return true;
    if (el.closest("#ai-chat-window,.ai-search-overlay,.halo-mindmap-block")) return true;
    var marker = ((el.id || "") + " " + (el.className || "") + " " + el.tagName).toLowerCase();
    return /comment|reply|toc|aside|sidebar|widget|footer|header|nav|menu|modal|search|share|copyright|operate|action/.test(marker);
  }

  function findArticleContainerByHeuristics() {
    var title = document.querySelector("h1");
    var titleText = title ? (title.innerText || "").trim() : "";
    var nodes = Array.prototype.slice.call(document.querySelectorAll("article, main, section, div"));
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!isUsableArticleContainer(el)) continue;
      var text = (el.innerText || "").trim();
      var rect = el.getBoundingClientRect();
      var score = 0;
      if (el.tagName === "ARTICLE") score += 35;
      if (/\b(markdown|content|article|post|entry|detail|main)\b/i.test((el.id || "") + " " + (el.className || ""))) score += 25;
      if (el.querySelector("p")) score += Math.min(30, el.querySelectorAll("p").length * 4);
      if (el.querySelector("h1,h2,h3")) score += Math.min(24, el.querySelectorAll("h1,h2,h3").length * 4);
      if (titleText && text.indexOf(titleText) >= 0) score += 12;
      if (text.length > 600) score += 18;
      if (text.length > 1400) score += 10;
      if (rect.width > 520) score += 8;
      if (rect.height > 360) score += 8;
      if (el.querySelector("[id^='comment-content-halo-run-Post-']")) score -= 35;
      if (best && best.contains(el) && score >= bestScore - 8) score += 6;
      if (score > bestScore) {
        bestScore = score;
        best = el;
      }
    }
    return best && bestScore >= 45
      ? { container: best, usedSelector: "heuristic:" + describeElement(best) }
      : null;
  }

  function describeElement(el) {
    if (!el) return "";
    var value = el.tagName.toLowerCase();
    if (el.id) value += "#" + el.id;
    if (el.className) value += "." + String(el.className).trim().split(/\s+/).slice(0, 4).join(".");
    return value;
  }

  // ===== 折叠状态（per-post localStorage） =====
  //
  // 默认值：
  //   localStorage 无记录 → 默认折叠（返回 true），首屏更轻、用户按需展开。
  //   老用户偏好保留：兼容 "halo-mindmap-collapsed:<postName>"，
  //                   新增 URL slug key，避免切换主题后 postName 来源变化导致偏好丢失。
  // 兼容说明：已存 "0"（用户偏好展开）的，下次进入仍展开；
  //           已存 "1"（用户偏好折叠）的仍折叠；没存过则折叠。
  function collapseKey(postName) {
    return "halo-mindmap-collapsed:" + (postName || "_");
  }

  function routeCollapseKey() {
    var segs = location.pathname.replace(/\/+$/, "").split("/").filter(Boolean);
    if (!segs.length) return "";
    return collapseKey("route:" + segs.join("/"));
  }

  function collapseKeys(postName) {
    var keys = [];
    var routeKey = routeCollapseKey();
    var postKey = collapseKey(postName);
    if (routeKey) keys.push(routeKey);
    if (postKey && keys.indexOf(postKey) < 0) keys.push(postKey);
    return keys;
  }

  function isCollapsed(postName) {
    try {
      var keys = collapseKeys(postName);
      for (var i = 0; i < keys.length; i++) {
        var v = window.localStorage.getItem(keys[i]);
        // 显式 "0" → 展开偏好（老用户 v2 时期点过"展开"留下的）
        if (v === "0") return false;
        // 显式 "1" → 折叠偏好
        if (v === "1") return true;
      }
      // 默认：未存过 → 折叠
      return true;
    } catch (e) {
      // 隐私模式 / 异常 → 也走默认折叠
      return true;
    }
  }

  function setCollapsed(postName, collapsed) {
    try {
      var keys = collapseKeys(postName);
      for (var i = 0; i < keys.length; i++) {
        window.localStorage.setItem(keys[i], collapsed ? "1" : "0");
      }
    } catch (e) { /* 隐私模式可能写不进去，忽略 */ }
  }

  // ===== 主流程：挂区块 + 渲染 markmap =====

  /**
   * 主入口。
   * v2 位置调整：
   *   1) 探测正文容器（5 步降级链）
   *   2) 把区块作为正文容器的第一个子元素插入
   *   3) 立即请求 markmap 数据 → 渲染
   * 失败（容器 / postName 任何一个拿不到）→ console.debug 后静默退出。
   */
  function mountMindmapBlock() {
    if (!mindmapConfigLoaded) {
      loadMindmapConfig(mountMindmapBlock);
      return;
    }
    // 防重复
    if (document.querySelector("." + BLOCK_CLASS)) return;

    if (!isArticlePage()) return;
    var postName = getPostName();
    if (!postName) {
      console.debug("[MindMap] postName 为空，跳过注入");
      return;
    }

    // 1) 探测正文容器（v2 锚点）
    var found = findArticleContainer();
    if (!found || !found.container) {
      console.debug(
        "[MindMap] 降级链全部失败，未找到可注入的正文容器，" +
        "已尝试：.post-content → article .content → article .post-body → " +
        "article .entry-content → article → main"
      );
      return;
    }

    var theme = detectTheme();
    var collapsed = isCollapsed(postName);

    // 3) 注入区块到正文容器最上方
    var block = buildBlock(postName, theme, collapsed);
    applyMindmapColor(block);
    insertBlockAtTopOfContainer(block, found.container);
    block.setAttribute("data-container-selector", found.usedSelector || "");

    syncMindmapTheme(theme);

    // 4) 请求数据 + 渲染
    // v3 优化：折叠态不渲染 markmap（body height=0 → svg 拿不到 size）。
    //    策略：fetch 完数据后暂存到 block.dataset.markdown；展开时如果还没渲染过，触发渲染。
    loadMindMapData(postName, block, theme);
  }

  /**
   * PJAX 首次切页时，主题正文 DOM 可能分多批替换：
   * 第一批只有外层 .column-main / .card，第二批才出现真正的
   * [data-target="Post"].main-content.article。老逻辑一旦把导图插到外层，
   * 后续因为“已有 .halo-mindmap-block”就不再重试，表现为导图跑到访客页最上方；
   * 刷新走完整 SSR HTML，正文容器一开始就存在，所以位置又恢复正常。
   *
   * 这里在后续重试/定时检查时主动校正位置：只移动已有 block，不重新请求数据。
   */
  function ensureMindmapPlacement() {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!block || !isArticlePage()) return;
    var found = findArticleContainer();
    if (!found || !found.container) return;
    if (block.parentNode !== found.container) {
      insertBlockAtTopOfContainer(block, found.container);
      block.setAttribute("data-container-selector", found.usedSelector || "");
      return;
    }
    if (!isBlockAtExpectedPosition(block, found.container)) {
      insertBlockAtTopOfContainer(block, found.container);
    }
  }

  function isBlockAtExpectedPosition(block, container) {
    if (!block || !container || block.parentNode !== container) return false;
    var h1El = findH1();
    var anchorIsH1 = h1El && h1El.el && h1El.el.parentNode === container
      && firstElementChild(container) === h1El.el;
    if (anchorIsH1) {
      return nextElementSibling(h1El.el) === block;
    }
    return firstElementChild(container) === block;
  }

  function firstElementChild(container) {
    return container ? container.firstElementChild : null;
  }

  function nextElementSibling(el) {
    return el ? el.nextElementSibling : null;
  }

  /**
   * 把区块插到正文容器的「最上方第一个内容元素位置」。
   * 边缘情况：
   *   - 容器第一个子元素就是 H1 自身（空文章 / 只有标题）→ prepend 到 H1 之后
   *   - 容器为空 → 直接 prepend
   *   - 其他情况 → container.insertBefore(block, container.firstChild)
   */
  function insertBlockAtTopOfContainer(block, container) {
    var h1El = findH1();
    var anchorIsH1 = h1El && h1El.el && h1El.el.parentNode === container
      && firstElementChild(container) === h1El.el;
    if (anchorIsH1) {
      // H1 是 firstChild → 把区块放到 H1 后面
      container.insertBefore(block, nextElementSibling(h1El.el));
    } else {
      // 标准情况：作为第一个子元素插入
      container.insertBefore(block, firstElementChild(container));
    }
  }

  /**
   * 构造专属区块 DOM：
   *   <section class="halo-mindmap-block" data-state="loading|ready|error">
   *     <header class="halo-mindmap-head">...折叠按钮...</header>
   *     <div class="halo-mindmap-body">
   *       <svg class="halo-mindmap-svg"></svg>  ← markmap 挂载点
   *       <div class="halo-mindmap-status">...</div>
   *     </div>
   *     <footer class="halo-mindmap-foot">...元信息...</footer>
   *   </section>
   */
  function buildBlock(postName, theme, collapsed) {
    var block = document.createElement("section");
    block.className = BLOCK_CLASS +
      (collapsed ? " halo-mindmap-block--collapsed" : "") +
      (theme === "dark" ? " halo-mindmap-block--dark" : " halo-mindmap-block--light");
    block.setAttribute("data-theme", theme);
    block.setAttribute("data-post", postName);
    block.setAttribute("data-state", "loading");

    // 头部
    // v3 头部布局：icon + "AI 思维导图" + 章节数 pill（loading 态是 "--",数据回来后回填）
    // + 展开/折叠主按钮（贴在标题栏右边的显眼大按钮） + 重新生成图标按钮
    var head = document.createElement("header");
    head.className = "halo-mindmap-head";

    var headLeft = document.createElement("div");
    headLeft.className = "halo-mindmap-head-left";
    var icon = document.createElement("span");
    icon.className = "halo-mindmap-head-icon";
    icon.innerHTML = ICON_MINDMAP;
    var label = document.createElement("span");
    label.className = "halo-mindmap-head-label";
    label.textContent = "AI 思维导图";
    var pill = document.createElement("span");
    pill.className = "halo-mindmap-head-pill";
    pill.setAttribute("data-state", "loading");
    pill.textContent = "加载章节中…";
    headLeft.appendChild(icon);
    headLeft.appendChild(label);
    headLeft.appendChild(pill);

    var headRight = document.createElement("div");
    headRight.className = "halo-mindmap-head-actions";
    var regenBtn = document.createElement("button");
    regenBtn.type = "button";
    regenBtn.className = "halo-mindmap-action";
    regenBtn.title = "重新生成";
    regenBtn.setAttribute("aria-label", "重新生成思维导图");
    regenBtn.innerHTML = ICON_REFRESH;
    regenBtn.addEventListener("click", function () {
      var b = document.querySelector("." + BLOCK_CLASS);
      if (!b) return;
      var pn = b.getAttribute("data-post");
      // v3：重新生成时把 pill 回到 loading 态
      var p = b.querySelector(".halo-mindmap-head-pill");
      if (p) { p.setAttribute("data-state", "loading"); p.textContent = "加载章节中…"; }
      loadMindMapData(pn, b, b.getAttribute("data-theme") || theme);
    });
    var fitBtn = document.createElement("button");
    fitBtn.type = "button";
    fitBtn.className = "halo-mindmap-action";
    fitBtn.title = "居中显示";
    fitBtn.setAttribute("aria-label", "居中显示思维导图");
    fitBtn.innerHTML = ICON_FIT;
    fitBtn.addEventListener("click", function () {
      try { fitAndCenterMarkmap(); } catch (e) { console.warn("[MindMap] 居中失败:", e); }
    });
    // 标题栏右侧的"展开"主按钮：v3 默认折叠态下，标题栏只看到这一颗按钮（regen 在折叠态也藏起来）
    var expandBtn = document.createElement("button");
    expandBtn.type = "button";
    expandBtn.className = "halo-mindmap-expand";
    expandBtn.title = "展开思维导图";
    expandBtn.setAttribute("aria-label", "展开思维导图");
    expandBtn.innerHTML = ICON_CHEVRON_DOWN + '<span>展开</span>';
    expandBtn.addEventListener("click", function () {
      var b = document.querySelector("." + BLOCK_CLASS);
      if (!b) return;
      expandMindmapBlock(b, b.getAttribute("data-post"));
    });
    var collapseBtn = document.createElement("button");
    collapseBtn.type = "button";
    collapseBtn.className = "halo-mindmap-action halo-mindmap-action--collapse";
    collapseBtn.title = "折叠";
    collapseBtn.setAttribute("aria-label", "折叠思维导图");
    collapseBtn.innerHTML = ICON_CHEVRON_DOWN;
    collapseBtn.addEventListener("click", function () {
      var b = document.querySelector("." + BLOCK_CLASS);
      if (!b) return;
      var willCollapse = !b.classList.contains("halo-mindmap-block--collapsed");
      b.classList.toggle("halo-mindmap-block--collapsed", willCollapse);
      setCollapsed(b.getAttribute("data-post"), willCollapse);
    });
    headRight.appendChild(regenBtn);
    headRight.appendChild(fitBtn);
    headRight.appendChild(expandBtn);
    headRight.appendChild(collapseBtn);

    head.appendChild(headLeft);
    head.appendChild(headRight);

    // 主体
    var body = document.createElement("div");
    body.className = "halo-mindmap-body";

    // 默认占位：loading 状态
    var status = document.createElement("div");
    status.className = "halo-mindmap-status";
    status.innerHTML =
      '<div class="halo-mindmap-loading">' +
      '  <div class="halo-mindmap-loading-dots"><span></span><span></span><span></span></div>' +
      '  <span>正在加载思维导图...</span>' +
      "</div>";
    body.appendChild(status);

    // 底部
    var foot = document.createElement("footer");
    foot.className = "halo-mindmap-foot";
    var footMeta = document.createElement("span");
    footMeta.className = "halo-mindmap-foot-meta";
    footMeta.textContent = "文章结构总览";
    var footNote = document.createElement("span");
    footNote.className = "halo-mindmap-foot-note";
    footNote.textContent = "由 AI智能套件生成";
    foot.appendChild(footMeta);
    foot.appendChild(footNote);

    block.appendChild(head);
    block.appendChild(body);
    block.appendChild(foot);

    return block;
  }

  // ===== 数据加载 + 渲染 =====

  function setBlockState(block, state) {
    block.setAttribute("data-state", state);
  }

  function showStatus(block, kind, message) {
    var body = block.querySelector(".halo-mindmap-body");
    if (!body) return;
    body.innerHTML = "";
    if (kind === "loading") {
      body.innerHTML =
        '<div class="halo-mindmap-status">' +
        '  <div class="halo-mindmap-loading">' +
        '    <div class="halo-mindmap-loading-dots"><span></span><span></span><span></span></div>' +
        '    <span>正在加载思维导图...</span>' +
        "  </div>" +
        "</div>";
    } else {
      body.innerHTML =
        '<div class="halo-mindmap-status">' +
        '  <div class="halo-mindmap-error">' +
        '    <span class="halo-mindmap-error-icon">⚠️</span>' +
        '    <span>' + escapeHtml(message || "加载失败") + '</span>' +
        "  </div>" +
        "</div>";
    }
  }

  function loadMindMapData(postName, block, theme) {
    var requestSerial = ++mindmapRequestSerial;
    setBlockState(block, "loading");
    showStatus(block, "loading");
    // v3：头部 pill 回到 loading 态
    setPillState(block, "loading", "加载章节中…");
    var url = API_BASE + "/mindmap?postName=" + encodeURIComponent(postName);
    fetch(url)
      .then(function (res) {
        if (!res.ok) throw new Error("HTTP " + res.status);
        return res.json();
      })
      .then(function (data) {
        if (!isActiveMindmapRequest(requestSerial, postName, block)) return;
        if (data.error) {
          setBlockState(block, "error");
          showStatus(block, "error", data.error);
          setPillState(block, "error", "生成失败");
          return;
        }
        var markdown = data.markdown;
        if (!markdown) {
          setBlockState(block, "error");
          showStatus(block, "error", "未返回思维导图数据");
          setPillState(block, "error", "无数据");
          return;
        }
        setBlockState(block, "ready");
        // v3: 缓存 markdown 到 dataset,展开时如果还没渲染则直接用(避免重新 fetch)
        block.setAttribute("data-markdown", markdown);
        // v3: 折叠/展开态都更新头部 pill(用户折叠时也能看到章节数)
        try { updateChapterPill(markdown); } catch (e) { console.warn("[MindMap] updateChapterPill 失败:", e); }
        // v3: 折叠态不渲染(避免 svg 容器 height=0 反复重试);
        //     展开态才渲染。展开按钮会触发 ensureMarkmapRendered()
        if (!block.classList.contains("halo-mindmap-block--collapsed")) {
          renderAfterExpand(block);
        }
      })
      .catch(function (e) {
        if (!isActiveMindmapRequest(requestSerial, postName, block)) return;
        console.error("[MindMap] fetch 异常:", e);
        setBlockState(block, "error");
        showStatus(block, "error", "加载失败：" + e.message);
        setPillState(block, "error", "加载失败");
      });
  }

  function isActiveMindmapRequest(requestSerial, postName, block) {
    return requestSerial === mindmapRequestSerial
      && isElementAttached(block)
      && document.querySelector("." + BLOCK_CLASS) === block
      && block.getAttribute("data-post") === postName;
  }

  // ===== v3 新增：头部章节数 pill =====
  //
  // 折叠态下，标题栏只显示一颗"展开"按钮。
  // pill 文本给个轻提示：数据回来后填 "N 章节" / "N 节点"。
  // 状态：
  //   loading → "加载章节中…"
  //   ready   → "N 章节"
  //   error   → "生成失败" / "无数据" / "加载失败"
  function setPillState(block, state, text) {
    var pill = block && block.querySelector(".halo-mindmap-head-pill");
    if (!pill) return;
    pill.setAttribute("data-state", state);
    pill.textContent = text;
  }

  function updateChapterPill(markdown) {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!block) return;
    var headings = collectHeadingsFromMarkdown(markdown || "");
    // 章节数 = H2 数（最贴近"章节"语义）；H1 是文章主标题不算
    var h2Count = 0, h3Count = 0, h4Count = 0;
    for (var i = 0; i < headings.length; i++) {
      if (headings[i].level === 2) h2Count++;
      else if (headings[i].level === 3) h3Count++;
      else if (headings[i].level === 4) h4Count++;
    }
    var total = h2Count;
    var text = total > 0
      ? (total + " 章节" + (h3Count > 0 ? " · " + h3Count + " 小节" : ""))
      : (headings.length > 0 ? headings.length + " 节点" : "0 章节");
    setPillState(block, "ready", text);
  }

  // ===== v3 新增：fit + viewBox 居中 + padding =====
  //
  // 居中策略（v3 强化）：
  //   1) CSS 上：.halo-mindmap-svg { width: 90%; height: 90%; margin: 5% auto; }
  //      → svg 元素只有 body 的 90% 大小，margin auto 让 svg 在 body 内水平居中
  //      → markmap fit 时按 svg 真实尺寸算 scale，svg 元素本身 90% 已天然留 padding
  //   2) fit 之后再手动调 viewBox：markmap 默认 fit 出来的 viewBox 几乎贴边，
  //      内容视觉上"撞边"。我们在 fit 之后读 viewBox，等比扩出 ~9% 的 padding
  //      （前后左右都加），再设回 svg。这样 SVG 内的内容中心点保持不动，
  //      但 viewBox 范围扩大 → 视觉上四周留白、节点不贴边。
  //   3) preserveAspectRatio="xMidYMid meet" 兜底：万一以后 markmap 改用 viewBox
  //      + 真实物理尺寸的策略，这条也能保证缩放时整体居中。
  //
  // 视觉 padding 比例：1.18 ≈ 每边 9% 留白，markmap 节点之间有呼吸空间但不过分。
  var MM_PADDING_FACTOR = 1.18;

  /**
   * 解析 svg viewBox 字符串 "x y w h" → 4 个数字。
   * markmap fit 后会在 svg 上写 viewBox 属性，格式是 "minX minY width height"。
   * 失败（含 svg 没 viewBox / 格式不对）→ 返回 null。
   */
  function readViewBox(svg) {
    try {
      var vb = svg.getAttribute("viewBox");
      if (!vb) return null;
      var parts = vb.trim().split(/[\s,]+/);
      if (parts.length !== 4) return null;
      var nums = [parseFloat(parts[0]), parseFloat(parts[1]),
                  parseFloat(parts[2]), parseFloat(parts[3])];
      for (var i = 0; i < 4; i++) {
        if (!isFinite(nums[i])) return null;
      }
      // 防御：width/height 异常小或 0（比如 markmap 刚初始化还没 fit）→ 不动
      if (nums[2] <= 1 || nums[3] <= 1) return null;
      return nums;
    } catch (e) { return null; }
  }

  /**
   * 在原 viewBox 四周等比扩 padding（每边 ~9%），同时保持内容中心点不动。
   * 输入 [x, y, w, h] → 输出新的 [x', y', w', h']。
   * 实现：把中心点 (cx, cy) 保持不变；w/h 各乘 paddingFactor；
   *       x = cx - w'/2, y = cy - h'/2。
   */
  function padViewBox(vb, factor) {
    if (!vb) return null;
    var x = vb[0], y = vb[1], w = vb[2], h = vb[3];
    var cx = x + w / 2;
    var cy = y + h / 2;
    var nw = w * factor;
    var nh = h * factor;
    return [cx - nw / 2, cy - nh / 2, nw, nh];
  }

  /**
   * 把 viewBox 写回 svg（保留 1 位小数避免浮点尾巴，4 位精度内不影响渲染）。
   * SVG 解析 viewBox 容忍空格和逗号；这里统一用空格。
   */
  function writeViewBox(svg, vb) {
    try {
      var s = vb[0].toFixed(1) + " " + vb[1].toFixed(1) + " " +
              vb[2].toFixed(1) + " " + vb[3].toFixed(1);
      svg.setAttribute("viewBox", s);
    } catch (e) { /* 容错 */ }
  }

  /**
   * v3 居中主函数：
   *   1) fit() — 让 markmap 算出当前 svg 物理尺寸下的 viewBox
   *   2) preserveAspectRatio="xMidYMid meet" — 兜底
   *   3) 读 viewBox → 等比扩 padding → 写回
   *   4) 标记 "v3-padded" 防止重复 padding
   */
  function fitAndCenterMarkmap() {
    if (!currentMarkmap || !currentMarkmapSvg) return;
    if (!isElementAttached(currentMarkmapSvg)) return;

    // 强制 xMidYMid meet：缩放时整体居中(即使将来 markmap 改用 viewBox,这条也兜底)
    try { currentMarkmapSvg.setAttribute("preserveAspectRatio", "xMidYMid meet"); } catch (e) { /* 容错 */ }

    // 先 fit 一次 — 让 markmap 算出基于 svg 真实物理尺寸的 viewBox
    try { currentMarkmap.fit(); } catch (e) { /* 容错 */ }

    // 等下一帧再读 viewBox（markmap 内部 d3 transition 是异步的，viewBox 可能在 transition 完才稳定）
    requestAnimationFrame(function () {
      if (!currentMarkmapSvg || !isElementAttached(currentMarkmapSvg)) return;
      var vb = readViewBox(currentMarkmapSvg);
      if (!vb) {
        // 还没拿到 viewBox，再等一帧
        requestAnimationFrame(function () {
          if (!currentMarkmapSvg || !isElementAttached(currentMarkmapSvg)) return;
          var vb2 = readViewBox(currentMarkmapSvg);
          if (!vb2) return;
          var padded2 = padViewBox(vb2, MM_PADDING_FACTOR);
          writeViewBox(currentMarkmapSvg, padded2);
        });
        return;
      }
      var padded = padViewBox(vb, MM_PADDING_FACTOR);
      writeViewBox(currentMarkmapSvg, padded);
    });
  }

  // ===== v3 新增：窗口 resize 时重新 fit + 重新 padding =====
  //
  // viewport 变了 svg 物理尺寸跟着变 → markmap fit 出来的 viewBox 也得重算。
  // 折中：200ms debounce，避免拖窗口时疯狂重算。
  var mmResizeTimer = null;
  function scheduleRefitOnResize() {
    if (mmResizeTimer) clearTimeout(mmResizeTimer);
    mmResizeTimer = setTimeout(function () {
      mmResizeTimer = null;
      try { fitAndCenterMarkmap(); } catch (e) { /* 容错 */ }
    }, 200);
  }
  // 只挂一次：DOMContentLoaded 之前也可能调用，window 已存在
  if (typeof window !== "undefined") {
    window.addEventListener("resize", scheduleRefitOnResize);
    window.addEventListener("click", function (event) {
      var block = document.querySelector("." + BLOCK_CLASS);
      if (!block || !event.target || !block.contains(event.target)) return;
      setTimeout(function () {
        bindNodeTextJump();
        decorateFoldControls();
      }, 360);
    }, true);
  }

  // ===== v3 新增：确保 markmap 已经被渲染（展开时调用） =====
  //
  // 折叠态下不渲染 markmap（svg 容器 height=0 → 渲染失败/重试死循环）。
  // 展开时如果 currentMarkmap 还没创建，且 dataset.markdown 已就绪 → 立即渲染。
  function ensureMarkmapRendered() {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!block) return;
    if (currentMarkmap && currentMarkmapSvg && isElementAttached(currentMarkmapSvg)) {
      // 已经渲染过 → 直接 fit + 居中
      try { fitAndCenterMarkmap(); } catch (e) { /* 容错 */ }
      return;
    }
    var md = block.getAttribute("data-markdown");
    if (!md) {
      // 数据还没好（用户极快地点了展开），不做事；fetch 完了会再调一次
      return;
    }
    var theme = block.getAttribute("data-theme") || detectTheme();
    renderMindMap(md, block, theme);
  }

  /**
   * markmap 渲染：
   * 1) 等区块 CSS 落位（offsetWidth > 0）
   * 2) 创建 svg 节点挂进 body
   * 3) Transformer 解析 markdown
   * 4) Markmap.create() 渲染
   * 5) onClick 节点：stopPropagation + 找对应 H2/H3 锚点 → scrollIntoView
   * 6) 解析时收集 markdown 各级 heading，与文章的 h1/h2/h3 配对建 id
   */
  function renderMindMap(markdown, block, theme) {
    // 等待区块布局稳定再渲染（否则 svg 宽高 = 0）
    requestAnimationFrame(function () {
      ensureMarkmap()
        .then(function () {
          try {
            var body = block.querySelector(".halo-mindmap-body");
            if (!body) return;
            // 清掉 loading
            body.innerHTML = "";

            var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
            svg.classList.add("halo-mindmap-svg");
            // svg 宽高由 CSS 控制（90% × 90%, margin auto 居中 → 视觉 padding）
            // 不要再 inline 设 width/height 否则盖掉 CSS
            // 主题色：markmap 内部 text 通过 .markmap color 继承
            svg.style.color = theme === "dark" ? "#E8E8EE" : "#1A1B22";
            body.appendChild(svg);
            currentMarkmapSvg = svg;

            var rect = svg.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) {
              console.warn("[MindMap] SVG 容器尺寸为 0，延迟一帧再渲染", rect.width, rect.height);
              requestAnimationFrame(function () { renderMindMap(markdown, block, theme); });
              return;
            }

            var transformer = new window.markmap.Transformer();
            var result = transformer.transform(markdown);
            if (!result || !result.root) {
              showStatus(block, "error", "Markdown 解析失败：返回数据为空");
              setBlockState(block, "error");
              return;
            }

            // 关键：用更大字号 + 较密 padding，沉浸感
            // v3: initialExpandLevel 2 → 5，让 L1/L2/L3/L4 4 级全展开
            //     spacingVertical 加大一点避免 H3/H4 挤在一起
            //     color() 走新版深度感知（已含 L3/L4 配色）
            currentMarkmap = window.markmap.Markmap.create(svg, {
              maxWidth: 320,
              initialExpandLevel: 5,
              duration: 300,
              paddingX: 24,
              spacingHorizontal: 120,  // 节点间距加大
              spacingVertical: 14,
              color: getMarkmapColor(theme)
            }, result.root);

            // 解析 markdown 各级 heading，为文章 H1-H6 配 id（如果还没有）
            indexHeadingsForJump(markdown);

            // 居中：markmap 内部 fit 是异步(用 d3 transition)，等下一帧再 fit + 调 viewBox
            // 否则 viewBox 还没设上,setAttribute 会被覆盖。
            // fitAndCenterMarkmap 内部自带二次兜底(若首帧 viewBox 还没好会再等一帧)
            requestAnimationFrame(function () {
              try { fitAndCenterMarkmap(); } catch (e) { console.warn("[MindMap] fitAndCenter 失败:", e); }
              bindNodeTextJump();
              decorateFoldControls();
            });
          } catch (err) {
            console.error("[MindMap] 渲染异常:", err);
            showStatus(block, "error", "渲染失败：" + err.message);
            setBlockState(block, "error");
          }
        })
        .catch(function (e) {
          console.error("[MindMap] 加载 markmap 异常:", e);
          showStatus(block, "error", "思维导图库加载失败：" + e.message);
          setBlockState(block, "error");
        });
    });
  }

  // 节点颜色：v3 加 L3/L4 配色，4 级全展开时仍有清晰视觉层级
  // depth: 0=L1 (H1, 文章根) / 1=L2 (H2, 章节) / 2=L3 (H3, 小节) / 3=L4 (H4, 子小节)
  function getMarkmapColor(theme) {
    var primary = mindmapConfig.color || (theme === "dark" ? "#A5B4FC" : "#4F46E5");
    if (theme === "dark") {
      return function (node) {
        var d = depthOf(node);
        if (d === 0) return primary;    // L1: 使用思维导图主题色
        if (d === 1) return "#A7F3D0";  // L2: 绿色（章节）
        if (d === 2) return "#FDE68A";  // L3: 暖黄（小节）
        return "#94A3B8";               // L4+: 灰蓝（不抢戏）
      };
    }
    return function (node) {
      var d = depthOf(node);
      if (d === 0) return primary;     // L1: 使用思维导图主题色
      if (d === 1) return "#059669";   // L2: 绿色（章节）
      if (d === 2) return "#D97706";   // L3: 暖色（小节）
      return "#64748B";                // L4+: 灰蓝（子小节）
    };
  }

  function depthOf(node) {
    var d = 0;
    var p = node && node.parent;
    while (p) { d++; p = p.parent; }
    return d;
  }

  // ===== 锚点跳转：精确 ID 优先 → 模糊匹配兜底 =====

  /**
   * 解析 markdown 各级 heading，与文章 DOM 中的 h1-h6 配对。
   * 配对策略：
   *   - 完全相同 textContent.trim() → 复制 id 到 markdown
   *   - 如果文章 h1-h6 没有 id，按"文本 -> 稳定 id"规则补一个
   * 目的：markmap 节点文字 click 时能根据文本找 id 跳转。
   */
  function indexHeadingsForJump(markdown) {
    try {
      var headingMap = collectHeadingsFromMarkdown(markdown);
      if (headingMap.length === 0) return;

      // 把所有可能的 h1-h6 抓出来（排除自身区块里的）
      var block = document.querySelector("." + BLOCK_CLASS);
      // v2: 区块现在插在正文容器（.post-content / article）内；找最近 <article> 祖先，兜底用 article/main/document
      var articleRoot = null;
      if (block && block.parentNode) {
        articleRoot = block.parentNode.closest("article") ||
                      block.parentNode.closest("main") ||
                      block.parentNode;
      }
      if (!articleRoot) articleRoot = document;
      var headings = articleRoot.querySelectorAll("h1, h2, h3, h4, h5, h6");
      var usedIds = {};
      headings.forEach(function (h) {
        if (block && block.contains(h)) return; // 排除区块内部
        var text = (h.textContent || "").trim();
        if (!text) return;
        var match = headingMap.find(function (m) { return m.text === text; });
        if (match) {
          if (!h.id) h.id = match.id;
          usedIds[match.id] = h;
        }
      });
    } catch (e) {
      console.warn("[MindMap] indexHeadingsForJump 失败:", e);
    }
  }

  /**
   * 从 markdown 字符串中收集所有 heading 及其文本。
   * 简单实现：逐行扫，遇到以 # 开头的行记录其 level + text。
   */
  function collectHeadingsFromMarkdown(md) {
    var lines = (md || "").split(/\r?\n/);
    var out = [];
    var seen = {};
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
      if (!m) continue;
      var level = m[1].length;
      var text = m[2].replace(/`/g, "").replace(/\*\*/g, "").replace(/\*/g, "").trim();
      if (!text) continue;
      var id = slugify(text);
      if (!id) continue;
      // 去重（同名 heading 多次出现时只取第一次）
      if (seen[id]) continue;
      seen[id] = true;
      out.push({ level: level, text: text, id: id });
    }
    return out;
  }

  function slugify(text) {
    // 简单 slug：去掉标点、保留中文/英文/数字、转 -
    var s = (text || "")
      .toLowerCase()
      .replace(/[\s_]+/g, "-")
      .replace(/[^\p{Letter}\p{Number}\-]+/gu, "")
      .replace(/^-+|-+$/g, "")
      .slice(0, 80);
    return s;
  }

  function bindNodeTextJump() {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!block) return;
    block.querySelectorAll(".halo-mindmap-body foreignObject").forEach(function (fo) {
      if (fo.getAttribute("data-jump-bound") === "1") return;
      fo.setAttribute("data-jump-bound", "1");
      fo.setAttribute("role", "link");
      fo.setAttribute("tabindex", "0");
      fo.setAttribute("title", "跳转到原文位置");
      fo.addEventListener("click", function (event) {
        try { event.stopPropagation(); } catch (e) {}
        jumpToAnchorText((fo.textContent || "").trim());
      });
      fo.addEventListener("keydown", function (event) {
        if (event.key !== "Enter" && event.key !== " ") return;
        try { event.preventDefault(); event.stopPropagation(); } catch (e) {}
        jumpToAnchorText((fo.textContent || "").trim());
      });
    });
  }

  function decorateFoldControls() {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!block) return;
    block.querySelectorAll(".halo-mindmap-body svg g.markmap-fold").forEach(function (g) {
      if (g.querySelector("text.halo-mindmap-fold-plus")) return;
      var circle = null;
      for (var i = 0; i < g.childNodes.length; i++) {
        var child = g.childNodes[i];
        if (child && child.tagName && child.tagName.toLowerCase() === "circle") {
          circle = child;
          break;
        }
      }
      if (!circle) return;
      var text = document.createElementNS("http://www.w3.org/2000/svg", "text");
      text.setAttribute("class", "halo-mindmap-fold-plus");
      text.setAttribute("x", circle.getAttribute("cx") || "0");
      text.setAttribute("y", circle.getAttribute("cy") || "0");
      text.setAttribute("text-anchor", "middle");
      text.setAttribute("dominant-baseline", "central");
      text.textContent = "+";
      g.appendChild(text);
    });
  }

  /**
   * 节点 → 锚点跳转
   * 1) 节点文本转 slug → document.getElementById
   * 2) 失败 → 模糊匹配（heading.textContent.includes(slug) 或 反向 includes）
   * 3) 失败 → 静默退出（不弹错）
   */
  function jumpToAnchor(node) {
    if (!node) return;
    var text = (node && node.content != null)
      ? String(node.content).replace(/<[^>]+>/g, "").trim()
      : (node.text || "").trim();
    jumpToAnchorText(text);
  }

  function jumpToAnchorText(text) {
    if (!text) return;

    // 1) 精确 slug
    var slug = slugify(text);
    var target = slug ? document.getElementById(slug) : null;

    // 2) 模糊匹配：在文章 h1-h6 里找包含文本关键字的
    if (!target) target = findHeadingByFuzzy(text) || findHeadingByFuzzy(slug);

    if (target && typeof target.scrollIntoView === "function") {
      target.scrollIntoView({ behavior: "smooth", block: "start" });
      // 高亮一下
      target.classList.add("halo-mindmap-flash");
      setTimeout(function () {
        target.classList.remove("halo-mindmap-flash");
      }, 1400);
    } else {
      console.debug("[MindMap] 未找到匹配锚点:", text, "→", slug);
    }
  }

  function findHeadingByFuzzy(text) {
    if (!text) return null;
    var block = document.querySelector("." + BLOCK_CLASS);
    var headings = document.querySelectorAll(
      "article h1, article h2, article h3, article h4, article h5, article h6, " +
      "main h1, main h2, main h3, main h4, main h5, main h6, " +
      "h1, h2, h3, h4, h5, h6"
    );
    var norm = text.toLowerCase();
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < headings.length; i++) {
      var h = headings[i];
      if (block && block.contains(h)) continue;
      var ht = (h.textContent || "").trim().toLowerCase();
      if (!ht) continue;
      var score = 0;
      if (ht === norm) score = 100;
      else if (ht.indexOf(norm) !== -1) score = 80 - (ht.length - norm.length) * 0.1;
      else if (norm.indexOf(ht) !== -1) score = 60;
      else {
        // 关键词 token 重合
        var tokens = norm.split(/[\s,\.，。；;]+/).filter(function (t) { return t.length >= 2; });
        var hit = 0;
        for (var j = 0; j < tokens.length; j++) {
          if (ht.indexOf(tokens[j]) !== -1) hit++;
        }
        if (tokens.length > 0) score = (hit / tokens.length) * 40;
      }
      if (score > bestScore) {
        bestScore = score;
        best = h;
      }
    }
    // 至少 25 分（关键词至少部分命中）才采纳
    return bestScore >= 25 ? best : null;
  }

  // ===== 销毁：PJAX 切页 =====

  /**
   * 移除当前区块、释放 markmap 引用。
   * 暴露到 window 方便外部触发；监听 pjax:end 自动调用。
   */
  function destroyMindmapBlock() {
    mindmapRequestSerial++;
    var block = document.querySelector("." + BLOCK_CLASS);
    if (block && block.parentNode) block.parentNode.removeChild(block);
    // 释放 flash class
    document.querySelectorAll(".halo-mindmap-flash").forEach(function (el) {
      el.classList.remove("halo-mindmap-flash");
    });
    currentMarkmap = null;
    currentMarkmapSvg = null;
  }

  // 暴露给 PJAX / 其他脚本触发
  window.destroyMindmapBlock = destroyMindmapBlock;

  // ===== UI 辅助 =====
  function escapeHtml(text) {
    if (!text) return "";
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  // ===== 启动 =====
  //
  // 1) DOMContentLoaded 首次挂载
  // 2) pjax:end 销毁 + 重挂
  // 3) 兜底 MutationObserver / history / popstate / 定时重试

  watchThemeChanges();

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mountMindmapBlock);
  } else {
    mountMindmapBlock();
  }

  // pjax:end（Dream 主题若支持）— 优先使用；不存在则走通用监听
  document.addEventListener("pjax:end", function () {
    destroyMindmapBlock();
    setTimeout(mountMindmapBlock, 30);
  });

  // Dream 使用 jQuery 触发 PJAX 自定义事件，原生 addEventListener 不一定能收到。
  // 同时绑定 jQuery 事件，保证生产主题切页后一定销毁上一篇脑图。
  if (window.jQuery && typeof window.jQuery(document).on === "function") {
    window.jQuery(document).on("pjax:end.aiSuiteMindmap", function () {
      destroyMindmapBlock();
      pjaxDirty = true;
      setTimeout(function () {
        tryMountWhenReady();
      }, 30);
    });
  }

  // 通用 PJAX 兜底
  var pjaxColumn = document.querySelector(".column-main");
  var pjaxDirty = false;
  var retryTimer = null;

  function tryMountWhenReady() {
    var block = document.querySelector("." + BLOCK_CLASS);
    if (!isArticlePage()) {
      if (block) destroyMindmapBlock();
      return;
    }

    var postName = getPostName();
    if (!postName) return;

    // PJAX 可能保留旧 DOM；只要文章身份变化，就必须销毁并重新请求。
    if (block && block.getAttribute("data-post") !== postName) {
      destroyMindmapBlock();
      block = null;
    }

    if (block) {
      ensureMindmapPlacement();
      return;
    }
    mountMindmapBlock();
  }

  function scheduleRetry() {
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(function () {
      retryTimer = null;
      if (!pjaxDirty) return;
      pjaxDirty = false;
      tryMountWhenReady();
      var delays = [100, 300, 700, 1500];
      for (var i = 0; i < delays.length; i++) {
        (function (d) {
          setTimeout(function () {
            tryMountWhenReady();
          }, d);
        })(delays[i]);
      }
    }, 80);
  }

  if (pjaxColumn && typeof MutationObserver !== "undefined") {
    var pjaxObserver = new MutationObserver(function () {
      pjaxDirty = true;
      scheduleRetry();
    });
    pjaxObserver.observe(pjaxColumn, { childList: true, subtree: false });
  }

  var lastPath = location.pathname;
  function onRouteChange() {
    if (location.pathname === lastPath) return;
    lastPath = location.pathname;
    destroyMindmapBlock();
    pjaxColumn = document.querySelector(".column-main");
    pjaxDirty = true;
    scheduleRetry();
  }
  var origPush = history.pushState;
  var origReplace = history.replaceState;
  history.pushState = function () {
    origPush.apply(this, arguments);
    onRouteChange();
  };
  history.replaceState = function () {
    origReplace.apply(this, arguments);
    onRouteChange();
  };
  window.addEventListener("popstate", onRouteChange);

  setInterval(function () {
    tryMountWhenReady();
  }, 3000);
})();
