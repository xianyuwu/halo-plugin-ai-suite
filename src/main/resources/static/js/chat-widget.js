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

  var configApiBase = "/apis/api.ai-suite.halo.run/v1alpha1";

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
    triggerOffsetY: 125,
    triggerOffsetX: 17,
    triggerShape: "square",
    stream: true,
    allowGuest: true,
    searchTheme: "inherit",
    searchColor: "",
  };

  var posSide = "right";
  var history = [];
  var isOpen = false;
  var isStreaming = false;
  var triggerPlacementFrame = 0;
  var triggerPlacementSettleTimer = 0;

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

  // ===== 悬浮按钮 / 头像图标库（内置 SVG，不依赖主题 Remix Icon 字体） =====
  // key 与后台 ICON_PRESETS 下发的 config.icon 类名完全一致，value 为内联 SVG。
  // 这样无论主题是否加载 Remix Icon 字体，配置的图标都能一致渲染。
  // path 数据取自 Remix Icon 官方 24px 版本（https://remixicon.com），保证视觉一致。
  var SVG_ATTR = 'viewBox="0 0 24 24" fill="currentColor" width="1em" height="1em"';
  var TRIGGER_ICONS = {
    "ri-sparkling-2-line": '<svg ' + SVG_ATTR + '><path d="M10 2C9.44772 2 9 2.44772 9 3V5.5C9 6.05228 9.44772 6.5 10 6.5C10.5523 6.5 11 6.05228 11 5.5V3C11 2.44772 10.5523 2 10 2Z"/><path d="M5.35786 4.22183C4.96695 3.8309 4.33379 3.8309 3.94287 4.22183C3.55195 4.61275 3.55195 5.24592 3.94287 5.63684L5.70753 7.4015C6.09845 7.79242 6.73162 7.79242 7.12254 7.4015C7.51346 7.01057 7.51346 6.37741 7.12254 5.98649L5.35786 4.22183Z"/><path d="M14.2925 7.4015L16.0572 5.63684C16.4481 5.24592 16.4481 4.61275 16.0572 4.22183C15.6663 3.8309 15.0331 3.8309 14.6422 4.22183L12.8775 5.98649C12.4866 6.37741 12.4866 7.01057 12.8775 7.4015C13.2684 7.79242 13.9016 7.79242 14.2925 7.4015Z"/><path d="M5.35786 15.7782L7.12254 14.0135C7.51346 13.6226 7.51346 12.9894 7.12254 12.5985C6.73162 12.2076 6.09845 12.2076 5.70753 12.5985L3.94287 14.3632C3.55195 14.7541 3.55195 15.3873 3.94287 15.7782C4.33379 16.1691 4.96695 16.1691 5.35786 15.7782Z"/><path d="M9 14C9 13.4477 9.44772 13 10 13C10.5523 13 11 13.4477 11 14V17C11 18.6569 12.3431 20 14 20H17C17.5523 20 18 20.4477 18 21C18 21.5523 17.5523 22 17 22H14C11.2386 22 9 19.7614 9 17V14Z"/></svg>',
    "ri-chat-3-line": '<svg ' + SVG_ATTR + '><path d="M7.29117 20.8242L2 22L3.17581 16.7088C2.42544 15.3056 2 13.7025 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C10.2975 22 8.69442 21.5746 7.29117 20.8242ZM7.41455 18.8492L7.95943 19.1487C9.13556 19.7936 10.5198 20.16 12 20.16C16.5053 20.16 20.16 16.5053 20.16 12C20.16 7.49471 16.5053 3.84 12 3.84C7.49471 3.84 3.84 7.49471 3.84 12C3.84 13.4802 4.2064 14.8644 4.85135 16.0406L5.15078 16.5854L4.48545 19.5146L7.41455 18.8492Z"/></svg>',
    "ri-robot-2-line": '<svg ' + SVG_ATTR + '><path d="M13 4.05484C17.5 4.55177 21 8.36688 21 13V20H3V13C3 8.36688 6.49997 4.55177 11 4.05484V2H13V4.05484ZM19 20V13C19 9.13401 15.866 6 12 6C8.13401 6 5 9.13401 5 13V20H19ZM9 13H7C7 15.2091 8.79086 17 11 17V15C9.89543 15 9 14.1046 9 13Z"/></svg>',
    "ri-message-2-line": '<svg ' + SVG_ATTR + '><path d="M7.29117 20.8242L2 22L3.17581 16.7088C2.42544 15.3056 2 13.7025 2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C10.2975 22 8.69442 21.5746 7.29117 20.8242ZM5 19.6914L7.62311 19.0461L8.15857 19.3405C9.30542 19.9706 10.6133 20.3148 12 20.3148C16.5276 20.3148 20.1429 16.6353 20.1429 12C20.1429 7.36471 16.5276 3.68519 12 3.68519C7.47242 3.68519 3.85714 7.36471 3.85714 12C3.85714 13.4193 4.2205 14.7765 4.88176 15.9617L5.18143 16.4923L5 19.6914ZM7 13H9C9 14.1046 9.89543 15 11 15V17C8.79086 17 7 15.2091 7 13Z"/></svg>',
    "ri-customer-service-2-line": '<svg ' + SVG_ATTR + '><path d="M14.7336 7.24228C14.7067 7.13159 14.6769 7.01941 14.6442 6.90595C13.9525 4.52064 11.8407 3.4 9.5 3.4C7.15881 3.4 5.04658 4.5213 4.35533 6.90761C4.32281 7.01996 4.29316 7.13103 4.26642 7.24061C2.90704 7.63373 2 8.9896 2 11C2 13.0104 2.90704 14.3663 4.26642 14.7594C4.29316 14.869 4.32281 14.98 4.35533 15.0924C5.04658 17.4787 7.15881 18.6 9.5 18.6C10.0523 18.6 10.5 18.1523 10.5 17.6C10.5 17.0477 10.0523 16.6 9.5 16.6C8.04001 16.6 6.94511 16.011 6.54557 14.6356L6.53351 14.5926C6.60206 14.5948 6.67069 14.6 6.74 14.6H17.26C17.3293 14.6 17.3979 14.5948 17.4665 14.5926L17.4544 14.6356C17.0549 16.011 15.96 16.6 14.5 16.6C13.9477 16.6 13.5 17.0477 13.5 17.6C13.5 18.1523 13.9477 18.6 14.5 18.6C16.8407 18.6 18.9525 17.4787 19.6442 15.0924C19.6769 14.979 19.7067 14.8668 19.7336 14.7561C21.0926 14.3627 22 13.0068 22 11C22 8.99324 21.0926 7.63734 19.7336 7.24388C19.7067 7.13319 19.6769 7.021 19.6442 6.90755C18.9525 4.52224 16.8407 3.4 14.5 3.4C13.9477 3.4 13.5 3.84772 13.5 4.4C13.5 4.95228 13.9477 5.4 14.5 5.4C15.96 5.4 17.0549 5.98902 17.4544 7.36439L17.4665 7.40736C17.3979 7.40516 17.3293 7.4 17.26 7.4H6.74C6.67069 7.4 6.60206 7.40516 6.53351 7.40736L6.54557 7.36439C6.94511 5.98902 8.04001 5.4 9.5 5.4C10.0523 5.4 10.5 4.95228 10.5 4.4C10.5 3.84772 10.0523 3.4 9.5 3.4Z"/></svg>',
    "ri-question-answer-line": '<svg ' + SVG_ATTR + '><path d="M15.364 21.4853L12.1213 18.2426H8C5.79086 18.2426 4 16.4518 4 14.2426V6.24264C4 4.0335 5.79086 2.24264 8 2.24264H20C22.2091 2.24264 24 4.0335 24 6.24264V14.2426C24 16.4518 22.2091 18.2426 20 18.2426H18.1213L15.364 21.4853ZM16.9497 16.2426H20C21.1046 16.2426 22 15.3472 22 14.2426V6.24264C22 5.13807 21.1046 4.24264 20 4.24264H8C6.89543 4.24264 6 5.13807 6 6.24264V14.2426C6 15.3472 6.89543 16.2426 8 16.2426H12.9497L15.0503 18.3431L16.9497 16.2426Z"/></svg>',
    "ri-shining-line": '<svg ' + SVG_ATTR + '><path d="M1.48464 13.879L5.54927 9.81437L7.67059 11.9357L5.54927 14.057C4.3777 15.2286 4.3777 17.1281 5.54927 18.2996C6.72084 19.4712 8.62033 19.4712 9.7919 18.2996L11.9132 16.1783L14.0345 18.2996L9.96989 22.3643C7.62674 24.7074 3.82779 24.7074 1.48464 22.3643C-0.858504 20.0211 -0.858504 16.2222 1.48464 13.879Z"/><path d="M9.81437 5.54927L13.879 1.48464C16.2222 -0.858504 20.0211 -0.858504 22.3643 1.48464C24.7074 3.82779 24.7074 7.62674 22.3643 9.96989L18.2996 14.0345L16.1783 11.9132L18.2996 9.7919C19.4712 8.62033 19.4712 6.72084 18.2996 5.54927C17.1281 4.3777 15.2286 4.3777 14.057 5.54927L11.9357 7.67059L9.81437 5.54927Z"/></svg>',
    "ri-lightbulb-line": '<svg ' + SVG_ATTR + '><path d="M9.97308 18H11V14H13V18H14.0269C14.1578 16.2341 14.7046 15.1875 15.7947 14.2352C17.0558 13.1312 18 11.6915 18 9.58594C18 6.02934 15.2917 3 12 3C8.70833 3 6 6.02934 6 9.58594C6 11.6915 6.94424 13.1312 8.20531 14.2352C9.29542 15.1875 9.84224 16.2341 9.97308 18ZM9 21H15V23H9V21Z"/></svg>',
    "ri-send-plane-line": '<svg ' + SVG_ATTR + '><path d="M3.49751 2.19039C3.05301 2.01449 2.54804 2.11129 2.20501 2.43662C1.86199 2.76195 1.7405 3.26156 1.89472 3.71416L4.17872 10.4129C4.3204 10.8287 4.68231 11.1315 5.11549 11.2073L11.4654 12.318C11.8014 12.384 12.0004 12.648 12.0004 13.0001C12.0004 13.3521 11.8014 13.6161 11.4654 13.6821L5.11549 14.7928C4.68231 14.8686 4.3204 15.1714 4.17872 15.5872L1.89472 22.2859C1.7405 22.7385 1.86199 23.2381 2.20501 23.5635C2.54804 23.8888 3.05301 23.9856 3.49751 23.8097L21.4975 16.6607C21.9745 16.4717 22.3004 16.0126 22.3004 15.4997V10.5004C22.3004 9.98754 21.9745 9.5284 21.4975 9.33944L3.49751 2.19039ZM4.62352 4.63168L18.9448 10.2965L4.80686 12.7739C4.79374 12.5225 4.79374 12.4777 4.80686 12.2263L4.62352 4.63168ZM4.62352 21.3684L4.80686 13.7737C4.79374 13.5223 4.79374 13.4775 4.80686 13.2261L18.9448 15.7035L4.62352 21.3684Z"/></svg>',
    "ri-thumb-up-line": '<svg ' + SVG_ATTR + '><path d="M2 9.5V20C2 20.5523 2.44772 21 3 21H5C5.55228 21 6 20.5523 6 20V9.5C6 8.94772 5.55228 8.5 5 8.5H3C2.44772 8.5 2 8.94772 2 9.5Z"/><path d="M7.5 9C7.5 6.79086 8.79086 5 10.5 5C11.6046 5 12.5 5.89543 12.5 7V9.5C12.5 10.0523 12.9477 10.5 13.5 10.5H17C17.8284 10.5 18.5 11.1716 18.5 12V14C18.5 14.2652 18.3946 14.5196 18.2071 14.7071L14.5 18.4142C14.3126 18.6017 14.0582 18.7071 13.7929 18.7071H8C7.72386 18.7071 7.5 18.4833 7.5 18.2071V9ZM10.5 3C7.46243 3 5.5 5.68629 5.5 9V19.2071C5.5 20.5878 6.61929 21.7071 8 21.7071H13.7929C14.5885 21.7071 15.3516 21.3911 15.9142 20.8284L19.6213 17.1213C20.1839 16.5587 20.5 15.7957 20.5 15V12C20.5 10.067 18.933 8.5 17 8.5H14.5V7C14.5 4.79086 12.7091 3 10.5 3Z"/></svg>',
    "ri-heart-line": '<svg ' + SVG_ATTR + '><path d="M12.001 3C9.71369 3 7.63441 3.92566 6.12915 5.42178C3.84634 7.68995 2.68259 11.2565 4.37864 15.0803C5.81121 18.2237 9.00922 20.4896 11.929 21.8594C11.9763 21.8815 12.0257 21.8815 12.073 21.8594C14.9928 20.4896 18.1908 18.2237 19.6234 15.0803C21.3194 11.2565 20.1557 7.68995 17.8729 5.42178C16.3676 3.92566 14.2883 3 12.001 3Z"/></svg>',
    "ri-star-line": '<svg ' + SVG_ATTR + '><path d="M12.0006 18.26L4.94715 22.2082L6.52248 14.2799L0.587891 8.7918L8.61493 7.84006L12.0006 0.5L15.3862 7.84006L23.4132 8.7918L17.4787 14.2799L19.054 22.2082L12.0006 18.26ZM12.0006 15.968L16.2473 18.3451L15.2986 13.5717L18.8719 10.2694L14.039 9.69434L12.0006 5.27502L9.96214 9.69434L5.12921 10.2694L8.70255 13.5717L7.75383 18.3451L12.0006 15.968Z"/></svg>'
  };

  /**
   * 悬浮按钮 / AI 头像统一图标 HTML。
   * 文字标签优先于图标；否则按 config.icon 查内置 SVG 表，未命中时用默认星光。
   * 不再依赖主题的 Remix Icon 字体，跨主题一致。
   */
  function getTriggerIconHTML() {
    if (config.triggerLabel) {
      return '<span class="ai-avatar-label">' + escapeHtml(config.triggerLabel) + '</span>';
    }
    return TRIGGER_ICONS[config.icon] || TRIGGER_ICONS["ri-sparkling-2-line"];
  }

  function getAvatarHTML() {
    return getTriggerIconHTML();
  }

  // ===== 按钮形状 → border-radius 映射 =====
  // 与后台 ui/src/utils/trigger-icons.ts 的 TRIGGER_SHAPES.radius 逐字一致。
  var SHAPE_RADIUS = {
    circle: "50%",
    rounded: "30%",
    square: "5px"
  };

  // ===== 创建 DOM =====

  var trigger = document.createElement("button");
  trigger.id = "ai-chat-trigger";
  // 初始占位用默认星光 SVG；applyConfig() 会按 config.icon 覆盖为实际配置图标。
  trigger.innerHTML = TRIGGER_ICONS["ri-sparkling-2-line"];
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

  function configuredTriggerBottom() {
    if (config.triggerAlign === "manual" &&
        typeof config.triggerOffsetY === "number" && config.triggerOffsetY > 0) {
      return config.triggerOffsetY;
    }
    return window.matchMedia && window.matchMedia("(max-width: 480px)").matches ? 16 : 80;
  }

  /**
   * 页面加载时扫描一次同侧的 compact fixed 控件，为 AI 按钮预留稳定槽位。
   * 主题按钮即使暂时滑出屏幕（例如 Dream 的 right:-48px）也会被计入，
   * 因此滚动期间无需追踪主题按钮的显示/隐藏状态，按钮不会上下跳动。
   */
  function detectFloatingControlsBottom(baseBottom) {
    if (!trigger.parentNode || !document.body) return baseBottom;

    var viewportWidth = document.documentElement.clientWidth || window.innerWidth;
    var viewportHeight = window.innerHeight;
    if (!viewportWidth || !viewportHeight) return baseBottom;

    var triggerSize = config.triggerSize || 35;
    var bottom = baseBottom;
    var nodes = document.body.querySelectorAll("*");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el === trigger || el === chatWindow || trigger.contains(el) || chatWindow.contains(el)) {
        continue;
      }

      var style = window.getComputedStyle(el);
      if (style.position !== "fixed" || style.display === "none" ||
          style.visibility === "hidden" || parseFloat(style.opacity || "1") < 0.05) continue;

      var rect = el.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0 ||
          rect.width > Math.max(180, viewportWidth * 0.5) ||
          rect.height > Math.min(300, viewportHeight * 0.5) ||
          rect.top < viewportHeight * 0.45 || rect.top >= viewportHeight) continue;

      var sideValue = posSide === "left" ? style.left : style.right;
      var sideOffset = sideValue === "auto" ? NaN : parseFloat(sideValue);
      var nearSide = posSide === "left"
        ? rect.left < 120 || (!isNaN(sideOffset) && sideOffset < 96)
        : rect.right > viewportWidth - 120 || (!isNaN(sideOffset) && sideOffset < 96);
      if (!nearSide) continue;

      bottom = Math.max(bottom, Math.ceil(viewportHeight - rect.top + 8));
    }
    return Math.min(bottom, Math.max(baseBottom, viewportHeight - triggerSize - 16));
  }

  function updateTriggerPlacement() {
    var bottom = configuredTriggerBottom();
    if (config.triggerAlign !== "manual") {
      bottom = detectFloatingControlsBottom(bottom);
    }
    trigger.style.setProperty("--ai-chat-trigger-bottom", bottom + "px");
    chatWindow.style.setProperty(
      "--ai-chat-window-bottom",
      (bottom + (config.triggerSize || 35) + 8) + "px"
    );
  }

  function updateTriggerHorizontalPlacement() {
    var configuredOffsetX = (typeof config.triggerOffsetX === "number" &&
      config.triggerOffsetX >= 0) ? config.triggerOffsetX : 16;
    var isMobile = window.matchMedia && window.matchMedia("(max-width: 480px)").matches;
    // 自动模式跟随移动端主题按钮的通用 8px 边距；手动模式始终尊重后台配置。
    var triggerOffsetX = config.triggerAlign !== "manual" && isMobile
      ? 8 : configuredOffsetX;
    trigger.style.setProperty("--ai-chat-trigger-x", triggerOffsetX + "px");
    chatWindow.style.setProperty("--ai-chat-window-x", (triggerOffsetX + 8) + "px");
  }

  function updateResponsiveTriggerPlacement() {
    updateTriggerHorizontalPlacement();
    updateTriggerPlacement();
  }

  function scheduleTriggerPlacement() {
    if (isContainerMode) return;
    if (!triggerPlacementFrame) {
      triggerPlacementFrame = requestAnimationFrame(function () {
        triggerPlacementFrame = 0;
        updateResponsiveTriggerPlacement();
      });
    }
    // 仅用于首次布局或横竖屏切换后的稳定校准，不绑定滚动事件。
    clearTimeout(triggerPlacementSettleTimer);
    triggerPlacementSettleTimer = setTimeout(updateResponsiveTriggerPlacement, 350);
  }

  function watchTriggerPlacement() {
    if (isContainerMode) return;
    window.addEventListener("orientationchange", scheduleTriggerPlacement);
    if (window.matchMedia) {
      var orientationQuery = window.matchMedia("(orientation: portrait)");
      var mobileQuery = window.matchMedia("(max-width: 480px)");
      if (orientationQuery.addEventListener) {
        orientationQuery.addEventListener("change", scheduleTriggerPlacement);
        mobileQuery.addEventListener("change", scheduleTriggerPlacement);
      } else if (orientationQuery.addListener) {
        orientationQuery.addListener(scheduleTriggerPlacement);
        mobileQuery.addListener(scheduleTriggerPlacement);
      }
    }
    // trigger 已挂载到 DOM，可在首次绘制前直接确定最终位置，避免加载时闪动。
    updateResponsiveTriggerPlacement();
    clearTimeout(triggerPlacementSettleTimer);
    triggerPlacementSettleTimer = setTimeout(updateResponsiveTriggerPlacement, 350);
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

    // 更新悬浮按钮内容：文字标签优先于图标（内置 SVG，不依赖主题字体）
    trigger.innerHTML = getTriggerIconHTML();

    // 更新所有 AI 头像为当前配置图标
    var avatars = chatWindow.querySelectorAll(".ai-chat-header-avatar, .ai-chat-row-avatar");
    var avatarHTML = getAvatarHTML();
    for (var ai = 0; ai < avatars.length; ai++) {
      avatars[ai].innerHTML = avatarHTML;
    }

    updateTriggerHorizontalPlacement();

    // 按钮形状：shape 映射成 border-radius，未命中时用默认圆形
    trigger.style.setProperty(
      "--ai-chat-trigger-radius",
      SHAPE_RADIUS[config.triggerShape] || SHAPE_RADIUS.square
    );

    applyTheme();

    posSide = config.position.indexOf("left") >= 0 ? "left" : "right";
    trigger.classList.remove("position-left", "position-right");
    trigger.classList.add("position-" + posSide);
    chatWindow.classList.remove("position-left", "position-right");
    chatWindow.classList.add("position-" + posSide);
    updateTriggerPlacement();
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
   * 构造 chat / chat-stream 的 POST 请求体。
   *
   * sendMessage 会先把当前 user 消息放进 history，因此这里排除最后一条，避免当前问题
   * 同时出现在 message 和 history 中。历史仍做数量和单条长度控制，避免无效上下文
   * 挤占模型 token，并确保请求体稳定落在 WebFlux 解码上限以内。
   */
  function buildChatBody(text) {
    var MAX_HISTORY = 12;
    var MAX_MSG_LEN = 2000;

    function truncate(s) {
      if (!s || s.length <= MAX_MSG_LEN) return s;
      return s.substring(0, 1400) + "\n…[内容已截断]…\n" + s.substring(s.length - 500);
    }

    var pruned = history.slice(0, -1).slice(-MAX_HISTORY).map(function (m) {
      return { role: m.role, content: truncate(m.content || "") };
    });
    return { message: text, history: pruned };
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

  function resolveComponentTheme(componentTheme, inheritedTheme) {
    if (componentTheme === "light" || componentTheme === "dark") return componentTheme;
    if (componentTheme === "system") {
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light";
    }
    if (componentTheme === "auto") {
      var detected = detectPageTheme();
      if (detected) return detected;
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light";
    }
    if (inheritedTheme === "light" || inheritedTheme === "dark") return inheritedTheme;
    var inheritedDetected = detectPageTheme();
    if (inheritedDetected) return inheritedDetected;
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark" : "light";
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

  function applySearchThemeVars(modal, color) {
    var resolvedColor = color || config.searchColor || config.color || "#7C3BED";
    modal.style.setProperty("--ai-chat-color", resolvedColor);
    modal.style.setProperty("--ai-search-color", resolvedColor);
    var rgb = hexToRgbParts(resolvedColor);
    if (rgb) modal.style.setProperty("--ai-search-color-rgb", rgb);
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
    var requestBody = buildChatBody(text);

    function ensureAssistantEl() {
      if (assistantEl) return;
      if (typingEl) { typingEl.remove(); typingEl = null; }
      assistantEl = addMessage("assistant", "");
      // logId 先于首 token 到达时，此时补上
      if (pendingLogId && !assistantEl._logId) {
        assistantEl._logId = pendingLogId;
      }
    }

    fetch(config.apiBase + "/chat/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream"
      },
      body: JSON.stringify(requestBody)
    })
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
    fetch(config.apiBase + "/chat", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json"
      },
      body: JSON.stringify(buildChatBody(text))
    })
      .then(function (res) {
        if (!res.ok) throw new Error("服务器错误: " + res.status);
        return res.json();
      })
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
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;")
      .replace(/\n/g, "<br>");
  }

  function escapeAttr(text) {
    if (!text) return "";
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function safeUrl(url) {
    if (!url) return "#";
    var value = String(url).trim();
    if (value.charAt(0) === "/" || value.charAt(0) === "#") return value;
    if (/^https?:\/\//i.test(value)) return value;
    return "#";
  }

  function sanitizeSnippet(html) {
    return escapeHtml(String(html || ""))
      .replace(/&lt;mark&gt;/gi, "<mark>")
      .replace(/&lt;\/mark&gt;/gi, "</mark>");
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
   * 200 字内中文 URL 编码约 600 字节，远低于上限。
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

    var apiBase = (config && config.apiBase) || "/apis/api.ai-suite.halo.run/v1alpha1";
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
          if (typeof data.triggerOffsetX === "number") config.triggerOffsetX = data.triggerOffsetX;
          if (data.triggerShape) config.triggerShape = data.triggerShape;
          config.stream = data.stream !== false;
          config.allowGuest = data.allowGuest !== false;
          config.showReferences = data.showReferences !== false;
          config.showRetrievalStatus = data.showRetrievalStatus === true;
          config.showPrivacyTip = data.showPrivacyTip === true;
          config.searchEnabled = data.searchEnabled !== false;
          config.searchShowAiAnswer = data.searchShowAiAnswer !== false;
          config.searchTheme = data.searchTheme || "inherit";
          config.searchColor = data.searchColor || config.color;
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
        watchTriggerPlacement();
        initResize();
        // 预览模式：URL 含 ?ai-preview=1 时自动打开浮窗
        if (!isEmbed && window.location.search.indexOf("ai-preview=1") >= 0) {
          setTimeout(function () { toggleChat(); }, 300);
        }
        // ===== 搜索弹框：快捷键 + 触发按钮 =====
        // 全局快捷键：Ctrl+K / Cmd+K 打开，Esc 关闭
        if (!isContainerMode) {
          window.addEventListener("keydown", function (e) {
            // Ctrl+K / Cmd+K — 打开搜索
            if ((e.ctrlKey || e.metaKey) && e.key === "k") {
              e.preventDefault();
              if (config.searchEnabled !== false) openSearchModal();
              return;
            }
            // / — 当焦点不在输入元素时打开搜索
            if (e.key === "/" && !["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) {
              e.preventDefault();
              if (config.searchEnabled !== false) openSearchModal();
              return;
            }
            // Esc — 关闭搜索弹框
            if (e.key === "Escape" && searchModal) {
              e.preventDefault();
              closeSearchModal();
              return;
            }
          });

          // 接管主题搜索入口：覆盖 SearchWidget.open()
          // 备份原生实现：searchEnabled 关闭时把点击转发回主题原生搜索，
          // 否则原生入口被覆盖丢失，主题搜索按钮点击会无反应
          if (!window.SearchWidget) { window.SearchWidget = {}; }
          var nativeSearchOpen = typeof window.SearchWidget.open === "function"
            ? window.SearchWidget.open : null;
          var nativeSearchClose = typeof window.SearchWidget.close === "function"
            ? window.SearchWidget.close : null;
          window.SearchWidget.open = function () {
            if (config.searchEnabled !== false) {
              openSearchModal();
            } else if (nativeSearchOpen) {
              nativeSearchOpen.call(window.SearchWidget);
            }
          };
          window.SearchWidget.close = function () {
            if (config.searchEnabled !== false) {
              closeSearchModal();
            } else if (nativeSearchClose) {
              nativeSearchClose.call(window.SearchWidget);
            }
          };
        }
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
    if (data.triggerAlign !== undefined) { config.triggerAlign = data.triggerAlign; needApply = true; }
    if (data.triggerOffsetY !== undefined) { config.triggerOffsetY = data.triggerOffsetY; needApply = true; }
    if (data.triggerOffsetX !== undefined) { config.triggerOffsetX = data.triggerOffsetX; needApply = true; }
    if (data.triggerShape !== undefined) { config.triggerShape = data.triggerShape; needApply = true; }
    if (data.triggerSize !== undefined) { config.triggerSize = data.triggerSize; needApply = true; }
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

  // ===== AI 搜索弹框 =====
  // Ctrl+K / Cmd+K / / 唤起，Esc 关闭。
  // 上方：AI 流式回答（复用 SearchAnswerEndpoint + parseSseStream）
  // 下方：关键词搜索结果（调用 PublicSearchEndpoint）

  var searchModal = null;     // 当前弹框 DOM 引用
  var searchAbort = null;     // AbortController，关闭时取消请求

  /** 打开搜索弹框 */
  function openSearchModal() {
    if (searchModal) return; // 防重复

    var theme = resolveComponentTheme(config.searchTheme, config.theme);

    // 遮罩层
    var overlay = document.createElement("div");
    overlay.className = "ai-search-overlay";
    overlay.setAttribute("data-theme", theme);

    // 弹框容器
    var modal = document.createElement("div");
    modal.className = "ai-search-modal";
    modal.setAttribute("data-theme", theme);
    applySearchThemeVars(modal, config.searchColor);

    modal.innerHTML =
      // 搜索输入区
      '<div class="ai-search-input-wrap">' +
        '<svg class="ai-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>' +
        '<input class="ai-search-input" type="text" placeholder="搜索文章..." autofocus />' +
        '<button class="ai-search-clear" title="清空" style="display:none">&times;</button>' +
        '<button class="ai-search-close" type="button" title="关闭" aria-label="关闭搜索">' +
          '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>' +
        '</button>' +
      '</div>' +
      // 结果区域（滚动）
      '<div class="ai-search-results">' +
        // AI 回答区（默认隐藏，有关键词后显示）
        '<div class="ai-search-ai-section" style="display:none">' +
          '<div class="ai-search-ai-header">' +
            '<span class="ai-search-ai-title">✨ AI 综合回答</span>' +
            '<span class="ai-search-ai-badge">AI</span>' +
          '</div>' +
          '<div class="ai-search-ai-body"></div>' +
        '</div>' +
        // 关键词结果区
        '<div class="ai-search-kw-section" style="display:none">' +
          '<div class="ai-search-kw-header">📄 搜索结果 <span class="ai-search-kw-count"></span></div>' +
          '<div class="ai-search-kw-list"></div>' +
        '</div>' +
        // 空状态
        '<div class="ai-search-empty">输入关键词开始搜索</div>' +
      '</div>';

    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    searchModal = overlay;

    // 聚焦输入框
    var input = modal.querySelector(".ai-search-input");
    var clearBtn = modal.querySelector(".ai-search-clear");
    setTimeout(function () { input.focus(); }, 50);

    // 关闭按钮（移动端全屏弹窗无遮罩可点、无 Esc 键，必须提供显式关闭入口）
    modal.querySelector(".ai-search-close").addEventListener("click", closeSearchModal);

    // 遮罩点击关闭
    overlay.addEventListener("click", function (e) {
      if (e.target === overlay) closeSearchModal();
    });

    // 点击关键词搜索结果项时关闭弹框
    // 主题 pjax 无刷新导航只替换内容区，浮窗 DOM 会残留遮挡新页面，故需主动移除
    var kwList = modal.querySelector(".ai-search-kw-list");
    kwList.addEventListener("click", function (e) {
      if (e.target.closest(".ai-search-kw-item")) closeSearchModal();
    });

    // 清空按钮
    clearBtn.addEventListener("click", function () {
      input.value = "";
      clearBtn.style.display = "none";
      input.focus();
      resetSearchResults(modal);
    });

    // 输入监听（防抖 300ms）
    var debounceTimer = null;
    input.addEventListener("input", function () {
      var val = input.value.trim();
      clearBtn.style.display = val ? "" : "none";
      if (debounceTimer) clearTimeout(debounceTimer);
      if (!val) {
        resetSearchResults(modal);
        return;
      }
      debounceTimer = setTimeout(function () { doSearch(val, modal, theme); }, 300);
    });

    // 回车立即搜索（跳过防抖）
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        e.preventDefault();
        if (debounceTimer) clearTimeout(debounceTimer);
        var val = input.value.trim();
        if (val) doSearch(val, modal, theme);
      }
    });
  }

  /** 关闭搜索弹框 */
  function closeSearchModal() {
    if (!searchModal) return;
    // 取消未完成的请求
    if (searchAbort) { searchAbort.abort(); searchAbort = null; }
    searchModal.remove();
    searchModal = null;
  }

  /** 重置搜索结果区域 */
  function resetSearchResults(modal) {
    var aiSection = modal.querySelector(".ai-search-ai-section");
    var kwSection = modal.querySelector(".ai-search-kw-section");
    var empty = modal.querySelector(".ai-search-empty");
    aiSection.style.display = "none";
    aiSection.querySelector(".ai-search-ai-body").innerHTML = "";
    kwSection.style.display = "none";
    kwSection.querySelector(".ai-search-kw-list").innerHTML = "";
    empty.style.display = "";
    empty.textContent = "输入关键词开始搜索";
  }

  /** 并行搜索：AI 流式回答 + 关键词结果 */
  function doSearch(keyword, modal, theme) {
    // 取消上一次请求
    if (searchAbort) searchAbort.abort();
    searchAbort = new AbortController();

    var apiBase = (config && config.apiBase) || "/apis/api.ai-suite.halo.run/v1alpha1";
    var aiSection = modal.querySelector(".ai-search-ai-section");
    var aiBody = modal.querySelector(".ai-search-ai-body");
    var kwSection = modal.querySelector(".ai-search-kw-section");
    var kwList = modal.querySelector(".ai-search-kw-list");
    var kwCount = modal.querySelector(".ai-search-kw-count");
    var empty = modal.querySelector(".ai-search-empty");

    // 隐藏空状态，按配置显示区域
    empty.style.display = "none";
    var showAi = config.searchShowAiAnswer !== false;
    aiSection.style.display = showAi ? "" : "none";
    kwSection.style.display = "none"; // 有结果后再显示
    aiBody.innerHTML = showAi ? '<div class="ai-search-ai-loading">AI 正在思考...</div>' : "";
    kwList.innerHTML = "";

    // 1) AI 流式回答（按配置决定是否请求）
    if (showAi) {
    fetch(apiBase + "/search/answer?keyword=" + encodeURIComponent(keyword), {
      signal: searchAbort.signal
    }).then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      var loadingEl = aiBody.querySelector(".ai-search-ai-loading");
      if (loadingEl) loadingEl.remove();

      parseSseStream(res, {
        onCitation: function (cites) {
          // 存引用，流结束后渲染
          aiBody._citations = cites;
        },
        onToken: function (token) {
          // 首 token 到达时清理 loading
          if (!aiBody._started) {
            aiBody._started = true;
            aiBody._content = "";
          }
          aiBody._content += token;
          renderAssistant(aiBody, aiBody._content);
        },
        onDone: function () {
          // 追加引用来源
          var cites = aiBody._citations;
          if (cites && cites.length > 0) {
            var citeHtml = '<div class="ai-search-ai-cites">';
            for (var i = 0; i < cites.length; i++) {
              var c = cites[i];
              citeHtml += '<a class="ai-search-ai-cite" href="' + escapeAttr(safeUrl(c.url || '#')) + '" target="_blank" rel="noopener noreferrer">'
                + '[' + (i + 1) + '] ' + escapeHtml(c.title || '未知') + '</a>';
            }
            citeHtml += '</div>';
            aiBody.insertAdjacentHTML("beforeend", citeHtml);
          }
          aiBody._started = false;
          aiBody._citations = null;
          aiBody._content = "";
        },
        onError: function () {
          aiBody.innerHTML = '<div class="ai-search-ai-error">AI 暂时不可用</div>';
        }
      });
    }).catch(function (e) {
      if (e.name === "AbortError") return;
      aiBody.innerHTML = '<div class="ai-search-ai-error">AI 暂时不可用</div>';
    });
    } // end showAi

    // 2) 关键词搜索结果
    fetch(apiBase + "/search/halo-results?keyword=" + encodeURIComponent(keyword), {
      signal: searchAbort.signal
    }).then(function (res) { return res.json(); })
    .then(function (data) {
      if (!data || !data.articles || data.articles.length === 0) {
        kwSection.style.display = "none";
        return;
      }
      kwSection.style.display = "";
      kwCount.textContent = "(" + data.total + ")";

      var html = "";
      for (var i = 0; i < data.articles.length; i++) {
        var a = data.articles[i];
        html += '<a class="ai-search-kw-item" href="' + escapeAttr(safeUrl(a.url || '#')) + '">' +
          '<div class="ai-search-kw-title">' + escapeHtml(a.title || '无标题') + '</div>' +
          '<div class="ai-search-kw-snippet">' + sanitizeSnippet(a.snippet || '') + '</div>' +
        '</a>';
      }
      kwList.innerHTML = html;
    }).catch(function (e) {
      if (e.name === "AbortError") return;
      kwSection.style.display = "none";
    });
  }

  init();
})();
