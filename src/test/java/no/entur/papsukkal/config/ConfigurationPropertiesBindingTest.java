package no.entur.papsukkal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds each {@code @ConfigurationProperties} record with Spring's {@code Binder} so that
 * {@code @DefaultValue} defaults and relaxed-binding property names are actually exercised — a
 * property-name typo or a default drift (which the hand-constructed unit tests would miss) fails here.
 */
class ConfigurationPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void tiamat_defaults_bind() {
        runner.withUserConfiguration(TiamatConfig.class).run(ctx -> {
            TiamatProperties p = ctx.getBean(TiamatProperties.class);
            assertThat(p.importType()).isEmpty();
            assertThat(p.oauth().clientRegistrationId()).isEqualTo("tiamat");
            assertThat(p.oauth().audience()).isEmpty();
            assertThat(p.retry().maxRetries()).isEqualTo(2);
            assertThat(p.retry().multiplier()).isEqualTo(3.0);
            assertThat(p.retry().delay()).hasSeconds(5);
            assertThat(p.retry().maxDelay()).hasSeconds(45);
        });
    }

    @Test
    void tiamat_overrides_bind_with_relaxed_binding() {
        runner.withUserConfiguration(TiamatConfig.class)
                .withPropertyValues(
                        "papsukkal.tiamat.url=https://tiamat.test/services/stop_places/netex",
                        "papsukkal.tiamat.import-type=MERGE",
                        "papsukkal.tiamat.retry.max-retries=5")
                .run(ctx -> {
                    TiamatProperties p = ctx.getBean(TiamatProperties.class);
                    assertThat(p.url()).isEqualTo("https://tiamat.test/services/stop_places/netex");
                    assertThat(p.importType()).isEqualTo("MERGE");
                    assertThat(p.retry().maxRetries()).isEqualTo(5);
                });
    }

    @Test
    void entur_defaults_including_download_host_allowlist() {
        runner.withUserConfiguration(EnturConfig.class).run(ctx -> {
            EnturProperties p = ctx.getBean(EnturProperties.class);
            assertThat(p.url()).isEqualTo("https://api.entur.io/distance/netex/fare-zones");
            assertThat(p.clientName()).isEqualTo("entur-papsukkal");
            assertThat(p.organisationId()).isNull();
            assertThat(p.downloadAllowedHosts()).containsExactly("storage.googleapis.com");
        });
    }

    @Test
    void entur_download_allowlist_binds_a_comma_list() {
        runner.withUserConfiguration(EnturConfig.class)
                .withPropertyValues("papsukkal.entur.download-allowed-hosts=storage.googleapis.com,localhost")
                .run(ctx -> assertThat(ctx.getBean(EnturProperties.class).downloadAllowedHosts())
                        .containsExactly("storage.googleapis.com", "localhost"));
    }

    @Test
    void validation_gcs_and_slack_defaults_and_relaxed_binding() {
        runner.withUserConfiguration(MiscConfig.class)
                .withPropertyValues(
                        "papsukkal.validation.fare-zone.min-count=400",
                        "papsukkal.validation.group.min-count=25")
                .run(ctx -> {
                    ValidationProperties v = ctx.getBean(ValidationProperties.class);
                    assertThat(v.fareZone().minCount()).isEqualTo(400);
                    assertThat(v.group().minCount()).isEqualTo(25);
                    assertThat(v.maxShrinkPct()).isEqualTo(10.0); // default

                    assertThat(ctx.getBean(GcsProperties.class).objectName()).isEqualTo("sync-state/last-sync.json");
                    assertThat(ctx.getBean(SlackProperties.class).webhookUrl()).isEmpty();
                });
    }

    @EnableConfigurationProperties(TiamatProperties.class)
    @Configuration
    static class TiamatConfig {
    }

    @EnableConfigurationProperties(EnturProperties.class)
    @Configuration
    static class EnturConfig {
    }

    @EnableConfigurationProperties({ValidationProperties.class, GcsProperties.class, SlackProperties.class})
    @Configuration
    static class MiscConfig {
    }
}
