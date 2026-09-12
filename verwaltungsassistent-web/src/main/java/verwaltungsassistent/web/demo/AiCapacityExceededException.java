package verwaltungsassistent.web.demo;

/**
 * Thrown when the demo AI concurrency guard rejects an expensive operation
 * because the global or per-user limit is currently reached.
 */
public class AiCapacityExceededException extends RuntimeException {

    public AiCapacityExceededException() {
        super(DemoAiConcurrencyGuard.CAPACITY_MESSAGE);
    }
}
