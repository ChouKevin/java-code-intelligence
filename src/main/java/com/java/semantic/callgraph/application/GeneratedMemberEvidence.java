package com.java.semantic.callgraph.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.domain.SyntaxInvocation.InvocationKind;

import org.springframework.util.StringUtils;

/**
 * 偵測 Lombok 等工具在編譯期生成、原始碼中不存在宣告的成員呼叫
 * <p>
 * Wave 1 規則集：getter、setter、object method（toString/equals/hashCode）、builder 進入點、
 * builder chain、由 {@code @NoArgsConstructor}/{@code @AllArgsConstructor} 生成的建構子
 * <p>
 * 每條規則皆為 fail-closed：任何無法明確判定的情況一律回傳空值，交由呼叫端視為非生成成員
 * <p>
 * 純元件，不做任何 I/O 或記錄
 */
public final class GeneratedMemberEvidence {

    private static final double CONFIDENCE = 1.0d;

    private static final Set<String> GETTER_ANNOTATIONS = Set.of("Data", "Getter", "Value");
    private static final Set<String> SETTER_ANNOTATIONS = Set.of("Data", "Setter");
    private static final Set<String> OBJECT_METHOD_ANNOTATIONS = Set.of("Data", "Value");
    private static final Set<String> BUILDER_ANNOTATIONS = Set.of("Builder", "SuperBuilder");
    private static final Set<String> NO_ARGS_CONSTRUCTOR_ANNOTATION = Set.of("NoArgsConstructor");
    private static final Set<String> ALL_ARGS_CONSTRUCTOR_ANNOTATION = Set.of("AllArgsConstructor");
    private static final Set<String> ZERO_ARITY_OBJECT_METHODS = Set.of("toString", "hashCode");

    /**
     * 評估一次呼叫語法證據是否對應生成成員規則
     *
     * @param receiverType 呼叫目標所屬型別 metadata；builder chain 一律傳入外層已標註型別
     * @param invocation   呼叫語法證據
     * @return 命中的證據，未命中任何規則時為空
     */
    Optional<EvidenceMatch> evaluate(ClassMetadata receiverType, SyntaxInvocation invocation) {
        Objects.requireNonNull(receiverType, "receiverType is required");
        Objects.requireNonNull(invocation, "invocation is required");

        Optional<Integer> arity = arityOf(invocation.expression());
        if (arity.isEmpty()) {
            return Optional.empty();
        }
        String invokedName = invokedNameOf(invocation);
        if (!StringUtils.hasText(invokedName) || hasSourceDeclaration(receiverType, invokedName, arity.orElseThrow())) {
            return Optional.empty();
        }

        int argumentCount = arity.orElseThrow();
        return matchGetter(receiverType, invocation, invokedName, argumentCount)
                .or(() -> matchSetter(receiverType, invocation, invokedName, argumentCount))
                .or(() -> matchObjectMethod(receiverType, invocation, invokedName, argumentCount))
                .or(() -> matchBuilderEntry(receiverType, invocation, invokedName, argumentCount))
                .or(() -> matchBuilderChain(receiverType, invocation, invokedName, argumentCount))
                .or(() -> matchGeneratedConstructor(receiverType, invocation, argumentCount));
    }

    private boolean hasSourceDeclaration(ClassMetadata receiverType, String invokedName, int argumentCount) {
        return receiverType.methods().stream()
                .anyMatch(declared -> declared.name().equals(invokedName) && declared.paramCount() == argumentCount);
    }

    private Optional<EvidenceMatch> matchGetter(
            ClassMetadata receiverType, SyntaxInvocation invocation, String invokedName, int argumentCount) {
        if (!isMethodOnReceiver(receiverType, invocation) || argumentCount != 0) {
            return Optional.empty();
        }
        Optional<String> annotation = matchedAnnotation(receiverType, GETTER_ANNOTATIONS);
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> field = accessorField(receiverType, invokedName, "get")
                .or(() -> accessorField(receiverType, invokedName, "is"))
                .or(() -> fluentField(receiverType, invokedName));
        return field.map(matchedField -> matched(
                receiverType.fullyQualifiedName() + "#get" + capitalize(matchedField) + "()",
                annotation.orElseThrow(), "getter", Optional.of(matchedField)));
    }

