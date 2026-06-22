# 文档图表维护

文档中的逻辑图和流程图使用代码化矢量方案维护：图表定义与统一渲染器位于 `source/render-diagrams.mjs`，生成的独立 SVG 位于 `exported/`。Markdown 只引用 SVG，不依赖阅读平台的 Mermaid 支持。

## 重新生成

```bash
node docs/diagrams/source/render-diagrams.mjs
```

生成后应确认：

```bash
find docs/diagrams/exported -name '*.svg' -print0 \
  | xargs -0 -n1 sh -c 'xmllint --noout "$0"'
```

## 视觉规范

| 语义 | 颜色 |
| --- | --- |
| 请求、数据主路径 | 蓝色 |
| AI、意图和模型处理 | 紫色 |
| 成功、输出、持久化完成 | 绿色 |
| 可选能力、风险和降级 | 橙色 |
| 错误或失败状态 | 红色 |
| 中性存储和辅助节点 | 灰蓝色 |

所有图使用统一的深色渐变画布、36px 网格、圆角卡片、柔和阴影和语义箭头。颜色之外必须同时使用标题、标签或形状表达含义。

## 新增图表

1. 在 `render-diagrams.mjs` 中增加图表定义。
2. 优先复用 `renderFlow` 或 `renderSequence`，不要复制一整套 SVG 样式。
3. 运行生成命令。
4. 在 Markdown 中引用 `../diagrams/exported/<name>.svg`。
5. 转换成 PNG 抽查文字、箭头、分组和留白。

核心品牌图仍保存在 `assets/readme/`。真实界面操作继续使用 `assets/readme/screenshots/` 中的截图。

文档提交前运行统一检查：

```bash
node scripts/check-docs.mjs
```

该检查会重新生成到临时目录并对比导出文件，同时验证版本、链接、API 路由覆盖、SVG 无障碍信息和可点击放大。
