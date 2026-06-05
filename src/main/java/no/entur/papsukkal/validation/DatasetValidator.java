package no.entur.papsukkal.validation;

import no.entur.papsukkal.config.ValidationProperties;
import no.entur.papsukkal.state.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The validation gateway (CLAUDE.md &gt; Validation Gateway).
 *
 * <p>Sanity-checks a downloaded NeTEx export <strong>before</strong> it is published, because the
 * target Tiamat imports fare zones with external versioning — a full replace that prunes any
 * zone/group missing from the delivery. A corrupt or partial upstream export would therefore
 * delete live data; this gateway is the interlock that prevents it.
 *
 * <p>Two tiers:
 * <ul>
 *   <li><b>Tier 1 — structural</b> (no baseline needed): parse-valid, counts above meaningful
 *       floors (not {@code > 0} — Tiamat already self-guards the empty delivery), and every group
 *       member ref resolves to a FareZone in the delivery.</li>
 *   <li><b>Tier 2 — drift</b> (only when a last-good baseline exists): fail-closed on a shrink
 *       beyond {@link ValidationProperties#maxShrinkPct()}. Growth never blocks.</li>
 * </ul>
 *
 * <p>Never throws on bad data — a parse failure is returned as a failed {@link ValidationResult}
 * so the caller treats it like any other fatal validation failure.
 */
@Component
public class DatasetValidator {

    private static final Logger log = LoggerFactory.getLogger(DatasetValidator.class);

    private final NetexDatasetInspector inspector;
    private final ValidationProperties props;

    public DatasetValidator(NetexDatasetInspector inspector, ValidationProperties props) {
        this.inspector = inspector;
        this.props = props;
    }

    /** @param baseline the last-good sync state, or {@code null} on first run / when state is absent. */
    public ValidationResult validate(InputStream body, SyncState baseline) {
        DatasetCounts counts;
        try {
            counts = inspector.inspect(body);
        } catch (XMLStreamException e) {
            log.warn("Validation failed: dataset is not well-formed XML", e);
            return new ValidationResult(
                    new DatasetCounts(0, 0, 0, List.of()),
                    false,
                    List.of("NeTEx body failed to parse: " + e.getMessage()));
        }

        List<String> failures = new ArrayList<>();

        // Tier 1 — structural
        if (counts.fareZoneCount() < props.fareZone().minCount()) {
            failures.add("FareZone count " + counts.fareZoneCount()
                    + " is below floor " + props.fareZone().minCount());
        }
        if (counts.groupCount() < props.group().minCount()) {
            failures.add("GroupOfTariffZones count " + counts.groupCount()
                    + " is below floor " + props.group().minCount());
        }
        if (!counts.unresolvedRefs().isEmpty()) {
            List<String> sample = counts.unresolvedRefs().stream().limit(3).toList();
            failures.add(counts.unresolvedRefs().size()
                    + " group member ref(s) do not resolve to a FareZone in this delivery (e.g. "
                    + sample + ")");
        }

        // Tier 2 — drift vs last-good baseline
        if (baseline != null) {
            addShrinkFailure(failures, "FareZone", counts.fareZoneCount(), baseline.fareZoneCount());
            addShrinkFailure(failures, "GroupOfTariffZones", counts.groupCount(), baseline.groupCount());
        }

        return ValidationResult.of(counts, failures);
    }

    /** Adds a failure if {@code current} shrank beyond the threshold vs {@code previous}. */
    private void addShrinkFailure(List<String> failures, String label, int current, int previous) {
        if (previous <= 0) {
            return; // no usable baseline for this metric
        }
        double minAllowed = previous * (1.0 - props.maxShrinkPct() / 100.0);
        if (current >= minAllowed) {
            return;
        }
        double dropPct = (previous - current) * 100.0 / previous;
        failures.add(String.format(
                "%s count %d is %.1f%% below baseline %d (max allowed shrink %.1f%%)",
                label, current, dropPct, previous, props.maxShrinkPct()));
    }
}
