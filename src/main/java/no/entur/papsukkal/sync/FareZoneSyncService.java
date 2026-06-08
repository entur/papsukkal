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

        String currentPath;
        try {
            currentPath = enturClient.currentExportPath();
        } catch (RuntimeException e) {
            log.error("Entur fetch failed during change check; state not advanced", e);
            return fail(options.trigger(), "(unknown — change check failed)",
                    "Entur fetch failed: " + e.getMessage());
        }

        SyncState baseline = stateStore.read();

        boolean changed = options.force()
                || baseline == null
                || !baseline.exportPath().equals(currentPath);
        if (!changed) {
            // Emit the current (unchanged) counts every run so Cloud Logging has a daily heartbeat
            // of the live fare-zone / group magnitude even when nothing is published.
            var skipLog = log.atInfo()
                    .setMessage("No change detected; skipping publish")
                    .addKeyValue("outcome", "SKIPPED")
                    .addKeyValue("trigger", options.trigger())
                    .addKeyValue("exportPath", currentPath);
            if (baseline != null) {
                skipLog = skipLog
                        .addKeyValue("fareZoneCount", baseline.fareZoneCount())
                        .addKeyValue("groupCount", baseline.groupCount())
                        .addKeyValue("memberCount", baseline.memberCount());
            }
            skipLog.log();
            return SyncOutcome.SKIPPED;
        }

        byte[] body;
        try {
            body = enturClient.downloadExport();
        } catch (RuntimeException e) {
            log.error("Entur download failed; state not advanced", e);
            return fail(options.trigger(), currentPath,
                    "Entur download failed: " + e.getMessage());
        }
        ValidationResult validation = validator.validate(new ByteArrayInputStream(body), baseline);

        if (!validation.passed()) {
            String reason = String.join("; ", validation.failures());
            if (options.bypassValidation()) {
                log.warn("Validation FAILED but BYPASS_VALIDATION is set — proceeding. Under external "
                        + "versioning this authorizes Tiamat to delete zones missing from the delivery. Reasons: {}", reason);
            } else {
                log.error("Validation gateway rejected dataset; state not advanced. Reasons: {}", reason);
                return fail(options.trigger(), currentPath, reason);
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
        // Counts are emitted as structured fields (jsonPayload.fareZoneCount, …) so they are queryable
        // in Cloud Logging and can back a log-based metric — see CLAUDE.md › Observability.
        log.atInfo()
                .setMessage("Published fare-zone export to Tiamat")
                .addKeyValue("outcome", "PUBLISHED")
                .addKeyValue("trigger", options.trigger())
                .addKeyValue("exportPath", currentPath)
                .addKeyValue("fareZoneCount", newState.fareZoneCount())
                .addKeyValue("groupCount", newState.groupCount())
                .addKeyValue("memberCount", newState.memberCount())
                .addKeyValue("durationMs", durationMs)
                .log();
        return SyncOutcome.PUBLISHED;
    }

    /** Emits a ❌ notification (state never advanced here) and returns {@link SyncOutcome#FAILED}. */
    private SyncOutcome fail(SyncTrigger trigger, String attemptedPath, String reason) {
        slack.failure(new SlackNotifier.Failure(trigger, attemptedPath, reason, false));
        return SyncOutcome.FAILED;
    }
}
