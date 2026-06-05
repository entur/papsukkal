package no.entur.papsukkal.state;

/**
 * Reads/writes the last-good {@link SyncState} in the GCS state bucket.
 *
 * <p>{@code read()} returns {@code null} on first run (object absent). {@code write()} is called
 * <strong>only</strong> after a successful publish, so the stored state is always last-known-good.
 */
public interface SyncStateStore {

    /** @return the last persisted state, or {@code null} if none exists yet. */
    SyncState read();

    void write(SyncState state);
}
