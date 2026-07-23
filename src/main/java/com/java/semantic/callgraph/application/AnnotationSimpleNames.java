package com.java.semantic.callgraph.application;

/**
 * 從原始碼寫法的 annotation 字串取出簡單名稱
 * <p>
 * 原始碼寫法可能包含完整路徑與引數，如 {@code lombok.Data} 或 {@code Mapper("value")}；
 * 皆須先去除引數，再取最後一段路徑
 */
final class AnnotationSimpleNames {

    private AnnotationSimpleNames() {
    }

    /**
     * 取得 annotation 的簡單名稱
     *
     * @param writtenAnnotation 原始碼寫法的 annotation 字串
     * @return 去除引數與套件路徑後的簡單名稱
     */
    static String simpleName(String writtenAnnotation) {
        int parenIndex = writtenAnnotation.indexOf('(');
        String withoutArguments = parenIndex >= 0 ? writtenAnnotation.substring(0, parenIndex) : writtenAnnotation;
        int lastDot = withoutArguments.lastIndexOf('.');
        return (lastDot >= 0 ? withoutArguments.substring(lastDot + 1) : withoutArguments).trim();
    }
}
