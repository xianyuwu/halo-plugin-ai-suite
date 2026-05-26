import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { HaloUIPluginBundlerKit } from "@halo-dev/ui-plugin-bundler-kit";
import Icons from "unplugin-icons/vite";

export default defineConfig({
  plugins: [
    vue(),
    HaloUIPluginBundlerKit(),
    Icons({
      compiler: "vue3",
      autoInstall: true,
    }),
  ],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    lib: {
      entry: "src/index.ts",
      formats: ["iife"],
      fileName: () => "main.js",
      cssFileName: "style",
    },
  },
});
