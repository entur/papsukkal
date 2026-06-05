package no.entur.papsukkal.entur;

/**
 * Client for the Entur fare-zone NeTEx export API.
 *
 * <p>The endpoint responds with a {@code 302} to a GCS V4 signed URL. {@link #currentExportPath()}
 * reads the {@code Location} with redirects <strong>disabled</strong> and strips the query string
 * (the signature params regenerate every request); {@link #downloadExport()} follows the URL to
 * fetch the raw NeTEx body.
 */
public interface FareZoneApiClient {

    /** The stable GCS object path (query string stripped) of the current export — for change detection. */
    String currentExportPath();

    /** The raw NeTEx XML body of the current export — only called when a publish is needed. */
    byte[] downloadExport();
}
