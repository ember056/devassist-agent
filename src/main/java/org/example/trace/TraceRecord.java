package org.example.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraceRecord {
    private String traceId;
    private String entrypoint;
    private String summary;
    private TraceStatus status;
    private Instant startedAt;
    private Instant endedAt;
    private String errorMessage;
    private List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());

    public TraceRecord() {
    }

    public TraceRecord(String traceId, String entrypoint, String summary) {
        this.traceId = traceId;
        this.entrypoint = entrypoint;
        this.summary = summary;
        this.status = TraceStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public synchronized void addEvent(TraceEvent event) {
        events.add(event);
    }

    public synchronized void finish(TraceStatus status, String errorMessage) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.endedAt = Instant.now();
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public void setStatus(TraceStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<TraceEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public void setEvents(List<TraceEvent> events) {
        this.events = Collections.synchronizedList(
                events == null ? new ArrayList<>() : new ArrayList<>(events)
        );
    }
}
