# DevAssist Harness

轻量级评估工程，用于验证 RAG / Agent 输出是否满足关键质量要求。

## 适用场景

- 检查普通聊天是否触发可信 RAG 校验。
- 检查回答是否包含参考来源。
- 检查 Query Rewrite、Rerank、Faithfulness 等增强信息是否出现在输出中。
- 检查 AIOps 假设图报告是否包含根因排序、证据、置信度更新和推荐动作。
- 检查 SSE 链路是否返回 taskId、traceId、content 和 done 事件。

## 测试集如何定义

Harness 测试集不是随便写几个问题，而是按“场景类型 + 能力覆盖 + 稳定断言”组织。

当前用例分三类：

| category | 目标 | 典型断言 |
|---|---|---|
| `chat` | 验证普通对话不会误走复杂 RAG / AIOps 链路 | 不包含 `可信 RAG 校验`，JSON 中有 `data.traceId` |
| `rag` | 验证知识库问答、混合召回、来源引用和可信校验 | 包含 `参考来源`、`Faithfulness`，JSON 中有 `data.answer` |
| `aiops` | 验证故障假设图 workflow 是否稳定 | SSE 有 `task/content/done`，报告包含 `Hypothesis Ranking`、`Confidence Update Trace` |

每个 case 建议包含：

```json
{
  "id": "aiops_cpu_hypothesis_graph",
  "name": "AIOps CPU 高使用率假设图诊断",
  "category": "aiops",
  "capabilities": [
    "hypothesis_graph",
    "evidence_collection",
    "confidence_update",
    "topk_pruning",
    "task_trace"
  ],
  "endpoint": "/api/ai_ops",
  "payload": {
    "userRequest": "payment-service CPU 使用率持续超过 90%，接口延迟升高，请分析可能根因并给出排查建议。"
  },
  "assertions": {
    "contains": ["Hypothesis Ranking", "Confidence Update Trace"],
    "not_contains": ["AI Ops analysis failed"],
    "regex": ["strong support\\s*\\|\\s*10\\.00"],
    "sse_types": ["task", "content", "done"],
    "sse_trace_id": true,
    "sse_task_id": true,
    "max_duration_ms": 180000
  }
}
```

定义原则：

1. 优先断言结构和关键证据，不死磕大模型自然语言。
2. RAG 用例要覆盖普通问答、知识库问答、来源引用和可信校验。
3. AIOps 用例要覆盖报告结构、根因候选、证据采集、LR 更新、剪枝和 trace/task。
4. 每个用例都要有 `capabilities`，方便看测试集覆盖了哪些能力。
5. 高风险改动后重点看失败原因和 report，不只看 pass/fail。

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

一键把项目内示例 Runbook 上传到知识库：

```bash
python harness/bootstrap_docs.py --base-url http://localhost:9900
```

运行带标注 Benchmark 数据集：

```bash
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/baseline.json
```

只校验测试集 schema，不请求服务：

```bash
python harness/runner.py --validate-only
```

查看当前会运行哪些用例：

```bash
python harness/runner.py --list
```

只运行某一类用例：

```bash
python harness/runner.py --category rag
python harness/runner.py --category rag,aiops
```

遇到第一条失败用例就停止：

```bash
python harness/runner.py --fail-fast
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
    "regex": ["Faithfulness[：:]\\s*(通过|未通过)"],
    "json_path_exists": ["data.traceId", "data.answer"],
    "json_path_equals": {
      "data.success": true
    },
    "sse_types": ["task", "content", "done"],
    "sse_trace_id": true,
    "sse_task_id": true,
    "max_duration_ms": 180000
  }
}
```

断言说明：

| assertion | 说明 |
|---|---|
| `contains` | 响应文本必须包含指定片段 |
| `not_contains` | 响应文本不能包含指定片段 |
| `regex` | 响应文本必须匹配正则 |
| `json_path_exists` | JSON 响应中必须存在字段，例如 `data.traceId` |
| `json_path_equals` | JSON 响应字段必须等于指定值 |
| `sse_types` | SSE 响应中必须出现指定消息类型 |
| `sse_trace_id` | SSE 响应必须包含 traceId |
| `sse_task_id` | SSE 响应必须包含 taskId |
| `max_duration_ms` | 端到端耗时上限 |

