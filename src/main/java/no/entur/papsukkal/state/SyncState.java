package no.entur.papsukkal.state;

/**
 * The persisted last-good sync state (see CLAUDE.md &gt; State Storage).
 *
 * <p>Stored as JSON at {@code sync-state/last-sync.json} in the GCS state bucket, written
 * <strong>only</strong> after a successful publish — so the counts here are always the
 * last-known-good baseline the validation gateway compares the next delivery against.
 *
 * @param exportPath    GCS object path (query string stripped) of the last published export
 * @param fareZoneCount FareZone count of that export — Tier-2 drift baseline
 * @param groupCount    GroupOfTariffZones count of that export — Tier-2 drift baseline
 * @param memberCount   total TariffZoneRef members of that export
 * @param publishedAt   ISO-8601 instant the publish succeeded
 */
public record SyncState(
        String exportPath,
        int fareZoneCount,
        int groupCount,
        int memberCount,
        String publishedAt) {
}
