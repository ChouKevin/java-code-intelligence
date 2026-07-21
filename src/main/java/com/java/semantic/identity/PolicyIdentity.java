package com.java.semantic.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.util.StringUtils;

/**
 * 供讀取政策與語法證據共用的 Java 宣告識別正規化。
 *
 * <p>類別名稱相對於其套件保留巢狀路徑，方法參數則採與語意簽章一致的簡單名稱。</p>
 */
public final class PolicyIdentity {

    private PolicyIdentity() {
    }

    public static String className(String packageName, String declarationName) {
        Objects.requireNonNull(packageName, "packageName is required");
        String value = Objects.requireNonNull(declarationName, "declarationName is required").trim()
                .replace('$', '.');
        String prefix = StringUtils.hasText(packageName) ? packageName + "." : "";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    public static List<String> parameterTypes(List<String> parameterTypes) {
        Objects.requireNonNull(parameterTypes, "parameterTypes is required");
        List<String> normalized = new ArrayList<>();
        for (String parameterType : parameterTypes) {
            normalized.add(parameterType(parameterType));
        }
        return List.copyOf(normalized);
    }

    public static String parameterType(String parameterType) {
        String value = Objects.requireNonNull(parameterType, "parameterType is required").trim();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            int genericEnd = value.lastIndexOf('>');
            String suffix = genericEnd >= genericStart ? value.substring(genericEnd + 1) : "";
            value = value.substring(0, genericStart) + suffix;
        }
        value = value.replace("...", "[]").trim();
        StringBuilder arraySuffix = new StringBuilder();
        while (value.endsWith("[]")) {
            arraySuffix.append("[]");
            value = value.substring(0, value.length() - 2).trim();
        }
        int lastSegment = value.lastIndexOf('.');
        String simpleName = lastSegment >= 0 ? value.substring(lastSegment + 1) : value;
        return simpleName + arraySuffix;
    }
}
