<template>
  <div class="route-studio">
    <header class="studio-hero">
      <button class="back-button" @click="router.push({ name: 'AISuiteIntentRoutes' })">← 返回路由列表</button>
      <div class="hero-copy">
        <div class="hero-mark">✦</div>
        <div><span class="eyebrow">AI ROUTE BUILDER</span><h1>把业务想法变成可执行路由</h1><p>描述目标，AI 会生成受约束的触发规则与 Pipeline；确认前不会改动线上配置。</p></div>
      </div>
      <div class="studio-stepper" aria-label="创建进度">
        <div v-for="item in [{n:1,t:'描述需求'},{n:2,t:'检查规则'},{n:3,t:'测试保存'}]" :key="item.n" class="step-item" :class="{ active: step === item.n, done: step > item.n }">
          <span>{{ step > item.n ? '✓' : item.n }}</span><b>{{ item.t }}</b>
        </div>
      </div>
    </header>

    <main v-if="step === 1" class="compose-layout">
      <section class="studio-panel compose-panel">
        <div class="panel-heading"><div><span class="section-kicker">01 · DESCRIBE</span><h2>你希望访客得到什么结果？</h2><p>像给同事提需求一样描述，无需理解正则或处理器参数。</p></div><span class="safe-badge">草稿模式</span></div>
        <div class="prompt-shell" :class="{ busy: generating }">
          <div class="prompt-icon">✦</div>
          <textarea v-model="requirement" maxlength="2000" rows="8" placeholder="例如：当访客询问 AI、大模型或 RAG 相关文章时，筛选相关主题，并按发布时间返回最近 8 篇……" />
          <div class="prompt-footer"><span>AI 将读取当前站点的标签、分类与已有路由</span><b>{{ requirement.length }}/2000</b></div>
        </div>
        <div v-if="error" class="route-alert error"><span>!</span><p>{{ error }}</p></div>
        <div class="primary-action"><button class="primary-button" :disabled="generating || !requirement.trim()" @click="generate">{{ generating ? '正在构建路由…' : '生成路由草稿' }}<span>→</span></button></div>
        <div v-if="generating" class="build-progress"><span class="pulse"></span><div><strong>正在构建可执行规则</strong><p>理解目标 · 匹配站点数据 · 编排 Pipeline · 检查冲突</p></div></div>
      </section>

      <aside class="inspiration-panel">
        <div class="aside-title"><span>灵感模板</span><small>点击即可使用</small></div>
        <button v-for="(example, index) in examples" :key="example" class="example-card" @click="requirement = example">
          <span class="example-icon">{{ ['⌁','↗','◇','◎'][index] }}</span><span><b>{{ ['主题最新内容','分类热度排行','标签内容导航','主题热门推荐'][index] }}</b><small>{{ example }}</small></span><i>→</i>
        </button>
        <div class="guard-note"><span>◈</span><p><b>安全生成</b><br />AI 只能组合现有处理器，无法生成或执行任意代码。</p></div>
      </aside>
    </main>

    <template v-else-if="draft">
      <main v-if="step === 2" class="review-layout">
        <aside class="review-sidebar">
          <section class="studio-panel insight-panel"><span class="section-kicker">AI INTERPRETATION</span><h2>生成思路</h2><ol><li v-for="(item,index) in explanations" :key="item"><span>{{ index + 1 }}</span><p>{{ item }}</p></li></ol></section>
          <section class="studio-panel health-panel"><div class="health-title"><h3>规则健康度</h3><span :class="hasErrors ? 'danger' : issues.length ? 'warn' : 'good'">{{ hasErrors ? '需修复' : issues.length ? '有建议' : '状态良好' }}</span></div>
            <div v-if="issues.length" class="issue-list"><div v-for="(issue,index) in issues" :key="index" class="route-alert" :class="issue.level"><span>{{ issue.level === 'error' ? '!' : 'i' }}</span><p>{{ issue.message }}</p></div></div><div v-else class="empty-health"><span>✓</span><p>没有发现规则冲突<br /><small>可以进入模拟测试</small></p></div>
          </section>
          <button class="regenerate-button" @click="step = 1">↻ 修改需求并重新生成</button>
        </aside>

        <section class="studio-panel rule-canvas">
          <div class="canvas-header"><div><span class="section-kicker">02 · REVIEW</span><h2>路由规则草稿</h2><p>业务信息在上，执行流程在下；所有修改仅作用于当前草稿。</p></div><span class="draft-badge">未启用</span></div>
          <div class="form-grid"><label><span>路由名称</span><input v-model="draft.displayName" class="studio-input" /></label><label><span>优先级</span><input v-model.number="draft.priority" type="number" class="studio-input compact" /></label><label class="wide"><span>业务说明</span><input v-model="draft.description" class="studio-input" /></label></div>
          <div class="canvas-section"><div class="section-title"><span class="section-number">A</span><div><h3>触发条件</h3><p>任意一条规则命中即可进入此路由</p></div></div><div class="trigger-editor"><div v-for="(_,index) in draft.triggerPatterns" :key="index" class="trigger-row"><span class="regex-tag">REGEX</span><input v-model="draft.triggerPatterns[index]" class="studio-input" /><button @click="draft.triggerPatterns.splice(index,1)">×</button></div><button class="add-link" @click="draft.triggerPatterns.push('')">＋ 添加触发规则</button></div><label class="fallback-toggle"><input v-model="draft.llmFallback" type="checkbox" /><span></span><p><b>语义兜底分类</b><small>正则未命中时，再让模型判断一次</small></p></label><input v-if="draft.llmFallback" v-model="draft.llmFallbackHint" class="studio-input" placeholder="描述什么情况下应该命中此意图" /></div>
          <div class="canvas-section"><div class="section-title"><span class="section-number">B</span><div><h3>执行 Pipeline</h3><p>数据从上到下流动：先筛选，再排序与限制数量</p></div></div><div class="pipeline-track"><article v-for="(pipelineStep,index) in draft.pipeline" :key="index" class="pipeline-node"><div class="node-rail"><span>{{ index + 1 }}</span><i v-if="index < draft.pipeline.length - 1"></i></div><div class="node-card"><div class="node-header"><span class="node-icon">{{ ['⌁','⇅','◇','◎'][index % 4] }}</span><div><strong>{{ processorName(pipelineStep.type) }}</strong><code>{{ pipelineStep.type }}</code></div><button @click="draft.pipeline.splice(index,1)">×</button></div><div class="param-grid"><label v-for="(_,key) in pipelineStep.params" :key="key"><span>{{ key }}</span><input v-model="pipelineStep.params[key]" class="studio-input" /></label></div></div></article></div></div>
          <div class="inline-actions"><button class="footer-secondary" @click="step = 1">← 返回修改需求</button><div><span>规则确认无误后，用真实站点数据试跑</span><button class="primary-button" @click="step = 3">继续测试 <b>→</b></button></div></div>
        </section>
      </main>

      <main v-else class="test-layout"><section class="studio-panel test-panel"><div class="panel-heading"><div><span class="section-kicker">03 · VERIFY</span><h2>用真实数据试跑这条路由</h2><p>草稿会执行实际 Pipeline，但不会保存，也不会影响访客端。</p></div><span class="safe-badge">沙盒测试</span></div><div class="test-command"><span>›</span><input v-model="testQuery" placeholder="输入一个访客可能提出的问题" @keyup.enter="simulate" /><button class="primary-button test-button" :disabled="simulating || !testQuery.trim()" @click="simulate">{{ simulating ? '执行中…' : '运行测试' }}</button></div><div v-if="testError" class="route-alert error"><span>!</span><p>{{ testError }}</p></div><div v-if="simulation" class="simulation-result"><div class="match-banner" :class="{ matched: simulation.matched }"><span>{{ simulation.matched ? '✓' : '!' }}</span><div><strong>{{ simulation.matched ? '问题已命中当前路由' : '问题未命中触发规则' }}</strong><p>{{ simulation.matched ? '下面是完整 Pipeline 执行结果' : '已继续模拟 Pipeline，建议调整触发条件' }}</p></div></div><div class="trace-timeline"><article v-for="(stage,index) in simulation.stages" :key="index"><div class="trace-dot">{{ index + 1 }}</div><div class="trace-card"><header><strong>{{ stage.label }}</strong><small>{{ stage.durationMs }} ms</small></header><p>{{ stage.detail }}</p><div v-if="stage.data?.posts?.length" class="result-posts"><a v-for="post in stage.data.posts.slice(0,10)" :key="post.title" :href="post.url" target="_blank"><span>↗</span>{{ post.title }}</a></div></div></article></div></div><div v-else class="test-empty"><div>⌁</div><h3>等待一次试跑</h3><p>输入问题后，这里会展示每个节点的输入、输出和真实文章结果。</p></div><div class="inline-actions"><button class="footer-secondary" @click="step = 2">← 返回检查规则</button><div><span>保存后默认关闭，可在路由列表中手动启用</span><button class="primary-button" :disabled="saving || hasErrors" @click="saveDraft">{{ saving ? '保存中…' : '保存为草稿' }}</button></div></div></section></main>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";

