package no.entur.papsukkal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import no.entur.papsukkal.config.EnturProperties;
import no.entur.papsukkal.config.GcsProperties;
import no.entur.papsukkal.config.TiamatProperties;
import no.entur.papsukkal.config.TiamatProperties.OAuth;
import no.entur.papsukkal.config.TiamatProperties.Retry;
import no.entur.papsukkal.config.ValidationProperties;
import no.entur.papsukkal.config.ValidationProperties.Floor;
import no.entur.papsukkal.entur.EnturFareZoneApiClient;
import no.entur.papsukkal.publish.TiamatNetexPublisher;
import no.entur.papsukkal.slack.SlackNotifier;
import no.entur.papsukkal.state.GcsSyncStateStore;
import no.entur.papsukkal.state.SyncState;
import no.entur.papsukkal.sync.FareZoneSyncService;
import no.entur.papsukkal.sync.SyncOptions;
import no.entur.papsukkal.sync.SyncOutcome;
import no.entur.papsukkal.sync.SyncTrigger;
import no.entur.papsukkal.validation.DatasetValidator;
import no.entur.papsukkal.validation.NetexDatasetInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rutebanken.helper.storage.repository.BlobStoreRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the full sync flow with the <em>real</em> components wired together
 * (FareZoneSyncService + EnturFareZoneApiClient + DatasetValidator + GcsSyncStateStore +
 * TiamatNetexPublisher). Only the true external boundaries are stubbed: a local HTTP server for
 * Entur (302 → NeTEx body), another for Tiamat, an in-memory blob store, a static OAuth token, and
 * a recording notifier. This is the one test that exercises real validation on real NeTEx XML and
 * confirms the bytes actually reach Tiamat.
 */
class SyncEndToEndTest {

    private static final String EXPORT_PATH = "/export/all/v28/all-farezones.xml";
    private static final String STATE_OBJECT = "sync-state/last-sync.json";

    private HttpServer entur;
    private HttpServer tiamat;
    private String enturBaseUrl;

    private byte[] enturBody;
    private final AtomicInteger tiamatRequests = new AtomicInteger();
    private final AtomicReference<byte[]> tiamatBody = new AtomicReference<>();
    private final AtomicReference<String> tiamatAuth = new AtomicReference<>();

    private final AtomicReference<byte[]> storedState = new AtomicReference<>();
    private RecordingSlackNotifier slack;
    private FareZoneSyncService service;

    @BeforeEach
    void setUp() throws IOException {
        enturBody = fixture("valid-farezones.xml");

        entur = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        entur.createContext("/", this::handleEntur);
        entur.start();
        enturBaseUrl = "http://localhost:" + entur.getAddress().getPort();

        tiamat = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        tiamat.createContext("/services/stop_places/netex", this::handleTiamat);
        tiamat.start();
        String tiamatUrl = "http://localhost:" + tiamat.getAddress().getPort() + "/services/stop_places/netex";

        service = buildService(tiamatUrl);
    }

    @AfterEach
    void tearDown() {
        entur.stop(0);
        tiamat.stop(0);
    }

    private FareZoneSyncService buildService(String tiamatUrl) {
        DatasetValidator validator = new DatasetValidator(
                new NetexDatasetInspector(),
                // floors low enough for the 10-zone / 2-group fixture to pass
                new ValidationProperties(new Floor(5), new Floor(1), 10.0));

        EnturFareZoneApiClient enturClient = new EnturFareZoneApiClient(
                new EnturProperties(enturBaseUrl + "/fare-zones", "test-client", null));

        TiamatNetexPublisher publisher = new TiamatNetexPublisher(
                new TiamatProperties(tiamatUrl, "MERGE", new OAuth("tiamat", ""),
                        new Retry(2, Duration.ofMillis(1), 1.0, Duration.ofMillis(2), Duration.ZERO)),
                () -> "e2e-token");

        GcsSyncStateStore stateStore = new GcsSyncStateStore(
                inMemoryBlobStore(), new ObjectMapper(), new GcsProperties("proj", "bucket", STATE_OBJECT));

        slack = new RecordingSlackNotifier();
        return new FareZoneSyncService(enturClient, validator, publisher, stateStore, slack);
    }

