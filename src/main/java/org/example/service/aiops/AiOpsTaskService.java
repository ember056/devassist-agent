package org.example.service.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.trace.AgentTraceService;
import org.example.trace.TraceContext;
import org.example.trace.TraceSpan;
import org.example.trace.TraceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AiOpsTaskService {
    private static final Logger logger = LoggerFactory.getLogger(AiOpsTaskService.class);

    private final AiOpsHypothesisAnalysisService hypothesisAnalysisService;
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, AiOpsTaskRecord> tasks = new ConcurrentHashMap<>();

    @Value("${aiops.task.storage-dir:./data/aiops-tasks}")
    private String storageDir;

    @Value("${aiops.task.persist-enabled:true}")
    private boolean persistEnabled;

    private Path taskPath;

    public AiOpsTaskService(
            AiOpsHypothesisAnalysisService hypothesisAnalysisService,
            AgentTraceService traceService
    ) {
        this.hypothesisAnalysisService = hypothesisAnalysisService;
        this.traceService = traceService;
    }

    @PostConstruct
    public void init() {
        taskPath = Path.of(storageDir);
        if (!persistEnabled) {
            return;
        }
        try {
            Files.createDirectories(taskPath);
            loadExistingTasks();
        } catch (IOException e) {
            logger.warn("Failed to initialize AIOps task storage: {}", e.getMessage());
        }
    }

    public AiOpsTaskRecord createTask(String incidentRequest) {
        String request = normalizeRequest(incidentRequest);
        String traceId = traceService.startTrace("ai_ops", request);
        String taskId = "aio_" + UUID.randomUUID().toString().replace("-", "");
        AiOpsTaskRecord task = new AiOpsTaskRecord(taskId, traceId, request);
        tasks.put(taskId, task);
        persist(task);
        traceService.event("aiops", "task_created", Map.of("taskId", taskId));
        return task;
    }

    public AiOpsAnalysisResult runTask(String taskId) {
        AiOpsTaskRecord task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("AIOps task does not exist: " + taskId);
        }

        try (TraceContext.Scope ignored = traceService.bind(task.getTraceId())) {
            task.setStatus(AiOpsTaskStatus.RUNNING);
            task.setStartedAt(Instant.now());
            persist(task);
            traceService.event("aiops", "task_started", Map.of("taskId", taskId));

            AiOpsAnalysisResult result;
            try (TraceSpan span = traceService.startSpan("aiops", "hypothesis_graph_analysis", Map.of("taskId", taskId))) {
                result = hypothesisAnalysisService.analyze(task.getRequest());
                span.success(summarizeGraph(result.getGraph()));
            }

            task.setStatus(AiOpsTaskStatus.SUCCESS);
            task.setCompletedAt(Instant.now());
            task.setReport(result.getReport());
            task.setGraphSummary(summarizeGraph(result.getGraph()));
            persist(task);
            traceService.finishTrace(task.getTraceId(), TraceStatus.SUCCESS, null);
            return result;
        } catch (Exception e) {
            task.setStatus(AiOpsTaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            task.setErrorMessage(e.getMessage());
            persist(task);
            traceService.finishTrace(task.getTraceId(), TraceStatus.ERROR, e.getMessage());
            throw e;
        }
    }

    public Optional<AiOpsTaskRecord> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<AiOpsTaskRecord> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tasks.values().stream()
                .sorted(Comparator.comparing(AiOpsTaskRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
    }

    private Map<String, Object> summarizeGraph(HypothesisGraph graph) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (graph == null) {
            return summary;
        }
        summary.put("hypothesisCount", graph.hypotheses().size());
        summary.put("evidenceCount", graph.evidence().size());
        summary.put("edgeCount", graph.edges().size());
        graph.bestActiveHypothesis().ifPresent(best -> {
            summary.put("bestHypothesisId", best.getId());
            summary.put("bestHypothesisName", best.getName());
            summary.put("bestConfidence", best.getCurrentConfidence());
            summary.put("bestStatus", best.getStatus().name());
        });
        return summary;
    }

    private String normalizeRequest(String incidentRequest) {
        return incidentRequest == null || incidentRequest.isBlank()
                ? "Analyze current active production alerts and identify the most likely root cause."
                : incidentRequest.trim();
    }

    private void loadExistingTasks() throws IOException {
        if (!Files.exists(taskPath)) {
            return;
        }
        try (var stream = Files.list(taskPath)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : files) {
                try {
                    AiOpsTaskRecord task = objectMapper.readValue(file.toFile(), AiOpsTaskRecord.class);
                    if (task.getTaskId() != null) {
                        tasks.put(task.getTaskId(), task);
                    }
                } catch (Exception e) {
                    logger.warn("Skip invalid AIOps task file {}: {}", file, e.getMessage());
                }
            }
        }
    }

    private void persist(AiOpsTaskRecord task) {
        if (!persistEnabled || task == null || task.getTaskId() == null) {
            return;
        }
        try {
            Files.createDirectories(taskPath);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(taskPath.resolve(task.getTaskId() + ".json").toFile(), task);
        } catch (Exception e) {
            logger.debug("AIOps task persist skipped: {}", e.getMessage());
        }
    }
}
