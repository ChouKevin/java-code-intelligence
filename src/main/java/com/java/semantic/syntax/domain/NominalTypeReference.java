package com.java.semantic.syntax.domain;

/** 可由宣告名稱識別的名義型別證據 */
public sealed interface NominalTypeReference extends TypeReference permits NamedTypeReference, ParameterizedTypeReference {

    String simpleTypeName();
}
