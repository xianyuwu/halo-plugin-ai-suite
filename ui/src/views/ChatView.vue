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
              <OptionCard v-model="form.streamOutput" title="流式输出" desc="逐步返回最终回答，不展示模型的内部思考内容" />
              <OptionCard v-model="form.allowVisitorReasoning" title="允许访客开启深度思考" desc="访客可在每次提问前自主选择，可能增加响应时间和 Token 消耗" />
              <OptionCard v-if="form.allowVisitorReasoning" v-model="form.reasoningDefaultEnabled" title="默认开启深度思考" desc="访客首次打开问答浮窗时的默认选择" />
              <OptionCard v-model="form.showRetrievalStatus" title="回答前显示检索状态" desc="在 AI 回答前展示「正在检索文章…」提示，增强用户对 RAG 过程的感知" />
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(['systemPrompt','temperature','maxTokens','historyTurns','allowVisitorReasoning','reasoningDefaultEnabled','streamOutput','showRetrievalStatus'])">恢复默认</VButton>
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
              <div class="shortcut-editor-head">
                <div>
                  <label class="ai-field-label">快捷问题</label>
                  <div class="ai-helper-text">建议保留 3-4 个高价值入口；绑定意图后点击将直接执行对应 Pipeline</div>
                </div>
                <VButton type="default" :disabled="form.shortcutItems.length >= 6" @click="addShortcut">添加问题</VButton>
              </div>
              <div v-if="form.shortcutItems.length" class="shortcut-editor-list">
                <div
                  v-for="(item, index) in form.shortcutItems"
                  :key="item.id"
                  class="shortcut-editor-item"
                  :class="{ disabled: !item.enabled, dragging: draggingShortcutIndex === index }"
                  draggable="true"
                  @dragstart="startShortcutDrag(index)"
                  @dragover.prevent
                  @drop="dropShortcut(index)"
                  @dragend="draggingShortcutIndex = null"
                >
                  <div class="shortcut-editor-top">
                    <button type="button" class="shortcut-drag" title="拖动排序">⋮⋮</button>
                    <span class="shortcut-order">{{ index + 1 }}</span>
                    <input class="ai-input shortcut-label-input" v-model="item.label" maxlength="20" placeholder="显示标题，如：热门文章" />
                    <label class="shortcut-enabled"><input type="checkbox" v-model="item.enabled" /> 启用</label>
                    <button type="button" class="shortcut-delete" title="删除" @click="removeShortcut(index)">×</button>
                  </div>
                  <div class="shortcut-editor-grid">
                    <div class="ai-form-field">
                      <label class="ai-field-label">实际问题</label>
                      <input class="ai-input" v-model="item.query" maxlength="200" placeholder="发送给 AI 的完整问题" />
                    </div>
                    <div class="ai-form-field">
                      <label class="ai-field-label">图标</label>
                      <select class="ai-input ai-select" v-model="item.icon">
                        <option v-for="icon in SHORTCUT_ICONS" :key="icon.value" :value="icon.value">{{ icon.emoji }} {{ icon.label }}</option>
                      </select>
                    </div>
                    <div class="ai-form-field shortcut-intent-field">
                      <label class="ai-field-label">绑定意图</label>
                      <select class="ai-input ai-select" v-model="item.intentRouteId">
                        <option value="">自动识别</option>
                        <option v-for="route in enabledIntentRoutes" :key="route.id" :value="route.id">{{ route.displayName }}</option>
                      </select>
                      <div v-if="item.intentRouteId && !enabledIntentRoutes.some(route => route.id === item.intentRouteId)" class="ai-helper-text error">绑定意图不存在或已停用</div>
                    </div>
                    <div class="shortcut-test-cell">
                      <VButton type="default" :disabled="!item.query.trim()" @click="testShortcut(item)">试运行</VButton>
                    </div>
                  </div>
                </div>
              </div>
              <div v-else class="shortcut-empty">暂无快捷问题，访客端将只显示欢迎语。</div>
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetShortcutSection">恢复默认</VButton>
              <VButton type="primary" :disabled="saving || shortcutValidationError !== ''" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
            <div v-if="shortcutValidationError" class="ai-helper-text error">{{ shortcutValidationError }}</div>
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

type ShortcutItem = {
  id: string;
  label: string;
  query: string;
  icon: string;
  intentRouteId: string;
  enabled: boolean;
};

type IntentRouteOption = {
  id: string;
  displayName: string;
  enabled: boolean;
};

const SHORTCUT_ICONS = [
  { value: "fire", label: "热门", emoji: "🔥" },
  { value: "clock", label: "最新", emoji: "🕒" },
  { value: "tag", label: "标签", emoji: "🏷️" },
  { value: "category", label: "分类", emoji: "📂" },
  { value: "search", label: "搜索", emoji: "🔍" },
  { value: "sparkles", label: "推荐", emoji: "✨" },
];