type PipelineStep = { type: string; params: Record<string, string> };
type Draft = { id?: string; displayName: string; description: string; enabled: boolean; priority: number; triggerPatterns: string[]; llmFallback: boolean; llmFallbackHint: string; pipeline: PipelineStep[]; outputTemplate: string };
type Issue = { level: "error" | "warning" | "info"; field: string; message: string };
type TraceStage = { label: string; detail: string; durationMs: number; data?: { posts?: Array<{ title: string; url: string }> } };

const API = "/apis/console.api.ai-suite.halo.run/v1alpha1/intent-routes";
const router = useRouter();
const step = ref(1);
const requirement = ref("");
const generating = ref(false);
const saving = ref(false);
const simulating = ref(false);
const error = ref("");
const testError = ref("");
const draft = ref<Draft | null>(null);
const issues = ref<Issue[]>([]);
const explanations = ref<string[]>([]);
const testQuery = ref("");
const simulation = ref<{ matched: boolean; stages: TraceStage[] } | null>(null);
const hasErrors = computed(() => issues.value.some((issue) => issue.level === "error"));

const examples = [
  "推荐最近发布的 AI、大模型和 RAG 文章，按时间倒序返回 8 篇",
  "查找旅行日记分类中浏览量最高的 10 篇文章",
  "用户查询 Kubernetes 标签文章时，优先推荐最新内容",
  "推荐云原生主题的热门文章，只返回真实的站内文章",
];

