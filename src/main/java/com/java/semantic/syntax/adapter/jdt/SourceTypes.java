package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * 型別走訪工具
 * <p>
 * 巢狀型別一律被展開成獨立的型別節點，每個型別只看自己的直屬成員，
 * 因此巢狀類別的方法不會被外層重複計算
 */
final class SourceTypes {

    private SourceTypes() {
    }

    /** 編譯單元中的所有型別，含任意深度的巢狀型別 */
    static List<AbstractTypeDeclaration> allTypesOf(CompilationUnit unit) {
        List<AbstractTypeDeclaration> types = new ArrayList<>();
        for (Object declaration : unit.types()) {
            collect((AbstractTypeDeclaration) declaration, types);
        }
        return List.copyOf(types);
    }

    private static void collect(AbstractTypeDeclaration type, List<AbstractTypeDeclaration> sink) {
        sink.add(type);
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof AbstractTypeDeclaration nested) {
                collect(nested, sink);
            }
        }
    }

    /** 型別的直屬方法，不遞迴進巢狀型別或匿名類別 */
    static List<MethodDeclaration> declaredMethodsOf(AbstractTypeDeclaration type) {
        List<MethodDeclaration> methods = new ArrayList<>();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method) {
                methods.add(method);
            }
        }
        return List.copyOf(methods);
    }

    /** 直屬成員宣告 */
    static List<BodyDeclaration> declaredMembersOf(AbstractTypeDeclaration type) {
        List<BodyDeclaration> members = new ArrayList<>();
        for (Object member : type.bodyDeclarations()) {
            members.add((BodyDeclaration) member);
        }
        return List.copyOf(members);
    }

    /**
     * 是否為 entry point 的候選型別，只有 class 與 interface 算數
     * <p>
     * record 與 enum 不會是 controller、listener 或排程宿主，
     * 放進來只會讓它們變成入口候選
     */
    static boolean isEntryPointCandidate(AbstractTypeDeclaration type) {
        return type instanceof TypeDeclaration && !(type instanceof RecordDeclaration);
    }

    /**
     * 是否應納入 class metadata，class、interface、record、enum 都算，annotation 宣告除外
     */
    static boolean isMetadataCandidate(AbstractTypeDeclaration type) {
        return !(type instanceof AnnotationTypeDeclaration);
    }

    /** 是否為 interface（record 與 enum 皆非） */
    static boolean isInterface(AbstractTypeDeclaration type) {
        return type instanceof TypeDeclaration typeDeclaration
                && !(type instanceof RecordDeclaration)
                && typeDeclaration.isInterface();
    }

    /** 是否為 enum */
    static boolean isEnum(AbstractTypeDeclaration type) {
        return type instanceof EnumDeclaration;
    }

    /**
     * 巢狀名稱，例如 Outer.Inner
     * <p>
     * 舊分析器只取最內層簡單名稱，導致巢狀 mapper 的 FQN 變成 com.pkg.Inner，永遠對不上 XML namespace
     */
    static String nestedName(AbstractTypeDeclaration type) {
        List<String> segments = new ArrayList<>();
        segments.add(type.getName().getIdentifier());
        ASTNode parent = type.getParent();
        while (Objects.nonNull(parent)) {
            if (parent instanceof AbstractTypeDeclaration enclosing) {
                segments.add(0, enclosing.getName().getIdentifier());
            }
            parent = parent.getParent();
        }
        return String.join(".", segments);
    }
}
