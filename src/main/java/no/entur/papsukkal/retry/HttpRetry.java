package no.entur.papsukkal.retry;

import no.entur.papsukkal.config.RetryProperties;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;

/**
 * Builds and runs a {@link RetryTemplate} that retries only transient HTTP failures
 * ({@link TransientHttpErrorPredicate}) with exponential backoff. Shared by the Entur fetch and the
 * Tiamat publish so both classify and back off identically (see CLAUDE.md &gt; Error Handling, Retry).
 */
public final class HttpRetry {

    private HttpRetry() {
    }

    /** A template that retries transient HTTP errors per {@code props}; fatal errors fail fast. */
    public static RetryTemplate transientHttpErrors(RetryProperties props) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(props.maxRetries())
                .delay(props.delay())
                .multiplier(props.multiplier())
                .maxDelay(props.maxDelay())
                .jitter(props.jitter())
                .predicate(new TransientHttpErrorPredicate())
                .build();
        return new RetryTemplate(policy);
    }

    /**
     * Runs {@code operation} under {@code template}, unwrapping the {@link RetryException} on
     * exhausted retries (or a fatal, non-retryable error) so the caller sees the original cause —
     * e.g. the {@code HttpServerErrorException} that an Entur {@code 500} or a Tiamat blip surfaced.
     */
    public static <T> T execute(RetryTemplate template, Retryable<T> operation) {
        try {
            return template.execute(operation);
        } catch (RetryException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Operation failed after retries: " + cause.getMessage(), cause);
        }
    }
}