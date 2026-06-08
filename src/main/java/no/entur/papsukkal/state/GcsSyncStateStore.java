package no.entur.papsukkal.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.entur.papsukkal.config.GcsProperties;
import org.rutebanken.helper.storage.repository.BlobStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * {@link SyncStateStore} backed by GCP Cloud Storage via the Entur {@code storage-gcp-gcs} helper.
 *
 * <p>Reads/writes {@code sync-state/last-sync.json}. A missing object means first run ({@code read()}
 * returns {@code null}); {@code write()} is invoked only after a successful publish, so the stored
 * state is always last-known-good. The object is small, so the whole document is read/written at once.
 *
 * <p>Uses its own {@link ObjectMapper} for this one small record rather than injecting one — Spring
 * Boot 4 auto-configures a Jackson 3 ({@code tools.jackson}) mapper, so there is no Jackson 2
 * {@code ObjectMapper} bean to wire here.
 */
@Component
public class GcsSyncStateStore implements SyncStateStore {

    private static final Logger log = LoggerFactory.getLogger(GcsSyncStateStore.class);
    private static final String CONTENT_TYPE = "application/json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BlobStoreRepository blobStore;
    private final String objectName;

    public GcsSyncStateStore(BlobStoreRepository blobStore, GcsProperties props) {
        this.blobStore = blobStore;
        this.objectName = props.objectName();
    }

    @Override
    public SyncState read() {
        if (!blobStore.exist(objectName)) {
            log.info("No sync state at {} — treating as first run", objectName);
            return null;
        }
        try (InputStream in = blobStore.getBlob(objectName)) {
            if (in == null) {
                return null;
            }
            SyncState state = OBJECT_MAPPER.readValue(in, SyncState.class);
            log.debug("Loaded sync state: {}", state);
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read sync state from " + objectName, e);
        }
    }

    @Override
    public void write(SyncState state) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(state);
            long generation = blobStore.uploadBlob(
                    objectName, new ByteArrayInputStream(json), CONTENT_TYPE);
            log.info("Wrote sync state to {} ({} bytes, generation {})", objectName, json.length, generation);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write sync state to " + objectName, e);
        }
    }
}
