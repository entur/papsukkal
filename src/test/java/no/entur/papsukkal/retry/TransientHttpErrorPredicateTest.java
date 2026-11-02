package no.entur.papsukkal.retry;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientHttpErrorPredicateTest {

    private final TransientHttpErrorPredicate predicate = new TransientHttpErrorPredicate();

    @Test
    void retries_on_5xx() {
        assertThat(predicate.test(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "x", null, null, null))).isTrue();
    }

    @Test
    void retries_on_429() {
        assertThat(predicate.test(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "x", null, null, null))).isTrue();
    }

    @Test
    void retries_on_connection_problems() {
        assertThat(predicate.test(new ResourceAccessException("conn refused", new IOException()))).isTrue();
    }

    @Test
    void fails_fast_on_other_4xx() {
        assertThat(predicate.test(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "x", null, null, null))).isFalse();
        assertThat(predicate.test(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "x", null, null, null))).isFalse();
        assertThat(predicate.test(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "x", null, null, null))).isFalse();
    }

    @Test
    void does_not_retry_unrelated_exceptions() {
        assertThat(predicate.test(new IllegalStateException("boom"))).isFalse();
    }
}
