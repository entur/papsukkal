package no.entur.papsukkal.validation;

import java.util.List;

/**
 * Outcome of the validation gateway for one downloaded dataset.
 *
 * @param counts   the counts extracted from the dataset (always present, even on failure — they
 *                 are written to the state baseline after a successful publish)
 * @param passed   true when {@code failures} is empty
 * @param failures human-readable reasons, each specific enough to put in a Slack ❌
 */
public record ValidationResult(DatasetCounts counts, boolean passed, List<String> failures) {

    public static ValidationResult of(DatasetCounts counts, List<String> failures) {
        return new ValidationResult(counts, failures.isEmpty(), List.copyOf(failures));
    }
}
