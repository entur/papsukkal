package no.entur.papsukkal.state;

import org.springframework.stereotype.Component;

/**
 * Skeleton {@link SyncStateStore} backed by GCP Cloud Storage.
 *
 * <p>TODO: implement against {@code sync-state/last-sync.json} using
 * {@code spring-cloud-gcp-starter-storage} (Workload Identity, no credentials to manage).
 * Serialize/deserialize {@link SyncState} as JSON with Jackson; treat a missing object as
 * {@code null} (first run).
 */
@Component
public class GcsSyncStateStore implements SyncStateStore {

    @Override
    public SyncState read() {
        throw new UnsupportedOperationException("GcsSyncStateStore not yet implemented");
    }

    @Override
    public void write(SyncState state) {
        throw new UnsupportedOperationException("GcsSyncStateStore not yet implemented");
    }
}
