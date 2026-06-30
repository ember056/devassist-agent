# Synthetic Incident Tickets

这些工单是脱敏模拟数据，不是企业真实工单。

设计目的：

- 给 RAG 提供可检索的业务化问题描述。
- 给 AIOps workflow 提供 expected root cause、expected evidence 等标注。
- 给 Benchmark 提供升级前后可比较的数据集。

数据来源原则：

- 参考公开 SRE/Incident Response/Runbook 的结构与常见故障类型。
- 不复制企业内部资料，不伪装成真实线上事故。
- 每条样例都显式标注 `type=synthetic_ticket`。

