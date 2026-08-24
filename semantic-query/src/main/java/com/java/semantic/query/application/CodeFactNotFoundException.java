package com.java.semantic.query.application;

/** Exact fact was absent from the authorized current generation. */
public final class CodeFactNotFoundException extends RuntimeException {
    public CodeFactNotFoundException() { super("CODE_FACT_NOT_FOUND"); }
}
