package no.entur.papsukkal.publish;

import org.springframework.stereotype.Component;

/**
 * Skeleton {@link TiamatPublisher}.
 *
 * <p>TODO: implement with {@code RestClient} (connect 10s / read 5 min, since the import is
 * synchronous), an OAuth2 client-credentials bearer token, and Spring Framework 7 native
 * {@code @Retryable} on the publish call — retry transient ({@code 5xx}/{@code 429}/IO), fail
 * fast on fatal {@code 4xx}.
 */
@Component
public class TiamatNetexPublisher implements TiamatPublisher {

    @Override
    public void publish(byte[] netexXml) {
        throw new UnsupportedOperationException("TiamatNetexPublisher not yet implemented");
    }
}
