import { computed, ref } from "vue";

const CONFIG_API = "/apis/console.api.ai-suite.halo.run/v1alpha1/config";
const FOUNDATION_API = "/apis/console.api.aifoundation.halo.run/v1alpha1";

interface ModelOption {
  name: string;
  modelId?: string;
  displayName?: string;
}

export function useContentModel() {
  const chatModel = ref("");
  const contentModel = ref("");
  const excerptModel = ref("");
  const tagModel = ref("");
  const foundationDefault = ref("");
  const options = ref<ModelOption[]>([]);
  const loaded = ref(false);
  const optionsAvailable = ref(false);

  async function load() {
    loaded.value = false;
    optionsAvailable.value = false;
    try {
      const [configResult, slotsResult, optionsResult] = await Promise.allSettled([
        fetch(CONFIG_API),
        fetch(`${FOUNDATION_API}/default-model-slots`),
        fetch(`${FOUNDATION_API}/model-options?modelType=language&available=true&enabled=true`),
      ]);
      if (configResult.status !== "fulfilled") throw new Error("配置接口不可用");
      const configResponse = configResult.value;
      if (!configResponse.ok) throw new Error(`配置接口返回 HTTP ${configResponse.status}`);
      const config = await configResponse.json();
      chatModel.value = config.models?.aiFoundationChatModelName || "";
      contentModel.value = config.models?.aiFoundationContentModelName || "";
      excerptModel.value = config.models?.aiFoundationExcerptModelName || "";
      tagModel.value = config.models?.aiFoundationTagModelName || "";
      foundationDefault.value = "";
      if (slotsResult.status === "fulfilled" && slotsResult.value.ok) {
        try {
          const slots = await slotsResult.value.json();
          foundationDefault.value = slots.languageModelName || "";
        } catch { /* The configured model is still available above. */ }
      }
      options.value = [];
      if (optionsResult.status === "fulfilled" && optionsResult.value.ok) {
        try {
          options.value = await optionsResult.value.json();
          optionsAvailable.value = true;
        } catch { /* Keep model selection disabled until the list can be read. */ }
      }
      loaded.value = true;
    } catch {
      loaded.value = false;
    }
  }

  function displayName(name: string) {
    const option = options.value.find(item => item.name === name);
    return option?.displayName || option?.modelId || name;
  }

  const defaultName = computed(() => chatModel.value || foundationDefault.value);
  const resolvedName = computed(() => contentModel.value || defaultName.value);
  const contentModelDescription = computed(() => {
    if (!loaded.value) return "当前模型暂不可读取";
    const name = resolvedName.value;
    if (!name) return "当前使用：AI Foundation 默认语言模型（模型名称暂不可读取）";
    const source = contentModel.value ? "内容创作默认模型"
      : chatModel.value ? "复用对话模型" : "复用 AI Foundation 默认对话模型";
    return `当前使用：${displayName(name)}（${source}）`;
  });

  function overrideDescription(override: string, feature: string) {
    if (!loaded.value) return "当前模型暂不可读取";
    return override ? `当前使用：${displayName(override)}（${feature}单独指定）`
      : contentModelDescription.value;
  }

  const excerptModelDescription = computed(() => overrideDescription(excerptModel.value, "AI 摘要"));
  const tagModelDescription = computed(() => overrideDescription(tagModel.value, "AI 标签"));

  async function saveOverride(feature: "excerpt" | "tag", value: string) {
    const field = feature === "excerpt" ? "aiFoundationExcerptModelName" : "aiFoundationTagModelName";
    const current = await fetch(CONFIG_API);
    if (!current.ok || !(current.headers.get("content-type") || "").includes("application/json")) {
      throw new Error("配置读取失败，请刷新页面后重试");
    }
    const config = await current.json();
    config.models = { ...config.models, [field]: value };
    const response = await fetch(`${CONFIG_API}/save`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ models: config.models }),
    });
    if (!response.ok || !(response.headers.get("content-type") || "").includes("application/json")) {
      throw new Error("保存失败，请检查登录状态后重试");
    }
    const result = await response.json();
    if (!result.saved) throw new Error(result.error || "保存失败");
    if (feature === "excerpt") excerptModel.value = value;
    else tagModel.value = value;
  }

  function writingModelDescription(override: string) {
    if (override) return `当前使用：${displayName(override)}（写作辅助单独指定）`;
    return contentModelDescription.value;
  }

  return {
    load, contentModelDescription, excerptModelDescription, tagModelDescription,
    writingModelDescription, excerptModel, tagModel, options, optionsAvailable,
    displayName, saveOverride,
  };
}
