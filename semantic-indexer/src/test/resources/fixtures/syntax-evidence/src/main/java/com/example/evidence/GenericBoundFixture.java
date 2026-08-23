package com.example.evidence;

class GenericBoundFixture {

    <T extends ForbiddenDto & ForbiddenMarker> T load(T value) {
        // METHOD_PARAMETER_RETURN_BOUND_FORBIDDEN_SENTINEL
        return value;
    }

    BoundedBox<?> related(BoundedBox<?> value) {
        return value;
    }

    <S extends Comparable<S>> S echo(S value) {
        return value;
    }
}

class BoundedBox<T extends ForbiddenDto> {

    // RELATED_CLASS_BOUND_FORBIDDEN_SENTINEL
    private T value;
}

class ForbiddenDto {

    // FORBIDDEN_DTO_BOUND_SENTINEL
}

interface ForbiddenMarker {
}
