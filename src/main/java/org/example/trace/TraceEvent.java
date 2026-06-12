package org.example.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TraceEvent {
    private String eventId;
    private String traceId;
    private String stage;
    private String name;
    private TraceStatus status;
    private Instant timestamp;
    private Long durationMs;
    private String errorMessage;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public TraceEvent() {
    }

    public TraceEvent(
            String traceId,
            String stage,
            String name,
            TraceStatus status,
            Long durationMs,
            String errorMessage,
            Map<String, Object> attributes
    ) {
        this.eventId = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.stage = stage;
        this.name = name;
        this.status = status;
        this.timestamp = Instant.now();
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        if (attributes != null) {
            this.attributes = new LinkedHashMap<>(attributes);
        }
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public void setStatus(TraceStatus status) {
        this.status = status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }
}
