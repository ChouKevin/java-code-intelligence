package com.java.semantic.model.codefact;

import java.util.Objects;

/** Canonical identity of one relation occurrence. */
public record RelationIdentity(
        CodeFactIdentity from,
        RelationKind relationKind,
        RelationTarget target,
        SourceRange occurrence) implements CanonicalIdentity {

    public RelationIdentity {
        from = Objects.requireNonNull(from, "relation source is required");
        relationKind = Objects.requireNonNull(relationKind, "relation kind is required");
        target = Objects.requireNonNull(target, "relation target is required");
        occurrence = Objects.requireNonNull(occurrence, "relation occurrence is required");
    }

    @Override
    public String canonicalForm() {
        return "relation" + framed(from.canonicalForm()) + framed(relationKind.name())
                + framed(target.canonicalForm()) + framed(occurrence.sourceFile())
                + framed(Integer.toString(occurrence.range().start().line()))
                + framed(Integer.toString(occurrence.range().start().character()))
                + framed(Integer.toString(occurrence.range().end().line()))
                + framed(Integer.toString(occurrence.range().end().character()));
    }

    private static String framed(String value) {
        return "[" + value.length() + "]" + value;
    }
}
