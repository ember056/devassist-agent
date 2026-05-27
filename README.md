# DevAssist 研发工单诊断 Agent 系统

> 基于 Java、Spring Boot、Spring AI Alibaba、RAG 与多 Agent 工作流的研发工单分诊、日志分析和故障诊断系统。

DevAssist 面向企业内部研发支持场景，解决研发工单处理中问题分类慢、日志与文档分散、处理经验难复用等问题。系统可以理解研发工单描述，检索内部故障处理文档，调用日志与指标查询工具，并生成结构化诊断报告。

## 项目背景

企业内部研发支持场景中，经常会出现这类问题：

- 测试同学反馈接口异常，但问题描述不完整，需要研发手动补充服务名、接口名、时间范围和错误码。
- 业务方反馈功能报错，研发需要在工单、日志平台、监控平台和知识库之间来回切换。
- 发布或联调过程中出现异常，历史处理经验散落在文档、聊天记录和历史工单中，复用成本高。
- 线上告警触发后，排查过程依赖值班人员经验，容易重复劳动。

DevAssist 将这些问题统一抽象为“研发工单诊断”任务，通过 Agent 自动完成工单理解、证据查询、知识库召回、根因分析和报告生成。

## 工单来源与系统边界

本项目中的“研发工单”不是绑定某一个具体平台，而是企业内部研发支持场景的统一抽象。真实企业中可以来自：

- Jira、禅道、TAPD 等项目/缺陷管理平台；
- 飞书、企微、钉钉等内部工单或机器人入口；
- 内部自研工单系统；
- 线上告警平台自动生成的排查任务；
- 测试、业务、研发在联调和发布过程中提交的问题反馈。

一条典型工单通常包含：

- 问题描述；
- 服务名、接口名、错误码；
- 请求 ID、Trace ID；
- 发生时间、环境信息；
- 优先级、提交人、相关业务线。

系统设计上没有强绑定具体工单平台，而是预留统一输入层。后续接入不同平台时，只需要实现对应 Adapter，将平台字段映射为统一的 Ticket 对象，再交给 Agent 执行诊断流程。

当前项目阶段使用模拟工单、Mock 日志和本地运维知识文档验证核心链路，重点展示的是工单解析、工具调用、RAG 召回、多 Agent 协作和诊断报告生成能力。

## 核心能力

- **研发工单分诊**：解析工单中的服务名、接口名、错误码、时间范围和请求 ID，判断问题类型与排查方向。
- **RAG 知识库**：支持故障处理手册、接口文档、发布规范和历史工单方案上传、分片、向量化、Milvus 检索和轻量 BM25 混合召回。
- **可信 RAG 校验**：对查询改写、重排序、参考来源和答案忠实度进行后置校验，降低幻觉风险。
- **检索路由**：根据 query 复杂度选择召回策略，简单问题直接走向量/BM25/混合召回，复杂问题召回候选后再触发 rerank。
- **工具调用**：将工单查询、日志查询、服务指标查询、内部文档检索和时间查询封装为 Agent 工具。
- **多 Agent 协作**：采用 Planner-Executor-Supervisor 流程，完成诊断计划制定、证据查询、结果反馈和报告生成。
- **Harness 质量评估**：通过 JSON 用例自动检查 RAG/Agent 输出是否包含来源、可信校验和关键结构。
- **分层记忆持久化**：使用 Redis List 保存 session 最近多轮短期对话，并使用 Milvus `chat_memory` collection 保存可语义召回的长期会话记忆。
- **流式响应**：基于 SSE 返回大模型生成内容，优化长耗时诊断任务的交互体验。
- **Web 与 REST API**：提供轻量前端页面和对话、流式对话、文件上传、诊断分析等接口。

## 技术栈

| 技术 | 作用 |
| --- | --- |
| Java 17 | 后端开发语言 |
| Spring Boot 3.2 | Web 应用与 REST API |
| Spring AI Alibaba | 大模型、Agent 与工具调用集成 |
| DashScope | 对话模型与文本向量化 |
| Milvus | 向量数据库 |
| Redis | 会话记忆持久化 |
| MCP | 外部工具服务接入 |
| SSE | 流式响应 |

## 项目结构

```text
devassist-agent/
├── aiops-docs/                         # 示例故障处理文档
├── harness/                            # RAG / Agent 质量评估用例与 runner
├── src/main/java/org/example/
│   ├── agent/tool/                     # Agent 工具
│   ├── client/                         # Milvus 客户端
│   ├── config/                         # 配置类
│   ├── controller/                     # REST 控制器
│   ├── dto/                            # 请求与响应对象
│   └── service/                        # 对话、RAG、向量检索与诊断服务
├── src/main/resources/
│   ├── static/                         # 前端页面
│   └── application.yml                 # 应用配置
├── vector-database.yml                 # Milvus Docker Compose 配置
├── Makefile
└── pom.xml
```

## 快速启动

### 1. 配置环境变量

```bash
export DASHSCOPE_API_KEY=your-dashscope-api-key
```

如果需要接入外部 MCP 服务，可以额外配置：

```bash
export MCP_CLIENT_ENABLED=true
export TENCENT_MCP_URL=https://mcp-api.tencent-cloud.com
export TENCENT_MCP_SSE_ENDPOINT=/sse/your-endpoint
```

