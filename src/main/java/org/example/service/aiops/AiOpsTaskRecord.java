package org.example.service.aiops;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class AiOpsTaskRecord {
    private String taskId;
    private String traceId;
    private String request;
    private AiOpsTaskStatus status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String report;
    private String errorMessage;
    private Map<String, Object> graphSummary = new LinkedHashMap<>();

    public AiOpsTaskRecord() {
    }

    public AiOpsTaskRecord(String taskId, String traceId, String request) {
        this.taskId = taskId;
        this.traceId = traceId;
        this.request = request;
        this.status = AiOpsTaskStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public AiOpsTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AiOpsTaskStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getGraphSummary() {
        return graphSummary;
    }

    public void setGraphSummary(Map<String, Object> graphSummary) {
        this.graphSummary = graphSummary == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graphSummary);
    }
}