    @Test
    void full_sync_validates_publishes_to_tiamat_and_advances_state() throws Exception {
        SyncOutcome outcome = service.run(new SyncOptions(false, false, SyncTrigger.SCHEDULED));

        assertThat(outcome).isEqualTo(SyncOutcome.PUBLISHED);

        // The exact NeTEx bytes reached Tiamat, with the bearer token.
        assertThat(tiamatRequests.get()).isEqualTo(1);
        assertThat(tiamatBody.get()).isEqualTo(enturBody);
        assertThat(tiamatAuth.get()).isEqualTo("Bearer e2e-token");

        // State advanced with the real counts parsed from the real fixture (10 zones / 2 groups / 5 members).
        SyncState state = new ObjectMapper().readValue(storedState.get(), SyncState.class);
        assertThat(state.exportPath()).isEqualTo(EXPORT_PATH);
        assertThat(state.fareZoneCount()).isEqualTo(10);
        assertThat(state.groupCount()).isEqualTo(2);
        assertThat(state.memberCount()).isEqualTo(5);

        assertThat(slack.started).hasSize(1);
        assertThat(slack.success).hasSize(1);
        assertThat(slack.failure).isEmpty();
    }

    @Test
    void second_run_with_unchanged_export_path_skips() {
        assertThat(service.run(new SyncOptions(false, false, SyncTrigger.SCHEDULED)))
                .isEqualTo(SyncOutcome.PUBLISHED);

        SyncOutcome second = service.run(new SyncOptions(false, false, SyncTrigger.SCHEDULED));

        assertThat(second).isEqualTo(SyncOutcome.SKIPPED);
        assertThat(tiamatRequests.get()).isEqualTo(1); // not published again
        assertThat(slack.started).hasSize(1);          // no new notifications on the skip
        assertThat(slack.success).hasSize(1);
    }

    @Test
    void real_validation_rejects_dangling_refs_and_does_not_publish() throws IOException {
        enturBody = fixture("dangling-ref-farezones.xml"); // a group member points at a missing FareZone

        SyncOutcome outcome = service.run(new SyncOptions(false, false, SyncTrigger.SCHEDULED));

        assertThat(outcome).isEqualTo(SyncOutcome.FAILED);
        assertThat(tiamatRequests.get()).isZero();   // never reached Tiamat
        assertThat(storedState.get()).isNull();       // state not advanced
        assertThat(slack.failure).hasSize(1);
        assertThat(slack.started).isEmpty();
    }

    // --- stub Entur: 302 → signed URL, then serves the NeTEx body ---

    private void handleEntur(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/fare-zones")) {
                exchange.getResponseHeaders().set("Location",
                        enturBaseUrl + EXPORT_PATH + "?X-Goog-Signature=sig&X-Goog-Expires=900");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals(EXPORT_PATH)) {
                exchange.sendResponseHeaders(200, enturBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(enturBody);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } finally {
            exchange.close();
        }
    }

    // --- stub Tiamat: capture body + auth, return 200 ---

    private void handleTiamat(HttpExchange exchange) throws IOException {
        tiamatRequests.incrementAndGet();
        tiamatAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        tiamatBody.set(exchange.getRequestBody().readAllBytes());
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    // --- in-memory blob store backing the real GcsSyncStateStore (round-trips JSON) ---

    private BlobStoreRepository inMemoryBlobStore() {
        BlobStoreRepository repo = mock(BlobStoreRepository.class);
        when(repo.exist(STATE_OBJECT)).thenAnswer(i -> storedState.get() != null);
        when(repo.getBlob(STATE_OBJECT)).thenAnswer(i ->
                storedState.get() == null ? null : new ByteArrayInputStream(storedState.get()));
        when(repo.uploadBlob(eq(STATE_OBJECT), any(InputStream.class), any())).thenAnswer(i -> {
            storedState.set(((InputStream) i.getArgument(1)).readAllBytes());
            return 1L;
        });
        return repo;
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static final class RecordingSlackNotifier implements SlackNotifier {
        private final List<Started> started = new ArrayList<>();
        private final List<Success> success = new ArrayList<>();
        private final List<Failure> failure = new ArrayList<>();

        @Override
        public void started(Started event) {
            started.add(event);
        }

        @Override
        public void success(Success event) {
            success.add(event);
        }

        @Override
        public void failure(Failure event) {
            failure.add(event);
        }
    }
}
