package com.java.semantic.syntax.application;

/** 型別成員探索的 source-qualified 型別不存在於固定 syntax snapshot */
public final class TypeMemberTypeNotFoundException extends RuntimeException {

    public TypeMemberTypeNotFoundException() {
        super("source-qualified type was not found");
    }
}
