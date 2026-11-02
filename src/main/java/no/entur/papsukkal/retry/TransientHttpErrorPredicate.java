package no.entur.papsukkal.retry;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Predicate;

/**
 * Classifies an HTTP failure as transient (worth retrying) vs fatal (fail fast), per
 * CLAUDE.md &gt; Error Handling, Retry &amp; Notifications. Shared by the Entur fetch and the Tiamat
 * publish so both treat an upstream blip identically.
 *
 * <p>Transient: HTTP {@code 5xx} ({@link HttpServerErrorException}), connection/read problems
 * ({@link ResourceAccessException}), and HTTP {@code 429}. Note {@code 429} arrives as
 * {@link HttpClientErrorException} (a {@code 4xx} type), so it needs an explicit check rather than
 * a blanket "retry all 5xx, fail all 4xx" rule. Everything else (400/401/403…) is fatal.
 */
public class TransientHttpErrorPredicate implements Predicate<Throwable> {

    private static final int TOO_MANY_REQUESTS = 429;

    @Override
    public boolean test(Throwable t) {
        if (t instanceof HttpServerErrorException) {
            return true;
        }
        if (t instanceof ResourceAccessException) {
            return true;
        }
        if (t instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == TOO_MANY_REQUESTS;
        }
        return false;
    }
}