const DEFAULT_SHORTCUTS: ShortcutItem[] = [
  { id: "shortcut-hot", label: "热门文章", query: "推荐当前站点的热门文章", icon: "fire", intentRouteId: "builtin-hot-articles", enabled: true },
  { id: "shortcut-latest", label: "最新发布", query: "最近发布了哪些文章", icon: "clock", intentRouteId: "builtin-latest-posts", enabled: true },
  { id: "shortcut-tag", label: "按标签查找", query: "按标签帮我查找文章", icon: "tag", intentRouteId: "builtin-by-tag", enabled: true },
];

function cloneDefaultShortcuts(): ShortcutItem[] {
  return DEFAULT_SHORTCUTS.map(item => ({ ...item }));
}


const DEFAULTS = {
  systemPrompt: "",
  temperature: 0.7,
  maxTokens: 2048,
  historyTurns: 5,
  allowVisitorReasoning: true,
  reasoningDefaultEnabled: false,
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
  shortcutItems: cloneDefaultShortcuts(),
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
const enabledIntentRoutes = ref<IntentRouteOption[]>([]);
const draggingShortcutIndex = ref<number | null>(null);

const shortcutValidationError = computed(() => {
  if (form.shortcutItems.length > 6) return "快捷问题最多 6 个";
  for (const item of form.shortcutItems) {
    if (!item.label.trim()) return "每个快捷问题都需要显示标题";
    if (!item.query.trim()) return `「${item.label || "未命名"}」缺少实际问题`;
    if (item.intentRouteId && !enabledIntentRoutes.value.some(route => route.id === item.intentRouteId)) {
      return `「${item.label}」绑定的意图不存在或已停用`;
    }
  }
  return "";
});

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
  const shortcuts = form.shortcutItems
    .filter(item => item.enabled && item.query.trim())
    .slice(0, 6)
    .map(item => ({ ...item }));
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
        allowVisitorReasoning: form.allowVisitorReasoning,
        reasoningDefaultEnabled: form.reasoningDefaultEnabled,
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
    form.shortcutItems,
    form.allowGuest,
    form.allowVisitorReasoning,
    form.reasoningDefaultEnabled,
  ],
  () => { sendPreviewConfig(); },
  { deep: true }
);

function addShortcut() {
  if (form.shortcutItems.length >= 6) return;
  form.shortcutItems.push({
    id: `shortcut-${Date.now()}`,
    label: "新快捷问题",
    query: "",
    icon: "sparkles",
    intentRouteId: "",
    enabled: true,
  });
}

function removeShortcut(index: number) {
  form.shortcutItems.splice(index, 1);
}

function startShortcutDrag(index: number) {
  draggingShortcutIndex.value = index;
}

function dropShortcut(index: number) {
  const from = draggingShortcutIndex.value;
  if (from === null || from === index) return;
  const [item] = form.shortcutItems.splice(from, 1);
  form.shortcutItems.splice(index, 0, item);
  draggingShortcutIndex.value = null;
}

function testShortcut(item: ShortcutItem) {
  if (!item.query.trim()) return;
  rightTab.value = "preview";
  iframeRef.value?.contentWindow?.postMessage({
    type: "ai-preview-query",
    payload: { query: item.query.trim(), intentRouteId: item.intentRouteId || "" },
  }, "*");
}

function resetShortcutSection() {
  form.welcomeMessage = DEFAULTS.welcomeMessage;
  form.shortcutItems = cloneDefaultShortcuts();
  Toast.success("已恢复默认");
}

async function loadIntentRoutes() {
  try {
    const resp = await fetch("/apis/console.api.ai-suite.halo.run/v1alpha1/intent-routes");
    if (!resp.ok) return;
    const routes = await resp.json();
    enabledIntentRoutes.value = Array.isArray(routes)
      ? routes.filter((route: IntentRouteOption) => route.enabled)
      : [];
  } catch {}
}

function migrateLegacyShortcuts() {
  if (Array.isArray(form.shortcutItems) && form.shortcutItems.length) return;
  form.shortcutItems = String(form.shortcutQuestions || "")
    .split("\n")
    .map(value => value.trim())
    .filter(Boolean)
    .slice(0, 6)
    .map((query, index) => inferLegacyShortcut(query, index));
}

function inferLegacyShortcut(query: string, index: number): ShortcutItem {
  let icon = "sparkles";
  let intentRouteId = "";
  if (query.includes("热门") || query.includes("热文")) {
    icon = "fire";
    intentRouteId = "builtin-hot-articles";
  } else if (query.includes("最新") || query.includes("最近")) {
    icon = "clock";
    intentRouteId = "builtin-latest-posts";
  } else if (query.includes("标签")) {
    icon = "tag";
    intentRouteId = "builtin-by-tag";
  } else if (query.includes("分类")) {
    icon = "category";
    intentRouteId = "builtin-by-category";
  }
  return { id: `legacy-${index + 1}`, label: query, query, icon, intentRouteId, enabled: true };
}

