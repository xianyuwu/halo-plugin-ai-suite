<template>
  <div class="chat-page">
    <div class="ai-content">
      <!-- 左侧：固定标题 + 可滚动卡片 -->
      <div class="chat-config">
        <div class="ai-section-heading">
          <h2><RiChatSmileLine /> 对话与外观</h2>
        </div>
        <div class="chat-config-scroll">
        <!-- 对话设置（标题已固定在上方，此处隐藏） -->
        <SectionCard title="对话设置" :icon-component="RiChatSmileLine" headerTitle="对话规则" headerDesc="设定系统提示词、生成参数和历史上下文长度，决定 AI 的回复质量与风格">
          <div class="ai-card-body">
            <div class="ai-form-field">
              <label class="ai-field-label">系统提示词</label>
              <textarea class="ai-input ai-textarea" v-model="form.systemPrompt" rows="4" placeholder="你是这个博客的 AI 助手，负责根据知识库内容回答用户问题..."></textarea>
              <div class="ai-helper-text">AI 助手的角色设定与行为准则，会作为每次对话的上下文前缀</div>
            </div>
            <div class="ai-form-grid-2" style="margin-top: 18px">
              <div class="ai-form-field">
                <label class="ai-field-label">温度 (Temperature) <span class="ai-range-value">{{ form.temperature }}</span></label>
                <input class="ai-range" v-model.number="form.temperature" type="range" min="0" max="1" step="0.05" />
                <div class="ai-helper-text">0 = 确定性输出，1 = 更具创造性</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">最大输出 Token</label>
                <input class="ai-input" v-model.number="form.maxTokens" type="number" min="256" max="8192" />
                <div class="ai-helper-text">单次回复的最大 token 上限</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">对话历史轮数</label>
                <input class="ai-input" v-model.number="form.historyTurns" type="number" min="0" max="20" />
                <div class="ai-helper-text">携带多少轮历史上下文</div>
              </div>
            </div>
            <div class="ai-option-grid" style="margin-top: 18px">
              <OptionCard v-model="form.streamOutput" title="流式输出" desc="逐字返回结果，访客可以看到 AI 正在「思考」，体验更流畅" />
              <OptionCard v-model="form.showRetrievalStatus" title="回答前显示检索状态" desc="在 AI 回答前展示「正在检索文章…」提示，增强用户对 RAG 过程的感知" />
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(['systemPrompt','temperature','maxTokens','historyTurns','streamOutput','showRetrievalStatus'])">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <!-- 欢迎语与快捷问题 -->
        <SectionCard title="欢迎语与快捷问题" :icon-component="RiHandHeartLine" headerTitle="首次互动" headerDesc="访客打开浮窗时看到的欢迎语和可点击的快捷问题">
          <div class="ai-card-body">
            <div class="ai-form-field">
              <label class="ai-field-label">欢迎语</label>
              <textarea class="ai-input ai-textarea" v-model="form.welcomeMessage" rows="3" placeholder="Hi! 有什么想了解的？"></textarea>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">快捷问题</label>
              <textarea class="ai-input ai-textarea ai-textarea-lg" v-model="form.shortcutQuestions" rows="5" placeholder="每行一个问题，最多 6 个；留空则不显示"></textarea>
              <div class="ai-helper-text">每行一个问题，最多 6 个；超出部分不会显示。留空则不显示快捷问题</div>
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(['welcomeMessage','shortcutQuestions'])">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <!-- 访客与权限 -->
        <SectionCard title="访客与权限" :icon-component="RiLockLine" headerTitle="访问控制" headerDesc="控制谁可以使用 AI 助手以及是否显示隐私提示">
          <div class="ai-card-body">
            <div class="ai-option-grid">
              <OptionCard v-model="form.allowGuest" title="允许游客使用" desc="未登录访客也可以使用 AI 助手，关闭后仅登录用户可见" />
              <OptionCard v-model="form.showPrivacyTip" title="显示隐私提示" desc="访客首次打开浮窗时展示隐私声明提示条，告知对话内容可能被记录" />
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetDefaults">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <!-- 浮窗外观（纯配置） -->
        <SectionCard title="浮窗外观" :icon-component="RiPaletteLine" headerTitle="浮窗样式" headerDesc="自定义浮窗的位置、主题色、尺寸与深浅色模式">
          <div class="ai-card-body">
            <div class="ai-form-grid-2">
              <div class="ai-form-field">
                <label class="ai-field-label">浮窗位置</label>
                <select class="ai-input ai-select" v-model="form.widgetPosition">
                  <option value="right-bottom">右下角</option>
                  <option value="left-bottom">左下角</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">深浅色模式</label>
                <select class="ai-input ai-select" v-model="form.widgetTheme">
                  <option value="auto">自动适配博客</option>
                  <option value="system">跟随系统</option>
                  <option value="light">强制浅色</option>
                  <option value="dark">强制深色</option>
                </select>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">窗口宽度 (px)</label>
                <input class="ai-input" v-model.number="form.widgetWidth" type="number" min="300" max="600" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">窗口高度 (px)</label>
                <input class="ai-input" v-model.number="form.widgetHeight" type="number" min="400" max="800" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮垂直位置</label>
                <select class="ai-input ai-select" v-model="form.widgetTriggerAlign">
                  <option value="auto">自动避让页面悬浮按钮</option>
                  <option value="manual">手动指定距底像素</option>
                </select>
                <div class="ai-helper-text">推荐移动端使用；加载时预留稳定位置，滚动过程中不会跳动</div>
              </div>
              <div class="ai-form-field" v-if="form.widgetTriggerAlign === 'manual'">
                <label class="ai-field-label">按钮距底部 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerOffsetY" type="number" min="16" max="240" />
                <div class="ai-helper-text">建议 80-120</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮水平边距 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerOffsetX" type="number" min="0" max="120" />
                <div class="ai-helper-text">距左/右边缘的距离，建议 16-32</div>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">按钮尺寸 (px)</label>
                <input class="ai-input" v-model.number="form.widgetTriggerSize" type="number" min="28" max="64" />
                <div class="ai-helper-text">建议 40-56</div>
              </div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">悬浮按钮图标</label>
              <div class="ai-icon-grid" :style="{ '--ai-chat-color': form.widgetThemeColor }">
                <button
                  v-for="icon in ICON_PRESETS"
                  :key="icon.value"
                  type="button"
                  :class="['ai-icon-grid-item', { active: form.widgetIcon === icon.value }]"
                  :title="icon.label"
                  @click="form.widgetIcon = icon.value"
                  v-html="icon.svg"
                ></button>
              </div>
              <div class="ai-helper-text">当前：{{ currentIconLabel }}</div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">按钮形状</label>
              <div class="ai-shape-grid" :style="{ '--ai-chat-color': form.widgetThemeColor }">
                <button
                  v-for="shape in TRIGGER_SHAPES"
                  :key="shape.value"
                  type="button"
                  :class="['ai-shape-item', { active: form.widgetTriggerShape === shape.value }]"
                  :title="shape.label"
                  @click="form.widgetTriggerShape = shape.value"
                >
                  <span class="ai-shape-preview" :style="{ borderRadius: shape.radius }"></span>
                  <span class="ai-shape-label">{{ shape.label }}</span>
                </button>
              </div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">按钮文字（留空则显示图标）</label>
              <input class="ai-input" v-model="form.widgetTriggerLabel" placeholder="如：AI" maxlength="4" />
              <div class="ai-helper-text">填写文字后图标配置失效，最多 4 个字符</div>
            </div>
            <div class="ai-form-field" style="margin-top: 18px">
              <label class="ai-field-label">主题色</label>
              <ThemeColorPicker
                v-model="form.widgetThemeColor"
                :effective-color="form.widgetThemeColor || DEFAULTS.widgetThemeColor"
                :invalid="!widgetThemeColorValid"
              />
              <div class="ai-helper-text" :class="{ error: !widgetThemeColorValid }">
                {{ widgetThemeColorHint }}
              </div>
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(['widgetPosition','widgetTheme','widgetWidth','widgetHeight','widgetTriggerAlign','widgetTriggerOffsetY','widgetTriggerOffsetX','widgetTriggerShape','widgetThemeColor','widgetIcon','widgetTriggerSize','widgetTriggerLabel'])">恢复默认</VButton>
              <VButton type="primary" :disabled="saving || !widgetThemeColorValid" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>
        </div>
      </div>

      <!-- 右侧：实时预览 / 调试追踪 -->
      <div class="chat-preview">
        <div class="ai-preview-label">预览与调试</div>
        <div class="ai-preview-tabs">
          <button :class="['ai-tab-btn', { active: rightTab === 'preview' }]" @click="rightTab = 'preview'">预览</button>
          <button :class="['ai-tab-btn', { active: rightTab === 'debug' }]" @click="rightTab = 'debug'">调试追踪</button>
        </div>
        <div v-show="rightTab === 'preview'" class="ai-tab-content">
          <iframe ref="iframeRef" class="ai-preview-iframe" :src="previewSrc" frameborder="0" @load="sendPreviewConfig"></iframe>
        </div>
        <div v-show="rightTab === 'debug'" class="ai-tab-content ai-debug-tab">
          <DebugTrace />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, watch } from "vue";
