package no.entur.papsukkal.config;

import org.entur.oauth2.OAuth2TokenService;
import org.entur.oauth2.TokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the OAuth2 client-credentials {@link TokenService} used to authenticate to Tiamat.
 *
 * <p>The token is minted from the {@code spring.security.oauth2.client} registration named by
 * {@code papsukkal.tiamat.oauth.client-registration-id}. This is the machine-to-machine client
 * that must be provisioned (see CLAUDE.md &gt; Open Items) — separate from the GCP Workload Identity
 * used for state storage and from the Entur source API's {@code ET-Client-Name} header.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuth2Configuration {

    @Bean
    public TokenService tiamatTokenService(OAuth2ClientProperties clientProperties, TiamatProperties props) {
        OAuth2TokenService.Builder builder = new OAuth2TokenService.Builder()
                .withOAuth2ClientProperties(clientProperties)
                .withClientRegistrationId(props.oauth().clientRegistrationId());
        if (!props.oauth().audience().isBlank()) {
            builder.withAudience(props.oauth().audience());
        }
        return builder.build();
    }
}
