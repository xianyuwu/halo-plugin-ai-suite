/**
 * 数字格式化工具 — 1234 → "1,234", 842600 → "842.6K", 1234567 → "1.2M"
 */
export function formatNum(n: number | string): string {
  const num = typeof n === "string" ? parseFloat(n) : n;
  if (!isFinite(num)) return String(n);
  if (Math.abs(num) < 1000) return num.toLocaleString();
  if (Math.abs(num) < 1_000_000) {
    const v = num / 1000;
    return (v % 1 === 0 ? v.toFixed(0) : v.toFixed(1)) + "K";
  }
  if (Math.abs(num) < 1_000_000_000) {
    const v = num / 1_000_000;
    return (v % 1 === 0 ? v.toFixed(0) : v.toFixed(1)) + "M";
  }
  return (num / 1_000_000_000).toFixed(1) + "B";
}

/** 百分比 — 33.33 → "33.3%" */
export function formatPct(n: number, digits = 1): string {
  if (!isFinite(n)) return "0%";
  return n.toFixed(digits) + "%";
}

/**
 * 算今日 vs 昨日的变化百分比
 *   today=12, yesterday=10 → { value: 20, direction: "up" }
 *   today=8,  yesterday=10 → { value: -20, direction: "down" }
 *   today=0,  yesterday=0  → { value: 0,  direction: "flat" }
 */
export function computeDelta(today: number, yesterday: number): {
  value: number;
  direction: "up" | "down" | "flat";
} {
  if (yesterday === 0 && today === 0) return { value: 0, direction: "flat" };
  if (yesterday === 0) return { value: 100, direction: "up" };  // 从 0 起步算 +∞
  const pct = Math.round(((today - yesterday) * 1000) / yesterday) / 10;
  if (pct > 0) return { value: pct, direction: "up" };
  if (pct < 0) return { value: Math.abs(pct), direction: "down" };
  return { value: 0, direction: "flat" };
}
