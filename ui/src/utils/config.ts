/**
 * 通用配置读写工具函数
 * 所有配置页面共用，避免重复代码
 */
const API_BASE = "/apis/console.api.ai-assistant.halo.run/v1alpha1/config";

/**
 * 从后端加载指定 group 的配置，填充到 reactive form
 */
export async function loadGroup(group: string, form: Record<string, any>) {
  try {
    const resp = await fetch(API_BASE);
    if (resp.ok) {
      const data = await resp.json();
      const g = data[group] || {};
      Object.keys(form).forEach((k) => {
        if (g[k] !== undefined) form[k] = g[k];
      });
    }
  } catch {}
}

/**
 * 将 reactive form 保存到后端指定 group
 */
export async function saveGroup(
  group: string,
  form: Record<string, any>,
  saving: { value: boolean },
  saveMsg: { value: string },
  saveOk: { value: boolean }
) {
  saving.value = true;
  saveMsg.value = "";
  try {
    // 先读取全量配置，再更新指定 group
    let allConfig: Record<string, any> = {};
    try {
      const resp = await fetch(API_BASE);
      if (resp.ok) allConfig = await resp.json();
    } catch {}

    allConfig[group] = { ...form };

    const resp = await fetch(API_BASE + "/save", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(allConfig),
    });
    const data = await resp.json();
    saveOk.value = data.saved;
    saveMsg.value = data.saved ? "保存成功" : "保存失败: " + (data.error || "");
  } catch (e: any) {
    saveOk.value = false;
    saveMsg.value = "保存失败: " + e.message;
  } finally {
    saving.value = false;
  }
}