function resetDefaults() {
  Object.assign(form, DEFAULTS);
  form.shortcutItems = cloneDefaultShortcuts();
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
  if (shortcutValidationError.value) {
    Toast.error(shortcutValidationError.value);
    return;
  }
  // 保留旧字段，便于旧版插件回退时仍能读取基本问题。
  form.shortcutQuestions = form.shortcutItems
    .filter(item => item.enabled)
    .map(item => item.query.trim())
    .filter(Boolean)
    .join("\n");
  await saveGroup("chat", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

onMounted(async () => {
  const [chatGroup] = await Promise.all([loadGroup("chat", form), loadIntentRoutes()]);
  if ((chatGroup as any)?.allowVisitorReasoning === undefined) {
    form.allowVisitorReasoning = true;
    form.reasoningDefaultEnabled = (chatGroup as any)?.reasoningMode === "enabled";
  }
  if (!Array.isArray((chatGroup as any)?.shortcutItems)
      && typeof (chatGroup as any)?.shortcutQuestions === "string") {
    form.shortcutItems = [];
    migrateLegacyShortcuts();
  }
  sendPreviewConfig();
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
  width: min(100%, 400px);
  height: min(600px, calc(100vh - 300px));
  min-height: 460px;
  border: none;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 32px rgba(0,0,0,0.08), 0 2px 12px rgba(0,0,0,0.04);
  align-self: center;
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
.shortcut-editor-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.shortcut-editor-list { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
.shortcut-editor-item { border: 1px solid #dbe2ea; border-radius: 12px; background: #fff; padding: 12px; transition: opacity .15s, border-color .15s, box-shadow .15s; }
.shortcut-editor-item:hover { border-color: #b8c4d3; box-shadow: 0 4px 14px rgba(15, 23, 42, .055); }
.shortcut-editor-item.disabled { opacity: .58; }
.shortcut-editor-item.dragging { opacity: .42; border-style: dashed; }
.shortcut-editor-top { display: flex; align-items: center; gap: 9px; }
.shortcut-drag, .shortcut-delete { border: 0; background: transparent; color: #94a3b8; cursor: pointer; }
.shortcut-drag { padding: 4px 1px; font-size: 15px; letter-spacing: -3px; cursor: grab; }
.shortcut-delete { width: 28px; height: 28px; border-radius: 7px; font-size: 20px; line-height: 1; }
.shortcut-delete:hover { color: #dc2626; background: #fef2f2; }
.shortcut-order { display: inline-flex; align-items: center; justify-content: center; width: 23px; height: 23px; border-radius: 7px; background: #eef2ff; color: #4f46e5; font-size: 11px; font-weight: 700; }
.shortcut-label-input { flex: 1; min-width: 0; height: 36px; font-weight: 650; }
.shortcut-enabled { display: inline-flex; align-items: center; gap: 5px; color: #475569; font-size: 12px; white-space: nowrap; }
.shortcut-editor-grid { display: grid; grid-template-columns: minmax(220px, 2fr) minmax(120px, .8fr); gap: 10px 12px; margin-top: 11px; padding-left: 42px; }
.shortcut-editor-grid .ai-field-label { font-size: 11px; }
.shortcut-intent-field { grid-column: 1; }
.shortcut-test-cell { display: flex; align-items: flex-end; padding-bottom: 1px; }
.shortcut-empty { margin-top: 12px; padding: 20px; border: 1px dashed #cbd5e1; border-radius: 11px; color: #64748b; font-size: 12px; text-align: center; }
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

@media (max-width: 1280px) {
  .chat-page .ai-content {
    gap: 16px;
    padding: 0 18px;
  }

  .chat-preview {
    flex-basis: 360px;
    padding: 20px 14px;
  }

  .ai-preview-iframe {
    min-height: 420px;
  }
}

@media (max-width: 900px) {
  .chat-page {
    height: auto;
    min-height: calc(100vh - 176px);
    overflow: visible;
  }

  .chat-page .ai-content {
    display: block;
    height: auto;
    padding: 0 16px 32px;
  }

  .chat-config {
    padding: 18px 0 0;
  }

  .chat-config-scroll {
    overflow: visible;
  }

  .chat-preview {
    margin-top: 22px;
    padding: 0 0 20px;
    overflow: visible;
  }

  .ai-preview-label {
    margin-bottom: 12px;
    font-size: 16px;
  }

  .ai-tab-content {
    min-height: 0;
  }

  .ai-preview-iframe {
    width: min(100%, 400px);
    height: 560px;
    min-height: 420px;
  }

  .ai-debug-tab {
    max-height: 560px;
    min-height: 320px;
  }
}

@media (max-width: 640px) {
  .chat-page .ai-content {
    padding: 0 10px 28px;
  }

  .chat-config {
    padding-top: 14px;
  }

  .chat-config-scroll {
    gap: 16px;
  }

  .ai-form-grid-2 {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .shortcut-editor-grid { grid-template-columns: 1fr; padding-left: 0; }
  .shortcut-intent-field { grid-column: auto; }
  .shortcut-editor-top { flex-wrap: wrap; }

  .ai-icon-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .ai-shape-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ai-preview-tabs {
    gap: 18px;
  }

  .ai-preview-iframe {
    height: 520px;
    min-height: 380px;
  }
}

@media (max-width: 420px) {
  .ai-icon-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
