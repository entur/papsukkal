package no.entur.papsukkal.validation;

import java.util.List;

/**
 * The counts extracted from a NeTEx fare-zone export by {@link NetexDatasetInspector}.
 *
 * @param fareZoneCount  distinct {@code FareZone} ids in the FareFrame
 * @param groupCount     {@code GroupOfTariffZones} elements in the SiteFrame
 * @param memberCount    total {@code TariffZoneRef} members across all groups
 * @param unresolvedRefs member refs that do not match any FareZone id in this delivery
 * @param stopPlaceCount {@code StopPlace} elements — must be 0; a fare-zone delivery must not carry
 *                       stop places (the import endpoint is the general NeTEx import, so foreign
 *                       entities would otherwise be imported/edited too — see {@link DatasetValidator})
 */
public record DatasetCounts(
        int fareZoneCount,
        int groupCount,
        int memberCount,
        List<String> unresolvedRefs,
        int stopPlaceCount) {
}