## Benchmark Labels

`harness/benchmark/cases` 中的用例比普通回归用例多了 `labels` 字段：

```json
{
  "labels": {
    "expectedSources": ["db_connection_pool.md"],
    "expectedRootCause": "Database connection pool exhaustion",
    "expectedEvidence": ["Database query timeout", "connection pool"],
    "expectedSections": ["Hypothesis Ranking", "Evidence Collected"]
  }
}
```

这些字段用于生成确定性指标：

| 指标 | 含义 |
|---|---|
| Source Hit Rate | 回答是否命中期望来源 |
| RootCause Hit Rate | AIOps 报告是否命中期望根因 |
| Structure Hit Rate | 报告是否包含期望结构 |

普通 Harness 看链路是否退化；Benchmark labels 用来回答“升级前后到底有没有变好”。

## 报告

默认输出到：

```text
harness/reports/latest-report.json
```

报告中会包含：

- 总用例数
- 通过数
- 失败数
- 按 `category` 汇总的通过情况
- 按 `capabilities` 汇总的能力覆盖情况
- 每条用例的响应摘要
- 失败断言原因

## 二次复查后的优化

这版 Harness 做了几个工程化补强：

1. 增加 case schema 校验，避免字段拼错导致用例“看似跑了、实际没断言”。
2. 增加 `--category`，可以只跑 `chat`、`rag` 或 `aiops`，方便定位是哪条链路退化。
3. 增加 `--list` 和 `--validate-only`，服务未启动时也能检查测试集本身。
4. 增加 `--fail-fast`，适合 CI 或本地快速定位第一条失败用例。
5. 报告增加 `byCategory` 和 `capabilityCoverage`，能看到测试集覆盖了哪些能力。
6. AIOps 用例优先断言报告结构、候选根因、证据和 LR，不全文匹配模型回答，降低非确定性误报。

## 后续扩展

- 增加 Context Recall 标注集。
- 增加 Faithfulness 自动评分阈值。
- 增加真实根因标签，统计 Top-1 Accuracy、Top-K Recall 和误剪率。
- 接入 CI，在每次改 Prompt 或 RAG 策略后自动跑评估。

## LLM-as-Judge / 离线语义评估

`judge_runner.py` 可以读取 Harness 报告，对回答做离线 LLM-as-Judge 评分。它不进入在线回答链路，默认只作为 Benchmark 质量评估补充。

运行普通 Harness：

```bash
python harness/runner.py --base-url http://localhost:9900 --output harness/reports/baseline.json
```

运行 Judge：

```bash
python harness/judge_runner.py --input harness/reports/baseline.json --output harness/benchmark/reports/judge-baseline.json
```

生成 Markdown 汇总报告：

```bash
python harness/benchmark_report.py --benchmark harness/benchmark/reports/structured-final6.json --judge harness/benchmark/reports/structured-final6-judge.json --output harness/benchmark/reports/structured-final6.md
```

只构造 Judge 输入、不调用模型：

```bash
python harness/judge_runner.py --input harness/judge_selftest/sample-report.json --dry-run
```

环境变量：

```text
DASHSCOPE_API_KEY          Judge 调用密钥
DASHSCOPE_JUDGE_MODEL      默认 qwen-plus
DASHSCOPE_JUDGE_ENDPOINT   默认 DashScope OpenAI-compatible endpoint
DASHSCOPE_JUDGE_TIMEOUT    默认 60 秒
```

Judge 输出指标：

- Judge Pass Rate
- Average Overall Score
- Average Faithfulness Score
- Average Actionability Score
- Unsupported Claim Count
- Critical Issue Count
- Low Score Cases
- Judge Parse Failure Count
- Rough Cost Estimate

注意：

