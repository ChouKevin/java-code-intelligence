package com.java.semantic.syntax.domain;

import java.util.List;

/**
 * 輕量型別 metadata
 *
 * @param className          巢狀名稱，例如 Outer.Inner
 * @param packageName        套件名稱
 * @param fullyQualifiedName packageName 與 className 組合，巢狀型別含外層名稱
 * @param filePath           source root 之下的檔案相對路徑
 * @param kind               型別種類
 * @param isAbstract         是否為抽象類別
 * @param implementedTypes   直接實作的介面，簡單名稱
 * @param extendedTypes      直接繼承的型別，簡單名稱
 * @param annotations        類別層 annotation，保留原始碼寫法
 * @param imports            編譯單元的 import
 * @param fields             欄位
 * @param methods            方法
 * @param hasFluentAccessors 是否標註 @Accessors(fluent = true)
 * @param profiles           @Profile 宣告的 profile
 */
public record ClassMetadata(
        String className,
        String packageName,
        String fullyQualifiedName,
        String filePath,
        TypeKind kind,
        boolean isAbstract,
        List<String> implementedTypes,
        List<String> extendedTypes,
        List<String> annotations,
        List<String> imports,
        List<FieldInfo> fields,
        List<MethodSignature> methods,
        boolean hasFluentAccessors,
        List<String> profiles) {

    public ClassMetadata {
        implementedTypes = List.copyOf(implementedTypes);
        extendedTypes = List.copyOf(extendedTypes);
        annotations = List.copyOf(annotations);
        imports = List.copyOf(imports);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        profiles = List.copyOf(profiles);
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
     */
    public record MethodSignature(
            String name,
            List<String> paramTypes,
            List<String> annotations,
            String sql,
            SqlSource sqlSource,
            int startLine,
            int endLine) {

        public MethodSignature {
            paramTypes = List.copyOf(paramTypes);
            annotations = List.copyOf(annotations);
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
     * @param name 欄位名稱
     * @param type 型別簡單名稱，去除泛型
     */
    public record FieldInfo(String name, String type) {
    }
}
