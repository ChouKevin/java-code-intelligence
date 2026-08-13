package com.java.semantic.syntax.domain;

import java.util.List;

/** 來源型別的欄位、方法與值型成員證據 */
public record SourceTypeMembers(
        List<SourceFieldMetadata> fields,
        List<SourceMethodMetadata> methods,
        List<SourceEnumConstantMetadata> enumConstants,
        List<SourceRecordComponentMetadata> recordComponents,
        boolean fluentSetters,
        boolean chainedAccessors) {

    public SourceTypeMembers {
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        enumConstants = List.copyOf(enumConstants);
        recordComponents = List.copyOf(recordComponents);
    }
}
