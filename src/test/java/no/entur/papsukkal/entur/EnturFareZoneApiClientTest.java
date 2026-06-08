package no.entur.papsukkal.entur;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import no.entur.papsukkal.config.EnturProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real {@code RestClient} + redirect-disabled behaviour against a tiny in-JVM
 * HTTP server (no external dependency), mimicking Entur's {@code 302 → signed GCS URL}.
 */
class EnturFareZoneApiClientTest {

    private static final String EXPORT_PATH =
            "/ent-gcs-fare-zone-netex-exporter-prd-001/all/v28/2026-01-01_open/all-farezones.xml";
    private static final byte[] NETEX_BODY =
            "<PublicationDelivery>ok</PublicationDelivery>".getBytes(UTF_8);

    private HttpServer server;
    private String baseUrl;
    private volatile boolean sendRedirect = true;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/fare-zones")) {
                if (sendRedirect) {
                    // Absolute signed-URL style Location with a regenerating query string.
                    exchange.getResponseHeaders().set("Location",
                            baseUrl + EXPORT_PATH + "?X-Goog-Signature=abc&X-Goog-Expires=900");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1); // no Location → error path
                }
            } else if (path.equals(EXPORT_PATH)) {
                exchange.sendResponseHeaders(200, NETEX_BODY.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(NETEX_BODY);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private EnturFareZoneApiClient client() {
        // The stub redirects to localhost, so allow that host instead of the GCS default.
        return new EnturFareZoneApiClient(
                new EnturProperties(baseUrl + "/fare-zones", "test-client", null, List.of("localhost")));
    }

    private EnturFareZoneApiClient clientAllowingOnly(String host) {
        return new EnturFareZoneApiClient(
                new EnturProperties(baseUrl + "/fare-zones", "test-client", null, List.of(host)));
    }

    @Test
    void currentExportPath_returns_redirect_path_with_query_stripped() {
        assertThat(client().currentExportPath()).isEqualTo(EXPORT_PATH);
    }

    @Test
    void downloadExport_follows_redirect_and_returns_body() {
        assertThat(client().downloadExport()).isEqualTo(NETEX_BODY);
    }

    @Test
    void throws_when_entur_returns_no_redirect() {
        sendRedirect = false;
        assertThatThrownBy(() -> client().currentExportPath())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void rejects_redirect_to_a_host_not_on_the_allowlist() {
        // Stub redirects to localhost, but only storage.googleapis.com is allowed → reject before download.
        assertThatThrownBy(() -> clientAllowingOnly("storage.googleapis.com").downloadExport())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in the allowed download hosts");
    }
}
