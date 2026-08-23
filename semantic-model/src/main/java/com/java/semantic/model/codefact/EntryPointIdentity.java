package com.java.semantic.model.codefact;

import java.util.Objects;

/** Canonical identity of a discovered entry point. */
public record EntryPointIdentity(
        EntryPointKind entryPointKind,
        MethodTarget method,
        EntryPointTrigger trigger) implements CanonicalIdentity {

    public EntryPointIdentity {
        entryPointKind = Objects.requireNonNull(entryPointKind, "entry point kind is required");
        method = Objects.requireNonNull(method, "entry point method is required");
        trigger = Objects.requireNonNull(trigger, "entry point trigger is required");
    }

    @Override
    public String canonicalForm() {
        return "entry-point" + framed(entryPointKind.name()) + framed(method.canonicalForm())
                + framed(trigger.canonicalForm());
    }

    private static String framed(String value) {
        return "[" + value.length() + "]" + value;
    }
}
