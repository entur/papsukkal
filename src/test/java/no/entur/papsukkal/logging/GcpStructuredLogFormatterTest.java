package no.entur.papsukkal.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.event.KeyValuePair;

/**
 * Pins the JSON contract Cloud Logging depends on: severity mapping, message (+ stack trace),
 * and SLF4J key/value + MDC fields flattened to top level so they land in {@code jsonPayload}.
 */
class GcpStructuredLogFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED = Instant.parse("2026-01-01T03:00:00Z");

    private final GcpStructuredLogFormatter formatter = new GcpStructuredLogFormatter();

    private static LoggingEvent event(Level level, String message) {
        return event(level, message, Map.of());
    }

    private static LoggingEvent event(Level level, String message, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setInstant(FIXED);
        event.setLoggerName("no.entur.papsukkal.sync.FareZoneSyncService");
        event.setThreadName("main");
        event.setMessage(message);
        // Must be set exactly once: logback rejects a second call, and a null map makes
        // getMDCPropertyMap() dereference the (absent) logger context.
        event.setMDCPropertyMap(mdc);
        return event;
    }

    private JsonNode format(LoggingEvent event) throws Exception {
        String line = formatter.format(event);
        // Cloud Logging requires one JSON object per physical line, newline-terminated.
        assertThat(line).endsWith("\n");
        assertThat(line.stripTrailing()).doesNotContain("\n");
        return MAPPER.readTree(line);
    }

    @ParameterizedTest
    @CsvSource({
        "ERROR, ERROR",
        "WARN,  WARNING",
        "INFO,  INFO",
        "DEBUG, DEBUG",
        "TRACE, DEBUG"
    })
    void mapsLogbackLevelToCloudLoggingSeverity(String level, String expectedSeverity) throws Exception {
        JsonNode node = format(event(Level.valueOf(level), "hello"));
        assertThat(node.get("severity").asText()).isEqualTo(expectedSeverity);
    }

    @Test
    void emitsCoreFields() throws Exception {
        JsonNode node = format(event(Level.INFO, "sync complete"));

        assertThat(node.get("time").asText()).isEqualTo("2026-01-01T03:00:00Z");
        assertThat(node.get("logger").asText()).isEqualTo("no.entur.papsukkal.sync.FareZoneSyncService");
        assertThat(node.get("thread").asText()).isEqualTo("main");
        assertThat(node.get("message").asText()).isEqualTo("sync complete");
    }

    @Test
    void flattensKeyValuePairsToTopLevelWithTypes() throws Exception {
        LoggingEvent event = event(Level.INFO, "published");
        event.setKeyValuePairs(List.of(
            new KeyValuePair("outcome", "PUBLISHED"),
            new KeyValuePair("fareZoneCount", 485),
            new KeyValuePair("groupCount", 29)));

        JsonNode node = format(event);

        // Queryable as jsonPayload.<key>; numbers stay numbers (not stringified).
        assertThat(node.get("outcome").asText()).isEqualTo("PUBLISHED");
        assertThat(node.get("fareZoneCount").isNumber()).isTrue();
        assertThat(node.get("fareZoneCount").asInt()).isEqualTo(485);
        assertThat(node.get("groupCount").asInt()).isEqualTo(29);
    }

    @Test
    void flattensMdcToTopLevel() throws Exception {
        LoggingEvent event = event(Level.INFO, "with context", Map.of("correlationId", "abc-123"));

        JsonNode node = format(event);

        assertThat(node.get("correlationId").asText()).isEqualTo("abc-123");
    }

    @Test
    void appendsStackTraceToMessageWhenThrowablePresent() throws Exception {
        LoggingEvent event = event(Level.ERROR, "publish failed");
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("boom")));

        JsonNode node = format(event);

        String message = node.get("message").asText();
        assertThat(message).startsWith("publish failed");
        assertThat(message).contains("java.lang.IllegalStateException", "boom");
    }
}
