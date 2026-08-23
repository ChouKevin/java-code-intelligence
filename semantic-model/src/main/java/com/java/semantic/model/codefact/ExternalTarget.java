package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public sealed interface ExternalTarget permits ExternalTarget.NominalType, ExternalTarget.Endpoint, ExternalTarget.Destination {

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
}
