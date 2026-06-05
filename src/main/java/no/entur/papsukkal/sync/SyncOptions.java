package no.entur.papsukkal.sync;

/**
 * Per-run options derived from env vars by {@code SyncRunner}.
 *
 * @param force            skip change detection and publish unconditionally ({@code FORCE})
 * @param bypassValidation skip the validation gateway ({@code BYPASS_VALIDATION}) — under external
 *                         versioning this authorizes Tiamat to delete zones missing from the delivery
 * @param trigger          how the run was triggered, for notification tagging
 */
public record SyncOptions(boolean force, boolean bypassValidation, SyncTrigger trigger) {
}
