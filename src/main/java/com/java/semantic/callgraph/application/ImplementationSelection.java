package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.MethodTarget;
import org.springframework.util.Assert;
import java.util.List;
import java.util.Objects;

/** Fail-closed selection of one implementation or complete unresolved alternatives. */
public sealed interface ImplementationSelection permits ImplementationSelection.Selected, ImplementationSelection.Ambiguous {

    record Selected(
            ImplementationCandidate candidate,
            ResolutionStrategy strategy) implements ImplementationSelection {

        public Selected {
            candidate = Objects.requireNonNull(candidate, "candidate is required");
            strategy = Objects.requireNonNull(strategy, "strategy is required");
        }
    }

    record Ambiguous(List<MethodTarget> candidates) implements ImplementationSelection {

        public Ambiguous {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            Assert.isTrue(candidates.size() > 1, "ambiguous candidates are required");
        }
    }
}