import { Toast , VButton, VSpace} from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";
import { ICON_PRESETS, TRIGGER_SHAPES } from "../utils/trigger-icons";
import SectionCard from "../components/SectionCard.vue";
import OptionCard from "../components/OptionCard.vue";
import DebugTrace from "../components/DebugTrace.vue";
import ThemeColorPicker from "../components/ThemeColorPicker.vue";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiHandHeartLine from "~icons/ri/hand-heart-line";
import RiLockLine from "~icons/ri/lock-line";
import RiPaletteLine from "~icons/ri/palette-line";

// 带时间戳防止浏览器缓存旧版 embed.html 及其引用的 chat-widget.js，
// 保证后台预览总是渲染最新部署的访客端代码（图标随配置实时联动）
const previewSrc =
  window.location.origin +
  "/plugins/ai-suite/assets/res/embed.html?ai-embed=1&_t=" +
  Date.now();

// 当前选中图标的中文名称（显示在选择器下方辅助说明）
const currentIconLabel = computed(
  () => ICON_PRESETS.find((i) => i.value === form.widgetIcon)?.label || "星光（默认）"
);


const DEFAULTS = {
  systemPrompt: "",
  temperature: 0.7,
  maxTokens: 2048,
  historyTurns: 5,
  streamOutput: true,
  showRetrievalStatus: false,
  widgetPosition: "right-bottom",
  widgetThemeColor: "#5387C4",
  widgetIcon: "ri-chat-3-line",
  widgetTriggerSize: 35,
  widgetTriggerLabel: "AI",
  widgetTheme: "auto",
  welcomeMessage: "Hi! 有什么想了解的？",
  shortcutQuestions: "推荐热门文章\n关于AI的最新文章\n旅行推荐",
  widgetWidth: 400,
  widgetHeight: 600,
  widgetTriggerAlign: "auto",
  widgetTriggerOffsetY: 125,
  widgetTriggerOffsetX: 17,
  widgetTriggerShape: "square",
  allowGuest: true,
  showPrivacyTip: false,
};

