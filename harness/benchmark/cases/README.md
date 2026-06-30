# Benchmark Cases

这里的 case 与 `harness/cases` 的区别：

- `harness/cases`：日常轻量回归，重点看链路是否退化。
- `harness/benchmark/cases`：升级前后对比，额外带 `labels` 标注字段。

`labels` 字段用于计算确定性指标：

- `expectedSources` -> Source Hit Rate
- `expectedRootCause` -> RootCause Hit Rate
- `expectedEvidence` -> Evidence coverage
- `expectedSections` -> Structure Hit Rate

运行示例：

```bash
python harness/bootstrap_docs.py --base-url http://localhost:9900
python harness/runner.py --cases harness/benchmark/cases --base-url http://localhost:9900 --output harness/benchmark/reports/baseline.json
python harness/judge_runner.py --input harness/benchmark/reports/baseline.json --output harness/benchmark/reports/judge-baseline.json
```
