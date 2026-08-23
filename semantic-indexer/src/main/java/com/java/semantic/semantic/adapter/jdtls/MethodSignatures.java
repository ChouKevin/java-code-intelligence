package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析與正規化方法簽章
 *
 * JDT 的符號名帶簽章(如「save(T) : void」)。這個類別僅保留給外部顯示與舊協定相容；
 * 精確 requested-root 選擇一律由語法 target 與其 declaration anchor 完成。
 */
final class MethodSignatures {

    private MethodSignatures() {
    }

    /**
     * 解析舊協定的外部顯示方法名或簽章
     *
     * 無括號代表裸名(hasParameters 為 false),參數比對時視為任意多載
     */
    static ParsedMethod parse(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim() : "";
        int open = value.indexOf('(');
        if (open < 0) {
            return new ParsedMethod(value, false, List.of());
        }
        String methodName = value.substring(0, open).trim();
        int close = value.indexOf(')', open);
        String inside = close > open ? value.substring(open + 1, close) : value.substring(open + 1);
        return new ParsedMethod(methodName, true, normalizeParameters(inside));
    }

    /** JDT 帶簽章名稱剝出的裸方法名 */
    static String bareName(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim() : "";
        int open = value.indexOf('(');
        return open >= 0 ? value.substring(0, open).trim() : value;
    }

    /** 型別的簡單名,去除泛型引數 */
    static String typeName(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim() : "";
        int open = firstOf(value, '<', '(', ' ');
        return open >= 0 ? value.substring(0, open).trim() : value;
    }

    static List<String> normalizeParameters(String inside) {
        if (!StringUtils.hasText(inside)) {
            return List.of();
        }
        return JavaIdentityNormalizer.parameterTypes(splitParameters(inside)).stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 依角括號深度切分參數列,巢狀泛型中的逗號不會誤切
     *
     * 「Map&lt;String, Order&gt;, Long」切成兩段而非三段
     */
    static List<String> splitParameters(String inside) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < inside.length(); index++) {
            char current = inside.charAt(index);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(inside.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(inside.substring(start));
        return parts;
    }

    /** 去除泛型與套件前綴,可變參數轉為陣列 */
    static String normalizeType(String type) {
        return JavaIdentityNormalizer.parameterType(type);
    }

    private static int firstOf(String value, char... markers) {
        int found = -1;
        for (char marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }

    /** 解析後的方法名與正規化參數 */
    record ParsedMethod(String methodName, boolean hasParameters, List<String> parameterTypes) {
    }
}
