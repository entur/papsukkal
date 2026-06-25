package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Within-run retry tuning for transient HTTP failures (5xx / 429 / IO), shared by the Entur fetch
 * and the Tiamat publish (see CLAUDE.md &gt; Error Handling, Retry &amp; Notifications). The CronJob
 * schedule is the outer, across-run retry; this governs the in-run blip-recovery only.
 *
 * @param maxRetries retries <em>after</em> the first attempt ({@code 2} ⇒ 3 attempts total)
 * @param delay      initial backoff before the first retry
 * @param multiplier exponential backoff factor applied to {@code delay} each retry
 * @param maxDelay   cap on the backoff between attempts
 * @param jitter     random ± added to each backoff to de-correlate retries
 */
public record RetryProperties(
        @DefaultValue("2") long maxRetries,
        @DefaultValue("5s") Duration delay,
        @DefaultValue("3.0") double multiplier,
        @DefaultValue("45s") Duration maxDelay,
        @DefaultValue("1s") Duration jitter) {
}