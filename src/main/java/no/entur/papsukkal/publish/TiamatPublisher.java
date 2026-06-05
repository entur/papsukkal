package no.entur.papsukkal.publish;

/**
 * Publishes the full NeTEx dataset to Tiamat's synchronous import endpoint
 * ({@code POST /services/stop_places/netex}, {@code Content-Type: application/xml}, OAuth2 bearer).
 *
 * <p>A {@code 2xx} confirms the import completed. Transient failures are retried with backoff;
 * fatal {@code 4xx} fail fast (see CLAUDE.md &gt; Error Handling, Retry &amp; Notifications).
 */
public interface TiamatPublisher {

    /**
     * @param netexXml the raw NeTEx body to import
     * @throws RuntimeException if the import ultimately fails (retries exhausted or fatal 4xx)
     */
    void publish(byte[] netexXml);
}
