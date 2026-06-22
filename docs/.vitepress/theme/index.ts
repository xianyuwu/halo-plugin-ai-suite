import DefaultTheme from "vitepress/theme";
import { h } from "vue";
import VersionBanner from "./VersionBanner.vue";
import "./custom.css";

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    "doc-before": () => h(VersionBanner),
  }),
};
