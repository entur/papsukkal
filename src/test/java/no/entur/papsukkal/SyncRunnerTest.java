package no.entur.papsukkal;

import no.entur.papsukkal.sync.FareZoneSyncService;
import no.entur.papsukkal.sync.SyncOptions;
import no.entur.papsukkal.sync.SyncOutcome;
import no.entur.papsukkal.sync.SyncTrigger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncRunnerTest {

    private final FareZoneSyncService service = mock(FareZoneSyncService.class);

    private SyncRunner runnerWithEnv(Map<String, String> env) {
        UnaryOperator<String> lookup = env::get;
        return new SyncRunner(service, lookup);
    }

    private SyncOptions runAndCapture(Map<String, String> env, SyncOutcome outcome) {
        when(service.run(any(SyncOptions.class))).thenReturn(outcome);
        runnerWithEnv(env).run(null);
        ArgumentCaptor<SyncOptions> opts = ArgumentCaptor.forClass(SyncOptions.class);
        verify(service).run(opts.capture());
        return opts.getValue();
    }

    // --- env var -> SyncOptions translation ---

    @Test
    void defaults_to_scheduled_with_no_flags_when_env_is_empty() {
        SyncOptions opts = runAndCapture(Map.of(), SyncOutcome.SKIPPED);

        assertThat(opts.force()).isFalse();
        assertThat(opts.bypassValidation()).isFalse();
        assertThat(opts.trigger()).isEqualTo(SyncTrigger.SCHEDULED);
    }

    @Test
    void force_flag_sets_force_and_force_trigger() {
        SyncOptions opts = runAndCapture(Map.of("FORCE", "true"), SyncOutcome.PUBLISHED);

        assertThat(opts.force()).isTrue();
        assertThat(opts.trigger()).isEqualTo(SyncTrigger.FORCE);
    }

    @Test
    void bypass_validation_flag_is_parsed() {
        SyncOptions opts = runAndCapture(Map.of("BYPASS_VALIDATION", "true"), SyncOutcome.PUBLISHED);

        assertThat(opts.bypassValidation()).isTrue();
    }

    @Test
    void trigger_manual_is_case_insensitive() {
        assertThat(runAndCapture(Map.of("TRIGGER", "manual"), SyncOutcome.SKIPPED).trigger())
                .isEqualTo(SyncTrigger.MANUAL);
        assertThat(runAndCaptureFresh(Map.of("TRIGGER", "MANUAL")).trigger())
                .isEqualTo(SyncTrigger.MANUAL);
    }

    @Test
    void unrecognised_trigger_falls_back_to_scheduled() {
        assertThat(runAndCapture(Map.of("TRIGGER", "nonsense"), SyncOutcome.SKIPPED).trigger())
                .isEqualTo(SyncTrigger.SCHEDULED);
    }

    @Test
    void force_overrides_trigger_env() {
        Map<String, String> env = new HashMap<>();
        env.put("FORCE", "true");
        env.put("TRIGGER", "manual");

        assertThat(runAndCapture(env, SyncOutcome.PUBLISHED).trigger()).isEqualTo(SyncTrigger.FORCE);
    }

    // --- SyncOutcome -> exit code mapping (the run-once Kubernetes contract) ---

    @Test
    void exit_code_is_zero_when_published() {
        assertThat(runExitCode(Map.of(), SyncOutcome.PUBLISHED)).isZero();
    }

    @Test
    void exit_code_is_zero_when_skipped() {
        assertThat(runExitCode(Map.of(), SyncOutcome.SKIPPED)).isZero();
    }

    @Test
    void exit_code_is_one_when_failed() {
        assertThat(runExitCode(Map.of(), SyncOutcome.FAILED)).isEqualTo(1);
    }

    @Test
    void exit_code_is_one_when_service_throws() {
        when(service.run(any(SyncOptions.class))).thenThrow(new RuntimeException("boom"));
        SyncRunner runner = runnerWithEnv(Map.of());

        runner.run(null);

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    private int runExitCode(Map<String, String> env, SyncOutcome outcome) {
        when(service.run(any(SyncOptions.class))).thenReturn(outcome);
        SyncRunner runner = runnerWithEnv(env);
        runner.run(null);
        return runner.getExitCode();
    }

    /** Second capture in a single test needs a fresh interaction count. */
    private SyncOptions runAndCaptureFresh(Map<String, String> env) {
        FareZoneSyncService fresh = mock(FareZoneSyncService.class);
        when(fresh.run(any(SyncOptions.class))).thenReturn(SyncOutcome.SKIPPED);
        new SyncRunner(fresh, (UnaryOperator<String>) env::get).run(null);
        ArgumentCaptor<SyncOptions> opts = ArgumentCaptor.forClass(SyncOptions.class);
        verify(fresh).run(opts.capture());
        return opts.getValue();
    }
}