- Judge 结果不能单独作为最终结论，必须和确定性指标一起看。
- Judge prompt 固定要求 JSON 输出、禁止使用外部知识、列出 unsupportedClaims 和 criticalIssues。
- Judge 失败不会影响原始 Harness 报告。

## Synthetic Benchmark Data

当前项目没有真实企业知识库和研发工单，所以采用脱敏模拟数据：

```text
aiops-docs/
  db_connection_pool.md
  redis_timeout.md
  mq_backlog.md
  pod_restart.md

harness/benchmark/tickets/
  incident_db_connection_pool.json
  incident_redis_timeout.json
  incident_mq_backlog.json
  incident_pod_restart.json
```

这些样例参考公开资料的结构和通用故障类型，但不复制真实企业内部内容。公开参考边界记录在：

```text
harness/benchmark/sources.md
```

## 2026-07-01 实测对比

本轮评估使用同一批 `harness/benchmark/cases`，先导入 `aiops-docs`，再分别运行确定性 Benchmark 和离线 Judge：

```bash
python harness/bootstrap_docs.py --base-url http://localhost:9900
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/structured-final6.json
python harness/judge_runner.py --input harness/benchmark/reports/structured-final6.json --output harness/benchmark/reports/structured-final6-judge.json
python harness/benchmark_report.py --benchmark harness/benchmark/reports/structured-final6.json --judge harness/benchmark/reports/structured-final6-judge.json --output harness/benchmark/reports/structured-final6.md
```

升级前后对比：

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Harness Pass Rate | 0.25 | 1.00 | +0.75 |
| Source Hit Rate | 0.25 | 1.00 | +0.75 |
| RootCause Hit Rate | 0.00 | 1.00 | +1.00 |
| Structure Hit Rate | 0.75 | 1.00 | +0.25 |
| Judge Pass Rate | 0.00 | 1.00 | +1.00 |
| Average Judge Score | 1.55 | 4.775 | +3.225 |

结论：

- 确定性指标已经证明 RAG 来源命中、AIOps 根因命中和报告结构没有退化。
- Judge 指标证明语义质量、忠实度、引用质量和风险控制也明显提升。
- 本轮把 Markdown Runbook 切片从字符重叠升级为标题树语义分片，并增加章节相关性过滤、来源去重和动作去重，解决了 MQ/Redis 相邻章节互相污染的问题。
- AIOps Pod 场景改为优先检索 `pod_restart.md`，避免被通用 service unavailable 或 CPU Runbook 带偏。
- 最终报告：`harness/benchmark/reports/structured-final6.md`。

## 2026-07-03 EvidenceSpan 阶段评估

本阶段借鉴 evidence extraction / evidence filtering 思路，将 grounded RAG 从 chunk 级回答升级为证据句级回答：

```text
retrieved chunks
  -> EvidenceSpanExtractorService
  -> evidence/action/safety/verification spans
  -> grounded answer
  -> Faithfulness + Judge
```

运行命令：

```bash
python harness/bootstrap_docs.py --base-url http://localhost:9900
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/evidence-span-final2.json
python harness/judge_runner.py --input harness/benchmark/reports/evidence-span-final2.json --output harness/benchmark/reports/evidence-span-final2-judge.json
python harness/benchmark_report.py --benchmark harness/benchmark/reports/evidence-span-final2.json --judge harness/benchmark/reports/evidence-span-final2-judge.json --output harness/benchmark/reports/evidence-span-final2.md
```

结果：

| Metric | Value |
|---|---:|
| Total cases | 12 |
| Harness Pass Rate | 1.0000 |
| Source Hit Rate | 1.0000 |
| RootCause Hit Rate | 1.0000 |
| Structure Hit Rate | 1.0000 |
| Judge Pass Rate | 1.0000 |
| Average Judge Score | 4.7500 |

结论：

- EvidenceSpan 没有破坏上一阶段的确定性指标。
- RAG 回答现在能展示每条 evidence/action/safety/verification 的来源和 support 分数。
- 后续可以在 EvidenceSpan 层继续做 MAIN-RAG 风格过滤、Runbook GraphRAG 邻域扩展和多步 Agentic Retrieval。

