package docrag;

/**
 * Stable safe failure classification. Never carries a provider response or an unchecked answer.
 */
public final class ResearchFailure extends IllegalStateException {
    private final Code code;

    public ResearchFailure(Code code) {
        super("Research withheld: " + code.name());
        this.code = java.util.Objects.requireNonNull(code);
    }

    public static Code classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof ResearchFailure failure) return failure.code;
        return error instanceof InterruptedException || Thread.currentThread().isInterrupted()
                ? Code.CANCELLED : Code.MODEL_UNAVAILABLE;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT, RESOURCE_LIMIT, INVALID_RESPONSE, EXTRACTION_FAILED, UNSUPPORTED_CLAIM,
        NUMERICAL_VALIDATION, METADATA_AMBIGUOUS, VERIFIER_UNAVAILABLE, MODEL_UNAVAILABLE, CANCELLED
    }
}
