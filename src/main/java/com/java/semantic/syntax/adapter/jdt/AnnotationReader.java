package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 以型別化 AST 讀取 annotation
 * <p>
 * 這裡完全不呼叫 ASTNode#toString，JDT 的 NaiveASTFlattener 與 JavaParser 的 pretty printer
 * 排版不同，任何字串手術都會在兩者之間靜默漂移
 * <p>
 * 屬性採真正的優先序：依呼叫端給定的順序逐一嘗試，第一個「有宣告且解析出非空值」的屬性勝出
 * 舊分析器把 varargs 收進 HashSet 做成員測試，實際上是原始碼順序決定勝負
 */
final class AnnotationReader {

    private static final String IMPLICIT_ATTRIBUTE = "value";

    private AnnotationReader() {
    }

    // --- 尋找 annotation ---

    /** 依最後一段名稱比對，@org.springframework...GetMapping 也會命中 GetMapping */
    static Optional<Annotation> find(BodyDeclaration declaration, String simpleName) {
        return annotationsOf(declaration).stream()
                .filter(annotation -> simpleName.equals(simpleNameOf(annotation)))
                .findFirst();
    }

    /** 依給定順序尋找第一個命中的 annotation，順序即優先序 */
    static Optional<Annotation> findAny(BodyDeclaration declaration, List<String> simpleNamesInPriorityOrder) {
        for (String simpleName : simpleNamesInPriorityOrder) {
            Optional<Annotation> found = find(declaration, simpleName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    static boolean isPresent(BodyDeclaration declaration, String simpleName) {
        return find(declaration, simpleName).isPresent();
    }

    static boolean isAnyPresent(BodyDeclaration declaration, List<String> simpleNames) {
        return findAny(declaration, simpleNames).isPresent();
    }

    static List<Annotation> annotationsOf(BodyDeclaration declaration) {
        List<Annotation> annotations = new ArrayList<>();
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation annotation) {
                annotations.add(annotation);
            }
        }
        return List.copyOf(annotations);
    }

    /** annotation 的最後一段名稱 */
    static String simpleNameOf(Annotation annotation) {
        return lastSegmentOf(annotation.getTypeName());
    }

    /**
     * annotation 在原始碼中寫出的完整名稱
     * <p>
     * 與 simpleNameOf 是兩套刻意並存的比對制度：metadata 保留寫法原貌，掃描比對只看最後一段
     */
    static String writtenNameOf(Annotation annotation) {
        return annotation.getTypeName().getFullyQualifiedName();
    }

    // --- 讀取屬性 ---

    /**
     * 依優先序讀取字串屬性，回傳該屬性的所有值
     * <p>
     * 陣列會被展開成多個值而非以逗號串接，@GetMapping({"/a","/b"}) 因此是兩條路由
     */
    static List<String> stringValues(Annotation annotation, String... attributesInPriorityOrder) {
        for (String attribute : attributesInPriorityOrder) {
            Optional<Expression> expression = attributeOf(annotation, attribute);
            if (expression.isEmpty()) {
                continue;
            }
            List<String> values = resolveStrings(expression.get());
            if (!CollectionUtils.isEmpty(values)) {
                return values;
            }
        }
        return List.of();
    }

    /** 依優先序讀取單一字串屬性 */
    static Optional<String> stringValue(Annotation annotation, String... attributesInPriorityOrder) {
        List<String> values = stringValues(annotation, attributesInPriorityOrder);
        return CollectionUtils.isEmpty(values) ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 讀取列舉常量屬性的簡單名稱，例如 method = {RequestMethod.GET, RequestMethod.POST} → [GET, POST]
     */
    static List<String> enumConstantNames(Annotation annotation, String attribute) {
        return attributeOf(annotation, attribute)
                .map(AnnotationReader::resolveEnumConstants)
                .orElseGet(List::of);
    }

    static Optional<Boolean> booleanValue(Annotation annotation, String attribute) {
        return attributeOf(annotation, attribute).flatMap(expression -> {
            if (expression instanceof BooleanLiteral literal) {
                return Optional.of(literal.booleanValue());
            }
            Object constant = expression.resolveConstantExpressionValue();
            return constant instanceof Boolean value ? Optional.of(value) : Optional.empty();
        });
    }

    /** 讀取數值屬性，@Scheduled(fixedDelay = 5000) 這類寫法用得到 */
    static Optional<Long> numberValue(Annotation annotation, String attribute) {
        return attributeOf(annotation, attribute).flatMap(expression -> {
            Object constant = expression.resolveConstantExpressionValue();
            return constant instanceof Number value ? Optional.of(value.longValue()) : Optional.empty();
        });
    }

    /** 屬性是否有被宣告，與它解析出什麼值無關 */
    static boolean hasAttribute(Annotation annotation, String attribute) {
        return attributeOf(annotation, attribute).isPresent();
    }

    private static Optional<Expression> attributeOf(Annotation annotation, String attribute) {
        if (annotation instanceof SingleMemberAnnotation single) {
            return IMPLICIT_ATTRIBUTE.equals(attribute)
                    ? Optional.of(single.getValue())
                    : Optional.empty();
        }
        if (annotation instanceof NormalAnnotation normal) {
            for (Object value : normal.values()) {
                MemberValuePair pair = (MemberValuePair) value;
                if (attribute.equals(pair.getName().getIdentifier())) {
                    return Optional.of(pair.getValue());
                }
            }
        }
        return Optional.empty();
    }

    // --- 表達式解析 ---

    private static List<String> resolveStrings(Expression expression) {
        if (expression instanceof ArrayInitializer array) {
            List<String> values = new ArrayList<>();
            for (Object element : array.expressions()) {
                resolveString((Expression) element).ifPresent(values::add);
            }
            return List.copyOf(values);
        }
        return resolveString(expression).map(List::of).orElseGet(List::of);
    }

    /**
     * 解析成字串
     * <p>
     * 先問 JDT 的編譯期常量，字面值、跨檔案常量與 "a" + "b" 的摺疊都由這一步涵蓋
     * 解析不出來時退回原始碼中寫出的名稱，仍走型別化節點，不經過任何 printer
     */
    private static Optional<String> resolveString(Expression expression) {
        Object constant = expression.resolveConstantExpressionValue();
        if (constant instanceof String value) {
            return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
        }
        if (expression instanceof StringLiteral literal) {
            String value = literal.getLiteralValue();
            return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
        }
        if (Objects.nonNull(constant)) {
            return Optional.of(String.valueOf(constant));
        }
        return writtenNameOf(expression);
    }

    private static List<String> resolveEnumConstants(Expression expression) {
        if (expression instanceof ArrayInitializer array) {
            List<String> names = new ArrayList<>();
            for (Object element : array.expressions()) {
                lastSegmentOf((Expression) element).ifPresent(names::add);
            }
            return List.copyOf(names);
        }
        return lastSegmentOf(expression).map(List::of).orElseGet(List::of);
    }

    private static Optional<String> writtenNameOf(Expression expression) {
        if (expression instanceof Name name) {
            return Optional.of(name.getFullyQualifiedName());
        }
        if (expression instanceof FieldAccess fieldAccess) {
            return Optional.of(fieldAccess.getName().getIdentifier());
        }
        return Optional.empty();
    }

    private static Optional<String> lastSegmentOf(Expression expression) {
        if (expression instanceof Name name) {
            return Optional.of(lastSegmentOf(name));
        }
        if (expression instanceof FieldAccess fieldAccess) {
            return Optional.of(fieldAccess.getName().getIdentifier());
        }
        return Optional.empty();
    }

    private static String lastSegmentOf(Name name) {
        if (name instanceof QualifiedName qualified) {
            return qualified.getName().getIdentifier();
        }
        return ((SimpleName) name).getIdentifier();
    }
}
