<template>
  <div ref="chatPageRef" class="chat-page">
    <div class="ai-content">
      <!-- 左侧：对话行为配置；页级标题由 PageTopbar 统一渲染 -->
      <div class="chat-config">
        <div class="chat-config-scroll">
        <!-- 对话设置 -->
        <SectionCard title="" :icon-component="RiChatSmileLine" headerTitle="对话设置" headerDesc="设定系统提示词、生成参数和历史上下文长度，决定 AI 的回复质量与风格">
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
              <VButton type="default" @click="resetFields(['systemPrompt','temperature','maxTokens','historyTurns','allowVisitorReasoning','reasoningDefaultEnabled','streamOutput','showRetrievalStatus'])">恢复本节默认</VButton>
            </div>
          </div>
        </SectionCard>

        <!-- 欢迎语与快捷问题 -->
        <SectionCard title="" :icon-component="RiHandHeartLine" headerTitle="欢迎语与快捷问题" headerDesc="访客打开浮窗时看到的欢迎语和可点击的快捷问题">
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
                    <button
                      type="button"
                      class="shortcut-expand"
                      :class="{ open: isShortcutExpanded(item.id) }"
                      :title="isShortcutExpanded(item.id) ? '收起详情' : '展开详情'"
                      :aria-expanded="isShortcutExpanded(item.id)"
                      @click="toggleShortcutExpanded(item.id)"
                    >›</button>
                    <button type="button" class="shortcut-delete" title="删除" @click="removeShortcut(index)">×</button>
                  </div>
                  <div v-if="isShortcutExpanded(item.id)" class="shortcut-editor-grid">
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
              <VButton type="default" @click="resetShortcutSection">恢复本节默认</VButton>
            </div>
            <div v-if="shortcutValidationError" class="ai-helper-text error">{{ shortcutValidationError }}</div>
          </div>
        </SectionCard>

        <!-- 访客与权限 -->
        <SectionCard title="" :icon-component="RiLockLine" headerTitle="访客与权限" headerDesc="控制谁可以使用 AI 助手以及是否显示隐私提示">
          <div class="ai-card-body">
            <div class="ai-option-grid">
              <OptionCard v-model="form.allowGuest" title="允许游客使用" desc="未登录访客也可以使用 AI 助手，关闭后仅登录用户可见" />
              <OptionCard v-model="form.showPrivacyTip" title="显示隐私提示" desc="访客首次打开浮窗时展示隐私声明提示条，告知对话内容可能被记录" />
            </div>
            <div class="ai-card-actions">
              <VButton type="default" @click="resetFields(['allowGuest','showPrivacyTip'])">恢复本节默认</VButton>
            </div>
          </div>
        </SectionCard>
        </div>

        <div class="chat-page-actions">
          <span v-if="saveMsg" class="ai-save-msg" :class="saveOk ? 'ai-save-ok' : 'ai-save-fail'">{{ saveMsg }}</span>
          <span v-else class="chat-save-hint">保存时会统一更新本页所有对话配置</span>
          <div class="chat-page-action-buttons">
            <VButton type="default" :disabled="saving" @click="resetDefaults">恢复全部默认</VButton>
            <VButton type="primary" :disabled="saving || shortcutValidationError !== ''" @click="save">{{ saving ? '保存中...' : '保存全部配置' }}</VButton>
          </div>
        </div>
      </div>

      <!-- 右侧：对话行为只负责调试；实时预览统一放在「浮窗外观」页 -->
      <aside ref="debugShellRef" class="chat-debug-shell" :class="{ expanded: debugExpanded }">
        <button
          type="button"
          class="chat-debug-summary"
          :aria-expanded="debugExpanded"
          aria-controls="chat-debug-content"
          @click="debugExpanded = !debugExpanded"
        >
          <span class="chat-debug-summary-title"><RiBugLine /> 调试追踪</span>
          <span class="chat-debug-summary-action">{{ debugExpanded ? '收起' : '展开' }}<span class="chat-debug-chevron">›</span></span>
        </button>
        <div id="chat-debug-content" class="chat-debug-panel">
          <div class="ai-debug-panel-heading">
            <span class="ai-debug-panel-icon"><RiBugLine /></span>
            <div class="ai-debug-panel-info">
              <div class="ai-debug-panel-title">调试追踪</div>
              <div class="ai-debug-panel-desc">输入问题试运行，查看完整管线的各阶段耗时与结果</div>
            </div>
          </div>
          <div class="ai-debug-tab">
            <DebugTrace ref="debugTraceRef" />
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, nextTick } from "vue";
import { Toast, VButton } from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";
import SectionCard from "../components/SectionCard.vue";
import OptionCard from "../components/OptionCard.vue";
import DebugTrace from "../components/DebugTrace.vue";
import RiChatSmileLine from "~icons/ri/chat-smile-line";
import RiHandHeartLine from "~icons/ri/hand-heart-line";
import RiLockLine from "~icons/ri/lock-line";
import RiBugLine from "~icons/ri/bug-line";

