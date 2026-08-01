package com.java.semantic.syntax.application.concept;

/** 固定 revision catalog 中不存在指定的精確 concept identity */
public final class ConceptIdentityNotFoundException extends RuntimeException {

    public ConceptIdentityNotFoundException() {
        super("concept identity was not found");
    }
}
