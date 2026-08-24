package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public sealed interface ExternalTarget permits ExternalTarget.NominalType, ExternalTarget.Endpoint, ExternalTarget.Destination,
        ExternalTarget.ConfigurationKey, ExternalTarget.SqlIdentifier, ExternalTarget.UnresolvedCall {

    String canonicalForm();

    record NominalType(DeclaredType type) implements ExternalTarget {

        public NominalType {
            type = Objects.requireNonNull(type, "declared type is required");
        }

        @Override
        public String canonicalForm() {
            return type.canonicalName();
        }
    }

    record Endpoint(String method, String uri) implements ExternalTarget {

        public Endpoint {
            method = ModelValidation.requiredText(method, "HTTP method");
            uri = ModelValidation.requiredText(uri, "HTTP URI");
        }

        @Override
        public String canonicalForm() {
            return method + " " + uri;
        }
    }

    record Destination(String broker, String destination) implements ExternalTarget {

        public Destination {
            broker = ModelValidation.requiredText(broker, "broker");
            destination = ModelValidation.requiredText(destination, "destination");
        }

        @Override
        public String canonicalForm() {
            return broker + ":" + destination;
        }
    }

    record ConfigurationKey(String key) implements ExternalTarget {

        public ConfigurationKey {
            key = ModelValidation.requiredText(key, "configuration key");
        }

        @Override
        public String canonicalForm() {
            return key;
        }
    }

    record SqlIdentifier(String identifier) implements ExternalTarget {

        public SqlIdentifier {
            identifier = ModelValidation.requiredText(identifier, "SQL identifier");
        }

        @Override
        public String canonicalForm() {
            return identifier;
        }
    }

    /** A call site whose exact written target could not be resolved against the current classpath. */
    record UnresolvedCall(String expression, String receiver, String methodName, int arity) implements ExternalTarget {

        public UnresolvedCall {
            expression = ModelValidation.requiredText(expression, "call expression");
            receiver = Objects.requireNonNullElse(receiver, "");
            methodName = ModelValidation.requiredText(methodName, "call method name");
            ModelValidation.require(arity >= 0, "call arity must not be negative");
        }

        @Override
        public String canonicalForm() {
            return expression + "|" + receiver + "|" + methodName + "|" + arity;
        }
    }
}
