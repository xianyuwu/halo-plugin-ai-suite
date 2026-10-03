// Selection uses original RGBA pixels, never the visible checkerboard/background.
export function selectSimilarPixels(data, width, height, selection, tolerance) {
  const selected = new Uint8Array(width * height);
  const sx = Math.min(width - 1, Math.max(0, Math.floor(selection.x * width)));
  const sy = Math.min(height - 1, Math.max(0, Math.floor(selection.y * height)));
  const seed = sy * width + sx;
  if (data[seed * 4 + 3] <= 8) return selected;
  const bounds = selection.bounds || [0, 0, 1, 1];
  const left = Math.max(0, Math.floor(bounds[0] * width));
  const top = Math.max(0, Math.floor(bounds[1] * height));
  const right = Math.min(width - 1, Math.floor(bounds[2] * width));
  const bottom = Math.min(height - 1, Math.floor(bounds[3] * height));
  const threshold = Math.max(0, Math.min(100, tolerance)) * 2.55;
  const seen = new Uint8Array(width * height);
  const queue = new Int32Array(width * height);
  let head = 0, tail = 0;
  const visit = index => {
    if (seen[index]) return;
    seen[index] = 1;
    const p = index * 4;
    if (data[p + 3] <= 8 || Math.max(Math.abs(data[p] - data[seed * 4]),
      Math.abs(data[p + 1] - data[seed * 4 + 1]), Math.abs(data[p + 2] - data[seed * 4 + 2])) > threshold) return;
    selected[index] = 1;
    queue[tail++] = index;
  };
  visit(seed);
  while (head < tail) {
    const i = queue[head++], x = i % width, y = Math.floor(i / width);
    if (x > left) visit(i - 1);
    if (x < right) visit(i + 1);
    if (y > top) visit(i - width);
    if (y < bottom) visit(i + width);
  }
  return selected;
}

export function selectionRuns(mask) {
  const runs = [];
  for (let i = 0; i < mask.length;) {
    if (!mask[i]) { i++; continue; }
    const start = i;
    while (i < mask.length && mask[i]) i++;
    runs.push([start, i - start]);
  }
  return runs;
}
