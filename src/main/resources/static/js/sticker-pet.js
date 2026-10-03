/**
 * AI 贴纸宠物渲染器 — 访客端
 *
 * 职责：把聊天浮窗触发器渲染成"活的"贴纸宠物。
 * 输入是一份 manifest：{ name, images: { idle, blink?, happy?, sad?, thinking? } }
 * 输出是 window.AIPet 全局：mount / setState / destroy / available。
 *
 * 设计要点：
 * - 纯 CSS transform/animation 驱动微动画，图片只负责"表情差分"；
 *   缺哪个状态图就跳过换图、只做动作，任何残缺 manifest 都能正常工作。
 * - prefers-reduced-motion 时 available() 返回 false，由 chat-widget 回退静态图标。
 * - 不依赖任何框架，不污染全局（只挂 window.AIPet）。
 */
(function () {
  "use strict";

  var REDUCED_MOTION = window.matchMedia
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var state = {
    root: null,        // 宠物容器 div.ai-pet
    img: null,         // 当前显示的 <img>
    manifest: null,    // 当前 manifest
    current: "idle",   // 当前逻辑状态
    blinkTimer: 0,     // 眨眼循环 timer
    revertTimer: 0,    // 临时状态回退 idle 的 timer
    tiltHandler: null, // hover 朝向鼠标的监听
    hovered: false,    // 鼠标是否悬停在触发器上
    hoverHandlers: null, // hover 进出场监听（用于 destroy 清理）
    phrases: [],       // 宠物语录（hover 气泡轮换文案）
    phraseIndex: 0,    // 语录轮换游标
    hoverBubble: null, // hover 气泡元素
    destroyed: false
  };

  function available() {
    return !REDUCED_MOTION;
  }

  function imageFor(logicalState) {
    var images = state.manifest && state.manifest.images;
    if (!images) return "";
    return images[logicalState] || images.idle || "";
  }

  /** 切换表情图（有图才换，没图保持现状只做动作） */
  function swapImage(logicalState) {
    var url = imageFor(logicalState);
    if (url && state.img && state.img.getAttribute("src") !== url) {
      state.img.setAttribute("src", url);
    }
  }

  /** 清掉所有状态 class，回到干净容器 */
  function clearStateClasses() {
    if (!state.root) return;
    state.root.className = state.root.className
      .replace(/ai-pet-\S+/g, "").trim();
    state.root.classList.add("ai-pet");
  }

  function scheduleRevertToIdle(ms) {
    clearTimeout(state.revertTimer);
    state.revertTimer = setTimeout(function () {
      if (!state.destroyed) applyState("idle");
    }, ms);
  }

  /** 眨眼循环：仅在 idle 时每隔 3-6 秒切眨眼帧 220ms */
  function scheduleBlink() {
    clearTimeout(state.blinkTimer);
    if (!imageFor("blink")) return; // 没有眨眼帧就不眨眼
    state.blinkTimer = setTimeout(function () {
      if (!state.destroyed && state.current === "idle" && !state.hovered) {
        swapImage("blink");
        setTimeout(function () {
          if (!state.destroyed && state.current === "idle" && !state.hovered) swapImage("idle");
        }, 220);
      }
      scheduleBlink();
    }, 3000 + Math.random() * 3000);
  }

  /** hover 时宠物朝向鼠标轻微倾斜。
   *  注意必须作用在容器 .ai-pet 上而不是 .ai-pet-img：
   *  img 上挂着呼吸等 CSS 动画，动画优先级高于内联 transform，直接改 img 会失效。 */
  function attachTilt() {
    detachTilt();
    state.tiltHandler = function (e) {
      if (!state.root || (state.current !== "idle" && state.current !== "open")) return;
      var rect = state.root.getBoundingClientRect();
      var dx = e.clientX - (rect.left + rect.width / 2);
      var dy = e.clientY - (rect.top + rect.height / 2);
      var dist = Math.sqrt(dx * dx + dy * dy);
      if (dist > 260) {
        state.root.style.transform = "";
        return;
      }
      // 距离越近倾角越大，封顶 12 度
      var angle = Math.max(-12, Math.min(12, dx / 20));
      var lift = Math.max(0, (260 - dist) / 260) * 6;
      state.root.style.transform =
        "rotate(" + angle + "deg) translateY(-" + lift + "px) scale(1.06)";
    };
    window.addEventListener("mousemove", state.tiltHandler, { passive: true });
  }

  function detachTilt() {
    if (state.tiltHandler) {
      window.removeEventListener("mousemove", state.tiltHandler);
      state.tiltHandler = null;
    }
    if (state.root) state.root.style.transform = "";
  }

  /** hover 反馈：idle / open（浮窗已打开但空闲）时切换开心表情 + 兴奋摇摆；
   *  思考/流式等工作状态不打扰 */
  function attachHover(trigger) {
    detachHover();
    var onEnter = function () {
      if (state.current !== "idle" && state.current !== "open") return;
      state.hovered = true;
      swapImage("happy");
      if (state.root) {
        // 先移除再强制重排后加回，保证快速移出再移入时摇摆动画重新播放
        state.root.classList.remove("ai-pet-excited");
        void state.root.offsetWidth;
        state.root.classList.add("ai-pet-excited");
      }
      showHoverBubble(trigger);
    };
    var onLeave = function () {
      state.hovered = false;
      if (state.root) state.root.classList.remove("ai-pet-excited");
      // open 状态本就显示开心表情，无需回退
      if (state.current === "idle") swapImage("idle");
      hideHoverBubble();
    };
    trigger.addEventListener("mouseenter", onEnter);
    trigger.addEventListener("mouseleave", onLeave);
    state.hoverHandlers = { trigger: trigger, enter: onEnter, leave: onLeave };
  }

  function detachHover() {
    var h = state.hoverHandlers;
    if (h) {
      h.trigger.removeEventListener("mouseenter", h.enter);
      h.trigger.removeEventListener("mouseleave", h.leave);
      state.hoverHandlers = null;
    }
    state.hovered = false;
  }

  /** hover 气泡：轮换显示语录；仅在配置了语录时启用 */
  function showHoverBubble(trigger) {
    if (!state.phrases.length || state.destroyed) return;
    var text = state.phrases[state.phraseIndex % state.phrases.length];
    state.phraseIndex += 1;
    if (!state.hoverBubble) {
      var bubble = document.createElement("span");
      bubble.className = "ai-pet-bubble ai-pet-hover-bubble";
      trigger.appendChild(bubble);
      state.hoverBubble = bubble;
    }
    state.hoverBubble.textContent = text;
    // 两帧后加 show 类，保证 transition 生效（新建元素首帧直接显示也无妨）
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        if (state.hoverBubble) state.hoverBubble.classList.add("show");
      });
    });
  }

  function hideHoverBubble() {
    if (!state.hoverBubble) return;
    var bubble = state.hoverBubble;
    bubble.classList.remove("show");
    setTimeout(function () {
      // 过渡期间再次 hover（show 已加回）则不移除
      if (bubble.classList.contains("show")) return;
      bubble.remove();
      if (state.hoverBubble === bubble) state.hoverBubble = null;
    }, 280);
  }

  function applyState(logicalState) {
    if (!state.root) return;
    // 关闭浮窗与 idle 等价：统一按 idle 处理。否则 "close" 会作为独立逻辑状态卡住，
    // 而 hover/tilt/语录气泡都只在 idle/open 时响应 → 关掉浮窗后宠物再不理会鼠标
    // （表现为：预览里关掉自动弹开的浮窗后，hover 无表情变化、无随机语录）。
    if (logicalState === "close") logicalState = "idle";
    state.current = logicalState;
    clearStateClasses();
    switch (logicalState) {
      case "open":
        swapImage("happy");
        state.root.classList.add("ai-pet-bounce");
        break;
      case "thinking":
        swapImage("thinking");
        state.root.classList.add("ai-pet-thinking");
        break;
      case "streaming":
        state.root.classList.add("ai-pet-sway");
        break;
      case "done":
      case "liked":
        swapImage("happy");
        state.root.classList.add("ai-pet-bounce");
        scheduleRevertToIdle(1600);
        break;
      case "disliked":
        swapImage("sad");
        state.root.classList.add("ai-pet-droop");
        scheduleRevertToIdle(2600);
        break;
      default: // idle
        swapImage("idle");
        state.root.classList.add("ai-pet-breathe");
        break;
    }
  }

  /**
   * 把宠物挂载到触发器按钮里。
   * @param trigger  #ai-chat-trigger 按钮元素（保留其点击行为，内部内容替换为宠物）
   * @param manifest { name, images: {...} }
   * @param opts     { size, greeting, greetingText }
   */
  function mount(trigger, manifest, opts) {
    destroy();
    if (!manifest || !manifest.images || !manifest.images.idle) return false;

    state.destroyed = false;
    state.manifest = manifest;
    opts = opts || {};
    state.phrases = Array.isArray(opts.phrases)
      ? opts.phrases.filter(function (s) { return typeof s === "string" && s.trim(); }).slice(0, 10)
      : [];
    state.phraseIndex = 0;
    state.hoverBubble = null;

    trigger.innerHTML = "";
    var root = document.createElement("span");
    root.className = "ai-pet";
    root.style.width = (opts.size || 96) + "px";
    root.style.height = (opts.size || 96) + "px";

    var img = document.createElement("img");
    img.className = "ai-pet-img";
    img.style.imageRendering = manifest.imageRendering === "pixelated" ? "pixelated" : "auto";
    img.src = manifest.images.idle;
    img.alt = manifest.name || "AI 宠物";
    img.draggable = false;
    root.appendChild(img);

    // 思考气泡（CSS 控制显隐）
    var dots = document.createElement("span");
    dots.className = "ai-pet-dots";
    dots.innerHTML = "<i></i><i></i><i></i>";
    root.appendChild(dots);

    trigger.appendChild(root);
    state.root = root;
    state.img = img;

    // 主图加载失败（如内置皮肤图片缺失 / 宠物被删）→ 通知调用方回退图标模式
    img.onerror = function () {
      destroy();
      if (typeof opts.onError === "function") opts.onError();
    };

    attachTilt();
    attachHover(trigger);
    applyState("idle");
    scheduleBlink();

    // 欢迎气泡：每次会话只主动打一次招呼
    if (opts.greeting && opts.greetingText) {
      try {
        if (!sessionStorage.getItem("ai-pet-greeted")) {
          sessionStorage.setItem("ai-pet-greeted", "1");
          var bubble = document.createElement("span");
          bubble.className = "ai-pet-bubble";
          bubble.textContent = opts.greetingText;
          trigger.appendChild(bubble);
          setTimeout(function () {
            bubble.classList.add("ai-pet-bubble-out");
            setTimeout(function () { bubble.remove(); }, 400);
          }, 6000);
        }
      } catch (e) { /* sessionStorage 不可用时静默跳过 */ }
    }
    return true;
  }

  function setState(logicalState) {
    if (state.destroyed || !state.root) return;
    clearTimeout(state.revertTimer);
    applyState(logicalState);
  }

  function destroy() {
    state.destroyed = true;
    clearTimeout(state.blinkTimer);
    clearTimeout(state.revertTimer);
    detachTilt();
    detachHover();
    if (state.hoverBubble && state.hoverBubble.parentNode) state.hoverBubble.remove();
    state.hoverBubble = null;
    state.phrases = [];
    state.phraseIndex = 0;
    if (state.root && state.root.parentNode) {
      state.root.parentNode.querySelectorAll(".ai-pet-bubble").forEach
        ? state.root.parentNode.querySelectorAll(".ai-pet-bubble")
            .forEach(function (n) { n.remove(); })
        : null;
      state.root.remove();
    }
    state.root = null;
    state.img = null;
    state.manifest = null;
    state.current = "idle";
  }

  window.AIPet = {
    available: available,
    mount: mount,
    setState: setState,
    destroy: destroy
  };
})();