## 2026-07-03 EvidenceSpan Filter 阶段评估

本阶段继续借鉴 MAIN-RAG 的过滤思想，在 EvidenceSpan 后增加确定性过滤服务：

```text
EvidenceSpanExtractorService
  -> EvidenceSpanFilterService
  -> retained spans
  -> grounded answer
```

过滤分数：

```text
filterScore = supportScore * 0.45
            + lexicalOverlap * 0.25
            + sectionAffinity * 0.20
            + typePrior * 0.10
```

运行命令：

```bash
python harness/bootstrap_docs.py --base-url http://localhost:9900
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/span-filter-final2.json
python harness/judge_runner.py --input harness/benchmark/reports/span-filter-final2.json --output harness/benchmark/reports/span-filter-final2-judge.json
python harness/benchmark_report.py --benchmark harness/benchmark/reports/span-filter-final2.json --judge harness/benchmark/reports/span-filter-final2-judge.json --output harness/benchmark/reports/span-filter-final2.md
```

结果：

| Metric | Value |
|---|---:|
| Total cases | 12 |
| Harness Pass Rate | 1.0000 |
| Source Hit Rate | 1.0000 |
| RootCause Hit Rate | 1.0000 |
| Structure Hit Rate | 1.0000 |
| Judge Pass Rate | 1.0000 |
| Average Judge Score | 4.7750 |

本轮复盘：

- 初版过滤后 `redis_timeout` 只有 4.0，原因是宽泛 Redis 排障问题未覆盖 `big key` 证据。
- 修复方式是把 focused source 数量从 3 放宽到 5，由 EvidenceSpanFilter 控噪。
- 参考来源改为只列实际参与回答的 source，并移除 metadata 原文，避免本地路径出现在答案里。

## 2026-07-03 Runbook GraphRAG 阶段评估

本阶段在 EvidenceSpanFilter 之前新增轻量 Runbook GraphRAG：

```text
retrieved / focused sources
  -> EvidenceSpanExtractorService
  -> RunbookGraphService
  -> EvidenceSpanFilterService
  -> grounded answer
```

核心变化：

- 根据 `headingPath` 把 Runbook 解析成 document / section / root cause / evidence / action / guardrail 节点。
- 对已命中的根因小节，补齐同一小节下的 evidence/action 兄弟节点。
- 对命中的 Runbook 文件，从本地 Markdown 补齐同源根因、安全边界和验证指标，避免向量 topK 漏掉相邻根因。
- 对宽泛排障问题，例如 Redis timeout + cache hit rate 下降，允许激活同一 Runbook 的多个候选根因。

运行命令：

```bash
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/graphrag-final4.json
python harness/judge_runner.py --input harness/benchmark/reports/graphrag-final4.json --output harness/benchmark/reports/graphrag-final4-judge.json
python harness/benchmark_report.py --benchmark harness/benchmark/reports/graphrag-final4.json --judge harness/benchmark/reports/graphrag-final4-judge.json --output harness/benchmark/reports/graphrag-final4.md
```

结果：

| Metric | Value |
|---|---:|
| Total cases | 12 |
| Harness Pass Rate | 1.0000 |
| Source Hit Rate | 1.0000 |
| RootCause Hit Rate | 1.0000 |
| Structure Hit Rate | 1.0000 |
| Judge Pass Rate | 1.0000 |
| Average Judge Score | 4.7583 |

关键复盘：

```text
初版只依赖向量 topK 构图，redis_timeout 宽泛问题仍漏掉 big key。
最终版本改为：向量召回负责定位 Runbook，本地 Markdown 图负责补齐同文档根因邻域。
bench_rag_redis_timeout 最终激活：
  hot key
  cache avalanche
  big key or slow command
```

这说明 GraphRAG 的收益不是“召回更多文档”，而是“在命中文档内部按结构补齐证据”，更适合 Runbook 这类强结构知识。
