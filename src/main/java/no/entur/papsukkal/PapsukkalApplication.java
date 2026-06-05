package no.entur.papsukkal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Papsukkal — run-once batch job that syncs Entur fare-zone NeTEx data to Tiamat.
 *
 * <p>No web layer: {@code spring.main.web-application-type=none} (see application.yml). The
 * single unit of work is driven by {@code SyncRunner} on startup; the JVM then exits with the
 * code that runner reports.
 *
 * <p>TODO: add {@code @EnableResilientMethods} (Spring Framework 7 native retry) once
 * {@code TiamatPublisher} annotates its publish call with {@code @Retryable}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PapsukkalApplication {

    public static void main(String[] args) {
        // Run-once: propagate SyncRunner's exit code to the JVM (0 = published/skipped, non-zero = failed).
        System.exit(SpringApplication.exit(SpringApplication.run(PapsukkalApplication.class, args)));
    }
}
