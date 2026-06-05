package no.entur.papsukkal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the validation gateway. Floors and the shrink threshold should be set to the
 * known-good magnitude of the production export — see CLAUDE.md &gt; Validation Gateway.
 *
 * <p>A {@code > 0} floor is unsafe: Tiamat already self-guards the <em>empty</em> delivery, so
 * the dangerous input is the small-but-nonzero one. Set {@link Floor#minCount()} to a meaningful
 * minimum (≈400 zones / 25 groups today). Concrete values are supplied in application.yml.
 *
 * @param fareZone     floor for the FareZone count
 * @param group        floor for the GroupOfTariffZones count
 * @param maxShrinkPct maximum tolerated shrink vs the last-good baseline, as a percentage
 */
@ConfigurationProperties(prefix = "papsukkal.validation")
public record ValidationProperties(
        @DefaultValue Floor fareZone,
        @DefaultValue Floor group,
        @DefaultValue("10.0") double maxShrinkPct) {

    public record Floor(@DefaultValue("0") int minCount) {
    }
}
