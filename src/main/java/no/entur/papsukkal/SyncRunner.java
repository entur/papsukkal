package no.entur.papsukkal;

import no.entur.papsukkal.sync.FareZoneSyncService;
import no.entur.papsukkal.sync.SyncOptions;
import no.entur.papsukkal.sync.SyncOutcome;
import no.entur.papsukkal.sync.SyncTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Run-once entrypoint: invokes {@link FareZoneSyncService} once on startup and records the exit
 * code the JVM should terminate with (0 = published or correctly skipped, non-zero = failed).
 *
 * <p>Reads {@code FORCE} (skip change detection) and {@code BYPASS_VALIDATION} (skip the validation
 * gateway) env vars; an optional {@code TRIGGER=manual} tags ad-hoc runs in notifications.
 */
@Component
public class SyncRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);

    private final FareZoneSyncService syncService;
    private int exitCode = 0;

    public SyncRunner(FareZoneSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean force = envFlag("FORCE");
        boolean bypassValidation = envFlag("BYPASS_VALIDATION");
        SyncTrigger trigger = force ? SyncTrigger.FORCE : triggerFromEnv();

        try {
            SyncOutcome outcome = syncService.run(new SyncOptions(force, bypassValidation, trigger));
            exitCode = (outcome == SyncOutcome.FAILED) ? 1 : 0;
        } catch (RuntimeException e) {
            log.error("Sync run failed unexpectedly", e);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static boolean envFlag(String name) {
        return Boolean.parseBoolean(System.getenv(name));
    }

    private static SyncTrigger triggerFromEnv() {
        return "manual".equalsIgnoreCase(System.getenv("TRIGGER"))
                ? SyncTrigger.MANUAL
                : SyncTrigger.SCHEDULED;
    }
}
