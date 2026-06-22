# Widget 开发

> 适用读者：前台 Widget 和主题兼容开发者

## 注入方式

`ChatWidgetFilter` 实现 Halo `AdditionalWebFilter`，拦截 HTML 响应并在第一个 `</body>` 前注入聊天和脑图资源。Console、登录、UC、API 和 actuator 路径不会注入。

静态资源通过 ReverseProxy 暴露在：

```text
/plugins/ai-suite/assets/res/**
```

对应目录为 `src/main/resources/static/`。

## 资源

- `js/chat-widget.js`：聊天、搜索、SSE 和反馈。
- `js/mindmap-widget.js`：文章脑图、折叠和原文跳转。
- `css/chat-widget.css`：聊天与搜索样式。
- `css/mindmap-widget.css`：脑图样式。

每次插件启动生成资源版本参数，用于刷新浏览器缓存。

## 主题兼容原则

- 使用语义 DOM 与响应式布局，不按主题名称或 User-Agent 写分支。
- 避免覆盖主题全局变量和通用类名。
- 对 PJAX/局部导航重复检测并幂等初始化。
- 移动端保留文章阅读上下文，避免不必要的强制全屏。
- 插入 HTML 前使用 DOMPurify 或受控模板。
- 高亮摘要只允许受控 `<mark>`。

## 文章识别

过滤器优先从 `data-target="Post"` 和 `data-id` 检测文章上下文，也兼容评论区域标识。非文章页由脚本自行跳过脑图渲染。

## 验证矩阵

- 首页、归档、搜索、文章、登录和 Console。
- 普通导航与 PJAX 导航。
- 亮色、暗色和自动主题。
- 桌面、窄屏和触摸设备。
- 登录访客与匿名访客。
- 功能开关关闭和配置读取失败。
- HTML 有 Content-Length、chunked 和多个代码示例 `</body>` 的情况。
