package no.entur.papsukkal.validation;

import java.util.List;

/**
 * The counts extracted from a NeTEx fare-zone export by {@link NetexDatasetInspector}.
 *
 * @param fareZoneCount  distinct {@code FareZone} ids in the FareFrame
 * @param groupCount     {@code GroupOfTariffZones} elements in the SiteFrame
 * @param memberCount    total {@code TariffZoneRef} members across all groups
 * @param unresolvedRefs member refs that do not match any FareZone id in this delivery
 */
public record DatasetCounts(
        int fareZoneCount,
        int groupCount,
        int memberCount,
        List<String> unresolvedRefs) {
}
