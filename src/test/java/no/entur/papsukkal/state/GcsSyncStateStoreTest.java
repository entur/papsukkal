package no.entur.papsukkal.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.entur.papsukkal.config.GcsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rutebanken.helper.storage.repository.BlobStoreRepository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcsSyncStateStoreTest {

    private static final String OBJECT = "sync-state/last-sync.json";

    private final BlobStoreRepository blobStore = mock(BlobStoreRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GcsSyncStateStore store =
            new GcsSyncStateStore(blobStore, objectMapper, new GcsProperties("proj", "bucket", OBJECT));

    @Test
    void read_returns_null_when_object_absent() {
        when(blobStore.exist(OBJECT)).thenReturn(false);

        assertThat(store.read()).isNull();
    }

    @Test
    void read_parses_existing_state() {
        String json = """
                {"exportPath":"/all/v28/all-farezones.xml","fareZoneCount":485,
                 "groupCount":29,"memberCount":639,"publishedAt":"2026-01-01T03:00:00Z"}
                """;
        when(blobStore.exist(OBJECT)).thenReturn(true);
        when(blobStore.getBlob(OBJECT)).thenReturn(new ByteArrayInputStream(json.getBytes(UTF_8)));

        SyncState state = store.read();

        assertThat(state).isEqualTo(new SyncState(
                "/all/v28/all-farezones.xml", 485, 29, 639, "2026-01-01T03:00:00Z"));
    }

    @Test
    void write_serializes_state_as_json_to_the_object() throws Exception {
        SyncState state = new SyncState("/all/v29/all-farezones.xml", 486, 29, 640, "2026-02-01T03:00:00Z");
        when(blobStore.uploadBlob(eq(OBJECT), any(InputStream.class), eq("application/json"))).thenReturn(7L);

        store.write(state);

        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(blobStore).uploadBlob(eq(OBJECT), body.capture(), eq("application/json"));

        SyncState roundTripped = objectMapper.readValue(body.getValue(), SyncState.class);
        assertThat(roundTripped).isEqualTo(state);
    }
}
