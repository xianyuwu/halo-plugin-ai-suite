<template>
  <div
    id="ai-chat-window"
    class="ai-embed open position-right"
    :style="'--ai-chat-color:' + themeColor"
  >
    <!-- 头部 -->
    <div class="ai-chat-header">
      <div class="ai-chat-header-avatar"><i class="ri-robot-2-line"></i></div>
      <div class="ai-chat-header-info"><h3>AI 助手</h3></div>
    </div>

    <!-- 消息区 -->
    <div class="ai-chat-messages" ref="messagesEl">
      <!-- 欢迎语 -->
      <div class="ai-chat-row assistant">
        <div class="ai-chat-row-avatar"><i class="ri-robot-2-line"></i></div>
        <div class="ai-chat-msg assistant"><p>Hi! 有什么想了解的？</p></div>
      </div>

      <!-- 快捷问题 -->
      <div class="ai-chat-shortcuts" v-if="messages.length === 0 && shortcuts.length">
        <button
          v-for="(s, i) in shortcuts"
          :key="i"
          class="ai-shortcut-card"
          type="button"
          @click="sendShortcut(s.text)"
        >
          <span class="ai-shortcut-card-icon"><component :is="s.icon" /></span>
          <span class="ai-shortcut-card-text">{{ s.text }}</span>
        </button>
      </div>

      <!-- 对话消息 -->
      <template v-for="(msg, i) in messages" :key="i">
        <!-- 用户消息 -->
        <div v-if="msg.role === 'user'" class="ai-chat-row user">
          <div class="ai-chat-msg user">{{ msg.content }}</div>
        </div>
        <!-- AI 消息 -->
        <div v-else class="ai-chat-row assistant">
          <div class="ai-chat-row-avatar"><i class="ri-robot-2-line"></i></div>
          <div class="ai-chat-msg assistant">
            <div v-if="msg.html" v-html="msg.html"></div>
            <div v-else>{{ msg.content }}</div>
          </div>
        </div>
        <!-- typing 指示器 -->
        <div v-if="msg.role === 'assistant' && msg.typing" class="ai-chat-typing">
          <span></span><span></span><span></span>
        </div>
        <!-- 引用 -->
        <div v-if="msg.role === 'assistant' && msg.citations?.length && !msg.typing" class="ai-chat-citations">
          <span class="ai-cite-label">参考文章：</span>
          <a
            v-for="(c, ci) in msg.citations"
            :key="ci"
            class="ai-cite-link"
            :data-num="ci + 1"
            :href="c.url || c.postId ? '/archives/' + (c.url || c.postId) : undefined"
            target="_blank"
          >{{ c.title }}</a>
        </div>
      </template>
    </div>

    <!-- 清除对话 -->
    <div class="ai-chat-clear-area">
      <button class="ai-chat-clear-btn" type="button" @click="clearChat" v-if="messages.length > 0">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="ai-chat-clear-icon"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/><line x1="2" y1="2" x2="22" y2="22"/></svg>
        清除对话上下文
      </button>
    </div>

    <!-- 输入区 -->
    <div class="ai-chat-input-area">
      <textarea
        class="ai-chat-input"
        v-model="inputText"
        placeholder="输入你的问题…"
        rows="1"
        @keydown.enter.exact.prevent="send"
        @input="autoResize"
        ref="inputEl"
      ></textarea>
      <button class="ai-chat-send" :disabled="!inputText.trim() || streaming" @click="send" aria-label="发送">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
      </button>
    </div>

    <!-- 免责声明 -->
    <div class="ai-chat-disclaimer">内容由 AI 生成，请仔细甄别</div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick, onUnmounted, markRaw, type Component } from "vue";
import RiFileTextLine from "~icons/ri/file-text-line";
import RiRobotLine from "~icons/ri/robot-line";
import RiSearchLine from "~icons/ri/search-line";
import RiLightbulbLine from "~icons/ri/lightbulb-line";
import RiBookOpenLine from "~icons/ri/book-open-line";
import RiSparkling2Line from "~icons/ri/sparkling-2-line";

// ---------- 类型 ----------
interface Citation { title: string; url?: string; postId?: string }
interface ChatMsg {
  role: "user" | "assistant";
  content: string;
  html?: string;
  citations?: Citation[];
  typing?: boolean;
}

// ---------- 状态 ----------
const messages = reactive<ChatMsg[]>([]);
const history = reactive<{ role: string; content: string }[]>([]);
const inputText = ref("");
const streaming = ref(false);
const messagesEl = ref<HTMLDivElement | null>(null);
const inputEl = ref<HTMLTextAreaElement | null>(null);
const shortcuts = ref<{ icon: Component; text: string }[]>([]);
const themeColor = ref("#4F46E5");

// Markdown 渲染器
let renderMd: ((text: string) => string) | null = null;
let cssLink: HTMLLinkElement | null = null;

