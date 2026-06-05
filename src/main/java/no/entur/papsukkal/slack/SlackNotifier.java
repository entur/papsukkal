package no.entur.papsukkal.slack;

import no.entur.papsukkal.sync.SyncTrigger;
import no.entur.papsukkal.validation.DatasetCounts;

/**
 * Posts started / success / failure notifications to a Slack incoming webhook. Delivery is
 * best-effort — a Slack failure is logged but never fails the sync (see CLAUDE.md &gt;
 * Notifications). There is no notification on a no-change skip.
 */
public interface SlackNotifier {

    /** 🔄 change detected and validated, publish about to begin. */
    void started(Started event);

    /** ✅ Tiamat returned 2xx and state was written. */
    void success(Success event);

    /** ❌ validation rejected the dataset, retries exhausted, fatal 4xx, or upstream fetch failed. */
    void failure(Failure event);

    record Started(SyncTrigger trigger, String newExportPath, String previousExportPath, DatasetCounts counts) {
    }

    record Success(SyncTrigger trigger, String exportPath, long durationMillis, int attempts) {
    }

    /**
     * @param bypassed true when the publish proceeded despite a failed validation gateway
     *                 ({@code BYPASS_VALIDATION}) — i.e. an authorized-deletion publish
     */
    record Failure(SyncTrigger trigger, String attemptedExportPath, String reason, boolean bypassed) {
    }
}
