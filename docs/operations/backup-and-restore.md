# 备份与恢复

> 适用读者：生产环境运维人员

## 需要备份的对象

| 对象 | 内容 | 恢复价值 |
| --- | --- | --- |
| Halo 数据库/Extension Store | 文章、自定义 Extension、配置对象 | 必须 |
| `ai-suite-configmap` | 普通配置 | 必须 |
| `ai-suite-api-keys` | 模型密钥 | 必须，需加密保存 |
| Halo 数据目录 | Lucene 索引、插件运行数据 | 建议 |
| 插件 JAR | 可回滚版本 | 建议 |
| Nginx/CDN 配置 | SSE 和真实 IP 设置 | 建议 |

ChatLog、EvaluationDataset、EvaluationRunRecord、IntentRoute 和 AgentTaskRecord 都属于 Halo 自定义 Extension，应随 Halo 数据一起备份。

## 索引是否必须备份

Lucene 索引可以从文章重新生成，因此不是唯一事实源。但文章多、Embedding 昂贵或恢复时间要求严格时，应备份数据目录以缩短恢复时间。

## 恢复顺序

1. 恢复 Halo 数据库和数据目录。
2. 安装匹配版本的插件 JAR。
3. 恢复 ConfigMap 与 Secret。
4. 启用插件并确认 `STARTED`。
5. 检查自定义 Extension 和索引统计。
6. 若索引不可读或不一致，执行全量重建。
7. 验证匿名权限、SSE、问答和后台页面。

## 安全

- Secret 备份必须加密，限制访问并记录恢复操作。
- 不要把 API Key 放进 Git、普通工单或未脱敏截图。
- 定期执行恢复演练；只有成功恢复过的备份才算可靠。
