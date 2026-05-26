<template>
  <div class="writing-page">
    <VPageHeader title="AI 写作辅助">
    </VPageHeader>
    <p class="description">粘贴文本，选择操作，AI 帮你续写、润色、翻译、总结</p>

    <div class="page-body">
    <VCard title="AI 写作编辑器">
      <div class="editor-layout">
      <!-- 输入区 -->
      <div class="panel input-panel">
        <div class="panel-header">
          <h3>输入文本</h3>
          <span class="char-count">{{ inputText.length }} 字</span>
        </div>
        <textarea
          v-model="inputText"
          class="text-input"
          placeholder="在此粘贴或输入需要处理的文本..."
        ></textarea>
      </div>

      <!-- 操作区 -->
      <div class="actions-panel">
        <div class="action-buttons">
          <button
            v-for="act in actions"
            :key="act.value"
            class="action-btn"
            :class="{ active: selectedAction === act.value }"
            @click="selectedAction = act.value"
            :disabled="processing"
          >
            <span class="act-icon">{{ act.icon }}</span>
            <span class="act-label">{{ act.label }}</span>
            <span class="act-desc">{{ act.desc }}</span>
          </button>
        </div>

        <div class="custom-instruction">
          <input
            v-model="customInstruction"
            type="text"
            class="instruction-input"
            placeholder="或输入自定义指令（如：「把这段改成更正式的语气」）"
            :disabled="processing"
          />
        </div>

        <VButton
          type="primary"
          :loading="processing"
          :disabled="!inputText.trim()"
          @click="runAssist"
        >
          {{ processing ? 'AI 处理中...' : '开始处理 →' }}
        </VButton>
      </div>

      <!-- 输出区 -->
      <div class="panel output-panel">
        <div class="panel-header">
          <h3>AI 输出</h3>
          <div class="output-actions">
            <VButton
              size="sm"
              @click="copyOutput"
              :disabled="!outputText"
            >
              复制
            </VButton>
            <VButton
              size="sm"
              @click="insertToInput"
              :disabled="!outputText"
            >
              插入到输入
            </VButton>
          </div>
        </div>
        <div class="text-output" :class="{ empty: !outputText }">
          {{ outputText || 'AI 处理结果将显示在这里...' }}
        </div>
        <div v-if="processing" class="processing-indicator">
          <span class="ai-dot"></span> AI 正在处理...
        </div>
      </div>
      </div>
    </VCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components";

const API_BASE = "/apis/console.api.ai-assistant.halo.run/v1alpha1";

const actions = [
  { value: "polish", label: "润色", icon: "✨", desc: "让文字更流畅有文采" },
  { value: "continue", label: "续写", icon: "✏️", desc: "保持风格继续往下写" },
  { value: "expand", label: "扩写", icon: "📝", desc: "增加细节和论述" },
  { value: "simplify", label: "简化", icon: "📄", desc: "用更通俗的语言表达" },
  { value: "summarize", label: "摘要", icon: "📋", desc: "提炼核心内容" },
  { value: "translate-en", label: "译英", icon: "🌐", desc: "翻译为英文" },
];

const inputText = ref("");
const outputText = ref("");
const selectedAction = ref("polish");
const customInstruction = ref("");
const processing = ref(false);

async function runAssist() {
  if (!inputText.value.trim() || processing.value) return;

  processing.value = true;
  outputText.value = "";

  try {
    const resp = await fetch(`${API_BASE}/writing/assist/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        text: inputText.value,
        action: customInstruction.value.trim() ? "" : selectedAction.value,
        instruction: customInstruction.value.trim(),
      }),
    });

    const reader = resp.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed.startsWith("data:")) {
          const data = trimmed.substring(5).trim();
          if (data) outputText.value += data;
        }
      }
    }
  } catch (e) {
    Toast.error("请求失败: " + (e as Error).message);
    outputText.value = "请求失败: " + (e as Error).message;
  } finally {
    processing.value = false;
  }
}

async function copyOutput() {
  try {
    await navigator.clipboard.writeText(outputText.value);
    Toast.success("已复制到剪贴板");
  } catch {
    const ta = document.createElement("textarea");
    ta.value = outputText.value;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    document.body.removeChild(ta);
    Toast.success("已复制到剪贴板");
  }
}

function insertToInput() {
  if (outputText.value) {
    inputText.value = inputText.value + "\n\n" + outputText.value;
    outputText.value = "";
  }
}
</script>
