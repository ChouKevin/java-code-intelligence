package com.example.evidence;

import static com.example.evidence.Audit.record;

import java.util.List;
import java.util.function.Function;

class TypeEvidenceFixture {

    @PolicyMarker.Nested
    List<? super SecretDto> inspect(List<? extends SecretDto> values) {
        accept(values);
        OuterIdentity.Inner created = new OuterIdentity.Inner();
        created.overloaded(values);
        Function<List<? extends SecretDto>, String> reference = created::overloaded;
        record("policy");
        varargs("policy");
        return List.of();
    }

    void accept(List<? extends SecretDto> values) {
    }

    void varargs(String... values) {
    }
}

class OuterIdentity {

    static class Inner {

        Inner() {
        }

        String overloaded(List<? extends SecretDto> values) {
            return "";
        }
    }
}

class SecretDto {
}

@interface PolicyMarker {

    @interface Nested {
    }
}
