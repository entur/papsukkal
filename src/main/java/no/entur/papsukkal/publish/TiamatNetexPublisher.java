package no.entur.papsukkal.publish;

import no.entur.papsukkal.config.TiamatProperties;
import no.entur.papsukkal.retry.HttpRetry;
import org.entur.oauth2.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Publishes the full NeTEx dataset to Tiamat's synchronous import endpoint.
 *
 * <p>The import is synchronous, so the read timeout is generous (the response blocks until Tiamat
 * finishes importing) and a {@code 2xx} confirms the import completed. Transient failures
 * (5xx / 429 / IO) are retried with exponential backoff via
 * {@link no.entur.papsukkal.retry.HttpRetry}; fatal {@code 4xx} fail fast. On exhausted retries or
 * a fatal error the original exception propagates, and the caller leaves state unadvanced.
 */
@Component
public class TiamatNetexPublisher implements TiamatPublisher {

    private static final Logger log = LoggerFactory.getLogger(TiamatNetexPublisher.class);

    private final TiamatProperties props;
    private final TokenService tokenService;
    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    public TiamatNetexPublisher(TiamatProperties props, TokenService tokenService) {
        this.props = props;
        this.tokenService = tokenService;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // Synchronous import: the response blocks until Tiamat finishes, so allow a long read window.
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.url())
                .build();

        this.retryTemplate = HttpRetry.transientHttpErrors(props.retry());
    }

    @Override
    public void publish(byte[] netexXml) {
        long start = System.nanoTime();
        // On exhausted retries or a fatal (non-retryable) failure, the original cause propagates
        // and the caller leaves state unadvanced.
        HttpRetry.execute(retryTemplate, () -> {
            doPost(netexXml);
            return null;
        });
        log.info("Tiamat import completed in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private void doPost(byte[] netexXml) {
        String token = tokenService.getToken();
        restClient.post()
                .uri(uri -> {
                    if (StringUtils.hasText(props.importType())) {
                        uri.queryParam("importType", props.importType());
                    }
                    return uri.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_XML)
                .body(netexXml)
                .retrieve()
                .toBodilessEntity();
    }
}