### 2. 启动 Milvus

```bash
docker compose -f vector-database.yml up -d
```

该 Compose 文件同时会启动 Redis，用于会话记忆持久化：

```text
Redis: localhost:6379
```

### 3. 启动后端服务

```bash
mvn clean install
mvn spring-boot:run
```

服务默认运行在：

```text
http://localhost:9900
```

## 核心接口

### 普通对话

```http
POST /api/chat
Content-Type: application/json

{
  "Id": "session-001",
  "Question": "订单服务创建订单接口报错，应该怎么排查？"
}
```

### 流式对话

```http
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-001",
  "Question": "请分析这条研发工单，并给出排查计划。"
}
```

### 工单诊断

```http
POST /api/ai_ops
```

该接口会触发多 Agent 诊断流程，查询可用证据、检索内部文档，并生成 Markdown 诊断报告。

### 上传知识库文档

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/cpu_high_usage.md"
```

## Harness 质量评估

项目提供了轻量级 harness，用来验证 RAG / Agent 输出质量。

运行前需要先启动服务，并完成知识库文档入库：

```bash
python harness/runner.py
```

指定服务地址：

```bash
python harness/runner.py --base-url http://localhost:9900
```

当前 harness 会检查：

- 文档/排障类问题是否触发“可信 RAG 校验”。
- 回答是否包含参考来源。
- 输出中是否包含 Query Rewrite、Rerank、Faithfulness 等关键质量信息。
- 普通闲聊是否不会被强制触发 RAG 校验。

报告默认输出到：

```text
harness/reports/latest-report.json
```

## 诊断流程示例

以“测试环境订单服务 `/order/create` 接口 500，错误码 `ORDER_STOCK_LOCK_FAILED`，请求 ID 为 `req-xxx`，发生时间为 14:32”为例：

1. Agent 从工单描述中提取服务名、接口名、错误码、请求 ID 和时间范围。
2. Planner 生成排查计划，例如先查应用日志，再查服务指标，最后检索知识库中的历史处理方案。
3. Executor 调用日志、指标和知识库工具，收集错误栈、异常指标和相关文档。
4. Supervisor 汇总 Planner 与 Executor 的结果，判断是否需要重新规划。
5. 系统输出问题分类、可能根因、证据摘要、处理建议和风险说明。

## 配置说明

`src/main/resources/application.yml` 中的关键配置：

```yaml
server:
  port: 9900

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}
    mcp:
      client:
        enabled: ${MCP_CLIENT_ENABLED:false}

milvus:
  host: localhost
  port: 19530

rag:
  top-k: 3
  model: qwen3-max
  retrieval:
    simple-mode: HYBRID
    complex-mode: HYBRID
    complex-min-length: 18
    candidate-multiplier: 3
    vector-weight: 0.65
    bm25-weight: 0.35
  bm25:
    bootstrap-enabled: true
  rerank:
    enabled: true
    min-query-length: 12
    close-score-delta: 0.2

chat:
  memory:
    redis-enabled: true
    key-prefix: onecall:session
    max-pairs: 6
    ttl-hours: 168
    semantic-enabled: true
    semantic-top-k: 3
    semantic-max-answer-length: 1200
```

Redis 记忆说明：

```text
Key: onecall:session:{sessionId}:messages
Type: List
窗口控制: RPUSH + LTRIM，只保留最近 6 轮用户/助手消息
过期控制: TTL 默认 7 天
降级策略: Redis 不可用时自动回退到本地内存
```

会话语义记忆说明：

```text
Collection: chat_memory
Fields: id, content, vector, metadata
写入时机: 每轮 user + assistant 对话完成后写入
召回时机: 下一轮对话构建 prompt 前，按当前问题从同一 session 的长期记忆中语义召回
边界: 当前按 sessionId 隔离，还没有做跨用户画像、敏感信息 MemoryPolicy 和长期记忆摘要压缩
```

RAG 检索路由说明：

```text
SIMPLE query:
  -> 按配置直接走 VECTOR / BM25 / HYBRID 召回
  -> 不触发 rerank

COMPLEX query:
  -> 先召回候选片段
  -> 再触发轻量 rerank

当前 rerank 为本地词项覆盖度 + 向量分数融合的轻量实现，后续可替换为专门的 rerank 模型。
```

## 面试说明

如果被问到工单来源，可以这样解释：

> 工单来源可以是 Jira、禅道、TAPD、飞书/企微工单、内部自研工单系统，也可以是线上告警平台自动生成的排查任务。项目没有绑定某一个具体平台，而是抽象了统一工单输入，核心关注工单解析、证据查询、知识库召回和诊断报告生成链路。

如果被问到是否已经真实接入企业工单平台，可以这样解释：

> 当前项目阶段使用模拟工单和 Mock 日志验证完整诊断链路，设计上预留了平台适配层。后续接入真实平台时，只需要写不同平台的 Adapter，把平台字段映射成统一 Ticket 对象即可。

## 注意事项

- 不要提交真实 API Key、MCP endpoint、日志文件或上传文件。
- `target/`、日志和上传目录已在 `.gitignore` 中忽略。
- `aiops-docs/` 下的文档用于本地演示和 RAG 测试。

## License

MIT