// ---------- 生命周期 ----------
onMounted(() => {
  loadWidgetCSS();
  loadMarkdownLibs();
  fetchConfig();
});

onUnmounted(() => {
  // 移除动态加载的 CSS
  if (cssLink) cssLink.remove();
});

// 加载 chat-widget.css
function loadWidgetCSS() {
  cssLink = document.createElement("link");
  cssLink.rel = "stylesheet";
  cssLink.href = "/plugins/ai-assistant/assets/res/css/chat-widget.css";
  document.head.appendChild(cssLink);
}

// 加载 marked + DOMPurify
function loadMarkdownLibs() {
  const base = "/plugins/ai-assistant/assets/res/js";
  loadScript(`${base}/marked.min.js`).then(() => loadScript(`${base}/purify.min.js`)).then(() => {
    const marked = (window as any).marked;
    const purify = (window as any).DOMPurify;
    if (marked && purify) {
      marked.setOptions({ breaks: true, gfm: true });
      renderMd = (text: string) => purify.sanitize(marked.parse(text));
    }
  }).catch(() => {});
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if ((window as any).__cpScripts?.[src]) { resolve(); return; }
    const s = document.createElement("script");
    s.src = src;
    s.onload = () => { ((window as any).__cpScripts ??= {})[src] = true; resolve(); };
    s.onerror = reject;
    document.head.appendChild(s);
  });
}

// 获取配置（快捷问题 + 主题色）
async function fetchConfig() {
  try {
    const res = await fetch("/apis/api.halo.run/v1alpha1/widget-config");
    const data = await res.json();
    if (data.color) themeColor.value = data.color;
    if (Array.isArray(data.shortcuts) && data.shortcuts.length) {
      const iconPool: Component[] = [
        RiFileTextLine, RiRobotLine, RiSearchLine,
        RiLightbulbLine, RiBookOpenLine, RiSparkling2Line,
      ];
      shortcuts.value = data.shortcuts.slice(0, 6).map((s: string, i: number) => ({
        icon: markRaw(iconPool[i % iconPool.length]),
        text: s,
      }));
    }
  } catch {}
}

// ---------- 发送消息 ----------
function sendShortcut(text: string) {
  inputText.value = text;
  send();
}

async function send() {
  const text = inputText.value.trim();
  if (!text || streaming.value) return;

  messages.push({ role: "user", content: text });
  history.push({ role: "user", content: text });
  inputText.value = "";
  autoResize();
  scrollToBottom();

  const aiMsg: ChatMsg = reactive({ role: "assistant", content: "", html: "", typing: true, citations: [] });
  messages.push(aiMsg);
  streaming.value = true;
  scrollToBottom();

  try {
    await streamChat(text, aiMsg);
  } catch {
    aiMsg.content = "请求失败，请稍后重试";
    aiMsg.typing = false;
  }
  streaming.value = false;
}

// ---------- SSE 流式 ----------
async function streamChat(text: string, aiMsg: ChatMsg) {
  const res = await fetch("/apis/api.halo.run/v1alpha1/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message: text, history: history.slice(0, -1) }),
  });
  if (!res.ok || !res.body) throw new Error("请求失败");

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() || "";

    for (const block of parts) {
      const trimmed = block.trim();
      if (!trimmed) continue;
      let eventType = "message", eventData = "";
      for (const line of trimmed.split("\n")) {
        if (line.startsWith("event:")) eventType = line.substring(6).trim();
        else if (line.startsWith("data:")) eventData = line.substring(5).trim();
      }
      if (eventType === "citations") {
        try { const c = JSON.parse(eventData); if (Array.isArray(c)) aiMsg.citations = c; } catch {}
      } else if (eventData === "[DONE]") {
        // end
      } else {
        try {
          const p = eventData.charAt(0) === "{" ? JSON.parse(eventData) : null;
          aiMsg.content += p?.content ?? eventData;
        } catch { aiMsg.content += eventData; }
        aiMsg.html = renderMd ? renderMd(aiMsg.content) : aiMsg.content;
        scrollToBottom();
      }
    }
  }

  aiMsg.typing = false;
  if (renderMd) aiMsg.html = renderMd(aiMsg.content);
  history.push({ role: "assistant", content: aiMsg.content });
}

// ---------- 清空 ----------
function clearChat() {
  messages.splice(0);
  history.splice(0);
}

// ---------- 工具 ----------
function scrollToBottom() {
  nextTick(() => { if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight; });
}
function autoResize() {
  nextTick(() => {
    if (inputEl.value) {
      inputEl.value.style.height = "auto";
      inputEl.value.style.height = Math.min(inputEl.value.scrollHeight, 100) + "px";
    }
  });
}
</script>

<style scoped>
/* 嵌入模式下隐藏关闭按钮 */
#ai-chat-window.ai-embed :deep(.ai-chat-close) { display: none; }
</style>