const form = reactive({ ...DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);
const rightTab = ref<"preview" | "debug">("preview");

const widgetThemeColorValid = computed(() => {
  const value = form.widgetThemeColor.trim();
  return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value);
});

const widgetThemeColorHint = computed(() => {
  if (!widgetThemeColorValid.value) return "请输入合法 HEX 色值，例如 #4F46E5。";
  return "访客问答浮窗、默认搜索弹框和默认脑图区块会使用这个主题色。";
});

// ===== 实时预览：通过 postMessage 将配置变化推送到 iframe =====
const iframeRef = ref<HTMLIFrameElement | null>(null);

function sendPreviewConfig() {
  if (!iframeRef.value?.contentWindow) return;
  const shortcuts = form.shortcutQuestions
    .split("\n")
    .map(s => s.trim())
    .filter(Boolean)
    .slice(0, 6);
  iframeRef.value.contentWindow.postMessage(
    {
      type: "ai-preview-config",
      payload: {
        color: form.widgetThemeColor,
        theme: form.widgetTheme,
        icon: form.widgetIcon,
        triggerLabel: form.widgetTriggerLabel,
        triggerAlign: form.widgetTriggerAlign === "manual" ? "manual" : "auto",
        triggerOffsetY: form.widgetTriggerOffsetY,
        triggerOffsetX: form.widgetTriggerOffsetX,
        triggerShape: form.widgetTriggerShape,
        triggerSize: form.widgetTriggerSize,
        welcome: form.welcomeMessage,
        shortcuts,
        allowGuest: form.allowGuest,
      },
    },
    "*"
  );
}

// 监听视觉属性变化，实时推送到 iframe 预览
watch(
  () => [
    form.widgetThemeColor,
    form.widgetTheme,
    form.widgetIcon,
    form.widgetTriggerLabel,
    form.widgetTriggerAlign,
    form.widgetTriggerOffsetY,
    form.widgetTriggerOffsetX,
    form.widgetTriggerShape,
    form.widgetTriggerSize,
    form.welcomeMessage,
    form.shortcutQuestions,
    form.allowGuest,
  ],
  () => { sendPreviewConfig(); }
);

function resetDefaults() {
  Object.assign(form, DEFAULTS);
  Toast.success("已恢复默认配置");
}

function resetFields(keys: string[]) {
  keys.forEach(function(k) { (form as any)[k] = (DEFAULTS as any)[k]; });
  Toast.success("已恢复默认");
}

async function save() {
  if (!widgetThemeColorValid.value) {
    saveOk.value = false;
    saveMsg.value = "主题色格式不正确";
    Toast.error("主题色格式不正确");
    return;
  }
  await saveGroup("chat", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  await loadGroup("chat", form);
});
</script>

