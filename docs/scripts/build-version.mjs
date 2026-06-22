import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const properties = readFileSync(new URL("../../gradle.properties", import.meta.url), "utf8");
const version = properties.match(/^version=(.+)$/m)?.[1]?.trim();

if (!version) {
  throw new Error("Unable to read project version from gradle.properties");
}

const versionsUrl = new URL("../versions.json", import.meta.url);
const publishedVersions = JSON.parse(readFileSync(versionsUrl, "utf8"));

if (!publishedVersions.includes(version)) {
  publishedVersions.unshift(version);
  writeFileSync(versionsUrl, `${JSON.stringify(publishedVersions, null, 2)}\n`);
}

const result = spawnSync("pnpm", ["exec", "vitepress", "build", "."], {
  cwd: new URL("..", import.meta.url),
  env: {
    ...process.env,
    DOCS_BASE: `/versions/${version}/`,
    DOCS_HISTORICAL_VERSION: version,
  },
  stdio: "inherit",
});

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

console.log(`Built documentation snapshot ${version} at /versions/${version}/`);
