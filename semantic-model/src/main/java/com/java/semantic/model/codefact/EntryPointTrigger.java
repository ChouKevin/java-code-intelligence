package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Optional;

public record EntryPointTrigger(
        Optional<String> httpMethod,
        Optional<String> httpPath,
        Optional<ExternalTarget.Destination> destination,
        Optional<String> schedule) {

    public EntryPointTrigger {
        httpMethod = Objects.requireNonNull(httpMethod, "HTTP method is required").map(value -> ModelValidation.requiredText(value, "HTTP method"));
        httpPath = Objects.requireNonNull(httpPath, "HTTP path is required").map(value -> ModelValidation.requiredText(value, "HTTP path"));
        destination = Objects.requireNonNull(destination, "destination is required");
        schedule = Objects.requireNonNull(schedule, "schedule is required").map(value -> ModelValidation.requiredText(value, "schedule"));
    }

    public String canonicalForm() {
        return optionalForm(httpMethod) + optionalForm(httpPath) + optionalForm(destination.map(ExternalTarget.Destination::canonicalForm))
                + optionalForm(schedule);
    }

    private static String optionalForm(Optional<String> value) {
        return value.map(entry -> "[1][" + entry.length() + "]" + entry).orElse("[0]");
    }
}
