package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for Slack notifications (see CLAUDE.md &gt; Notifications).
 *
 * @param webhookUrl the Slack incoming-webhook URL; blank disables notifications (best-effort,
 *                   so an unconfigured webhook never fails the sync — it is simply skipped)
 */
@ConfigurationProperties(prefix = "papsukkal.slack")
public record SlackProperties(@DefaultValue("") String webhookUrl) {
}