// 本页只持有 chat 配置组里的「对话行为」字段；外观字段归「浮窗外观」页
// 管理，保存时由 saveGroup 合并写入，互不覆盖。
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
  welcomeMessage: "Hi! 有什么想了解的？",
  shortcutQuestions: "推荐热门文章\n关于AI的最新文章\n旅行推荐",
  shortcutItems: cloneDefaultShortcuts(),
  allowGuest: true,
  showPrivacyTip: false,
};

const form = reactive({ ...DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);
const enabledIntentRoutes = ref<IntentRouteOption[]>([]);
const draggingShortcutIndex = ref<number | null>(null);
const expandedShortcutIds = ref<string[]>([]);

function isShortcutExpanded(id: string) {
  return expandedShortcutIds.value.includes(id);
}

function toggleShortcutExpanded(id: string) {
  const i = expandedShortcutIds.value.indexOf(id);
  if (i >= 0) expandedShortcutIds.value.splice(i, 1);
  else expandedShortcutIds.value.push(id);
}

const debugTraceRef = ref<InstanceType<typeof DebugTrace> | null>(null);
const chatPageRef = ref<HTMLElement | null>(null);
const debugShellRef = ref<HTMLElement | null>(null);
const debugExpanded = ref(false);

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

function addShortcut() {
  if (form.shortcutItems.length >= 6) return;
  const id = `shortcut-${Date.now()}`;
  form.shortcutItems.push({
    id,
    label: "新快捷问题",
    query: "",
    icon: "sparkles",
    intentRouteId: "",
    enabled: true,
  });
  expandedShortcutIds.value.push(id);
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

/** 试运行：把快捷问题填入右侧调试追踪并直接发送 */
function testShortcut(item: ShortcutItem) {
  if (!item.query.trim()) return;
  const compactLayout = (chatPageRef.value?.clientWidth || window.innerWidth) < 960;
  if (compactLayout) debugExpanded.value = true;
  debugTraceRef.value?.fillQuery(item.query.trim());
  debugTraceRef.value?.sendDebug();
  if (compactLayout) {
    nextTick(() => debugShellRef.value?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }
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
});
</script>

<style scoped>
.chat-page {
  container-type: inline-size;
  min-height: 100%;
  background: #f5f7fb;
}
.chat-page .ai-content {
  display: grid;
  grid-template-columns: minmax(480px, 600px) minmax(380px, 460px);
  align-items: start;
  gap: 20px;
  padding: 20px 24px 44px;
}
.chat-config {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.chat-config-scroll {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.chat-config-scroll :deep(.ai-section-block) { margin-bottom: 0; }

/* 本页开关项采用轻量列表行，避免整页大边框重卡片 */
.chat-config-scroll :deep(.ai-option-grid) { gap: 10px; }
.chat-config-scroll :deep(.ai-option-card) {
  padding: 13px 16px;
  border-radius: 10px;
  box-shadow: none;
}
.chat-config-scroll :deep(.ai-option-card:hover),
.chat-config-scroll :deep(.ai-option-card.active) { box-shadow: none; }

.chat-debug-shell {
  min-width: 0;
  position: sticky;
  top: 16px;
}
.chat-debug-summary { display: none; }
.chat-debug-panel {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
  padding: 0;
  overflow: hidden;
  background: var(--ai-color-bg-card);
  border: 1px solid #e5e7eb;
  border-radius: var(--ai-radius-xl);
}

/* 右侧只承载管线调试，与「浮窗外观」页的实时预览分工 */
.ai-debug-panel-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffff;
  flex-shrink: 0;
}
.ai-debug-panel-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--ai-radius-md);
  background: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  flex-shrink: 0;
}
.ai-debug-panel-icon :deep(svg) { width: 16px; height: 16px; }
.ai-debug-panel-info { flex: 1; min-width: 0; }
.ai-debug-panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
}
.ai-debug-panel-desc {
  margin-top: 3px;
  font-size: 12px;
  color: #64748b;
}
.ai-debug-tab {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow-y: auto;
  padding: 14px 18px;
}

