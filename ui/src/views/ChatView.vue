<template>
  <div class="ai-page">
    <VPageHeader title="对话与外观">
      <template #actions>
        <VButton type="primary" size="sm" :loading="saving" @click="save">保存配置</VButton>
      </template>
    </VPageHeader>
    <p class="ai-desc">聊天行为参数和浮窗样式</p>

    <div class="page-body">
    <VCard title="对话设置">
      <div class="ai-form-grid">
        <div class="ai-field" style="grid-column: 1/-1">
          <label>系统提示词</label>
          <textarea v-model="form.systemPrompt" rows="3" placeholder="你是这个博客的 AI 助手..."></textarea>
        </div>
        <div class="ai-field">
          <label>温度 (Temperature)</label>
          <input v-model.number="form.temperature" type="range" min="0" max="1" step="0.1" />
          <span class="ai-help">{{ form.temperature }}</span>
        </div>
        <div class="ai-field">
          <label>最大输出 Token</label>
          <input v-model.number="form.maxTokens" type="number" min="256" max="8192" />
        </div>
        <div class="ai-field">
          <label>对话历史轮数</label>
          <input v-model.number="form.historyTurns" type="number" min="0" max="20" />
        </div>
        <label><input type="checkbox" v-model="form.streamOutput" /> 流式输出</label>
      </div>
    </VCard>

    <VCard title="浮窗外观">
      <div class="ai-form-grid">
        <div class="ai-field">
          <label>浮窗位置</label>
          <select v-model="form.widgetPosition">
            <option value="right-bottom">右下角</option>
            <option value="left-bottom">左下角</option>
          </select>
        </div>
        <div class="ai-field">
          <label>主题色</label>
          <input v-model="form.widgetThemeColor" type="color" />
        </div>
        <div class="ai-field">
          <label>深浅色模式</label>
          <select v-model="form.widgetTheme">
            <option value="auto">自动适配博客</option>
            <option value="light">强制浅色</option>
            <option value="dark">强制深色</option>
          </select>
          <span class="ai-help">自动适配 = 先探测博客 &lt;body&gt; 类名 / 背景色亮度；探测不到时再看访客操作系统的深浅色偏好。博客本身固定深/浅色时建议直接锁死对应模式。</span>
        </div>
        <div class="ai-field">
          <label>欢迎语</label>
          <input v-model="form.welcomeMessage" placeholder="Hi! 有什么想了解的？" />
        </div>
        <div class="ai-field" style="grid-column: 1/-1">
          <label>快捷问题</label>
          <textarea v-model="form.shortcutQuestions" rows="3" placeholder="每行一个问题，最多 6 个；留空则不显示"></textarea>
          <span class="ai-help">显示在欢迎语下方的 chip，访客点击即直接发送</span>
        </div>
        <div class="ai-field">
          <label>窗口宽度 (px)</label>
          <input v-model.number="form.widgetWidth" type="number" min="300" max="600" />
        </div>
        <div class="ai-field">
          <label>窗口高度 (px)</label>
          <input v-model.number="form.widgetHeight" type="number" min="400" max="800" />
        </div>
        <div class="ai-field">
          <label>按钮对齐策略</label>
          <select v-model="form.widgetTriggerAlign">
            <option value="auto">自动对齐博客按钮组</option>
            <option value="manual">手动指定距底像素</option>
          </select>
          <span class="ai-help">自动 = 检测博客 .actions 容器，AI 按钮堆叠在其上方；手动 = 用下方「距底部」数值。博客没有 .actions 时自动会 fallback 到手动数值。</span>
        </div>
        <div class="ai-field" v-if="form.widgetTriggerAlign === 'manual'">
          <label>按钮距底部 (px)</label>
          <input v-model.number="form.widgetTriggerOffsetY" type="number" min="16" max="240" />
          <span class="ai-help">建议 80-120</span>
        </div>
        <label><input type="checkbox" v-model="form.allowGuest" /> 允许游客使用</label>
      </div>
    </VCard>
    </div>

  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";

const form = reactive({
  systemPrompt: "", temperature: 0.7, maxTokens: 2048, historyTurns: 5, streamOutput: true,
  widgetPosition: "right-bottom", widgetThemeColor: "#4F46E5", widgetTheme: "auto",
  welcomeMessage: "Hi! 有什么想了解的？",
  shortcutQuestions: "推荐热门文章\n关于AI的最新文章\n如何优化SEO",
  widgetWidth: 400, widgetHeight: 600,
  widgetTriggerAlign: "auto", widgetTriggerOffsetY: 24,
  allowGuest: true,
});
const saving = ref(false);
const saveMsg = ref(""), saveOk = ref(false);

async function save() {
  await saveGroup("chat", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}
onMounted(async () => { await loadGroup("chat", form); });
</script>
