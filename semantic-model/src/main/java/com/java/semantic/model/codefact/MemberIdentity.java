package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;

/** Canonical identity for a declared field, enum constant, or record component. */
public record MemberIdentity(SourceTypeIdentity owner, String name) implements CanonicalIdentity {

    public MemberIdentity {
        owner = Objects.requireNonNull(owner, "member owner is required");
        name = ModelValidation.requiredText(name, "member name");
    }

    @Override
    public String canonicalForm() {
        return owner.canonicalForm() + "." + name;
    }
}