    private Optional<EvidenceMatch> matchSetter(
            ClassMetadata receiverType, SyntaxInvocation invocation, String invokedName, int argumentCount) {
        if (!isMethodOnReceiver(receiverType, invocation) || argumentCount != 1) {
            return Optional.empty();
        }
        Optional<String> annotation = matchedAnnotation(receiverType, SETTER_ANNOTATIONS);
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> field = accessorField(receiverType, invokedName, "set")
                .or(() -> fluentField(receiverType, invokedName));
        return field.map(matchedField -> matched(
                receiverType.fullyQualifiedName() + "#set" + capitalize(matchedField) + "(..)",
                annotation.orElseThrow(), "setter", Optional.of(matchedField)));
    }

    private Optional<EvidenceMatch> matchObjectMethod(
            ClassMetadata receiverType, SyntaxInvocation invocation, String invokedName, int argumentCount) {
        if (!isMethodOnReceiver(receiverType, invocation)) {
            return Optional.empty();
        }
        boolean shapeMatches = (ZERO_ARITY_OBJECT_METHODS.contains(invokedName) && argumentCount == 0)
                || ("equals".equals(invokedName) && argumentCount == 1);
        if (!shapeMatches) {
            return Optional.empty();
        }
        Optional<String> annotation = matchedAnnotation(receiverType, OBJECT_METHOD_ANNOTATIONS);
        return annotation.map(matchedAnnotation -> matched(
                receiverType.fullyQualifiedName() + "#" + invokedName + argumentSuffix(argumentCount),
                matchedAnnotation, "object method", Optional.empty()));
    }

    private Optional<EvidenceMatch> matchBuilderEntry(
            ClassMetadata receiverType, SyntaxInvocation invocation, String invokedName, int argumentCount) {
        if (!isMethodOnReceiver(receiverType, invocation) || argumentCount != 0 || !"builder".equals(invokedName)) {
            return Optional.empty();
        }
        Optional<String> annotation = matchedAnnotation(receiverType, BUILDER_ANNOTATIONS);
        return annotation.map(matchedAnnotation -> matched(
                receiverType.fullyQualifiedName() + "#builder()", matchedAnnotation, "builder entry", Optional.empty()));
    }

