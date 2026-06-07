<template>
  <div class="ai-content">
    <!-- 页面顶部：写作辅助总开关，靠右 -->
    <div class="ai-writing-topbar">
      <div class="ai-writing-topbar-text">
        <div class="ai-writing-topbar-title">AI 写作辅助功能</div>
        <div class="ai-writing-topbar-desc">
          <strong>关闭后：</strong>文章编辑器内将隐藏「AI 大纲」工具栏按钮，选区上方也不再出现润色/续写/扩写/简化/译英 5 个 AI 操作菜单。
          <strong>已打开的编辑器需刷新</strong>才能让按钮完全消失。
        </div>
      </div>
      <label
        class="ai-switch-label"
        :title="form.enabled ? '点击关闭 AI 写作辅助' : '点击启用 AI 写作辅助'"
      >
        <span class="ai-switch-track" :class="{ off: !form.enabled }" @click="toggleEnabled"></span>
        <span><strong>{{ form.enabled ? '已启用' : '已关闭' }}</strong></span>
      </label>
    </div>

    <div class="ai-layout-grid">
      <!-- 左列：配置 -->
      <div class="ai-section-block">
        <!-- 写作模型配置 -->
        <SectionCard title="写作模型" :icon-component="RiRobotLine" headerTitle="模型配置" headerDesc="配置写作辅助使用的 LLM 模型，留空则复用「模型配置」页的对话模型">
          <div class="ai-card-body">
            <div class="ai-toggle-row">
              <label class="ai-switch-label">
                <span class="ai-switch-track" :class="{ off: !form.useCustomModel }" @click="form.useCustomModel = !form.useCustomModel"></span>
                <span>{{ form.useCustomModel ? '使用独立模型' : '复用对话模型' }}</span>
              </label>
              <span class="ai-toggle-desc">开启后可单独指定写作辅助的模型，不影响对话功能</span>
            </div>

            <div class="ai-form-grid" style="margin-top: 16px;">
              <div class="ai-form-field">
                <label class="ai-field-label">API Base URL</label>
                <input type="text" class="ai-input" v-model="form.writingBaseUrl" placeholder="https://api.deepseek.com/v1" :disabled="!form.useCustomModel || saving" />
                <span class="ai-helper-text">OpenAI 兼容协议的 API 地址</span>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">API Key</label>
                <input type="password" class="ai-input" v-model="form.writingApiKey" placeholder="sk-..." :disabled="!form.useCustomModel || saving" />
                <span class="ai-helper-text">密钥安全存储在 Secret 中</span>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">模型名称</label>
                <input type="text" class="ai-input" v-model="form.writingModel" placeholder="deepseek-chat" :disabled="!form.useCustomModel || saving" />
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">最大输出 Token</label>
                <input type="number" class="ai-input" v-model.number="form.maxTokens" min="256" max="8192" step="256" :disabled="saving" />
                <span class="ai-helper-text">建议 2048，大纲生成可能需要更多</span>
              </div>
            </div>

            <div :class="{ 'is-disabled': !form.useCustomModel }" class="ai-card-actions">
              <VButton type="default" :disabled="!form.useCustomModel" @click="resetDefaults">恢复默认</VButton>
              <VButton type="primary" :disabled="!form.useCustomModel || saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>

        <!-- 大纲生成配置 -->
        <SectionCard title="大纲生成" :icon-component="RiListCheck3" headerTitle="大纲配置" headerDesc="控制 AI 生成文章大纲的行为参数">
          <div class="ai-card-body">
            <div class="ai-form-grid">
              <div class="ai-form-field">
                <label class="ai-field-label">章节数量</label>
                <input type="number" class="ai-input" v-model.number="form.outlineSections" min="3" max="12" step="1" :disabled="saving" />
                <span class="ai-helper-text">大纲包含的章节数量，建议 5-8</span>
              </div>
              <div class="ai-form-field">
                <label class="ai-field-label">嵌套深度</label>
                <select class="ai-input ai-select" v-model.number="form.outlineDepth" :disabled="saving">
                  <option v-for="d in DEPTH_OPTIONS" :key="d" :value="d">第 {{ cnDepth(d) }} 层（共 {{ d }} 层嵌套）</option>
                </select>
                <span class="ai-helper-text">{{ depthHint }}</span>
              </div>
              <div class="ai-form-field" style="grid-column: 1 / -1;">
                <label class="ai-field-label">编号方式</label>
                <select class="ai-input ai-select" v-model="form.outlineNumbering" :disabled="saving">
                  <option v-for="opt in NUMBERING_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                </select>
                <span class="ai-helper-text">预览：<span class="ai-numbering-preview">{{ depthPreview }}</span></span>
              </div>
              <div class="ai-form-field" style="grid-column: 1 / -1;">
                <label class="ai-field-label">生成温度 <span class="ai-label-hint">{{ form.outlineTemperature.toFixed(1) }}</span></label>
                <input type="range" class="ai-range" v-model.number="form.outlineTemperature" min="0.1" max="0.8" step="0.1" :disabled="saving" />
                <span class="ai-helper-text">越低越稳定，建议 0.2-0.4</span>
              </div>
              <div class="ai-form-field" style="grid-column: 1 / -1;">
                <label class="ai-field-label">自定义要求</label>
                <textarea class="ai-input ai-textarea" v-model="form.outlineExtraPrompt" rows="3" placeholder="如：使用中文数字编号、每节包含案例说明..." :disabled="saving"></textarea>
                <span class="ai-helper-text">追加到大纲生成 prompt 的额外约束，留空使用默认</span>
              </div>
            </div>

            <div class="ai-card-actions">
              <VButton type="default" @click="resetDefaults">恢复默认</VButton>
              <VButton type="primary" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</VButton>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- 右列：使用说明 -->
      <div class="ai-section-block">
        <SectionCard title="使用说明" :icon-component="RiBookOpenLine" headerTitle="如何使用写作辅助" headerDesc="在 Halo 文章编辑器中使用 AI 写作辅助功能">
          <div class="ai-card-body">
            <div class="ai-guide">
              <div class="ai-guide-section">
                <div class="ai-guide-title">一、选区操作（润色 / 续写 / 扩写 / 简化 / 译英）</div>
                <ol class="ai-guide-steps">
                  <li>在文章编辑器中<strong>选中一段文字</strong></li>
                  <li>选区上方自动出现气泡菜单，包含 5 个 AI 动作按钮</li>
                  <li>点击对应动作（如「润色」），AI 开始流式生成结果</li>
                  <li>生成完毕后，点击 <strong>✓ 应用</strong> 替换选区内容</li>
                  <li>也可以在输入框中追加指令进行多轮交互</li>
                </ol>
              </div>
              <div class="ai-guide-section">
                <div class="ai-guide-title">二、大纲生成</div>
                <ol class="ai-guide-steps">
                  <li>点击编辑器工具栏的 <strong>📋 大纲</strong> 按钮</li>
                  <li>在弹窗中输入文章主题（如「云原生架构的演进」）</li>
                  <li>点击「生成大纲」，AI 将输出结构化的章节标题 + 提要</li>
                  <li>预览满意后点击 <strong>插入到编辑器</strong></li>
                </ol>
              </div>
              <div class="ai-guide-section">
                <div class="ai-guide-title">三、小贴士</div>
                <ul class="ai-guide-tips">
                  <li>选区操作支持多轮对话：首次生成后可输入追加指令继续调整</li>
                  <li>Ctrl+Z 可撤销 AI 替换的内容</li>
                  <li>如果对结果不满意，点「↻ 重新生成」重试</li>
                  <li>大纲格式为 JSON 结构化输出，渲染效果稳定可靠</li>
                </ul>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from "vue";
