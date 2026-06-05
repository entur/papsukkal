package no.entur.papsukkal.entur;

import org.springframework.stereotype.Component;

/**
 * Skeleton {@link FareZoneApiClient} for the Entur API.
 *
 * <p>TODO: implement with {@code RestClient} —
 * <ul>
 *   <li>{@code currentExportPath()}: GET {@code /distance/netex/fare-zones} with the
 *       {@code ET-Client-Name} header and <em>redirects disabled</em>; read the {@code Location}
 *       header and strip the query string.</li>
 *   <li>{@code downloadExport()}: follow that URL and return the body bytes.</li>
 * </ul>
 * Distinguish transient vs fatal failures for the retry classification (see CLAUDE.md).
 */
@Component
public class EnturFareZoneApiClient implements FareZoneApiClient {

    @Override
    public String currentExportPath() {
        throw new UnsupportedOperationException("EnturFareZoneApiClient not yet implemented");
    }

    @Override
    public byte[] downloadExport() {
        throw new UnsupportedOperationException("EnturFareZoneApiClient not yet implemented");
    }
}