    private Optional<EvidenceMatch> matchBuilderChain(
            ClassMetadata receiverType, SyntaxInvocation invocation, String invokedName, int argumentCount) {
        if (invocation.kind() != InvocationKind.METHOD) {
            return Optional.empty();
        }
        String builderFqn = builderFqnOf(receiverType);
        if (!invocation.receiverDeclaration().endsWith(builderFqn)) {
            return Optional.empty();
        }
        boolean isBuildMethod = "build".equals(invokedName) && argumentCount == 0;
        boolean isFieldSetter = argumentCount == 1 && fieldExists(receiverType, invokedName);
        if (!isBuildMethod && !isFieldSetter) {
            return Optional.empty();
        }
        Optional<String> annotation = matchedAnnotation(receiverType, BUILDER_ANNOTATIONS);
        if (annotation.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> field = isBuildMethod ? Optional.empty() : Optional.of(invokedName);
        return Optional.of(matched(
                builderFqn + "#" + invokedName + argumentSuffix(argumentCount),
                annotation.orElseThrow(), "builder chain", field));
    }

    private Optional<EvidenceMatch> matchGeneratedConstructor(
            ClassMetadata receiverType, SyntaxInvocation invocation, int argumentCount) {
        if (invocation.kind() != InvocationKind.CONSTRUCTOR
                || !receiverType.fullyQualifiedName().equals(invocation.receiverDeclaration())) {
            return Optional.empty();
        }
        boolean noArgsMatch = argumentCount == 0
                && matchedAnnotation(receiverType, NO_ARGS_CONSTRUCTOR_ANNOTATION).isPresent();
        boolean allArgsMatch = argumentCount == receiverType.fields().size()
                && matchedAnnotation(receiverType, ALL_ARGS_CONSTRUCTOR_ANNOTATION).isPresent();
        if (!noArgsMatch && !allArgsMatch) {
            return Optional.empty();
        }
        String annotation = noArgsMatch ? "NoArgsConstructor" : "AllArgsConstructor";
        return Optional.of(matched(
                receiverType.fullyQualifiedName() + "#<init>(" + argumentCount + ")",
                annotation, "constructor", Optional.empty()));
    }

    private boolean isMethodOnReceiver(ClassMetadata receiverType, SyntaxInvocation invocation) {
        return invocation.kind() == InvocationKind.METHOD
                && receiverType.fullyQualifiedName().equals(invocation.receiverDeclaration());
    }

    private Optional<String> accessorField(ClassMetadata receiverType, String invokedName, String prefix) {
        if (!invokedName.startsWith(prefix) || invokedName.length() <= prefix.length()
                || !Character.isUpperCase(invokedName.charAt(prefix.length()))) {
            return Optional.empty();
        }
        String fieldName = decapitalize(invokedName.substring(prefix.length()));
        return fieldExists(receiverType, fieldName) ? Optional.of(fieldName) : Optional.empty();
    }

    private Optional<String> fluentField(ClassMetadata receiverType, String invokedName) {
        if (!receiverType.hasFluentAccessors()) {
            return Optional.empty();
        }
        return fieldExists(receiverType, invokedName) ? Optional.of(invokedName) : Optional.empty();
    }

    private boolean fieldExists(ClassMetadata receiverType, String fieldName) {
        return receiverType.fields().stream().anyMatch(field -> field.name().equals(fieldName));
    }

    private Optional<String> matchedAnnotation(ClassMetadata receiverType, Set<String> expectedSimpleNames) {
        return receiverType.annotations().stream()
                .map(AnnotationSimpleNames::simpleName)
                .filter(expectedSimpleNames::contains)
                .findFirst();
    }

    private String builderFqnOf(ClassMetadata receiverType) {
        return receiverType.fullyQualifiedName() + "." + simpleClassName(receiverType) + "Builder";
    }

    private String simpleClassName(ClassMetadata receiverType) {
        String className = receiverType.className();
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    private static String argumentSuffix(int argumentCount) {
        return argumentCount == 0 ? "()" : "(..)";
    }

    private static EvidenceMatch matched(String opaqueSymbol, String annotation, String rule, Optional<String> field) {
        String suffix = field.map(matchedField -> " for field " + matchedField).orElse("");
        List<String> generatedEvidence = List.of("@" + annotation + " generated " + rule + suffix);
        return new EvidenceMatch(ResolutionStrategy.LOMBOK_GENERATED, opaqueSymbol, Optional.empty(),
                CONFIDENCE, generatedEvidence);
    }

    /**
     * 取得呼叫語法「自身」的成員名稱；receiver chain（如
     * {@code service.findOrder(id).getTotal()}）必須取尾端呼叫的名稱，而非第一個括號前的名稱
     */
    private static String invokedNameOf(SyntaxInvocation invocation) {
        String expression = invocation.expression().trim();
        if (invocation.kind() == InvocationKind.CONSTRUCTOR) {
            expression = stripPrefix(expression, "new ");
        }
        if (!expression.endsWith(")")) {
            return "";
        }
        int openIndex = matchingOpenParenIndex(expression);
        if (openIndex < 0) {
            return "";
        }
        String beforeParen = expression.substring(0, openIndex);
        int lastDot = beforeParen.lastIndexOf('.');
        return (lastDot >= 0 ? beforeParen.substring(lastDot + 1) : beforeParen).trim();
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /**
     * 計算呼叫語法「自身」尾端引數列表的引數個數；語法無法解析時回傳空值（fail-closed）
     * <p>
     * JDT 呼叫語法的 expression 保留完整 receiver chain（如
     * {@code service.findOrder(id).getTotal()}），因此必須從字串結尾往回找到與最後一個
     * {@code )} 配對的開括號，而非直接取第一個 {@code (}，否則會誤讀 chain 中前段呼叫的引數列表
     * <p>
     * 僅計算該引數列表最外層深度的逗號，巢狀括號（如 {@code set(a, f(b, c))}）內的逗號不計入
     */
    private static Optional<Integer> arityOf(String expression) {
        String trimmedExpression = expression.trim();
        if (!trimmedExpression.endsWith(")")) {
            return Optional.empty();
        }
        int openIndex = matchingOpenParenIndex(trimmedExpression);
        if (openIndex < 0) {
            return Optional.empty();
        }
        String innerArguments = trimmedExpression.substring(openIndex + 1, trimmedExpression.length() - 1).trim();
        if (innerArguments.isEmpty()) {
            return Optional.of(0);
        }
        return Optional.of(topLevelCommaCount(innerArguments) + 1);
    }

    private static int matchingOpenParenIndex(String trimmedExpression) {
        int depth = 0;
        for (int index = trimmedExpression.length() - 1; index >= 0; index--) {
            char character = trimmedExpression.charAt(index);
            if (character == ')') {
                depth++;
            } else if (character == '(') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static int topLevelCommaCount(String innerArguments) {
        int depth = 0;
        int commaCount = 0;
        for (int index = 0; index < innerArguments.length(); index++) {
            char character = innerArguments.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                commaCount++;
            }
        }
        return commaCount;
    }

    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
