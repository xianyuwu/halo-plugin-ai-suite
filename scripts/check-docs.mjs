import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];
const read = path => readFileSync(path, "utf8");

function walk(dir, suffix) {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path, suffix) : path.endsWith(suffix) ? [path] : [];
  });
}

const markdown = [join(root, "README.md"), ...walk(join(root, "docs"), ".md")]
  .filter(path => !path.endsWith("ui-analysis-and-optimization.md"));

for (const file of markdown) {
  const text = read(file);
  const label = relative(root, file);
  if (text.includes("```mermaid")) failures.push(`${label}: Mermaid is not allowed as final artwork`);
  if ((text.match(/```/g) || []).length % 2) failures.push(`${label}: unclosed code fence`);

  for (const match of text.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g)) {
    const target = match[1];
    if (/^(https?:|mailto:|#)/.test(target)) continue;
    const local = target.split("#", 1)[0];
    if (local && !statExists(resolve(dirname(file), local))) {
      failures.push(`${label}: missing link target ${target}`);
    }
  }

  for (const match of text.matchAll(/(^|[^\[])!\[[^\]]*\]\(([^)]+\.svg)\)/gm)) {
    failures.push(`${label}: SVG should be clickable for zoom: ${match[2]}`);
  }
}

function statExists(path) {
  try { statSync(path); return true; } catch { return false; }
}

const version = read(join(root, "gradle.properties")).match(/^version=(.+)$/m)?.[1]?.trim();
const pluginVersion = read(join(root, "src/main/resources/plugin.yaml")).match(/^\s*version:\s*["']?([^"'\s]+)["']?/m)?.[1];
const uiVersion = JSON.parse(read(join(root, "ui/package.json"))).version;
if (!version) failures.push("gradle.properties: version missing");
if (pluginVersion !== version) failures.push(`plugin.yaml version ${pluginVersion} != ${version}`);
if (uiVersion !== version) failures.push(`ui/package.json version ${uiVersion} != ${version}`);
if (!read(join(root, "README.md")).includes(`version-${version}-`)) {
  failures.push(`README badge does not use version ${version}`);
}

for (const file of markdown) {
  for (const found of read(file).matchAll(/\b0\.2\.\d+(?:-SNAPSHOT)?\b/g)) {
    if (found[0] !== version) failures.push(`${relative(root, file)}: stale version ${found[0]}`);
  }
}

const svgDir = join(root, "docs/diagrams/exported");
for (const file of walk(svgDir, ".svg")) {
  const text = read(file);
  const label = relative(root, file);
  if (!text.startsWith("<svg ")) failures.push(`${label}: invalid SVG root`);
  if (!text.includes('role="img"')) failures.push(`${label}: missing role=img`);
  if (!text.includes("<title ")) failures.push(`${label}: missing title`);
  if (!text.includes("<desc ")) failures.push(`${label}: missing description`);
  if (!text.trimEnd().endsWith("</svg>")) failures.push(`${label}: SVG is not closed`);
}

const temp = mkdtempSync(join(tmpdir(), "ai-suite-docs-"));
try {
  execFileSync(process.execPath, [join(root, "docs/diagrams/source/render-diagrams.mjs"), temp], {
    cwd: root, stdio: "pipe"
  });
  const expected = readdirSync(svgDir).filter(name => name.endsWith(".svg")).sort();
  const generated = readdirSync(temp).filter(name => name.endsWith(".svg")).sort();
  if (expected.join("\n") !== generated.join("\n")) failures.push("exported SVG file list is stale");
  for (const name of expected) {
    if (statExists(join(temp, name)) && read(join(svgDir, name)) !== read(join(temp, name))) {
      failures.push(`docs/diagrams/exported/${name}: generated output is stale`);
    }
  }
} finally {
  rmSync(temp, { recursive: true, force: true });
}

const apiDocs = walk(join(root, "docs/api"), ".md").map(read).join("\n");
for (const file of walk(join(root, "src/main/java/run/halo/ai/suite/endpoint"), "Endpoint.java")) {
  for (const match of read(file).matchAll(/\.(?:GET|POST|PUT|DELETE)\("([^"]+)"/g)) {
    if (!apiDocs.includes(match[1])) failures.push(`${basename(file)}: undocumented route ${match[1]}`);
  }
}

if (failures.length) {
  console.error(`Documentation checks failed (${failures.length}):`);
  failures.forEach(item => console.error(`- ${item}`));
  process.exit(1);
}

console.log(`Documentation checks passed: ${markdown.length} Markdown files, ${walk(svgDir, ".svg").length} SVG diagrams.`);
