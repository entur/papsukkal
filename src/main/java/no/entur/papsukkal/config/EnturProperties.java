package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration for the Entur fare-zone NeTEx export API (see CLAUDE.md &gt; Source API).
 *
 * @param url                  the export endpoint
 * @param clientName           value for the required {@code ET-Client-Name} header ({@code <company>-<application>})
 * @param organisationId       optional {@code organisationId} filter; {@code null} means the full ("all") export
 * @param downloadAllowedHosts hosts the {@code 302} redirect may point at before the body is downloaded
 *                             (SSRF guard); defaults to the GCS signed-URL host
 */
@ConfigurationProperties(prefix = "papsukkal.entur")
public record EnturProperties(
        @DefaultValue("https://api.entur.io/distance/netex/fare-zones") String url,
        @DefaultValue("entur-papsukkal") String clientName,
        Integer organisationId,
        @DefaultValue("storage.googleapis.com") List<String> downloadAllowedHosts) {
}
