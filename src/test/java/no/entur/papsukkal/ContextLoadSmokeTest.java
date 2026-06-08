package no.entur.papsukkal;

import no.entur.papsukkal.entur.EnturFareZoneApiClient;
import no.entur.papsukkal.publish.TiamatNetexPublisher;
import no.entur.papsukkal.slack.WebhookSlackNotifier;
import no.entur.papsukkal.state.GcsSyncStateStore;
import no.entur.papsukkal.sync.FareZoneSyncService;
import no.entur.papsukkal.validation.DatasetValidator;
import org.entur.oauth2.TokenService;
import org.junit.jupiter.api.Test;
import org.rutebanken.helper.storage.repository.BlobStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring context-load smoke test: proves the real beans wire and {@code @ConfigurationProperties}
 * bind under the actual {@code @SpringBootApplication}.
 *
 * <p>Three beans are replaced with mocks so the context loads deterministically without external
 * infrastructure:
 * <ul>
 *   <li>{@code FareZoneSyncService} — so {@code SyncRunner}'s {@code ApplicationRunner} does no real
 *       work (no network to Entur) on startup;</li>
 *   <li>{@code TokenService} / {@code BlobStoreRepository} — so {@code OAuth2Configuration} /
 *       {@code GcsConfiguration} don't build real OAuth/GCS clients (no ADC, no credentials).</li>
 * </ul>
 * Every other component bean is created for real, so a broken constructor wiring or a configuration
 * binding failure surfaces as a context-load failure here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Values normally supplied at deploy time (OAuth creds via the ExternalSecret env vars; the
        // Tiamat URL via the mounted ConfigMap) — provided here so the context binds and loads.
        properties = {
                "spring.security.oauth2.client.registration.tiamat.client-id=test-client-id",
                "spring.security.oauth2.client.registration.tiamat.client-secret=test-client-secret",
                "spring.security.oauth2.client.provider.tiamat.token-uri=https://example.test/oauth/token",
                "papsukkal.tiamat.url=https://tiamat.test/services/stop_places/netex",
        })
class ContextLoadSmokeTest {

    @MockitoBean
    FareZoneSyncService syncService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    BlobStoreRepository blobStoreRepository;

    @Autowired
    ApplicationContext context;

    @Test
    void context_loads_and_wires_the_core_beans() {
        assertThat(context.getBean(DatasetValidator.class)).isNotNull();
        assertThat(context.getBean(EnturFareZoneApiClient.class)).isNotNull();
        assertThat(context.getBean(TiamatNetexPublisher.class)).isNotNull();
        assertThat(context.getBean(GcsSyncStateStore.class)).isNotNull();
        assertThat(context.getBean(WebhookSlackNotifier.class)).isNotNull();
        assertThat(context.getBean(SyncRunner.class)).isNotNull();
    }
}
