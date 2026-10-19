import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const properties = readFileSync(new URL("../../gradle.properties", import.meta.url), "utf8");
const version = properties.match(/^version=(.+)$/m)?.[1]?.trim();

if (!version) {
  throw new Error("Unable to read project version from gradle.properties");
}

const versionsUrl = new URL("../versions.json", import.meta.url);
const publishedVersions = JSON.parse(readFileSync(versionsUrl, "utf8"));
const versionSeries = version.replace(/\.\d+(?:[-+].*)?$/, ".x");
const existingSeriesVersion = publishedVersions.find(
  published => published.replace(/\.\d+(?:[-+].*)?$/, ".x") === versionSeries,
);

if (existingSeriesVersion && !publishedVersions.includes(version)) {
  throw new Error(
    `${versionSeries} already has a published documentation snapshot (${existingSeriesVersion}); build the root site for patch releases instead.`,
  );
}

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
