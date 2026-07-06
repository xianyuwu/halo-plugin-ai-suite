import { readFile, writeFile } from "node:fs/promises";

const bundlePath = new URL(
  "../../src/main/resources/static/js/markmap-bundle.min.js",
  import.meta.url,
);

let bundle = await readFile(bundlePath, "utf8");

// markmap-common exports an unused generic npm URL builder. It is retained by the browser bundle
// even though LocalTransformer has no asset plugins. Neutralize its remote providers so no future
// code path can turn an npm package name into a third-party network request.
bundle = bundle
  .replaceAll("https://cdn.jsdelivr.net/npm/", "/plugins/ai-suite/assets/res/vendor/")
  .replaceAll("https://unpkg.com/", "/plugins/ai-suite/assets/res/vendor/");

const forbidden = [
  "cdn.jsdelivr.net",
  "unpkg.com",
  "@highlightjs/cdn-assets",
  "webfontloader",
];
const found = forbidden.filter((value) => bundle.includes(value));
if (found.length > 0) {
  throw new Error(`Markmap bundle contains remote asset loaders: ${found.join(", ")}`);
}

await writeFile(bundlePath, bundle);
