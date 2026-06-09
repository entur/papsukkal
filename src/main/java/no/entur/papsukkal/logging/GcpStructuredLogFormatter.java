package no.entur.papsukkal.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured-log formatter that emits Google Cloud Logging-shaped JSON (one line per event), so
 * Cloud Logging maps {@code severity} and {@code message} correctly and our SLF4J key/value fields
 * (fareZoneCount, groupCount, …) land in {@code jsonPayload}.
 *
 * <p>Spring Boot has no built-in {@code gcp} structured format (only {@code ecs}/{@code gelf}/
 * {@code logstash}), so this is referenced by fully-qualified class name:
 * {@code logging.structured.format.console=no.entur.papsukkal.logging.GcpStructuredLogFormatter}.
 *
 * <p>Loaded by the logging system via its no-arg constructor — it is not a Spring bean.
 */
public class GcpStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThrowableProxyConverter throwableProxyConverter = new ThrowableProxyConverter();

    public GcpStructuredLogFormatter() {
        throwableProxyConverter.start();
    }

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("severity", severity(event.getLevel()));
        entry.put("time", event.getInstant().toString()); // RFC3339 — Cloud Logging entry time
        entry.put("logger", event.getLoggerName());
        entry.put("thread", event.getThreadName());

        String message = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) {
            // Append the stack trace so Cloud Logging / Error Reporting picks it up.
            message = message + "\n" + throwableProxyConverter.convert(event);
        }
        entry.put("message", message);

        // Flatten MDC + SLF4J key/value pairs (the counts) to top level → queryable as jsonPayload.<key>.
        event.getMDCPropertyMap().forEach(entry::putIfAbsent);
        List<KeyValuePair> keyValuePairs = event.getKeyValuePairs();
        if (keyValuePairs != null) {
            for (KeyValuePair pair : keyValuePairs) {
                entry.putIfAbsent(pair.key, pair.value);
            }
        }

        try {
            return MAPPER.writeValueAsString(entry) + "\n";
        } catch (Exception ex) {
            return "{\"severity\":\"ERROR\",\"message\":\"log JSON serialization failed\"}\n";
        }
    }

    /** Map Logback levels to Cloud Logging severities. */
    private static String severity(Level level) {
        if (level == null) {
            return "DEFAULT";
        }
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARNING";
            case Level.INFO_INT -> "INFO";
            case Level.DEBUG_INT, Level.TRACE_INT -> "DEBUG";
            default -> "DEFAULT";
        };
    }
}
