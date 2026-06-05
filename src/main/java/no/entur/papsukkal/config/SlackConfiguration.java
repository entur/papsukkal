package no.entur.papsukkal.config;

import org.rutebanken.helper.slack.SlackPostService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Entur {@code slack} helper's {@link SlackPostService} as a bean, wired to our own
 * {@code papsukkal.slack.webhook-url} property (rather than the helper's default
 * {@code helper.slack.endpoint}, since we don't component-scan the helper package).
 */
@Configuration
public class SlackConfiguration {

    @Bean
    public SlackPostService slackPostService(SlackProperties props) {
        return new SlackPostService(props.webhookUrl());
    }
}
