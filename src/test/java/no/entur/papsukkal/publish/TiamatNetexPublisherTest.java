package no.entur.papsukkal.publish;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import no.entur.papsukkal.config.TiamatProperties;
import no.entur.papsukkal.config.TiamatProperties.OAuth;
import no.entur.papsukkal.config.TiamatProperties.Retry;
import org.entur.oauth2.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TiamatNetexPublisherTest {

    private static final String PATH = "/services/stop_places/netex";
    private static final byte[] NETEX = "<PublicationDelivery/>".getBytes();

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String lastAuth;
    private volatile String lastContentType;
    private volatile String lastQuery;

    private final TokenService tokenService = mock(TokenService.class);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(PATH, this::handle);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + PATH;
        when(tokenService.getToken()).thenReturn("test-token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastQuery = exchange.getRequestURI().getRawQuery();
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(responseStatus, -1);
        exchange.close();
    }

    /** Fast retry timings so the 5xx test doesn't actually back off for seconds. */
    private TiamatNetexPublisher publisher(String importType) {
        TiamatProperties props = new TiamatProperties(
                baseUrl,
                importType,
                new OAuth("tiamat", ""),
                new Retry(2, Duration.ofMillis(1), 1.0, Duration.ofMillis(2), Duration.ZERO));
        return new TiamatNetexPublisher(props, tokenService);
    }

    @Test
    void posts_with_bearer_token_xml_content_type_and_import_type() {
        responseStatus = 200;

        publisher("MERGE").publish(NETEX);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(lastAuth).isEqualTo("Bearer test-token");
        assertThat(lastContentType).startsWith("application/xml");
        assertThat(lastQuery).contains("importType=MERGE");
    }

    @Test
    void omits_import_type_when_blank() {
        responseStatus = 200;

        publisher("").publish(NETEX);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(lastQuery).isNull();
    }

    @Test
    void fails_fast_on_4xx_without_retrying() {
        responseStatus = 400;

        assertThatThrownBy(() -> publisher("MERGE").publish(NETEX))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retries_then_fails_on_5xx() {
        responseStatus = 503;

        assertThatThrownBy(() -> publisher("MERGE").publish(NETEX))
                .isInstanceOf(RuntimeException.class);
        // maxRetries=2 => 3 attempts total
        assertThat(requests.get()).isEqualTo(3);
    }
}
