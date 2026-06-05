package no.entur.papsukkal.slack;

import no.entur.papsukkal.config.SlackProperties;
import no.entur.papsukkal.validation.DatasetCounts;
import org.rutebanken.helper.slack.SlackPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link SlackNotifier} backed by the Entur {@code slack} helper.
 *
 * <p>Delivery is best-effort: {@link SlackPostService#publish} already swallows errors and returns
 * {@code false}, and this class additionally guards against any unexpected throw — a Slack problem
 * is logged but never fails the sync. When no webhook URL is configured, notifications are skipped.
 */
@Component
public class WebhookSlackNotifier implements SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSlackNotifier.class);

    private final SlackPostService slack;
    private final boolean enabled;

    public WebhookSlackNotifier(SlackPostService slack, SlackProperties props) {
        this.slack = slack;
        this.enabled = StringUtils.hasText(props.webhookUrl());
        if (!enabled) {
            log.warn("Slack webhook URL not configured — notifications are disabled");
        }
    }

    @Override
    public void started(Started event) {
        DatasetCounts c = event.counts();
        String dataset = c == null
                ? "n/a"
                : "%d zones, %d groups, %d members".formatted(c.fareZoneCount(), c.groupCount(), c.memberCount());
        post("""
                🔄 *Fare zone sync started* (trigger: %s)
                • New export: `%s`
                • Previous: %s
                • Dataset: %s""".formatted(
                event.trigger(),
                event.newExportPath(),
                event.previousExportPath() == null ? "_(none — first run)_" : "`" + event.previousExportPath() + "`",
                dataset));
    }

    @Override
    public void success(Success event) {
        post("""
                ✅ *Fare zone sync published* (trigger: %s)
                • Export: `%s`
                • Duration: %.1fs, attempts: %d""".formatted(
                event.trigger(),
                event.exportPath(),
                event.durationMillis() / 1000.0,
                event.attempts()));
    }

    @Override
    public void failure(Failure event) {
        StringBuilder text = new StringBuilder("""
                ❌ *Fare zone sync failed* (trigger: %s)
                • Attempted export: `%s`
                • Reason: %s
                • ⚠️ State NOT advanced — will retry next run.""".formatted(
                event.trigger(),
                event.attemptedExportPath(),
                event.reason()));
        if (event.bypassed()) {
            text.append("\n• ⚠️ Validation was BYPASSED (authorized deletions).");
        }
        post(text.toString());
    }

    private void post(String text) {
        if (!enabled) {
            log.debug("Slack disabled; skipping notification:\n{}", text);
            return;
        }
        try {
            if (!slack.publish(text)) {
                log.warn("Slack publish reported failure for message:\n{}", text);
            }
        } catch (RuntimeException e) {
            log.warn("Slack publish threw — ignoring (best-effort delivery)", e);
        }
    }
}