import { Toast , VButton, VSpace} from "@halo-dev/components";
import { saveGroup, loadGroup } from "../utils/config";
import SectionCard from "../components/SectionCard.vue";
import { setWritingEnabled } from "../extensions/ai-writing/writing-enabled";
import RiRobotLine from "~icons/ri/robot-line";
import RiListCheck3 from "~icons/ri/list-check-3";
import RiBookOpenLine from "~icons/ri/book-open-line";

// 写作辅助默认配置
const WRITING_DEFAULTS = {
  enabled: true,
  useCustomModel: false,
  writingBaseUrl: "",
  writingApiKey: "",
  writingModel: "",
  maxTokens: 2048,
  outlineTemperature: 0.3,
  outlineSections: 6,
  outlineDepth: 1,
  outlineNumbering: "chinese",
  outlineExtraPrompt: "",
};

// 嵌套深度 1-3（封顶 3，深度 4+ LLM 准确率不可靠）
const DEPTH_OPTIONS = [1, 2, 3] as const;

// 编号方式 5 枚举：value 透传到后端 / label 给人看 / preview 配合 depthPreview 拼出
const NUMBERING_OPTIONS = [
  { value: "chinese", label: "中文一、二、三", top: "一、xxx", sub: "1.1 xxx", sub2: "1.1.1 xxx" },
  { value: "chinese-paren", label: "中文（一）（二）（三）", top: "（一）xxx", sub: "1.1 xxx", sub2: "1.1.1 xxx" },
  { value: "arabic", label: "阿拉伯 1. 2. 3.", top: "1. xxx", sub: "1.1 xxx", sub2: "1.1.1 xxx" },
  { value: "roman", label: "罗马 I. II. III.", top: "I. xxx", sub: "1.1 xxx", sub2: "1.1.1 xxx" },
  { value: "none", label: "无编号", top: "xxx", sub: "xxx", sub2: "xxx" },
];

// 嵌套深度中文映射（"一" / "二" / "三"）用于下拉显示
const CN_DEPTH = ["", "一", "二", "三"];
function cnDepth(d: number) {
  return CN_DEPTH[d] || String(d);
}

