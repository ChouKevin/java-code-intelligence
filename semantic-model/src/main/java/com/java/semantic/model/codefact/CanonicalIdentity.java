package com.java.semantic.model.codefact;

public sealed interface CanonicalIdentity permits EntryPointIdentity, JavaTypeIdentity, MapperStatementIdentity,
        MethodTarget, RelationIdentity, SourceTypeIdentity {

    String canonicalForm();
}
