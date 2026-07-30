package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 輕量型別 metadata
 *
 * @param className          巢狀名稱，例如 Outer.Inner
 * @param packageName        套件名稱
 * @param fullyQualifiedName packageName 與 className 組合，巢狀型別含外層名稱
 * @param sourceFile         儲存庫根目錄下的檔案相對路徑，與 MethodTarget.sourceFile 語意一致
 * @param kind               型別種類
 * @param isAbstract         是否為抽象類別
 * @param implementedTypes   直接實作的介面，簡單名稱
 * @param extendedTypes      直接繼承的型別，簡單名稱
 * @param annotations        類別層 annotation，保留原始碼寫法
 * @param imports            編譯單元的 import
 * @param fields             欄位
 * @param methods            方法
 * @param hasFluentAccessors 是否標註 @Accessors(fluent = true)
 * @param hasChainedAccessors @Accessors 的有效 chain 值；未明示時沿用 fluent
 * @param profiles           @Profile 宣告的 profile
 * @param range              型別宣告的精確範圍
 * @param source             型別宣告的完整原始碼
 * @param primary            是否標註 @Primary
 * @param beanQualifiers     型別宣告的 bean qualifier
 */
public record ClassMetadata(
        String className,
        String packageName,
        String fullyQualifiedName,
        String sourceFile,
        TypeKind kind,
        boolean isAbstract,
        List<String> implementedTypes,
        List<String> extendedTypes,
        List<String> annotations,
        List<String> imports,
        List<FieldInfo> fields,
        List<MethodSignature> methods,
        boolean hasFluentAccessors,
        boolean hasChainedAccessors,
        List<String> profiles,
        SyntaxRange range,
        SourceSlice source,
        boolean primary,
        List<String> beanQualifiers,
        List<AnnotationEvidence> annotationEvidence) {

    public ClassMetadata {
        implementedTypes = List.copyOf(implementedTypes);
        extendedTypes = List.copyOf(extendedTypes);
        annotations = List.copyOf(annotations);
        imports = List.copyOf(imports);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        profiles = List.copyOf(profiles);
        Objects.requireNonNull(range, "range is required");
        Objects.requireNonNull(source, "source is required");
        beanQualifiers = List.copyOf(beanQualifiers);
        annotationEvidence = List.copyOf(annotationEvidence);
    }

    /** 保留舊 metadata 建構子；舊資料沒有已解析的註解 identity */
    public ClassMetadata(
            String className, String packageName, String fullyQualifiedName, String sourceFile, TypeKind kind,
            boolean isAbstract, List<String> implementedTypes, List<String> extendedTypes,
            List<String> annotations, List<String> imports, List<FieldInfo> fields, List<MethodSignature> methods,
            boolean hasFluentAccessors, boolean hasChainedAccessors, List<String> profiles, SyntaxRange range,
            SourceSlice source, boolean primary, List<String> beanQualifiers) {
        this(className, packageName, fullyQualifiedName, sourceFile, kind, isAbstract, implementedTypes, extendedTypes,
                annotations, imports, fields, methods, hasFluentAccessors, hasChainedAccessors, profiles, range,
                source, primary, beanQualifiers, List.of());
    }

    /** 型別種類 */
    public enum TypeKind {
        CLASS, INTERFACE, RECORD, ENUM
    }

    /**
     * 方法簽名快照
     *
     * @param name        方法名稱
     * @param paramTypes  參數型別，簡單名稱且去除泛型
     * @param annotations 方法 annotation，保留原始碼寫法
     * @param sql         MyBatis SQL，annotation 優先於 XML；兩者皆無時為 null
     * @param sqlSource   sql 的來源，sql 為 null 時亦為 null
     * @param startLine   起始行，1 起算
     * @param endLine     結束行，1 起算
     * @param range       方法宣告的精確範圍
     * @param source      方法宣告的完整原始碼
     * @param parameterTypeReferences 保留泛型與 binding 的參數型別
     * @param returnType  保留泛型與 binding 的回傳型別，建構子為空
     * @param invocations 方法內的呼叫語法證據
     * @param bodyTypeReferences 方法本體與 annotation member 的 binding 已證實型別 identity
     * @param abstractDeclaration 是否具備 abstract 宣告語意：明確 abstract，或可覆寫且無 method body 的 interface method
     */
    public record MethodSignature(
            String name,
            List<String> paramTypes,
            List<String> annotations,
            String sql,
            SqlSource sqlSource,
            int startLine,
            int endLine,
            SyntaxRange range,
            SourceSlice source,
            List<TypeReference> parameterTypeReferences,
            Optional<TypeReference> returnType,
            List<SyntaxInvocation> invocations,
            List<AnnotationEvidence> annotationEvidence,
            List<ResolvedTypeIdentity> bodyTypeReferences,
            SyntaxPosition namePosition,
            MethodTargetResolution analysisTarget,
            boolean executableDeclaration,
            boolean abstractDeclaration,
            boolean overridableDeclaration) {

        public MethodSignature {
            paramTypes = List.copyOf(paramTypes);
            annotations = List.copyOf(annotations);
            Objects.requireNonNull(range, "range is required");
            Objects.requireNonNull(source, "source is required");
            parameterTypeReferences = List.copyOf(parameterTypeReferences);
            Objects.requireNonNull(returnType, "returnType is required");
            invocations = List.copyOf(invocations);
            annotationEvidence = List.copyOf(annotationEvidence);
            bodyTypeReferences = List.copyOf(Objects.requireNonNull(
                    bodyTypeReferences, "bodyTypeReferences is required"));
            Objects.requireNonNull(namePosition, "namePosition is required");
            Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        }

        /** 參數個數 */
        public int paramCount() {
            return paramTypes.size();
        }
    }

    /** SQL 的來源 */
    public enum SqlSource {

        /** @Select / @Insert / @Update / @Delete */
        ANNOTATION,

        /** MyBatis mapper XML */
        MAPPER_XML
    }

    /**
     * 欄位
     *
     * @param name          欄位名稱
     * @param type          型別簡單名稱，去除泛型
     * @param annotations   欄位 annotation，保留原始碼寫法
     * @param qualifier     @Qualifier 的值，未宣告或無法解析時為空字串
     * @param typeReference 保留泛型與 binding 的型別
     */
    public record FieldInfo(
            String name,
            String type,
            List<String> annotations,
            String qualifier,
            TypeReference typeReference,
            List<AnnotationEvidence> annotationEvidence) {

        public FieldInfo {
            annotations = List.copyOf(annotations);
            qualifier = Objects.requireNonNullElse(qualifier, "");
            Objects.requireNonNull(typeReference, "typeReference is required");
            annotationEvidence = List.copyOf(annotationEvidence);
        }

        /** 保留舊 metadata 建構子；舊資料沒有已解析的註解 identity。 */
        public FieldInfo(
                String name, String type, List<String> annotations, String qualifier, TypeReference typeReference) {
            this(name, type, annotations, qualifier, typeReference, List.of());
        }
    }
}
