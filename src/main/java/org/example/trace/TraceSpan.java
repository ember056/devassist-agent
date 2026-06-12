package org.example.trace;

import java.util.Map;

public class TraceSpan implements AutoCloseable {
    private final AgentTraceService traceService;
    private final String traceId;
    private final String stage;
    private final String name;
    private final Map<String, Object> attributes;
    private final long startedAtMillis;
    private boolean closed;

    TraceSpan(
            AgentTraceService traceService,
            String traceId,
            String stage,
            String name,
            Map<String, Object> attributes
    ) {
        this.traceService = traceService;
        this.traceId = traceId;
        this.stage = stage;
        this.name = name;
        this.attributes = attributes;
        this.startedAtMillis = System.currentTimeMillis();
    }

    public void success(Map<String, Object> extraAttributes) {
        if (closed) {
            return;
        }
        closed = true;
        traceService.record(traceId, stage, name, TraceStatus.SUCCESS, duration(), null,
                AgentTraceService.merge(attributes, extraAttributes));
    }

    public void error(Throwable error, Map<String, Object> extraAttributes) {
        if (closed) {
            return;
        }
        closed = true;
        String message = error == null ? null : error.getMessage();
        traceService.record(traceId, stage, name, TraceStatus.ERROR, duration(), message,
                AgentTraceService.merge(attributes, extraAttributes));
    }

    @Override
    public void close() {
        success(null);
    }

    private long duration() {
        return System.currentTimeMillis() - startedAtMillis;
    }
}
