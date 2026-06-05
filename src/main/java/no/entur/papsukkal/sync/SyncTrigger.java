package no.entur.papsukkal.sync;

/** How this run was triggered — tagged on every Slack notification. */
public enum SyncTrigger {
    SCHEDULED,
    MANUAL,
    FORCE
}
