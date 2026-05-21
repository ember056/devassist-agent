# DevAssist

> A Java-based RAG and multi-agent system for developer ticket triage, log analysis, and fault diagnosis.

DevAssist is an internal developer-support Agent system built with Spring Boot and Spring AI Alibaba. It helps engineering teams triage developer tickets, retrieve relevant troubleshooting knowledge, query logs and metrics, and generate structured diagnosis reports.

## Features

- **Developer ticket diagnosis**: classify ticket intent, extract key service/error information, and produce diagnosis suggestions.
- **RAG knowledge base**: upload troubleshooting manuals, API docs, release notes, and historical ticket solutions into Milvus for retrieval-augmented generation.
- **Tool calling**: expose ticket lookup, log query, metric query, internal document retrieval, and time utilities as Agent tools.
- **Multi-agent workflow**: use a Planner-Executor-Supervisor flow to plan diagnosis steps, collect evidence, and generate a Markdown report.
- **Streaming response**: use SSE to stream long-running model responses and diagnosis progress.
- **Web and REST APIs**: provide a lightweight web UI and REST endpoints for chat, streaming chat, file upload, and diagnosis.

## Tech Stack

| Technology | Usage |
| --- | --- |
| Java 17 | Main language |
| Spring Boot 3.2 | Web application framework |
| Spring AI Alibaba | LLM and Agent integration |
| DashScope | Chat and embedding models |
| Milvus | Vector database |
| MCP | External tool integration |
| SSE | Streaming response |

## Project Structure

```text
devassist-agent/
├── aiops-docs/                         # Demo troubleshooting documents
├── src/main/java/org/example/
│   ├── agent/tool/                     # Agent tools
│   ├── client/                         # Milvus client
│   ├── config/                         # Application configuration
│   ├── controller/                     # REST controllers
│   ├── dto/                            # Request/response DTOs
│   └── service/                        # Chat, RAG, vector, and diagnosis services
├── src/main/resources/
│   ├── static/                         # Web UI
│   └── application.yml                 # Runtime configuration
├── vector-database.yml                 # Milvus docker compose file
├── Makefile
└── pom.xml
```

## Quick Start

### 1. Set Environment Variables

```bash
export DASHSCOPE_API_KEY=your-dashscope-api-key
```

Optional MCP configuration:

```bash
export MCP_CLIENT_ENABLED=true
export TENCENT_MCP_URL=https://mcp-api.tencent-cloud.com
export TENCENT_MCP_SSE_ENDPOINT=/sse/your-endpoint
```

### 2. Start Milvus

```bash
docker compose -f vector-database.yml up -d
```

### 3. Start the Application

```bash
mvn clean install
mvn spring-boot:run
```

The service runs on:

```text
http://localhost:9900
```

## Core APIs

### Chat

```http
POST /api/chat
Content-Type: application/json

{
  "Id": "session-001",
  "Question": "How should I troubleshoot a slow response ticket?"
}
```

### Streaming Chat

```http
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-001",
  "Question": "Analyze this service error ticket and return a diagnosis plan."
}
```

### Diagnosis

```http
POST /api/ai_ops
```

The diagnosis flow queries available evidence, retrieves internal documents, and generates a Markdown report.

### Upload Knowledge Documents

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/cpu_high_usage.md"
```

## Configuration

Important fields in `src/main/resources/application.yml`:

```yaml
server:
  port: 9900

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}

milvus:
  host: localhost
  port: 19530

rag:
  top-k: 3
  model: qwen3-max
```

## Notes

- Do not commit real API keys, MCP endpoints, logs, or uploaded files.
- `target/`, logs, and upload directories are ignored by `.gitignore`.
- The sample documents under `aiops-docs/` are for local demonstration and RAG testing.

## License

MIT
