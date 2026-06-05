package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the GCS state bucket (see CLAUDE.md &gt; State Storage).
 *
 * @param projectId  GCP project id (credentials resolved via Workload Identity / ADC)
 * @param bucket     the state bucket name
 * @param objectName the state object path within the bucket
 */
@ConfigurationProperties(prefix = "papsukkal.gcs")
public record GcsProperties(
        String projectId,
        String bucket,
        @DefaultValue("sync-state/last-sync.json") String objectName) {
}
