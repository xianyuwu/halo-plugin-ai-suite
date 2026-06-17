/**
 * 统一的 HTML / Markdown 净化工具.
 *
 * <p>历史: 项目曾有三套并存的 sanitize 实现 —— ChatPreview 用 window 全局 DOMPurify
 * (widget 端), DebugTrace 用自研 sanitizeHtml(白名单 DOM 清理), SearchView 用
 * escapeHtml + 正则插标签. 自研实现靠正则守门, 非纵深防御, 易在回归中漏过 XSS.
 * 现统一用 DOMPurify(工业级), Console 端走 import 打包.
 *
 * <p>说明: 只渲染受信图标 SVG 的场景(如 MetricCard/ChatView 的 icon)无需走本工具,
 * 因为其内容来自代码常量而非用户/AI 输出. 本工具专门处理"后端/AI 返回的文本".
 */
import DOMPurify from "dompurify";
import { marked } from "marked";

// 全局 hook: 所有 <a> 强制新窗口打开 + noopener, 防止反向 tabnabbing.
// 一次性注册, 对所有 sanitizeHtml 调用生效.
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

/**
 * 净化任意 HTML, 允许常见排版标签但剥离 script/事件处理器/javascript: 协议.
 * 用于渲染后端 snippet、AI 原始输出等.
 */
export function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      "a", "b", "i", "em", "strong", "p", "br", "hr",
      "ul", "ol", "li", "blockquote", "code", "pre",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "mark", "sup", "sub", "span", "div",
      "img", "table", "thead", "tbody", "tr", "th", "td",
    ],
    ALLOWED_ATTR: ["href", "title", "target", "rel", "src", "alt", "class"],
    // 阻止所有 on* 事件属性与 javascript: 协议(DOMPurify 默认已禁, 显式声明更清晰)
    FORBID_ATTR: ["style", "onerror", "onload", "onclick", "onmouseover"],
  });
}

/**
 * 渲染 Markdown 为 HTML 并净化. 用于 AI 输出(markdown 格式)→ 安全 HTML.
 */
export function renderMarkdown(markdown: string): string {
  const raw = marked.parse(markdown, { async: false }) as string;
  return sanitizeHtml(raw);
}

/**
 * 高亮关键词: 先 escape HTML, 再用 &lt;mark&gt; 包裹命中词, 最后经 DOMPurify 净化.
 * 用于搜索结果 snippet —— snippet 来自后端, 可能含用户搜索词, 需防 XSS 同时支持高亮.
 *
 * @param text 原始文本(未转义)
 * @param keywords 需高亮的关键词列表
 */
export function highlightAndSanitize(text: string, keywords: string[]): string {
  if (!text) return "";
  // 先转义, 确保原始文本里的 < > & 不被当 HTML 解释
  const escaped = text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  let result = escaped;
  // 再包裹关键词(转义后的文本里匹配关键词的原文形式)
  for (const kw of keywords) {
    if (!kw) continue;
    const escapedKw = kw.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    result = result.replace(new RegExp(`(${escapedKw})`, "gi"), "<mark>$1</mark>");
  }
  // 最后净化(只放行 mark 等安全标签, 剥离任何意外注入)
  return sanitizeHtml(result);
}