const names: Record<string, string> = { TOPIC_MATCH: "主题综合匹配", LLM_TITLE_FILTER: "LLM 标题推理", TAG_MATCH: "标签匹配", KEYWORD_MATCH: "关键词匹配", CATEGORY_MATCH: "分类匹配", TIME_SORT: "时间排序", VISIT_SORT: "浏览量排序" };
const processorName = (type: string) => names[type] || type;

async function generate() {
  generating.value = true; error.value = "";
  try {
    const response = await fetch(`${API}/generate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ requirement: requirement.value }) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
    draft.value = { ...data.draft, enabled: false };
    issues.value = data.issues || [];
    explanations.value = data.explanations || [];
    step.value = 2;
  } catch (e: any) { error.value = e.message; } finally { generating.value = false; }
}

async function simulate() {
  if (!draft.value) return;
  simulating.value = true; testError.value = ""; simulation.value = null;
  try {
    const response = await fetch(`${API}/simulate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ draft: sanitizedDraft(), query: testQuery.value }) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
    simulation.value = data;
    if (data.issues) issues.value = data.issues;
  } catch (e: any) { testError.value = e.message; } finally { simulating.value = false; }
}

async function saveDraft() {
  saving.value = true; testError.value = "";
  try {
    const response = await fetch(API, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(sanitizedDraft()) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
    await router.push({ name: "AISuiteIntentRoutes" });
  } catch (e: any) { testError.value = e.message; } finally { saving.value = false; }
}

function sanitizedDraft() {
  if (!draft.value) return null;
  return { ...draft.value, id: draft.value.id || "", enabled: false, triggerPatterns: draft.value.triggerPatterns.map((item) => item.trim()).filter(Boolean) };
}
</script>

<style scoped>
.ai-route-create-page{padding:16px;max-width:1180px;margin:0 auto 90px}.route-create-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.route-steps{display:flex;align-items:center;gap:10px;color:var(--color-gray,#94a3b8);font-size:13px;font-weight:600}.route-steps span.active{color:var(--color-primary,#4f46e5)}.route-steps i{display:block;width:42px;height:1px;background:var(--color-border,#dbe2ea)}.requirement-input,.output-template{width:100%;box-sizing:border-box;border:1px solid var(--color-border,#dbe2ea);border-radius:12px;padding:16px;background:var(--color-surface,#fff);color:inherit;resize:vertical;line-height:1.7}.char-count{text-align:right;color:var(--color-gray,#94a3b8);font-size:12px}.example-title{margin:18px 0 8px;font-weight:600}.example-list{display:flex;flex-wrap:wrap;gap:8px}.example-list button{border:1px solid var(--color-border,#dbe2ea);background:var(--color-surface,#fff);border-radius:999px;padding:7px 12px;color:inherit;cursor:pointer}.route-actions{display:flex;margin-top:20px}.route-actions-end{justify-content:flex-end}.generating-card{margin-top:16px;padding:16px;border-radius:10px;background:var(--color-primary-soft,#eef2ff);display:flex;justify-content:space-between;gap:16px}.review-grid{display:grid;grid-template-columns:minmax(280px,.7fr) minmax(0,1.3fr);gap:16px}.explanation-list{padding-left:20px;line-height:1.9}.issue-list{display:grid;gap:8px;margin:18px 0}.issue{display:flex;gap:10px;padding:10px 12px;border-radius:8px;background:#eff6ff}.issue.warning{background:#fff7ed;color:#9a3412}.issue.error{background:#fef2f2;color:#b91c1c}.route-success{margin:16px 0;padding:12px;background:#ecfdf5;color:#047857;border-radius:8px}.route-error{margin-top:12px;color:#b91c1c}.form-grid{display:grid;grid-template-columns:1fr 140px;gap:12px}.form-grid label{display:grid;gap:6px}.form-grid .wide{grid-column:1/-1}.ai-input{width:100%;box-sizing:border-box;border:1px solid var(--color-border,#dbe2ea);border-radius:8px;padding:9px 11px;background:var(--color-surface,#fff);color:inherit}.trigger-list{display:grid;gap:8px}.trigger-list>div{display:flex;gap:8px}.trigger-list button,.remove-step{border:0;background:transparent;color:#94a3b8;font-size:20px;cursor:pointer}.toggle-line{display:flex;gap:8px;margin:14px 0}.pipeline-list{display:grid;gap:10px}.pipeline-step{display:grid;grid-template-columns:34px 1fr 28px;gap:10px;padding:14px;border:1px solid var(--color-border,#dbe2ea);border-radius:10px}.step-number{width:28px;height:28px;border-radius:50%;display:grid;place-items:center;background:var(--color-primary-soft,#eef2ff);color:var(--color-primary,#4f46e5);font-weight:700}.step-main code{margin-left:8px;color:#94a3b8}.param-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:12px}.param-list label{display:grid;gap:4px;font-size:12px;color:#64748b}.test-bar{display:flex;gap:10px}.test-bar .ai-input{flex:1}.simulation-result{margin-top:18px}.match-result{padding:12px;border-radius:8px;background:#fff7ed;color:#9a3412}.match-result.matched{background:#ecfdf5;color:#047857}.trace-flow{display:grid;gap:10px;margin-top:12px}.trace-stage{padding:14px;border:1px solid var(--color-border,#dbe2ea);border-radius:10px;display:grid;gap:5px}.trace-stage small{color:#94a3b8}.post-preview{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.post-preview a{padding:5px 8px;border-radius:6px;background:var(--color-primary-soft,#eef2ff);color:var(--color-primary,#4f46e5);text-decoration:none;font-size:12px}.sticky-actions{position:fixed;left:var(--ai-sidebar-width,240px);right:0;bottom:0;z-index:20;display:flex;justify-content:space-between;padding:12px 24px;background:var(--color-surface,#fff);border-top:1px solid var(--color-border,#dbe2ea);box-shadow:0 -8px 24px rgba(15,23,42,.06)}h3{font-size:14px;margin:22px 0 10px}@media(max-width:900px){.review-grid{grid-template-columns:1fr}.route-create-head{align-items:flex-start;gap:12px;flex-direction:column}.route-steps{width:100%;justify-content:center}.route-steps i{width:16px}.param-list{grid-template-columns:1fr}.sticky-actions{left:0}.generating-card{flex-direction:column}.test-bar{flex-direction:column}}
</style>

<style scoped>
.route-studio{padding-bottom:42px;font-family:var(--font-sans)}
.primary-button{display:inline-flex;align-items:center;justify-content:center;gap:13px;min-width:132px;height:40px;padding:0 18px;border:1px solid #4e5ae7;border-radius:9px;background:linear-gradient(180deg,#6671f4,#515de6);box-shadow:0 5px 13px rgba(81,93,230,.22);color:#fff;font-family:var(--font-sans);font-size:12px;font-weight:650;letter-spacing:.01em;line-height:1;cursor:pointer;transition:transform .15s,box-shadow .15s,background .15s}
.primary-button:hover:not(:disabled){transform:translateY(-1px);box-shadow:0 8px 17px rgba(81,93,230,.28);background:linear-gradient(180deg,#707af7,#5864ea)}
.primary-button:active:not(:disabled){transform:translateY(0)}
.primary-button:disabled{border-color:#d8dce5;background:#e5e8ee;box-shadow:none;color:#a0a7b3;cursor:not-allowed}
.primary-button span,.primary-button b{font-size:15px;font-weight:500}
.test-command{grid-template-columns:25px minmax(0,1fr) auto}
.test-command input{min-width:0;font-family:var(--font-sans)}
.test-button{height:36px;min-width:104px}
.footer-secondary{height:38px;padding:0 13px;border:1px solid #dfe3e9!important;border-radius:8px!important;background:#fff!important;color:#596273!important;font-family:var(--font-sans)!important;font-size:11px!important;font-weight:600}
.footer-secondary:hover{border-color:#c5cbd5!important;background:#f8f9fb!important}
@media(max-width:760px){.route-studio{padding-bottom:28px}.test-command{grid-template-columns:22px minmax(0,1fr)}.test-command .primary-button{grid-column:1/-1;width:100%}}
</style>

<style scoped>
.route-studio{--ink:#172033;--muted:#6b7485;--line:#e4e8ef;--soft:#f7f8fb;--accent:#5865f2;--accent-soft:#eef0ff;max-width:1240px;margin:0 auto;padding:22px 24px 104px;color:var(--ink)}
.studio-hero{position:relative;overflow:hidden;padding:26px 28px 0;margin-bottom:22px;border:1px solid var(--line);border-radius:18px;background:linear-gradient(135deg,#fff 0%,#f9faff 70%,#f1f3ff 100%);box-shadow:0 8px 30px rgba(24,32,51,.05)}
.studio-hero:after{content:"";position:absolute;width:280px;height:280px;right:-120px;top:-170px;border-radius:50%;background:radial-gradient(circle,rgba(88,101,242,.13),transparent 68%)}
.back-button,.regenerate-button,.footer-secondary{border:0;background:transparent;color:var(--muted);font:inherit;font-size:13px;cursor:pointer}.back-button:hover,.regenerate-button:hover{color:var(--ink)}
.hero-copy{display:flex;align-items:center;gap:16px;margin:25px 0 26px}.hero-mark{display:grid;place-items:center;width:48px;height:48px;border-radius:14px;background:linear-gradient(145deg,#6670f6,#4754e8);color:#fff;font-size:22px;box-shadow:0 10px 24px rgba(88,101,242,.25)}
.eyebrow,.section-kicker{display:block;margin-bottom:7px;color:var(--accent);font-size:10px;font-weight:800;letter-spacing:.15em}.hero-copy h1,.panel-heading h2,.canvas-header h2,.insight-panel h2{margin:0;color:var(--ink);font-weight:720;letter-spacing:-.025em}.hero-copy h1{font-size:25px}.hero-copy p,.panel-heading p,.canvas-header p{margin:7px 0 0;color:var(--muted);font-size:13px;line-height:1.65}
.studio-stepper{display:grid;grid-template-columns:repeat(3,1fr);margin:0 -28px;border-top:1px solid var(--line);background:rgba(255,255,255,.66)}.step-item{display:flex;align-items:center;justify-content:center;gap:9px;padding:14px;color:#9aa2b1;font-size:12px;border-right:1px solid var(--line)}.step-item:last-child{border-right:0}.step-item span{display:grid;place-items:center;width:23px;height:23px;border:1px solid #ccd2dc;border-radius:50%;font-size:11px}.step-item.active{color:var(--accent);background:#fff}.step-item.active span{border-color:var(--accent);background:var(--accent);color:#fff;box-shadow:0 4px 12px rgba(88,101,242,.22)}.step-item.done{color:#168263}.step-item.done span{border-color:#b9e7d8;background:#eaf9f3;color:#168263}
.compose-layout,.review-layout{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(300px,.72fr);gap:18px;align-items:start}.review-layout{grid-template-columns:310px minmax(0,1fr)}.studio-panel{border:1px solid var(--line);border-radius:16px;background:#fff;box-shadow:0 5px 20px rgba(24,32,51,.045)}.compose-panel,.rule-canvas,.test-panel{padding:26px}.panel-heading,.canvas-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:22px}.panel-heading h2,.canvas-header h2,.insight-panel h2{font-size:19px}.safe-badge,.draft-badge{display:inline-flex;align-items:center;height:28px;padding:0 10px;border-radius:999px;border:1px solid #d9dffb;background:#f5f6ff;color:#5661d8;font-size:11px;font-weight:700;white-space:nowrap}.draft-badge{border-color:#e4e8ef;background:#f7f8fb;color:#737c8c}
.prompt-shell{position:relative;border:1px solid #d9dee7;border-radius:14px;background:#fbfcfe;transition:.2s}.prompt-shell:focus-within{border-color:#8e98f8;box-shadow:0 0 0 4px rgba(88,101,242,.09);background:#fff}.prompt-shell.busy{opacity:.72}.prompt-icon{position:absolute;top:17px;left:17px;color:var(--accent)}.prompt-shell textarea{width:100%;box-sizing:border-box;padding:16px 18px 10px 46px;border:0;outline:0;background:transparent;color:var(--ink);font:inherit;font-size:14px;line-height:1.75;resize:vertical}.prompt-shell textarea::placeholder{color:#a2a9b6}.prompt-footer{display:flex;justify-content:space-between;padding:10px 16px;border-top:1px solid #edf0f4;color:#9098a6;font-size:11px}.prompt-footer b{font-weight:600}.primary-action{display:flex;justify-content:flex-end;margin-top:16px}.primary-action :deep(button){min-width:165px;height:40px;border-radius:9px!important}.build-progress{display:flex;align-items:center;gap:12px;margin-top:16px;padding:14px 16px;border:1px solid #dfe3ff;border-radius:10px;background:#f7f8ff}.build-progress strong{font-size:13px}.build-progress p{margin:4px 0 0;color:var(--muted);font-size:11px}.pulse{width:9px;height:9px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 6px rgba(88,101,242,.12);animation:pulse 1.5s infinite}@keyframes pulse{50%{box-shadow:0 0 0 10px rgba(88,101,242,0)}}
.inspiration-panel{padding:18px;border:1px solid var(--line);border-radius:16px;background:linear-gradient(180deg,#fff,#fafbfc)}.aside-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}.aside-title span{font-size:13px;font-weight:700}.aside-title small{color:#9aa2b1;font-size:10px}.example-card{display:grid;grid-template-columns:31px 1fr 16px;gap:10px;align-items:center;width:100%;padding:12px 10px;margin-bottom:8px;border:1px solid transparent;border-radius:10px;background:#f7f8fa;color:var(--ink);text-align:left;cursor:pointer;transition:.16s}.example-card:hover{border-color:#d9dffb;background:#f4f5ff;transform:translateY(-1px)}.example-icon{display:grid;place-items:center;width:29px;height:29px;border-radius:8px;background:#fff;color:var(--accent);box-shadow:0 1px 3px rgba(0,0,0,.06)}.example-card b{display:block;font-size:12px}.example-card small{display:-webkit-box;margin-top:3px;overflow:hidden;color:var(--muted);font-size:10px;line-height:1.4;-webkit-line-clamp:2;-webkit-box-orient:vertical}.example-card i{color:#a5acb9;font-style:normal}.guard-note{display:flex;gap:10px;margin-top:14px;padding:12px;border-top:1px solid var(--line);color:var(--muted)}.guard-note>span{color:#168263}.guard-note p{margin:0;font-size:10px;line-height:1.55}.guard-note b{color:var(--ink);font-size:11px}
.review-sidebar{display:grid;gap:14px;position:sticky;top:16px}.insight-panel,.health-panel{padding:20px}.insight-panel ol{display:grid;gap:13px;margin:17px 0 0;padding:0;list-style:none}.insight-panel li{display:flex;gap:10px}.insight-panel li>span{display:grid;place-items:center;flex:0 0 24px;height:24px;border-radius:7px;background:var(--accent-soft);color:var(--accent);font-size:10px;font-weight:800}.insight-panel li p{margin:2px 0;color:var(--muted);font-size:12px;line-height:1.55}.health-title{display:flex;align-items:center;justify-content:space-between}.health-title h3{margin:0;font-size:14px}.health-title span{padding:4px 8px;border-radius:999px;font-size:10px;font-weight:700}.health-title .good{background:#eaf9f3;color:#168263}.health-title .warn{background:#fff5e7;color:#b66a12}.health-title .danger{background:#fff0f0;color:#c43c3c}.issue-list{display:grid;gap:8px;margin-top:15px}.route-alert{display:flex;gap:9px;padding:10px;border-radius:9px;background:#f2f6ff;color:#4c6796;font-size:11px}.route-alert>span{display:grid;place-items:center;flex:0 0 19px;height:19px;border-radius:50%;background:#dfe9ff;font-weight:800}.route-alert p{margin:1px 0;line-height:1.5}.route-alert.warning{background:#fff7ea;color:#9b611e}.route-alert.warning>span{background:#ffe8be}.route-alert.error{margin-top:12px;background:#fff1f1;color:#a93636}.route-alert.error>span{background:#ffdada}.empty-health{display:flex;align-items:center;gap:11px;margin-top:16px;padding:14px;border-radius:10px;background:#f2faf7}.empty-health>span{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;background:#d9f3e9;color:#168263}.empty-health p{margin:0;color:#236b58;font-size:12px}.empty-health small{color:#6b8f85}.regenerate-button{width:100%;padding:11px;border:1px solid var(--line);border-radius:10px;background:#fff}.regenerate-button:hover{border-color:#cbd1dd;background:#fafbfc}
.rule-canvas{padding:25px 27px}.form-grid{display:grid;grid-template-columns:1fr 120px;gap:12px;padding:18px;border:1px solid var(--line);border-radius:12px;background:#fafbfc}.form-grid label,.param-grid label{display:grid;gap:6px}.form-grid label>span,.param-grid label>span{color:var(--muted);font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.form-grid .wide{grid-column:1/-1}.studio-input{width:100%;box-sizing:border-box;border:1px solid #d9dee7;border-radius:8px;background:#fff;padding:9px 11px;color:var(--ink);font:inherit;font-size:12px;outline:0;transition:.15s}.studio-input:focus{border-color:#8e98f8;box-shadow:0 0 0 3px rgba(88,101,242,.09)}.canvas-section{padding-top:24px;margin-top:24px;border-top:1px solid #edf0f4}.section-title{display:flex;gap:11px;align-items:center;margin-bottom:15px}.section-number{display:grid;place-items:center;width:27px;height:27px;border-radius:8px;background:#f0f2f6;color:#5f6878;font-size:10px;font-weight:800}.section-title h3{margin:0;font-size:14px}.section-title p{margin:3px 0 0;color:var(--muted);font-size:10px}.trigger-editor{display:grid;gap:8px}.trigger-row{display:grid;grid-template-columns:55px 1fr 28px;gap:8px;align-items:center}.regex-tag{padding:7px 6px;border-radius:6px;background:#f0f2f6;color:#747d8b;font:700 9px ui-monospace,SFMono-Regular,monospace;text-align:center}.trigger-row button,.node-header button{border:0;background:transparent;color:#a1a8b4;font-size:18px;cursor:pointer}.add-link{justify-self:start;border:0;background:transparent;color:var(--accent);font:600 11px inherit;cursor:pointer}.fallback-toggle{display:flex;align-items:center;gap:10px;margin:15px 0 10px;cursor:pointer}.fallback-toggle>input{display:none}.fallback-toggle>span{position:relative;width:34px;height:19px;border-radius:999px;background:#d8dde5;transition:.2s}.fallback-toggle>span:after{content:"";position:absolute;top:3px;left:3px;width:13px;height:13px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.15);transition:.2s}.fallback-toggle>input:checked+span{background:var(--accent)}.fallback-toggle>input:checked+span:after{transform:translateX(15px)}.fallback-toggle p{margin:0;font-size:11px}.fallback-toggle p b,.fallback-toggle p small{display:block}.fallback-toggle p small{margin-top:2px;color:var(--muted)}
.pipeline-track{display:grid}.pipeline-node{display:grid;grid-template-columns:38px 1fr;gap:8px}.node-rail{display:flex;flex-direction:column;align-items:center}.node-rail>span{display:grid;place-items:center;width:25px;height:25px;border-radius:8px;background:#222b3d;color:#fff;font-size:10px;font-weight:800}.node-rail i{width:1px;flex:1;min-height:18px;background:linear-gradient(#bbc2ce,#e7eaf0)}.node-card{margin-bottom:10px;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 2px 7px rgba(24,32,51,.035);overflow:hidden}.node-header{display:grid;grid-template-columns:32px 1fr 25px;gap:10px;align-items:center;padding:12px 13px;background:#fafbfc}.node-icon{display:grid;place-items:center;width:30px;height:30px;border-radius:8px;background:var(--accent-soft);color:var(--accent);font-size:15px}.node-header strong{display:block;font-size:12px}.node-header code{display:block;margin-top:2px;color:#929aa8;font-size:9px}.param-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;padding:12px 13px;border-top:1px solid #edf0f4}
.test-layout{max-width:920px;margin:0 auto}.test-panel{min-height:510px}.test-command{display:grid;grid-template-columns:25px 1fr auto;align-items:center;gap:8px;padding:8px;border:1px solid #d9dee7;border-radius:12px;background:#fafbfc}.test-command>span{color:var(--accent);font-size:22px;text-align:center}.test-command input{border:0;outline:0;background:transparent;color:var(--ink);font:inherit;font-size:13px}.match-banner{display:flex;gap:12px;margin-top:20px;padding:15px;border-radius:11px;background:#fff7ea;color:#95601e}.match-banner.matched{background:#edf9f5;color:#167259}.match-banner>span{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:rgba(255,255,255,.7);font-weight:800}.match-banner strong{font-size:12px}.match-banner p{margin:4px 0 0;font-size:10px}.trace-timeline{margin-top:18px}.trace-timeline article{display:grid;grid-template-columns:35px 1fr;gap:9px}.trace-dot{display:grid;place-items:center;align-self:start;width:25px;height:25px;border-radius:8px;background:#222b3d;color:#fff;font-size:10px}.trace-card{position:relative;margin-bottom:11px;padding:13px 14px;border:1px solid var(--line);border-radius:10px}.trace-card header{display:flex;justify-content:space-between}.trace-card strong{font-size:12px}.trace-card small{color:#99a1ae;font-size:9px}.trace-card p{margin:5px 0;color:var(--muted);font-size:11px}.result-posts{display:flex;flex-wrap:wrap;gap:6px;margin-top:9px}.result-posts a{padding:5px 8px;border-radius:6px;background:#f2f4f8;color:#536077;text-decoration:none;font-size:10px}.result-posts a span{margin-right:4px;color:var(--accent)}.test-empty{display:grid;place-items:center;padding:90px 20px;color:var(--muted);text-align:center}.test-empty>div{display:grid;place-items:center;width:52px;height:52px;border-radius:15px;background:#f1f3f7;color:#8b94a2;font-size:23px}.test-empty h3{margin:14px 0 5px;color:var(--ink);font-size:14px}.test-empty p{max-width:380px;margin:0;font-size:11px;line-height:1.6}
.inline-actions{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:26px;padding-top:18px;border-top:1px solid #edf0f4}.inline-actions>div{display:flex;align-items:center;justify-content:flex-end;gap:14px;min-width:0}.inline-actions>div>span{color:var(--muted);font-size:10px;line-height:1.5;text-align:right}.inline-actions .primary-button{flex:0 0 auto;min-width:124px}
@media(max-width:1400px){.review-layout{grid-template-columns:1fr}.review-sidebar{position:static;grid-template-columns:1fr 1fr}.review-sidebar .regenerate-button{grid-column:1/-1}}
@media(max-width:1050px){.compose-layout,.review-layout{grid-template-columns:1fr}.review-sidebar{position:static;grid-template-columns:1fr 1fr}.regenerate-button{grid-column:1/-1}.inspiration-panel{display:grid;grid-template-columns:1fr 1fr;gap:8px}.aside-title,.guard-note{grid-column:1/-1}.example-card{margin:0}}
@media(max-width:760px){.route-studio{padding:12px 12px 28px}.studio-hero{padding:18px 18px 0}.hero-copy{align-items:flex-start}.hero-copy h1{font-size:20px}.studio-stepper{margin:0 -18px}.step-item b{display:none}.compose-panel,.rule-canvas,.test-panel{padding:18px}.inspiration-panel,.review-sidebar{grid-template-columns:1fr}.aside-title,.guard-note,.regenerate-button{grid-column:auto}.form-grid,.param-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}.panel-heading,.canvas-header{align-items:flex-start}.test-command{grid-template-columns:22px minmax(0,1fr)}.test-command .primary-button{grid-column:1/-1;width:100%}.inline-actions{align-items:stretch;flex-direction:column-reverse}.inline-actions>div{align-items:stretch;flex-direction:column}.inline-actions>div>span{text-align:left}.inline-actions .primary-button,.inline-actions .footer-secondary{width:100%}}
</style>
