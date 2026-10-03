import { viteConfig } from "@halo-dev/ui-plugin-bundler-kit";
import Icons from "unplugin-icons/vite";

export default viteConfig({
  vite: {
    plugins: [Icons({ compiler: "vue3", autoInstall: false })],
    build: { outDir: "../src/main/resources/console", emptyOutDir: true },
  },
});