<style scoped>
/* 左右分栏布局：固定高度，左侧滚动 */
/* 高度公式：100vh - (Halo顶栏64 + 布局上padding24 + 顶栏88) = 100vh - 176 */
/* 原来 200 减的是 layout 下 padding 24px，已在 styles.css 移除 */
.chat-page {
  height: calc(100vh - 176px);
  overflow: hidden;
  background: #f5f7fb;
}
.chat-page .ai-content {
  display: flex;
  height: 100%;
  gap: 22px;
  /* 覆盖全局 .ai-content 的上下 padding 28/52，避免把卡片可用高度挤掉 80px */
  padding: 0 24px;
}
/* 左右两栏自己留上下呼吸，避免紧贴顶栏/视口底 */
.chat-config {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 0;
}
.chat-config-scroll {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.chat-preview {
  flex: 0 0 480px;
  display: flex;
  flex-direction: column;
  padding: 20px 28px;
  overflow: hidden;
}

/* 预览 */
.ai-preview-label {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0;
  color: #111827;
  margin: 0 0 16px 0;
}
.ai-preview-iframe {
  width: 400px;
  height: 600px;
  border: none;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 2px 12px rgba(0,0,0,0.04);
}

/* Tab 切换 — 下划线指示器 */
.ai-preview-tabs {
  display: flex;
  gap: 24px;
  margin-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.ai-tab-btn {
  padding: 0 0 10px 0;
  border: none;
  border-radius: 0;
  background: transparent;
  color: #9ca3af;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s;
  position: relative;
}
.ai-tab-btn::after {
  content: "";
  position: absolute;
  bottom: -1px;
  left: 0;
  right: 0;
  height: 2px;
  background: transparent;
  border-radius: 1px;
  transition: background 0.15s;
}
.ai-tab-btn.active {
  color: #111827;
  font-weight: 600;
}
.ai-tab-btn.active::after {
  background: #4f46e5;
}
.ai-tab-btn:hover:not(.active) {
  color: #6b7280;
}
.ai-tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.ai-debug-tab {
  width: 100%;
  overflow-y: auto;
}

/* 表单工具样式 */
.ai-form-grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 18px; }
.ai-range-value { margin-left: auto; font-size: 13px; font-weight: 700; color: #4b5563; font-variant-numeric: tabular-nums; }
.ai-textarea-lg { min-height: 126px; }
.ai-color-row { display: flex; align-items: center; gap: 12px; }
.ai-color-preview { width: 46px; height: 46px; border-radius: 10px; flex-shrink: 0; box-shadow: inset 0 0 0 4px #fff, 0 0 0 1px #e5e7eb; border: 1px solid #e5e7eb; }
.ai-color-row .ai-input { flex: 1; }
.ai-helper-text.error { color: #dc2626; }
.ai-range { width: 100%; height: 6px; appearance: none; background: linear-gradient(to right, #111827 0%, #111827 50%, #e5e7eb 50%, #e5e7eb 100%); border-radius: 999px; outline: none; cursor: pointer; margin-top: 4px; }
.ai-range::-webkit-slider-thumb { appearance: none; width: 22px; height: 22px; border-radius: 50%; background: #fff; border: 2px solid #111827; box-shadow: 0 2px 8px rgba(17,24,39,0.15); cursor: pointer; }
.ai-range::-moz-range-thumb { width: 22px; height: 22px; border-radius: 50%; background: #fff; border: 2px solid #111827; box-shadow: 0 2px 8px rgba(17,24,39,0.15); cursor: pointer; }

.ai-input[type="number"] { border: 1px solid #94a3b8 !important; background: #fff !important; -webkit-appearance: none; -moz-appearance: textfield; appearance: none; }

/* 悬浮按钮图标网格选择器 — 所见即所得，SVG 与访客端同源 */
.ai-icon-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.ai-icon-grid-item {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  color: #4b5563;
  font-size: 22px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, box-shadow 0.15s, transform 0.1s;
}
.ai-icon-grid-item :deep(svg) { width: 1em; height: 1em; }
.ai-icon-grid-item:hover { color: #111827; border-color: #cbd5e1; }
.ai-icon-grid-item:active { transform: scale(0.94); }
.ai-icon-grid-item.active {
  color: var(--ai-chat-color, #4F46E5);
  border-color: var(--ai-chat-color, #4F46E5);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ai-chat-color, #4F46E5) 18%, transparent);
}
.ai-icon-grid-item.active :deep(svg) { fill: currentColor; }

/* 按钮形状选择器 — 用主题色预览块展示真实 border-radius */
.ai-shape-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.ai-shape-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 4px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.ai-shape-item:hover { border-color: #cbd5e1; }
.ai-shape-item.active {
  border-color: var(--ai-chat-color, #4F46E5);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ai-chat-color, #4F46E5) 18%, transparent);
}
.ai-shape-preview {
  width: 28px;
  height: 28px;
  background: var(--ai-chat-color, #4F46E5);
}
.ai-shape-label {
  font-size: 12px;
  color: #4b5563;
  white-space: nowrap;
}
.ai-shape-item.active .ai-shape-label { color: var(--ai-chat-color, #4F46E5); font-weight: 600; }
</style>
