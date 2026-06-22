# 测试指南

> 适用读者：插件维护者、贡献者

## 后端测试

```bash
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
```

当前重点测试覆盖：

- 文档切片边界与重叠。
- 混合检索与降级。
- 访客限流和模型用量守卫。
- LLM 请求、安全校验与错误处理。
- Public Chat/Search 路由和请求方法。

## 构建验证

```bash
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

完整构建同时验证后端编译、测试、Console 构建和资源打包。

## 前端现状

`ui/package.json` 当前还没有真实单元测试，`test:unit` 只是占位命令。新增复杂状态逻辑时，优先为纯工具函数、SSE 解析、配置转换和写作状态编排增加 Vitest 测试。

## 手动集成验证

1. `./dev-start.sh --deploy-only`
2. 确认插件 `STARTED`
3. 测试管理员配置保存与刷新后回显
4. 测试匿名 POST Chat/Search
5. 测试旧 GET 路由是否按预期不存在或仅保留兼容接口
6. 测试 SSE 逐帧返回
7. 检查 Halo 日志无新异常

## 文档验证

```bash
node scripts/check-docs.mjs
```

该命令检查链接、SVG、版本一致性、生成结果和关键 API 覆盖。
