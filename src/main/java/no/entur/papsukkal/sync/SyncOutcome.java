package no.entur.papsukkal.sync;

/** Result of a single sync run. {@code FAILED} maps to a non-zero JVM exit. */
public enum SyncOutcome {
    /** No change detected — nothing published (the common, silent case). */
    SKIPPED,
    /** Dataset validated and imported by Tiamat; state advanced. */
    PUBLISHED,
    /** Validation rejected the dataset, or the publish failed; state NOT advanced. */
    FAILED
}
