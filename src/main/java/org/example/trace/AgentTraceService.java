package org.example.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentTraceService {
    private static final Logger logger = LoggerFactory.getLogger(AgentTraceService.class);

    @Value("${agent.trace.enabled:true}")
    private boolean enabled;

    @Value("${agent.trace.storage-dir:./data/traces}")
    private String storageDir;

    @Value("${agent.trace.persist-enabled:true}")
    private boolean persistEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentMap<String, TraceRecord> traces = new ConcurrentHashMap<>();
    private Path tracePath;

    @PostConstruct
    public void init() {
        tracePath = Path.of(storageDir);
        if (!persistEnabled) {
            return;
        }
        try {
            Files.createDirectories(tracePath);
            loadExistingTraces();
        } catch (IOException e) {
            logger.warn("Failed to initialize trace storage: {}", e.getMessage());
        }
    }

    public String startTrace(String entrypoint, String summary) {
        if (!enabled) {
            return null;
        }
        String traceId = "trc_" + UUID.randomUUID().toString().replace("-", "");
        TraceRecord record = new TraceRecord(traceId, entrypoint, abbreviate(summary, 160));
        traces.put(traceId, record);
        record(traceId, "trace", "start", TraceStatus.SUCCESS, 0L, null, Map.of(
                "entrypoint", entrypoint,
                "summary", record.getSummary()
        ));
        persist(record);
        return traceId;
    }

    public TraceContext.Scope bind(String traceId) {
        return TraceContext.bind(traceId);
    }

    public String currentTraceId() {
        return TraceContext.currentTraceId();
    }

    public TraceSpan startSpan(String stage, String name, Map<String, Object> attributes) {
        return startSpan(currentTraceId(), stage, name, attributes);
    }

    public TraceSpan startSpan(String traceId, String stage, String name, Map<String, Object> attributes) {
        return new TraceSpan(this, traceId, stage, name, attributes);
    }

    public void event(String stage, String name, Map<String, Object> attributes) {
        record(currentTraceId(), stage, name, TraceStatus.SUCCESS, null, null, attributes);
    }

    public void record(
            String traceId,
            String stage,
            String name,
            TraceStatus status,
            Long durationMs,
            String errorMessage,
            Map<String, Object> attributes
    ) {
        if (!enabled || traceId == null || traceId.isBlank()) {
            return;
        }
        TraceRecord record = traces.get(traceId);
        if (record == null) {
            return;
        }
        record.addEvent(new TraceEvent(traceId, stage, name, status, durationMs, errorMessage, attributes));
        persist(record);
    }

    public void finishTrace(String traceId, TraceStatus status, String errorMessage) {
        if (!enabled || traceId == null || traceId.isBlank()) {
            return;
        }
        TraceRecord record = traces.get(traceId);
        if (record == null) {
            return;
        }
        record.finish(status, errorMessage);
        record(traceId, "trace", "finish", status, 0L, errorMessage, Map.of("status", status.name()));
        persist(record);
    }

    public Optional<TraceRecord> getTrace(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    public List<TraceRecord> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return traces.values().stream()
                .sorted(Comparator.comparing(TraceRecord::getStartedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
    }

    static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (left != null) {
            result.putAll(left);
        }
        if (right != null) {
            result.putAll(right);
        }
        return result;
    }

    private void loadExistingTraces() throws IOException {
        if (!Files.exists(tracePath)) {
            return;
        }
        try (var stream = Files.list(tracePath)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : files) {
                try {
                    TraceRecord record = objectMapper.readValue(file.toFile(), TraceRecord.class);
                    if (record.getTraceId() != null) {
                        traces.put(record.getTraceId(), record);
                    }
                } catch (Exception e) {
                    logger.warn("Skip invalid trace file {}: {}", file, e.getMessage());
                }
            }
        }
    }

    private void persist(TraceRecord record) {
        if (!persistEnabled || record == null || record.getTraceId() == null) {
            return;
        }
        try {
            Files.createDirectories(tracePath);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tracePath.resolve(record.getTraceId() + ".json").toFile(), record);
        } catch (Exception e) {
            logger.debug("Trace persist skipped: {}", e.getMessage());
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