.chat-page-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 18px;
  padding: 14px 16px;
  border: 1px solid #dbe2ea;
  border-radius: 12px;
  background: rgba(255, 255, 255, .94);
}
.chat-save-hint { color: #64748b; font-size: 12px; }
.chat-page-action-buttons { display: flex; gap: 8px; flex-shrink: 0; }

/* 快捷问题编辑器 */
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
.shortcut-expand {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #94a3b8;
  font-size: 19px;
  line-height: 1;
  cursor: pointer;
  transform: rotate(90deg);
  transition: transform .16s ease, color .15s, background .15s;
}
.shortcut-expand:hover { color: #4f46e5; background: #eef2ff; }
.shortcut-expand.open { transform: rotate(-90deg); }
.shortcut-order { display: inline-flex; align-items: center; justify-content: center; width: 23px; height: 23px; border-radius: 7px; background: #eef2ff; color: #4f46e5; font-size: 11px; font-weight: 700; }
.shortcut-label-input { flex: 1; min-width: 0; height: 36px; font-weight: 650; }
.shortcut-enabled { display: inline-flex; align-items: center; gap: 5px; color: #475569; font-size: 12px; white-space: nowrap; }
.shortcut-editor-grid { display: grid; grid-template-columns: minmax(220px, 2fr) minmax(120px, .8fr); gap: 10px 12px; margin-top: 11px; padding-left: 42px; }
.shortcut-editor-grid .ai-field-label { font-size: 11px; }
.shortcut-intent-field { grid-column: 1; }
.shortcut-test-cell { display: flex; align-items: flex-end; padding-bottom: 1px; }
.shortcut-empty { margin-top: 12px; padding: 20px; border: 1px dashed #cbd5e1; border-radius: 11px; color: #64748b; font-size: 12px; text-align: center; }

/* 温度滑杆 */
.ai-range { width: 100%; height: 6px; appearance: none; background: linear-gradient(to right, #111827 0%, #111827 50%, #e5e7eb 50%, #e5e7eb 100%); border-radius: 999px; outline: none; cursor: pointer; margin-top: 4px; }
.ai-range::-webkit-slider-thumb { appearance: none; width: 22px; height: 22px; border-radius: 50%; background: #fff; border: 2px solid #111827; box-shadow: 0 2px 8px rgba(17,24,39,0.15); cursor: pointer; }
.ai-range::-moz-range-thumb { width: 22px; height: 22px; border-radius: 50%; background: #fff; border: 2px solid #111827; box-shadow: 0 2px 8px rgba(17,24,39,0.15); cursor: pointer; }

/* 表单工具样式 */
.ai-form-grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 18px; }
.ai-form-grid-2 .ai-input[type="number"] { max-width: 220px; }
.ai-range-value { margin-left: auto; font-size: 13px; font-weight: 700; color: #4b5563; font-variant-numeric: tabular-nums; }
.ai-textarea-lg { min-height: 126px; }
.ai-input[type="number"] { border: 1px solid #94a3b8 !important; background: #fff !important; -webkit-appearance: none; -moz-appearance: textfield; appearance: none; }
.ai-helper-text.error { color: #dc2626; }

/* 根据插件内容区而非浏览器视口切换布局。 */
@container (max-width: 959px) {
  .chat-page .ai-content {
    grid-template-columns: minmax(0, 1fr);
    padding: 16px 16px 36px;
  }

  .chat-debug-shell {
    grid-row: 1;
    top: 10px;
    z-index: 6;
  }

  .chat-config { grid-row: 2; }

  .chat-debug-summary {
    width: 100%;
    min-height: 46px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 14px;
    border: 1px solid #dbe2ea;
    border-radius: 12px;
    background: rgba(255, 255, 255, .96);
    box-shadow: 0 6px 18px rgba(15, 23, 42, .08);
    color: #111827;
    cursor: pointer;
  }
  .chat-debug-summary-title,
  .chat-debug-summary-action { display: inline-flex; align-items: center; gap: 7px; }
  .chat-debug-summary-title { font-size: 14px; font-weight: 600; }
  .chat-debug-summary-title :deep(svg) { width: 17px; height: 17px; }
  .chat-debug-summary-action { color: #64748b; font-size: 12px; }
  .chat-debug-chevron { display: inline-block; font-size: 19px; line-height: 1; transform: rotate(90deg); transition: transform .16s ease; }
  .chat-debug-shell.expanded .chat-debug-chevron { transform: rotate(-90deg); }

  .chat-debug-panel { display: none; }
  .chat-debug-shell.expanded .chat-debug-panel {
    display: flex;
    max-height: min(620px, calc(100dvh - 84px));
    margin-top: 8px;
    padding: 14px;
    border: 1px solid #dbe2ea;
    border-radius: 12px;
    background: #f5f7fb;
    box-shadow: 0 12px 28px rgba(15, 23, 42, .1);
  }
  .ai-debug-panel-heading { display: none; }
  .ai-debug-tab { min-height: 280px; padding: 0; }
}

@container (max-width: 639px) {
  .chat-page .ai-content {
    padding: 12px 10px 28px;
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
  .shortcut-editor-head { align-items: stretch; flex-direction: column; }

  .chat-page-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .chat-page-action-buttons { display: grid; grid-template-columns: 1fr 1fr; }

  .ai-debug-tab :deep(.debug-input-area) {
    display: grid;
    grid-template-columns: 1fr auto;
    align-items: center;
  }
  .ai-debug-tab :deep(.debug-textarea) {
    grid-column: 1 / -1;
    width: 100%;
    box-sizing: border-box;
  }
  .ai-debug-tab :deep(.debug-icon-btn) { justify-self: end; }
}
</style>
