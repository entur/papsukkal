package no.entur.papsukkal.entur;

import no.entur.papsukkal.config.EnturProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link FareZoneApiClient} backed by Spring's {@code RestClient}.
 *
 * <p>The Entur endpoint answers with a {@code 302} to a GCS V4 signed URL. We hit it with
 * <strong>redirects disabled</strong> so we can read the {@code Location} header ourselves:
 * <ul>
 *   <li>{@link #currentExportPath()} strips the query string (the signature params regenerate
 *       every request) and returns the stable object path — the change-detection key.</li>
 *   <li>{@link #downloadExport()} re-fetches a fresh signed URL and downloads its body with a
 *       separate client that carries none of the Entur headers (the signed URL is
 *       self-authenticating; nothing should leak to GCS).</li>
 * </ul>
 */
@Component
public class EnturFareZoneApiClient implements FareZoneApiClient {

    private static final Logger log = LoggerFactory.getLogger(EnturFareZoneApiClient.class);

    private final EnturProperties props;
    /** Hits Entur with redirects disabled and the {@code ET-Client-Name} header. */
    private final RestClient enturClient;
    /** Plain client for the absolute GCS signed URL — no Entur headers, follows redirects. */
    private final RestClient downloadClient;

    public EnturFareZoneApiClient(EnturProperties props) {
        this.props = props;

        HttpClient noRedirect = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory enturFactory = new JdkClientHttpRequestFactory(noRedirect);
        enturFactory.setReadTimeout(Duration.ofSeconds(30));
        this.enturClient = RestClient.builder()
                .requestFactory(enturFactory)
                .baseUrl(props.url())
                .defaultHeader("ET-Client-Name", props.clientName())
                .build();

        HttpClient followRedirect = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory downloadFactory = new JdkClientHttpRequestFactory(followRedirect);
        // The NeTEx body is multi-MB; allow a generous read window.
        downloadFactory.setReadTimeout(Duration.ofMinutes(2));
        this.downloadClient = RestClient.builder()
                .requestFactory(downloadFactory)
                .build();
    }

    @Override
    public String currentExportPath() {
        URI location = fetchExportLocation();
        String path = location.getRawPath();
        log.debug("Entur current export path: {}", path);
        return path;
    }

    @Override
    public byte[] downloadExport() {
        URI signedUrl = fetchExportLocation();
        byte[] body = downloadClient.get()
                .uri(signedUrl)
                .retrieve()
                .body(byte[].class);
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Empty NeTEx body from " + signedUrl.getRawPath());
        }
        log.info("Downloaded NeTEx export {} ({} bytes)", signedUrl.getRawPath(), body.length);
        return body;
    }

    /** Fetches the export endpoint (redirects disabled) and returns the {@code Location} URL. */
    private URI fetchExportLocation() {
        ResponseEntity<Void> response = enturClient.get()
                .uri(uri -> {
                    if (props.organisationId() != null) {
                        uri.queryParam("organisationId", props.organisationId());
                    }
                    return uri.build();
                })
                .retrieve()
                .toBodilessEntity();

        HttpStatusCode status = response.getStatusCode();
        URI location = response.getHeaders().getLocation();
        if (!status.is3xxRedirection() || location == null) {
            throw new IllegalStateException(
                    "Expected a 3xx redirect with a Location header from Entur, got " + status
                            + (location == null ? " and no Location" : ""));
        }
        return location;
    }
}