// 嵌套深度说明（按 depth 给出每层结构提示）
const DEPTH_HINTS: Record<number, string> = {
  1: "只生成顶层章节，所有章节同级",
  2: "顶层 + 二级子章节，每章下挂 1-3 个子节",
  3: "顶层 + 二级 + 三级，更深的层级会按 LLM 能力评估",
};

// 按当前 depth 和 numbering 实时拼出预览示例
const depthPreview = computed(() => {
  const opt = NUMBERING_OPTIONS.find((o) => o.value === form.outlineNumbering);
  if (!opt) return "";
  if (form.outlineDepth === 1) return opt.top;
  if (form.outlineDepth === 2) return `${opt.top} / ${opt.sub}`;
  return `${opt.top} / ${opt.sub} / ${opt.sub2}`;
});

const depthHint = computed(() => DEPTH_HINTS[form.outlineDepth] || "");

const form = reactive({ ...WRITING_DEFAULTS });
const saving = ref(false);
const saveMsg = ref("");
const saveOk = ref(false);
// 自动保存的 debounce 句柄（开关切换时用，避免连续点多次触发多次保存）
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

function resetDefaults() {
  Object.assign(form, WRITING_DEFAULTS);
  Toast.success("已恢复默认配置");
}

async function save() {
  await saveGroup("writing", form, saving, saveMsg, saveOk);
  if (saveOk.value) {
    Toast.success(saveMsg.value || "保存成功");
  } else {
    Toast.error(saveMsg.value || "保存失败");
  }
}

/**
 * 切换总开关：立即同步到模块级 ref，让 AIBubbleMenu/ChatComposer 动态响应
 * （选区气泡菜单和 chat composer 立即消失，不需刷新编辑器；工具栏「📋 大纲」按钮
 * 受 Halo 框架限制需要刷新编辑器才消失）
 *
 * <p>同时 300ms debounce 自动保存到后端，避免用户忘了点「保存配置」导致刷新后
 * 开关状态回弹（后端 AIProperties 没存 enabled 字段，loadGroup 会用默认值 true 覆盖）
 */
function toggleEnabled() {
  form.enabled = !form.enabled;
  setWritingEnabled(form.enabled);
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => {
    autoSave();
  }, 300);
}

async function autoSave() {
  await saveGroup("writing", form, saving, saveMsg, saveOk);
  if (!saveOk.value) {
    Toast.error(saveMsg.value || "保存失败");
  }
  // 成功不弹 toast（避免每次切换开关都打扰用户；失败需要弹）
}

onMounted(async () => {
  await loadGroup("writing", form);
  // 同步总开关到模块级 ref
  setWritingEnabled(form.enabled);
  // 兜底：若后端没存过 useCustomModel 但 writingBaseUrl 有值，按"使用独立模型"对待
  // 兼容旧版只存了 BaseURL 未存开关的状态
  if (form.writingBaseUrl && form.useCustomModel === false) {
    form.useCustomModel = true;
  }
});
</script>

<style scoped>
.ai-toggle-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 4px;
}

/* 特色 toggle row：带浅色背景 + 圆角，用于页面级总开关等需要突出的开关 */
.ai-toggle-row-featured {
  background: linear-gradient(180deg, #f0fdf4, #f9fafb);
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 16px;
}

/* 页面顶部总开关行：靠右对齐 + 浅绿背景 */
.ai-writing-topbar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 18px;
  margin-bottom: 20px;
  padding: 12px 18px;
  background: linear-gradient(180deg, #f0fdf4, #f9fafb);
  border: 1px solid #bbf7d0;
  border-radius: 12px;
}

.ai-writing-topbar-text {
  flex: 1;
  min-width: 0;
  margin-right: auto; /* 把开关挤到最右 */
}

.ai-writing-topbar-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 2px;
}

.ai-writing-topbar-desc {
  font-size: 12px;
  color: #4b5563;
  line-height: 1.5;
}

.ai-switch-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

.ai-switch-track {
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: #111827;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}

.ai-switch-track.off {
  background: #d1d5db;
}

.ai-switch-track::after {
  content: "";
  position: absolute;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  top: 2px;
  left: 18px;
  transition: left 0.2s;
}

.ai-switch-track.off::after {
  left: 2px;
}

.ai-toggle-desc {
  font-size: 12px;
  color: #8a94a6;
}

.ai-range {
  width: 100%;
  accent-color: #111827;
  margin-top: 4px;
}

.ai-guide {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.ai-guide-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 8px;
}

.ai-guide-steps {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  color: #4b5563;
}

.ai-guide-steps li::marker {
  color: #111827;
  font-weight: 600;
}

.ai-guide-tips {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: #4b5563;
}

.ai-guide-tips li::marker {
  color: #9ca3af;
}

/* 编号方式预览样式 */
.ai-numbering-preview {
  font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
  color: #111827;
  font-weight: 600;
  margin-left: 4px;
}

</style>
