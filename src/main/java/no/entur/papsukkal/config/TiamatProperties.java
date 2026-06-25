package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for publishing to Tiamat's synchronous NeTEx import endpoint
 * (see CLAUDE.md &gt; Target System — Tiamat, and Error Handling, Retry &amp; Notifications).
 *
 * @param url        the import endpoint, e.g. {@code https://<host>/services/stop_places/netex}
 * @param importType {@code importType} query param; blank omits it (Tiamat uses its configured
 *                   default), or set {@code MERGE}. Note: prune-on-missing is governed by Tiamat's
 *                   {@code externalVersioning}, not by this param.
 * @param oauth      OAuth2 client-credentials settings for the bearer token
 * @param retry      transient-failure retry tuning (shared shape with the Entur fetch)
 */
@ConfigurationProperties(prefix = "papsukkal.tiamat")
public record TiamatProperties(
        String url,
        @DefaultValue("") String importType,
        @DefaultValue OAuth oauth,
        @DefaultValue RetryProperties retry) {

    /**
     * @param clientRegistrationId matches a {@code spring.security.oauth2.client.registration.<id>}
     * @param audience             optional token audience; blank omits it
     */
    public record OAuth(
            @DefaultValue("tiamat") String clientRegistrationId,
            @DefaultValue("") String audience) {
    }
}
