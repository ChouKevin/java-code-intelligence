package com.java.semantic.model.codefact;

import java.util.Objects;

/** Typed target for a relation occurrence, preserving internal and external target semantics. */
public sealed interface RelationTarget permits RelationTarget.External, RelationTarget.Internal {

    String canonicalForm();

    record Internal(CodeFactIdentity identity) implements RelationTarget {

        public Internal {
            identity = Objects.requireNonNull(identity, "internal target identity is required");
        }

        @Override
        public String canonicalForm() {
            return "internal" + framed(identity.canonicalForm());
        }
    }

    record External(ExternalTarget target) implements RelationTarget {

        public External {
            target = Objects.requireNonNull(target, "external target is required");
        }

        @Override
        public String canonicalForm() {
            if (target instanceof ExternalTarget.NominalType nominalType) {
                return "external-nominal-type" + framed(nominalType.type().canonicalName());
            }
            if (target instanceof ExternalTarget.Endpoint endpoint) {
                return "external-endpoint" + framed(endpoint.method()) + framed(endpoint.uri());
            }
            if (target instanceof ExternalTarget.Destination destination) {
                return "external-destination" + framed(destination.broker()) + framed(destination.destination());
            }
            if (target instanceof ExternalTarget.ConfigurationKey configurationKey) {
                return "external-configuration-key" + framed(configurationKey.key());
            }
            if (target instanceof ExternalTarget.SqlIdentifier sqlIdentifier) {
                return "external-sql-identifier" + framed(sqlIdentifier.identifier());
            }
            if (target instanceof ExternalTarget.UnresolvedCall unresolvedCall) {
                return "external-unresolved-call" + framed(unresolvedCall.expression())
                        + framed(unresolvedCall.receiver()) + framed(unresolvedCall.methodName())
                        + framed(Integer.toString(unresolvedCall.arity()));
            }
            throw new IllegalStateException("unsupported external relation target");
        }
    }

    private static String framed(String value) {
        return "[" + value.length() + "]" + value;
    }
}
