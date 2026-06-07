/**
 * Markdown → HTML 渲染器，基于 marked 库。
 *
 * <p>不再手写正则解析，只用一个小预处理来兜底 LLM 输出的不规范格式：
 * <ul>
 *   <li>{@code #标题}（井号和文字间缺空格）→ 补空格</li>
 *   <li>{@code ##标题} / {@code ###标题} 出现在行中间 → 前面补换行</li>
 * </ul>
 * 其余全部交给 marked（遵循 CommonMark 标准）处理。
 */

import { marked } from "marked";

/**
 * LLM 输出常见不规范 → 预处理后再交给 marked。
 * 顺序敏感：先拆行，再补空格。因为拆行后 ## 才到行首，补空格的正则（带 ^）才能命中。
 */
function preprocess(md: string): string {
  return md
    // 1. 行内 ## / ### 前补换行
    .replace(/([^\n#])(#{2,3})/g, "$1\n$2")
    // 2. 行首 #text / ##text / ###text → 补空格（后续步骤依赖标准格式）
    .replace(/^(#{1,3})([^\s#])/gm, "$1 $2")
    // 3. 标题行用中文冒号 "：" 分隔标题与提要 → 拆成标题 + 空行 + 段落
    .replace(/^(#{1,3}\s+[^：\n]+)：(.+)$/gm, "$1\n\n$2");
}

export function markdownToHtml(md: string): string {
  if (!md) return "";
  const preprocessed = preprocess(md);
  try {
    const html = marked.parse(preprocessed);
    return typeof html === "string" ? html : md;
  } catch (e) {
    console.error("[outline] marked error:", e);
    return md.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
}
