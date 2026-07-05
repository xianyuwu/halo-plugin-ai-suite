# 贡献指南

感谢你参与 AI 智能套件的改进。

## 开始之前

1. 先搜索现有 Issue，避免重复工作。
2. 功能调整或较大重构请先创建 Issue，说明场景、边界和兼容影响。
3. 安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 本地验证

项目要求 JDK 21、Node.js 20+ 和 pnpm。提交前至少运行：

```bash
JAVA_HOME=/path/to/jdk21 ./gradlew test
node scripts/check-docs.mjs
git diff --check
```

涉及界面、访客组件或文档图片时，请同时提供前后对比截图。涉及公开 API、配置或迁移行为时，请同步更新对应文档。

## Pull Request

- 一个 PR 聚焦一个问题；
- 描述问题、实现方案、验证方法和兼容影响；
- 不提交 API Key、生产配置、数据库、日志、构建产物或个人数据；
- 保持插件版本不变，版本调整由维护者在发布流程中统一完成。
