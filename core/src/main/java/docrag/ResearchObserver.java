package docrag;

/**
 * Low-cardinality measurements; never receives document text, prompts, credentials or company identifiers.
 */
@FunctionalInterface
public interface ResearchObserver {
    ResearchObserver NONE = (operation, elapsedNanos, items, success) -> {
    };

    /**
     * Exporter, listener and application callbacks must never affect processing.
     */
    static ResearchObserver safe(ResearchObserver delegate) {
        java.util.Objects.requireNonNull(delegate);
        return new ResearchObserver() {
            public void completed(String op, long nanos, int items, boolean success) {
                try {
                    delegate.completed(op, nanos, items, success);
                } catch (RuntimeException ignored) {
                }
            }

            public void usage(Usage usage) {
                try {
                    delegate.usage(usage);
                } catch (RuntimeException ignored) {
                }
            }

            public void event(String op, String outcome, int count) {
                try {
                    delegate.event(op, outcome, count);
                } catch (RuntimeException ignored) {
                }
            }

            public void gauge(String name, long value) {
                try {
                    delegate.gauge(name, value);
                } catch (RuntimeException ignored) {
                }
            }

            public Context capture() {
                try {
                    var context = delegate.capture();
                    return () -> {
                        try {
                            var scope = context.attach();
                            return () -> {
                                try {
                                    scope.close();
                                } catch (Exception ignored) {
                                }
                            };
                        } catch (RuntimeException ignored) {
                            return () -> {
                            };
                        }
                    };
                } catch (RuntimeException ignored) {
                    return ResearchObserver.NONE.capture();
                }
            }

            public Span start(String op, String job) {
                try {
                    var span = delegate.start(op, job);
                    return (outcome, items) -> {
                        try {
                            span.finish(outcome, items);
                        } catch (RuntimeException ignored) {
                        }
                    };
                } catch (RuntimeException ignored) {
                    return ResearchObserver.NONE.start(op, job);
                }
            }
        };
    }

    void completed(String operation, long elapsedNanos, int items, boolean success);

    /**
     * No content-bearing fields are accepted by the telemetry boundary.
     */
    default void usage(Usage usage) {
    }

    default void event(String operation, String outcome, int count) {
    }

    default void gauge(String name, long value) {
    }

    default Context capture() {
        return () -> () -> {
        };
    }

    default Span start(String operation, String jobId) {
        long started = System.nanoTime();
        return new Span() {
            private boolean ended;

            public void finish(String outcome, int items) {
                if (ended) return;
                ended = true;
                completed(operation, System.nanoTime() - started, items, "OK".equals(outcome));
                event(operation, outcome, items);
            }
        };
    }

    interface Context {
        AutoCloseable attach();
    }

    interface Span extends AutoCloseable {
        void finish(String outcome, int items);

        default void close() {
            finish("ERROR", 0);
        }
    }

    record Usage(String role, String model, Long inputTokens, Long outputTokens, Long loadNanos,
                 Long promptNanos, Long generationNanos) {
    }
}
