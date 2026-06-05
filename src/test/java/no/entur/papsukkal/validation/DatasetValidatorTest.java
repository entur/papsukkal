package no.entur.papsukkal.validation;

import no.entur.papsukkal.config.ValidationProperties;
import no.entur.papsukkal.config.ValidationProperties.Floor;
import no.entur.papsukkal.state.SyncState;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetValidatorTest {

    private final NetexDatasetInspector inspector = new NetexDatasetInspector();

    /** Floors low enough for the 10-zone / 2-group fixtures to clear Tier 1; 10% shrink threshold. */
    private DatasetValidator validatorWith(int fareZoneFloor, int groupFloor, double maxShrinkPct) {
        ValidationProperties props =
                new ValidationProperties(new Floor(fareZoneFloor), new Floor(groupFloor), maxShrinkPct);
        return new DatasetValidator(inspector, props);
    }

    private DatasetValidator lenientValidator() {
        return validatorWith(5, 1, 10.0);
    }

    private InputStream fixture(String name) {
        InputStream in = getClass().getResourceAsStream("/fixtures/" + name);
        if (in == null) {
            throw new IllegalStateException("missing fixture: " + name);
        }
        return in;
    }

    private SyncState baseline(int fareZones, int groups) {
        return new SyncState("/prev/path.xml", fareZones, groups, 0, "2026-01-01T00:00:00Z");
    }

    @Test
    void passes_a_healthy_dataset_with_no_baseline() {
        ValidationResult result = lenientValidator().validate(fixture("valid-farezones.xml"), null);

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.counts().fareZoneCount()).isEqualTo(10);
        assertThat(result.counts().groupCount()).isEqualTo(2);
    }

    @Test
    void fails_when_below_absolute_floor() {
        // Floor of 400 zones — the 10-zone fixture is far below.
        ValidationResult result = validatorWith(400, 25, 10.0).validate(fixture("valid-farezones.xml"), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("FareZone count 10 is below floor 400"));
    }

    @Test
    void fails_when_a_member_ref_does_not_resolve() {
        ValidationResult result = lenientValidator().validate(fixture("dangling-ref-farezones.xml"), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("do not resolve"));
        assertThat(result.counts().unresolvedRefs()).containsExactly("ENT:FareZone:99");
    }

    @Test
    void fails_on_significant_shrink_versus_baseline() {
        // 10 zones now vs 20 before = 50% drop, beyond the 10% threshold.
        ValidationResult result = lenientValidator().validate(fixture("valid-farezones.xml"), baseline(20, 2));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("FareZone count 10 is 50.0% below baseline 20"));
    }

    @Test
    void allows_growth_versus_baseline() {
        // 10 zones now vs 5 before = growth, never blocks.
        ValidationResult result = lenientValidator().validate(fixture("valid-farezones.xml"), baseline(5, 1));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void allows_a_shrink_within_threshold() {
        // 10 vs 11 = ~9% drop, within the 10% threshold.
        ValidationResult result = lenientValidator().validate(fixture("valid-farezones.xml"), baseline(11, 2));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void treats_malformed_xml_as_a_failure_without_throwing() {
        ValidationResult result = lenientValidator().validate(fixture("malformed.xml"), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("failed to parse"));
    }
}
