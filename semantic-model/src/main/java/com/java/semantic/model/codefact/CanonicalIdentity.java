package com.java.semantic.model.codefact;

public sealed interface CanonicalIdentity permits EntryPointIdentity, JavaTypeIdentity, MapperStatementIdentity,
        MemberIdentity, MethodTarget, RelationIdentity, SourceTypeIdentity {

    String canonicalForm();
}
