package no.entur.papsukkal.sync;

import no.entur.papsukkal.entur.FareZoneApiClient;
import no.entur.papsukkal.publish.TiamatPublisher;
import no.entur.papsukkal.slack.SlackNotifier;
import no.entur.papsukkal.state.SyncState;
import no.entur.papsukkal.state.SyncStateStore;
import no.entur.papsukkal.validation.DatasetCounts;
import no.entur.papsukkal.validation.DatasetValidator;
import no.entur.papsukkal.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FareZoneSyncServiceTest {

    private static final String PATH = "/all/v29/all-farezones.xml";
    private static final DatasetCounts COUNTS = new DatasetCounts(485, 29, 639, List.of());

    private final FareZoneApiClient enturClient = mock(FareZoneApiClient.class);
    private final DatasetValidator validator = mock(DatasetValidator.class);
    private final TiamatPublisher publisher = mock(TiamatPublisher.class);
    private final SyncStateStore stateStore = mock(SyncStateStore.class);
    private final SlackNotifier slack = mock(SlackNotifier.class);

    private final FareZoneSyncService service =
            new FareZoneSyncService(enturClient, validator, publisher, stateStore, slack);

    private SyncOptions options(boolean force, boolean bypass) {
        return new SyncOptions(force, bypass, force ? SyncTrigger.FORCE : SyncTrigger.SCHEDULED);
    }

    private SyncState state(String path) {
        return new SyncState(path, 485, 29, 639, "2026-01-01T03:00:00Z");
    }

    private void givenValidation(boolean passed) {
        when(validator.validate(any(InputStream.class), any()))
                .thenReturn(ValidationResult.of(COUNTS, passed ? List.of() : List.of("FareZone count too low")));
    }

    @Test
    void skips_when_export_path_unchanged() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(state(PATH));

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED);
        verify(enturClient, never()).downloadExport();
        verify(stateStore, never()).write(any());
        verifyNoInteractions(validator, publisher, slack);
    }

    @Test
    void publishes_and_advances_state_when_changed() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(null); // first run
        when(enturClient.downloadExport()).thenReturn("<xml/>".getBytes());
        givenValidation(true);

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.PUBLISHED);
        verify(slack).started(any(SlackNotifier.Started.class));
        verify(publisher).publish(any(byte[].class));

        ArgumentCaptor<SyncState> written = ArgumentCaptor.forClass(SyncState.class);
        verify(stateStore).write(written.capture());
        assertThat(written.getValue().exportPath()).isEqualTo(PATH);
        assertThat(written.getValue().fareZoneCount()).isEqualTo(485);
        assertThat(written.getValue().groupCount()).isEqualTo(29);
        assertThat(written.getValue().memberCount()).isEqualTo(639);
        assertThat(written.getValue().publishedAt()).isNotBlank();

        verify(slack).success(any(SlackNotifier.Success.class));
        verify(slack, never()).failure(any());
    }

    @Test
    void force_publishes_even_when_path_unchanged() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(state(PATH)); // same path
        when(enturClient.downloadExport()).thenReturn("<xml/>".getBytes());
        givenValidation(true);

        SyncOutcome outcome = service.run(options(true, false));

        assertThat(outcome).isEqualTo(SyncOutcome.PUBLISHED);
        verify(publisher).publish(any(byte[].class));
        verify(stateStore).write(any());
    }

    @Test
    void validation_failure_aborts_without_publishing() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(null);
        when(enturClient.downloadExport()).thenReturn("<xml/>".getBytes());
        givenValidation(false);

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.FAILED);
        verify(slack).failure(any(SlackNotifier.Failure.class));
        verify(publisher, never()).publish(any());
        verify(stateStore, never()).write(any());
        verify(slack, never()).started(any());
        verify(slack, never()).success(any());
    }

    @Test
    void bypass_validation_publishes_despite_failure() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(null);
        when(enturClient.downloadExport()).thenReturn("<xml/>".getBytes());
        givenValidation(false);

        SyncOutcome outcome = service.run(options(true, true));

        assertThat(outcome).isEqualTo(SyncOutcome.PUBLISHED);
        verify(publisher).publish(any(byte[].class));
        verify(stateStore).write(any());
        verify(slack).success(any(SlackNotifier.Success.class));
        verify(slack, never()).failure(any());
    }

    @Test
    void change_check_fetch_failure_notifies_and_fails() {
        when(enturClient.currentExportPath()).thenThrow(new RuntimeException("Entur 503"));

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.FAILED);
        verify(slack).failure(any(SlackNotifier.Failure.class));
        verify(enturClient, never()).downloadExport();
        verify(stateStore, never()).write(any());
        verifyNoInteractions(validator, publisher);
        verify(slack, never()).started(any());
    }

    @Test
    void download_failure_notifies_and_fails() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(null);
        when(enturClient.downloadExport()).thenThrow(new RuntimeException("Entur read timeout"));

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.FAILED);
        verify(slack).failure(any(SlackNotifier.Failure.class));
        verify(stateStore, never()).write(any());
        verifyNoInteractions(validator, publisher);
        verify(slack, never()).started(any());
    }

    @Test
    void publish_failure_does_not_advance_state() {
        when(enturClient.currentExportPath()).thenReturn(PATH);
        when(stateStore.read()).thenReturn(null);
        when(enturClient.downloadExport()).thenReturn("<xml/>".getBytes());
        givenValidation(true);
        doThrow(new RuntimeException("Tiamat 503")).when(publisher).publish(any());

        SyncOutcome outcome = service.run(options(false, false));

        assertThat(outcome).isEqualTo(SyncOutcome.FAILED);
        verify(slack).started(any(SlackNotifier.Started.class));
        verify(slack).failure(any(SlackNotifier.Failure.class));
        verify(stateStore, never()).write(any());
        verify(slack, never()).success(any());
    }
}
