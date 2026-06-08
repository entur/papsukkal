package no.entur.papsukkal.slack;

import no.entur.papsukkal.config.SlackProperties;
import no.entur.papsukkal.slack.SlackNotifier.Failure;
import no.entur.papsukkal.slack.SlackNotifier.Started;
import no.entur.papsukkal.slack.SlackNotifier.Success;
import no.entur.papsukkal.sync.SyncTrigger;
import no.entur.papsukkal.validation.DatasetCounts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rutebanken.helper.slack.SlackPostService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookSlackNotifierTest {

    private final SlackPostService slack = mock(SlackPostService.class);

    private WebhookSlackNotifier enabledNotifier() {
        return new WebhookSlackNotifier(slack, new SlackProperties("https://hooks.slack.test/abc"));
    }

    @Test
    void started_posts_formatted_message_with_paths_and_counts() {
        when(slack.publish(anyString())).thenReturn(true);

        enabledNotifier().started(new Started(
                SyncTrigger.SCHEDULED, "/v29/farezones.xml", "/v28/farezones.xml",
                new DatasetCounts(485, 29, 639, List.of(), 0)));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(slack).publish(msg.capture());
        assertThat(msg.getValue())
                .contains("started")
                .contains("/v29/farezones.xml")
                .contains("/v28/farezones.xml")
                .contains("485 zones, 29 groups, 639 members");
    }

    @Test
    void started_marks_first_run_when_no_previous_path() {
        when(slack.publish(anyString())).thenReturn(true);

        enabledNotifier().started(new Started(
                SyncTrigger.SCHEDULED, "/v1/farezones.xml", null,
                new DatasetCounts(485, 29, 639, List.of(), 0)));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(slack).publish(msg.capture());
        assertThat(msg.getValue()).contains("first run");
    }

    @Test
    void success_posts_duration_and_attempts() {
        when(slack.publish(anyString())).thenReturn(true);

        enabledNotifier().success(new Success(SyncTrigger.MANUAL, "/v29/farezones.xml", 12_300, 2));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(slack).publish(msg.capture());
        assertThat(msg.getValue()).contains("published").contains("12.3s").contains("attempts: 2");
    }

    @Test
    void failure_includes_reason_state_note_and_bypass_flag() {
        when(slack.publish(anyString())).thenReturn(true);

        enabledNotifier().failure(new Failure(
                SyncTrigger.FORCE, "/v29/farezones.xml", "FareZone count 12 is 97.5% below baseline 485", true));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(slack).publish(msg.capture());
        assertThat(msg.getValue())
                .contains("failed")
                .contains("97.5% below baseline 485")
                .contains("State NOT advanced")
                .contains("BYPASSED");
    }

    @Test
    void publish_returning_false_is_swallowed_and_never_fails_the_sync() {
        when(slack.publish(anyString())).thenReturn(false);

        assertThatCode(() -> enabledNotifier()
                .success(new Success(SyncTrigger.SCHEDULED, "/v29/farezones.xml", 1000, 1)))
                .doesNotThrowAnyException();
        verify(slack).publish(anyString());
    }

    @Test
    void publish_throwing_is_swallowed_and_never_fails_the_sync() {
        when(slack.publish(anyString())).thenThrow(new RuntimeException("slack down"));

        assertThatCode(() -> enabledNotifier()
                .failure(new Failure(SyncTrigger.SCHEDULED, "/v29/farezones.xml", "boom", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void disabled_when_no_webhook_url_skips_publishing() {
        WebhookSlackNotifier disabled = new WebhookSlackNotifier(slack, new SlackProperties(""));

        disabled.started(new Started(SyncTrigger.SCHEDULED, "/v1/f.xml", null,
                new DatasetCounts(485, 29, 639, List.of(), 0)));

        verifyNoInteractions(slack);
    }
}
