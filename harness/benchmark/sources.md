# Public Reference Boundary

本目录里的 Benchmark 数据采用“公开资料启发 + 项目内脱敏模拟”的方式建设。

## 可借鉴的公开资料

- [Prometheus Operator Runbooks](https://runbooks.prometheus-operator.dev/)：可参考告警 Runbook 的组织方式，例如告警名、影响、排查步骤、验证方式。
- [Google SRE Book - Managing Incidents](https://sre.google/sre-book/managing-incidents/)：可参考故障响应分工、止损优先级和复盘意识。
- [Google SRE Workbook - Incident Response](https://sre.google/workbook/incident-response/)：可参考 incident response 的工程流程。
- [Google SRE Workbook - Postmortem Culture](https://sre.google/workbook/postmortem-culture/)：可参考无责复盘和行动项结构。
- [Atlassian Incident Management](https://www.atlassian.com/incident-management)：可参考 incident、postmortem、runbook 的产品化表达方式。

## 项目内怎么使用

公开资料只用于借鉴结构和通用故障类型，不直接复制企业内部内容。

当前项目新增了：

- `aiops-docs/db_connection_pool.md`
- `aiops-docs/redis_timeout.md`
- `aiops-docs/mq_backlog.md`
- `aiops-docs/pod_restart.md`
- `harness/benchmark/tickets/*.json`
- `harness/benchmark/cases/*.json`

这些数据都属于 synthetic / sanitized benchmark data，适合：

- 本地演示 RAG 入库与检索。
- 测试 AIOps workflow 的根因命中。
- 比较 RAG 改造前后的 Source Hit Rate、RootCause Hit Rate、Structure Hit Rate 和 Judge 分数。

## 面试表达

可以这样说：

> 我没有使用真实企业内部工单，因为这类数据涉及权限和隐私。项目里采用公开 SRE Runbook、Prometheus Operator Runbook、Google SRE incident response 等资料的结构，构造了脱敏模拟工单和 Runbook。每条 benchmark case 都有 expectedSources、expectedRootCause、expectedEvidence 等标注，用来评估 RAG 召回、AIOps 根因命中和报告结构质量。这样既能复现工程链路，又不会把模拟数据伪装成真实企业数据。

