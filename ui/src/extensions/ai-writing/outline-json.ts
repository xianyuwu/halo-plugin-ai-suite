/**
 * 大纲 JSON 解析器 — 替代 outline-markdown.ts
 *
 * <p>LLM 输出 JSON 而非 markdown，前端解析后用代码渲染 HTML。
 * 格式由代码保证，不再依赖 LLM 的 markdown 格式化能力。
 */

export interface OutlineSection {
  heading: string;
  summary: string;
  /** 子章节（depth >= 2 时存在）— 递归结构，最深到 3 层 */
  children?: OutlineSection[];
}

export interface OutlineData {
  title: string;
  /** 嵌套深度 1-3：1=只有顶层 sections；2=顶层+children；3=顶层+children+children */
  depth: number;
  summary: string;
  sections: OutlineSection[];
}

/** 从 LLM 原始输出中提取 JSON（容错处理） */
export function parseOutlineJson(raw: string): OutlineData | null {
  if (!raw || !raw.trim()) return null;

  let text = raw.trim();

  // 去掉 markdown 代码块包裹（LLM 有时会在 JSON 外加 ```json ... ```）
  const codeBlockMatch = text.match(/```(?:json)?\s*\n?([\s\S]*?)\n?\s*```/);
  if (codeBlockMatch) {
    text = codeBlockMatch[1].trim();
  }

  // 第一次尝试：直接 parse
  try {
    const data = JSON.parse(text);
    if (isValidOutline(data)) return normalize(data);
  } catch {
    // 继续
  }

  // 第二次尝试：提取第一个 { 到最后一个 } 之间的内容
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start >= 0 && end > start) {
    try {
      const data = JSON.parse(text.substring(start, end + 1));
      if (isValidOutline(data)) return normalize(data);
    } catch {
      // 继续
    }
  }

  return null;
}

/**
 * 渲染时 h 标签基准：顶层 h1，深度 2 → h2，深度 3 → h3
 *
 * <p>主标题（title）不渲染为 heading：用户点"插入到编辑器"时主标题会和
 * Halo 文章页的 h1 重复，所以主标题用普通 div 包装（带 .ai-outline-master-title
 * class，靠 OutlineModal 的 CSS 模拟 h1 视觉）。
 */
const HEADING_BASE_LEVEL = 1;

/** 递归校验 OutlineSection（children 是可选的，递归调用自己） */
function isValidSection(s: unknown): s is OutlineSection {
  if (!s || typeof s !== "object") return false;
  const sec = s as Record<string, unknown>;
  if (typeof sec.heading !== "string" || !sec.heading.trim()) return false;
  if (typeof sec.summary !== "string") return false;
  if (sec.children !== undefined) {
    if (!Array.isArray(sec.children)) return false;
    return sec.children.every(isValidSection);
  }
  return true;
}

function isValidOutline(data: unknown): data is OutlineData {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;
  if (typeof d.title !== "string" || !d.title.trim()) return false;
  if (!Array.isArray(d.sections) || d.sections.length === 0) return false;
  // depth 可选，缺省/不合法回落 1（与后端默认一致）
  if (d.depth !== undefined && (typeof d.depth !== "number"
      || d.depth < 1 || d.depth > 3 || !Number.isInteger(d.depth))) {
    return false;
  }
  return d.sections.every(isValidSection);
}

/** 兜底：老 LLM 输出无 depth 字段时注入默认 1（与后端 AIProperties 默认一致） */
function normalize(data: OutlineData): OutlineData {
  if (typeof data.depth !== "number" || data.depth < 1 || data.depth > 3) {
    return { ...data, depth: 1 };
  }
  return data;
}

/** OutlineData → 预览 HTML（与 .ai-outline-content CSS 配合） */
export function outlineToHtml(data: OutlineData): string {
  const parts: string[] = [];

  // 嵌套深度越界回落 1（与后端 AIProperties.parseWritingConfig 一致）
  const depth = data.depth >= 1 && data.depth <= 3 && Number.isInteger(data.depth)
    ? data.depth : 1;

  // 主标题不渲染 heading（用普通 div 包装，靠 .ai-outline-master-title 样式
  // 模拟 h1 视觉；避免插入到 Halo 文章时和文章页自身 h1 冲突）
  if (data.title?.trim()) {
    parts.push(`<div class="ai-outline-master-title">${escapeHtml(data.title)}</div>`);
  }

  // 总体摘要
  if (data.summary?.trim()) {
    parts.push(`<p>${escapeHtml(data.summary)}</p>`);
  }

  // 递归渲染 sections：每深一级 h 标签 +1（顶层 h1，深度 2 → h2，深度 3 → h3）
  parts.push(...renderSections(data.sections, 0, depth));

  return parts.join("\n");
}

/**
 * 递归渲染章节列表。
 * @param sections 当前层级 sections
 * @param level 当前深度（0 = 顶层，对应 h2）
 * @param maxDepth 总深度上限（depth 字段）
 */
function renderSections(
  sections: OutlineSection[],
  level: number,
  maxDepth: number
): string[] {
  const parts: string[] = [];
  // 超出 maxDepth 就不再下钻（防 LLM 偶然输出深度 > 配置时无限渲染）
  if (level >= maxDepth) return parts;

  for (const sec of sections) {
    const headingLevel = HEADING_BASE_LEVEL + level;
    parts.push(`<h${headingLevel}>${escapeHtml(sec.heading)}</h${headingLevel}>`);
    if (sec.summary?.trim()) {
      parts.push(`<p>${escapeHtml(sec.summary)}</p>`);
    }
    if (sec.children && sec.children.length > 0) {
      parts.push(...renderSections(sec.children, level + 1, maxDepth));
    }
  }
  return parts;
}

/** 统计已出现的章节数量（流式阶段显示进度） */
export function countStreamingSections(raw: string): number {
  const matches = raw.match(/"heading"\s*:/g);
  return matches ? matches.length : 0;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
