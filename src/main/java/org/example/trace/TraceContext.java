package org.example.trace;

public final class TraceContext {
    private static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String currentTraceId() {
        return CURRENT_TRACE_ID.get();
    }

    public static Scope bind(String traceId) {
        String previous = CURRENT_TRACE_ID.get();
        CURRENT_TRACE_ID.set(traceId);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT_TRACE_ID.remove();
            } else {
                CURRENT_TRACE_ID.set(previous);
            }
        }
    }
}
