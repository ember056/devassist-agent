# DevAssist Harness

轻量级评估工程，用于验证 RAG / Agent 输出是否满足关键质量要求。

## 适用场景

- 检查普通聊天是否触发可信 RAG 校验。
- 检查回答是否包含参考来源。
- 检查 Query Rewrite、Rerank、Faithfulness 等增强信息是否出现在输出中。
- 为后续 AIOps 多 Agent 报告评估预留结构。

## 运行前提

1. 已配置 `DASHSCOPE_API_KEY`。
2. Milvus 已启动并完成 `aiops-docs` 文档入库。
3. Spring Boot 服务运行在 `http://localhost:9900`。

## 运行

```bash
python harness/runner.py
```

指定服务地址：

```bash
python harness/runner.py --base-url http://localhost:9900
```

指定用例目录：

```bash
python harness/runner.py --cases harness/cases
```

## 用例格式

```json
{
  "id": "rag_cpu_runbook",
  "name": "CPU 高使用率 Runbook 检索",
  "endpoint": "/api/chat",
  "payload": {
    "Id": "harness-rag-cpu",
    "Question": "CPU 使用率过高应该怎么排查？请参考内部文档。"
  },
  "assertions": {
    "contains": ["可信 RAG 校验", "参考来源"],
    "not_contains": ["unsupportedClaims"],
    "regex": ["Faithfulness[：:]\\s*(通过|未通过)"]
  }
}
```

## 报告

默认输出到：

```text
harness/reports/latest-report.json
```

报告中会包含：

- 总用例数
- 通过数
- 失败数
- 每条用例的响应摘要
- 失败断言原因

## 后续扩展

- 增加 `/api/ai_ops` 报告结构检查。
- 增加 Context Recall 标注集。
- 增加 Faithfulness 自动评分阈值。
- 接入 CI，在每次改 Prompt 或 RAG 策略后自动跑评估。
