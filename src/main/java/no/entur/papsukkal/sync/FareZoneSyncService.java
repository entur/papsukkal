package no.entur.papsukkal.sync;

import no.entur.papsukkal.entur.FareZoneApiClient;
import no.entur.papsukkal.publish.TiamatPublisher;
import no.entur.papsukkal.slack.SlackNotifier;
import no.entur.papsukkal.state.SyncState;
import no.entur.papsukkal.state.SyncStateStore;
import no.entur.papsukkal.validation.DatasetValidator;
import no.entur.papsukkal.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;

/**
 * Orchestrates one sync run: fetch the Entur export path, detect change, download + validate the
 * NeTEx body, publish to Tiamat, and advance state — emitting Slack notifications along the way.
 *
 * <p>State is written <strong>only</strong> after a successful publish, so any failure (validation
 * reject, publish failure) leaves the baseline untouched and the next run re-attempts.
 */
@Service
public class FareZoneSyncService {

    private static final Logger log = LoggerFactory.getLogger(FareZoneSyncService.class);

    private final FareZoneApiClient enturClient;
    private final DatasetValidator validator;
    private final TiamatPublisher publisher;
    private final SyncStateStore stateStore;
    private final SlackNotifier slack;

    public FareZoneSyncService(FareZoneApiClient enturClient,
                               DatasetValidator validator,
                               TiamatPublisher publisher,
                               SyncStateStore stateStore,
                               SlackNotifier slack) {
        this.enturClient = enturClient;
        this.validator = validator;
        this.publisher = publisher;
        this.stateStore = stateStore;
        this.slack = slack;
    }

    public SyncOutcome run(SyncOptions options) {
        long startNanos = System.nanoTime();

        String currentPath = enturClient.currentExportPath();
        SyncState baseline = stateStore.read();

        boolean changed = options.force()
                || baseline == null
                || !baseline.exportPath().equals(currentPath);
        if (!changed) {
            log.info("No change detected ({}); skipping publish", currentPath);
            return SyncOutcome.SKIPPED;
        }

        byte[] body = enturClient.downloadExport();
        ValidationResult validation = validator.validate(new ByteArrayInputStream(body), baseline);

        if (!validation.passed()) {
            String reason = String.join("; ", validation.failures());
            if (options.bypassValidation()) {
                log.warn("Validation FAILED but BYPASS_VALIDATION is set — proceeding. Under external "
                        + "versioning this authorizes Tiamat to delete zones missing from the delivery. Reasons: {}", reason);
            } else {
                log.error("Validation gateway rejected dataset; state not advanced. Reasons: {}", reason);
                slack.failure(new SlackNotifier.Failure(options.trigger(), currentPath, reason, false));
                return SyncOutcome.FAILED;
            }
        }

        slack.started(new SlackNotifier.Started(
                options.trigger(),
                currentPath,
                baseline == null ? null : baseline.exportPath(),
                validation.counts()));

        try {
            publisher.publish(body);
        } catch (RuntimeException e) {
            log.error("Publish to Tiamat failed; state not advanced", e);
            slack.failure(new SlackNotifier.Failure(
                    options.trigger(), currentPath, e.getMessage(), options.bypassValidation()));
            return SyncOutcome.FAILED;
        }

        SyncState newState = new SyncState(
                currentPath,
                validation.counts().fareZoneCount(),
                validation.counts().groupCount(),
                validation.counts().memberCount(),
                Instant.now().toString());
        stateStore.write(newState);

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        slack.success(new SlackNotifier.Success(options.trigger(), currentPath, durationMs, 1));
        log.info("Published export {} ({} zones, {} groups) in {} ms",
                currentPath, newState.fareZoneCount(), newState.groupCount(), durationMs);
        return SyncOutcome.PUBLISHED;
    }
}
