package no.entur.papsukkal.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skeleton {@link SlackNotifier}.
 *
 * <p>For now it only logs; TODO: POST a formatted message to the incoming webhook
 * ({@code papsukkal.slack.webhook-url}) with {@code RestClient}, catching and logging any
 * delivery error so a Slack outage never fails the sync.
 */
@Component
public class WebhookSlackNotifier implements SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSlackNotifier.class);

    @Override
    public void started(Started event) {
        log.info("🔄 started: {}", event);
    }

    @Override
    public void success(Success event) {
        log.info("✅ success: {}", event);
    }

    @Override
    public void failure(Failure event) {
        log.error("❌ failure: {}", event);
    }
}
