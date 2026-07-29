package com.dwinovo.numen.core.task.survival.shelter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Diagnostic result of bounded static shelter recognition. */
public record ShelterAnalysis(
        Outcome outcome,
        Optional<ShelterBlueprint> blueprint,
        int sourceCellsExamined,
        int expandedCellsExamined,
        List<String> reasons) {

    public enum Outcome {
        SUPPORTED_RECTANGULAR,
        NOT_A_SHELTER,
        LIMIT_EXCEEDED
    }

    public ShelterAnalysis {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
        if (outcome == Outcome.SUPPORTED_RECTANGULAR && blueprint.isEmpty()) {
            throw new IllegalArgumentException("supported analysis requires a blueprint");
        }
        if (outcome != Outcome.SUPPORTED_RECTANGULAR && blueprint.isPresent()) {
            throw new IllegalArgumentException("rejected analysis cannot expose a blueprint");
        }
    }
}
