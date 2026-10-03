import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
const dir = fileURLToPath(new URL("../../src/main/resources/console/", import.meta.url));
const sentinel = process.env.AI_SUITE_BUILD_SENTINEL;
assert.ok(sentinel, "Provide a harmless build sentinel to verify environment isolation");
const manifestPath = path.join(dir, "ui-plugin.json");
const entry = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, "utf8")).entry : "main.js";
assert.ok(entry && fs.existsSync(path.resolve(dir, entry)), "Built Console entry is missing");
function scripts(folder) {
  return fs.readdirSync(folder, { withFileTypes: true }).flatMap(item => {
    const file = path.join(folder, item.name);
    return item.isDirectory() ? scripts(file) : item.name.endsWith(".js") ? [file] : [];
  });
}
const files = scripts(dir);
assert.ok(files.length, "No browser scripts were built");
for (const file of files) assert.ok(!fs.readFileSync(file, "utf8").includes(sentinel), `Build environment sentinel leaked into ${path.relative(dir, file)}`);
console.log(`PASS: build environment sentinel is absent from ${files.length} browser scripts.`);
