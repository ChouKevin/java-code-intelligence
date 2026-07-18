package com.java.semantic.syntax.adapter.jdt;

import java.util.Objects;

import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.springframework.util.StringUtils;

/** 套件名稱工具 */
final class PackageNames {

    private PackageNames() {
    }

    /** 編譯單元的套件名稱，預設套件回傳空字串 */
    static String of(ParsedSource parsed) {
        PackageDeclaration declaration = parsed.unit().getPackage();
        return Objects.isNull(declaration) ? "" : declaration.getName().getFullyQualifiedName();
    }

    /** 套件名稱與型別名稱組合 */
    static String qualify(String packageName, String typeName) {
        return StringUtils.hasText(packageName) ? packageName + "." + typeName : typeName;
    }
}
