package com.java.semantic.indexer.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.FrameworkEvidence;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import com.java.semantic.model.index.RelationContract.SourceSite;
import com.java.semantic.model.index.RelationContract.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Bounded source evidence for the fixed HTTP, event and database recognizers. */
public final class FrameworkEvidenceExtractor {
    private static final String STATIC_API = "STATIC_API";
    private static final String FULLY_QUALIFIED = "FULLY_QUALIFIED";
    private static final String DIRECT_IMPORT = "DIRECT_IMPORT";
    private static final String AMBIGUOUS_SIMPLE = "AMBIGUOUS_SIMPLE";
    private static final String PLACEHOLDER_UNCERTAINTY = "written placeholder has unresolved runtime binding";
    private static final String UNKNOWN_OVERLOAD_UNCERTAINTY = "overload signature has no verified argument-role mapping";

    private record AnnotationRule(String qualifiedName, Kind kind, String recognizerId) { }
    private record ApiMethod(String owner, String name) { }
    private record ApiFamily(Kind kind, String recognizerId, List<String> unresolvedRoles) { }
    private record ApiArguments(Map<String, Integer> literalArgumentIndexes, List<String> unresolvedRoles) {
        private ApiArguments {
            literalArgumentIndexes = Map.copyOf(literalArgumentIndexes);
            unresolvedRoles = List.copyOf(unresolvedRoles);
        }
    }
    private record AnnotationResolution(String qualifiedName, Optional<Target> target) { }
    private record TypeEvidence(String qualifiedName, String basis, List<SourceSite> supportingEvidence,
            List<String> uncertainties) { }
    private record ReceiverSyntax(String writtenName, Node evidenceNode, ParsedJavaUnit evidenceUnit) { }

    private static final List<AnnotationRule> ANNOTATION_RULES = List.of(
            new AnnotationRule("org.springframework.web.bind.annotation.RequestMapping", Kind.HTTP_EVIDENCE,
                    "spring.request-mapping"),
            new AnnotationRule("org.springframework.web.bind.annotation.GetMapping", Kind.HTTP_EVIDENCE,
                    "spring.get-mapping"),
            new AnnotationRule("org.springframework.web.bind.annotation.PostMapping", Kind.HTTP_EVIDENCE,
                    "spring.post-mapping"),
            new AnnotationRule("org.springframework.web.bind.annotation.PutMapping", Kind.HTTP_EVIDENCE,
                    "spring.put-mapping"),
            new AnnotationRule("org.springframework.web.bind.annotation.DeleteMapping", Kind.HTTP_EVIDENCE,
                    "spring.delete-mapping"),
            new AnnotationRule("org.springframework.web.bind.annotation.PatchMapping", Kind.HTTP_EVIDENCE,
                    "spring.patch-mapping"),
            new AnnotationRule("org.springframework.cloud.openfeign.FeignClient", Kind.HTTP_EVIDENCE,
                    "spring.feign-client"),
            new AnnotationRule("org.springframework.kafka.annotation.KafkaListener", Kind.EVENT_EVIDENCE,
                    "spring.kafka-listener"),
            new AnnotationRule("org.springframework.amqp.rabbit.annotation.RabbitListener", Kind.EVENT_EVIDENCE,
                    "spring.rabbit-listener"),
            new AnnotationRule("javax.persistence.Entity", Kind.DB_EVIDENCE, "jpa.javax-entity"),
            new AnnotationRule("javax.persistence.Table", Kind.DB_EVIDENCE, "jpa.javax-table"),
            new AnnotationRule("jakarta.persistence.Entity", Kind.DB_EVIDENCE, "jpa.jakarta-entity"),
            new AnnotationRule("jakarta.persistence.Table", Kind.DB_EVIDENCE, "jpa.jakarta-table"),
            new AnnotationRule("org.springframework.data.jpa.repository.Query", Kind.DB_EVIDENCE,
                    "spring-data-jpa-query"),
            new AnnotationRule("org.apache.ibatis.annotations.Select", Kind.DB_EVIDENCE, "mybatis-select"),
            new AnnotationRule("org.apache.ibatis.annotations.Insert", Kind.DB_EVIDENCE, "mybatis-insert"),
            new AnnotationRule("org.apache.ibatis.annotations.Update", Kind.DB_EVIDENCE, "mybatis-update"),
            new AnnotationRule("org.apache.ibatis.annotations.Delete", Kind.DB_EVIDENCE, "mybatis-delete"));

    private static final Map<ApiMethod, ApiFamily> API_FAMILIES = apiFamilies();
    private static final Map<String, ApiArguments> VERIFIED_ARGUMENT_ROLES = verifiedArgumentRoles();

    public void extract(ParsedJavaUnit unit, SourceResolutionSession session, Consumer<Fact> sink) {
        Objects.requireNonNull(unit, "parsed Java unit");
        Objects.requireNonNull(session, "source resolution session");
        Objects.requireNonNull(sink, "fact sink");
        if (unit.ast().isEmpty() || !session.inScope(unit.syntax().file().path())) return;

        CompilationUnit compilationUnit = unit.ast().orElseThrow();
        List<ImportDeclaration> imports = compilationUnit.getImports();
        compilationUnit.walk(node -> {
            if (node instanceof AnnotationExpr annotation) {
                extractAnnotation(unit, session, annotation, imports, sink);
            } else if (node instanceof MethodCallExpr call) {
                extractCall(unit, session, call, sink);
            }
        });
    }

    private static void extractAnnotation(ParsedJavaUnit unit, SourceResolutionSession session,
            AnnotationExpr annotation, List<ImportDeclaration> imports, Consumer<Fact> sink) {
        String writtenName = annotation.getNameAsString();
        List<String> candidates = new ArrayList<>();
        for (AnnotationRule rule : ANNOTATION_RULES) {
            if (simpleName(rule.qualifiedName()).equals(simpleName(writtenName))) candidates.add(rule.qualifiedName());
        }
        if (candidates.isEmpty()) return;

        Optional<AnnotationResolution> resolution = resolveAnnotation(annotation, session);
        if (resolution.isPresent() && annotationRule(resolution.orElseThrow().qualifiedName()).isEmpty()) return;
        Optional<TypeEvidence> typeEvidence = typeEvidence(unit, session, writtenName, annotation.getName(), candidates, imports);
        if (typeEvidence.isEmpty()) return;

        TypeEvidence recognition = typeEvidence.orElseThrow();
        if (resolution.isPresent()
                && !resolution.orElseThrow().qualifiedName().equals(recognition.qualifiedName())) return;
        AnnotationRule rule = annotationRule(recognition.qualifiedName()).orElseThrow();
        SourceSite source = session.site(unit, annotation);
        Optional<Target> target = resolution.flatMap(AnnotationResolution::target);
        AnnotationValues values = annotationValues(annotation);
        List<String> uncertainties = new ArrayList<>(recognition.uncertainties());
        appendPlaceholderUncertainties(values.literalValues(), uncertainties);
        emit(session, rule.kind(), source, target, rule.recognizerId(), writtenName, recognition.basis(),
                values.literalValues(), values.unresolvedMembers(), uncertainties,
                recognition.supportingEvidence(), sink);
    }

    private static void extractCall(ParsedJavaUnit unit, SourceResolutionSession session, MethodCallExpr call,
            Consumer<Fact> sink) {
        Optional<ResolvedMethodDeclaration> resolved = resolve(call);
        if (resolved.isPresent()) {
            ResolvedMethodDeclaration method = resolved.orElseThrow();
            ApiFamily family = API_FAMILIES.get(new ApiMethod(method.declaringType().getQualifiedName(), method.getName()));
            if (method.toAst().isPresent()) return;
            if (Objects.nonNull(family)) {
                ApiArguments arguments = VERIFIED_ARGUMENT_ROLES.get(method.getQualifiedSignature());
                List<String> uncertainties = new ArrayList<>();
                Map<String, List<String>> literals = new LinkedHashMap<>();
                List<String> unresolved = new ArrayList<>();
                if (Objects.nonNull(arguments)) {
                    addArgumentRoles(call, arguments.literalArgumentIndexes(), literals, unresolved);
                    unresolved.addAll(arguments.unresolvedRoles());
                } else {
                    unresolved.addAll(family.unresolvedRoles());
                    uncertainties.add(UNKNOWN_OVERLOAD_UNCERTAINTY);
                }
                SourceSite source = session.site(unit, call);
                List<String> placeholders = new ArrayList<>();
                appendPlaceholderUncertainties(literals, placeholders);
                uncertainties.addAll(placeholders);
                emit(session, family.kind(), source, externalTarget(method, session), family.recognizerId(),
                        call.getNameAsString(), STATIC_API, literals, unresolved, uncertainties, List.of(source), sink);
                return;
            }
        }

        Optional<ReceiverSyntax> receiver = receiverSyntax(call, session);
        if (receiver.isEmpty()) return;
        ReceiverSyntax syntax = receiver.orElseThrow();
        List<String> candidates = new ArrayList<>();
        for (ApiMethod apiMethod : API_FAMILIES.keySet()) {
            if (apiMethod.name().equals(call.getNameAsString())
                    && simpleName(apiMethod.owner()).equals(simpleName(syntax.writtenName()))) {
                candidates.add(apiMethod.owner());
            }
        }
        ParsedJavaUnit evidenceUnit = syntax.evidenceUnit();
        List<ImportDeclaration> evidenceImports = evidenceUnit.ast().orElseThrow().getImports();
        Optional<TypeEvidence> typeEvidence = typeEvidence(evidenceUnit, session, syntax.writtenName(),
                syntax.evidenceNode(), candidates, evidenceImports);
        if (typeEvidence.isEmpty()) return;

        TypeEvidence recognition = typeEvidence.orElseThrow();
        ApiFamily family = API_FAMILIES.get(new ApiMethod(recognition.qualifiedName(), call.getNameAsString()));
        if (Objects.isNull(family)) return;
        List<String> uncertainties = new ArrayList<>(recognition.uncertainties());
        uncertainties.add(UNKNOWN_OVERLOAD_UNCERTAINTY);
        List<String> placeholders = new ArrayList<>();
        appendPlaceholderUncertainties(Map.of(), placeholders);
        uncertainties.addAll(placeholders);
        SourceSite source = session.site(unit, call);
        emit(session, family.kind(), source, Optional.empty(), family.recognizerId(), call.getNameAsString(),
                recognition.basis(), Map.of(), family.unresolvedRoles(), uncertainties,
                recognition.supportingEvidence(), sink);
    }

    private static Optional<ResolvedMethodDeclaration> resolve(MethodCallExpr call) {
        try {
            return Optional.of(call.resolve());
        } catch (MethodAmbiguityException | UnsolvedSymbolException | UnresolvedRelationException
                | UnsupportedOperationException unresolved) {
            return Optional.empty();
        }
    }

    private static Optional<Target> externalTarget(ResolvedDeclaration declaration, SourceResolutionSession session) {
        try {
            Target target = session.target(declaration);
            return target.kind() == RelationContract.TargetKind.EXTERNAL_TARGET ? Optional.of(target) : Optional.empty();
        } catch (UnresolvedRelationException | UnsupportedOperationException unresolved) {
            return Optional.empty();
        }
    }

    private static Optional<AnnotationResolution> resolveAnnotation(AnnotationExpr annotation,
            SourceResolutionSession session) {
        try {
            ResolvedDeclaration declaration = annotation.resolve();
            if (!(declaration instanceof ResolvedTypeDeclaration type)) {
                return Optional.of(new AnnotationResolution("", Optional.empty()));
            }
            return Optional.of(new AnnotationResolution(type.getQualifiedName(), externalTarget(declaration, session)));
        } catch (MethodAmbiguityException | UnsolvedSymbolException | UnresolvedRelationException
                | UnsupportedOperationException unresolved) {
            return Optional.empty();
        }
    }

    private static Optional<ReceiverSyntax> receiverSyntax(MethodCallExpr call, SourceResolutionSession session) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) return Optional.empty();
        Expression expression = scope.orElseThrow();
        Optional<Type> declaredType = sourceValueType(expression);
        if (declaredType.isPresent()) {
            Type type = declaredType.orElseThrow();
            if (type instanceof ClassOrInterfaceType classType) {
                return Optional.of(new ReceiverSyntax(classType.getNameWithScope(), type, session.owningUnit(type)));
            }
            return Optional.empty();
        }
        if (expression instanceof ObjectCreationExpr creation) {
            return Optional.of(new ReceiverSyntax(creation.getType().getNameWithScope(), creation.getType(),
                    session.owningUnit(creation.getType())));
        }
        if (expression instanceof CastExpr cast && cast.getType() instanceof ClassOrInterfaceType classType) {
            return Optional.of(new ReceiverSyntax(classType.getNameWithScope(), classType, session.owningUnit(classType)));
        }
        if (expression instanceof NameExpr || expression instanceof FieldAccessExpr) {
            return Optional.of(new ReceiverSyntax(expression.toString(), expression, session.owningUnit(expression)));
        }
        return Optional.empty();
    }

    private static Optional<Type> sourceValueType(Expression expression) {
        try {
            if (expression instanceof NameExpr name) return declarationType(name.resolve().toAst());
            if (expression instanceof FieldAccessExpr field) return declarationType(field.resolve().toAst());
            return Optional.empty();
        } catch (MethodAmbiguityException | UnsolvedSymbolException | UnresolvedRelationException
                | UnsupportedOperationException unresolved) {
            return Optional.empty();
        }
    }

    private static Optional<Type> declarationType(Optional<Node> sourceDeclaration) {
        if (sourceDeclaration.isEmpty()) return Optional.empty();
        Node declaration = sourceDeclaration.orElseThrow();
        if (declaration instanceof VariableDeclarator variable) return Optional.of(variable.getType());
        if (declaration instanceof FieldDeclaration field) return Optional.of(field.getElementType());
        if (declaration instanceof Parameter parameter) return Optional.of(parameter.getType());
        return Optional.empty();
    }

    private static Optional<TypeEvidence> typeEvidence(ParsedJavaUnit unit, SourceResolutionSession session,
            String writtenName, Node evidenceNode, List<String> candidates, List<ImportDeclaration> imports) {
        if (candidates.isEmpty()) return Optional.empty();
        for (String candidate : candidates) {
            if (candidate.equals(writtenName)) {
                return Optional.of(new TypeEvidence(candidate, FULLY_QUALIFIED,
                        List.of(session.site(unit, evidenceNode)), List.of()));
            }
        }
        if (writtenName.contains(".")) return Optional.empty();
        List<String> matchingCandidates = new ArrayList<>();
        for (String candidate : candidates) {
            if (simpleName(candidate).equals(writtenName)) matchingCandidates.add(candidate);
        }
        if (matchingCandidates.isEmpty()) return Optional.empty();

        List<ImportDeclaration> sameSimpleImports = new ArrayList<>();
        List<ImportDeclaration> directMatches = new ArrayList<>();
        for (ImportDeclaration importDeclaration : imports) {
            if (importDeclaration.isStatic() || importDeclaration.isAsterisk()) continue;
            String importedName = importDeclaration.getNameAsString();
            if (!simpleName(importedName).equals(writtenName)) continue;
            sameSimpleImports.add(importDeclaration);
            if (matchingCandidates.contains(importedName)) directMatches.add(importDeclaration);
        }
        if (!directMatches.isEmpty()) {
            String importedName = directMatches.get(0).getNameAsString();
            if (directMatches.stream().anyMatch(value -> !value.getNameAsString().equals(importedName))) return Optional.empty();
            String basis = sameSimpleImports.size() == 1 ? DIRECT_IMPORT : AMBIGUOUS_SIMPLE;
            List<SourceSite> support = importEvidence(unit, session, evidenceNode, sameSimpleImports);
            List<String> uncertainties = basis.equals(AMBIGUOUS_SIMPLE)
                    ? List.of("simple type has competing direct imports") : List.of();
            return Optional.of(new TypeEvidence(importedName, basis, support, uncertainties));
        }

        String wildcardName = "";
        List<ImportDeclaration> wildcardMatches = new ArrayList<>();
        for (ImportDeclaration importDeclaration : imports) {
            if (importDeclaration.isStatic() || !importDeclaration.isAsterisk()) continue;
            String packageName = importDeclaration.getNameAsString();
            for (String candidate : matchingCandidates) {
                if (packageName.equals(packageNameOf(candidate))) {
                    if (!wildcardName.isEmpty() && !wildcardName.equals(candidate)) return Optional.empty();
                    wildcardName = candidate;
                    wildcardMatches.add(importDeclaration);
                }
            }
        }
        if (wildcardName.isEmpty()) return Optional.empty();
        return Optional.of(new TypeEvidence(wildcardName, AMBIGUOUS_SIMPLE,
                importEvidence(unit, session, evidenceNode, wildcardMatches),
                List.of("simple type is recognized through a wildcard import")));
    }

    private static List<SourceSite> importEvidence(ParsedJavaUnit unit, SourceResolutionSession session,
            Node writtenType, List<ImportDeclaration> imports) {
        List<SourceSite> evidence = new ArrayList<>();
        evidence.add(session.site(unit, writtenType));
        for (ImportDeclaration importDeclaration : imports) {
            evidence.add(session.site(unit, importDeclaration.getName()));
        }
        return List.copyOf(evidence);
    }

    private static Optional<AnnotationRule> annotationRule(String qualifiedName) {
        return ANNOTATION_RULES.stream().filter(rule -> rule.qualifiedName().equals(qualifiedName)).findFirst();
    }

    private static AnnotationValues annotationValues(AnnotationExpr annotation) {
        Map<String, List<String>> literalValues = new LinkedHashMap<>();
        List<String> unresolvedMembers = new ArrayList<>();
        List<String> seenMembers = new ArrayList<>();
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            addAnnotationValue("value", singleMember.getMemberValue(), seenMembers, literalValues, unresolvedMembers);
        } else if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            normalAnnotation.getPairs().forEach(pair -> addAnnotationValue(pair.getNameAsString(), pair.getValue(),
                    seenMembers, literalValues, unresolvedMembers));
        }
        return new AnnotationValues(literalValues, unresolvedMembers);
    }

    private record AnnotationValues(Map<String, List<String>> literalValues, List<String> unresolvedMembers) { }

    private static void addAnnotationValue(String memberName, Expression expression, List<String> seenMembers,
            Map<String, List<String>> literalValues, List<String> unresolvedMembers) {
        if (seenMembers.contains(memberName)) {
            literalValues.remove(memberName);
            if (!unresolvedMembers.contains(memberName)) unresolvedMembers.add(memberName);
            return;
        }
        seenMembers.add(memberName);
        Optional<List<String>> values = JavaSyntaxExtractor.literalValues(expression);
        if (values.isPresent()) literalValues.put(memberName, values.orElseThrow());
        else unresolvedMembers.add(memberName);
    }

    private static void addArgumentRoles(MethodCallExpr call, Map<String, Integer> roles,
            Map<String, List<String>> literalValues, List<String> unresolvedMembers) {
        for (Map.Entry<String, Integer> role : roles.entrySet()) {
            int index = role.getValue();
            if (index >= call.getArguments().size()) {
                unresolvedMembers.add(role.getKey());
                continue;
            }
            Optional<List<String>> values = JavaSyntaxExtractor.literalValues(call.getArgument(index));
            if (values.isPresent()) literalValues.put(role.getKey(), values.orElseThrow());
            else unresolvedMembers.add(role.getKey());
        }
    }

    private static void appendPlaceholderUncertainties(Map<String, List<String>> literalValues,
            List<String> uncertainties) {
        for (Map.Entry<String, List<String>> member : literalValues.entrySet()) {
            if (member.getValue().stream().anyMatch(FrameworkEvidenceExtractor::hasPlaceholder)) {
                uncertainties.add(member.getKey() + ": " + PLACEHOLDER_UNCERTAINTY);
            }
        }
    }

    private static boolean hasPlaceholder(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static void emit(SourceResolutionSession session, Kind kind, SourceSite source, Optional<Target> target,
            String recognizerId, String spelling, String basis, Map<String, List<String>> literalValues,
            List<String> unresolvedMembers, List<String> bindingUncertainties, List<SourceSite> supportingEvidence,
            Consumer<Fact> sink) {
        FrameworkEvidence evidence = new FrameworkEvidence(recognizerId, spelling, basis, literalValues,
                unresolvedMembers, bindingUncertainties, supportingEvidence);
        sink.accept(new Fact(RelationContract.factIdentity(session.generation(), kind, source, target), kind, source,
                ResolutionState.CANDIDATE, target, Optional.empty(), Optional.of(evidence)));
    }

    private static String simpleName(String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private static String packageNameOf(String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static Map<ApiMethod, ApiFamily> apiFamilies() {
        Map<ApiMethod, ApiFamily> families = new HashMap<>();
        ApiFamily connection = new ApiFamily(Kind.DB_EVIDENCE, "jdbc-connection-sql", List.of("sql"));
        ApiFamily statement = new ApiFamily(Kind.DB_EVIDENCE, "jdbc-statement-sql", List.of("sql"));
        ApiFamily kafka = new ApiFamily(Kind.EVENT_EVIDENCE, "spring-kafka-send", List.of("topic"));
        ApiFamily rabbit = new ApiFamily(Kind.EVENT_EVIDENCE, "spring-rabbit-convert-and-send",
                List.of("exchange", "routingKey"));
        families.put(new ApiMethod("java.sql.Connection", "prepareStatement"), connection);
        families.put(new ApiMethod("java.sql.Connection", "prepareCall"), connection);
        families.put(new ApiMethod("java.sql.Statement", "executeQuery"), statement);
        families.put(new ApiMethod("java.sql.Statement", "executeUpdate"), statement);
        families.put(new ApiMethod("java.sql.Statement", "executeLargeUpdate"), statement);
        families.put(new ApiMethod("java.sql.Statement", "execute"), statement);
        families.put(new ApiMethod("java.sql.Statement", "addBatch"), statement);
        families.put(new ApiMethod("org.springframework.kafka.core.KafkaTemplate", "send"), kafka);
        families.put(new ApiMethod("org.springframework.amqp.rabbit.core.RabbitTemplate", "convertAndSend"), rabbit);
        return Map.copyOf(families);
    }

    private static Map<String, ApiArguments> verifiedArgumentRoles() {
        ApiArguments sql = new ApiArguments(Map.of("sql", 0), List.of());
        ApiArguments kafkaTopic = new ApiArguments(Map.of("topic", 0), List.of());
        ApiArguments rabbitExplicitExchange = new ApiArguments(Map.of("exchange", 0, "routingKey", 1), List.of());
        ApiArguments rabbitDefaultExchange = new ApiArguments(Map.of("routingKey", 0), List.of("exchange"));
        // Match exact JavaParser 3.28.2 signature spelling; preserve canonical target identity without normalization.
        // KafkaTemplate's String-topic overloads put topic first; RabbitTemplate distinguishes explicit/default exchanges.
        return Map.ofEntries(
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String)", sql),
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String, int)", sql),
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String, int, int)", sql),
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String, int, int, int)", sql),
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String, int[])", sql),
                Map.entry("java.sql.Connection.prepareStatement(java.lang.String, java.lang.String[])", sql),
                Map.entry("java.sql.Connection.prepareCall(java.lang.String)", sql),
                Map.entry("java.sql.Connection.prepareCall(java.lang.String, int, int)", sql),
                Map.entry("java.sql.Connection.prepareCall(java.lang.String, int, int, int)", sql),
                Map.entry("java.sql.Statement.executeQuery(java.lang.String)", sql),
                Map.entry("java.sql.Statement.executeUpdate(java.lang.String)", sql),
                Map.entry("java.sql.Statement.executeUpdate(java.lang.String, int)", sql),
                Map.entry("java.sql.Statement.executeUpdate(java.lang.String, int[])", sql),
                Map.entry("java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])", sql),
                Map.entry("java.sql.Statement.executeLargeUpdate(java.lang.String)", sql),
                Map.entry("java.sql.Statement.executeLargeUpdate(java.lang.String, int)", sql),
                Map.entry("java.sql.Statement.executeLargeUpdate(java.lang.String, int[])", sql),
                Map.entry("java.sql.Statement.executeLargeUpdate(java.lang.String, java.lang.String[])", sql),
                Map.entry("java.sql.Statement.execute(java.lang.String)", sql),
                Map.entry("java.sql.Statement.execute(java.lang.String, int)", sql),
                Map.entry("java.sql.Statement.execute(java.lang.String, int[])", sql),
                Map.entry("java.sql.Statement.execute(java.lang.String, java.lang.String[])", sql),
                Map.entry("java.sql.Statement.addBatch(java.lang.String)", sql),
                Map.entry("org.springframework.kafka.core.KafkaTemplate.send(java.lang.String, V)", kafkaTopic),
                Map.entry("org.springframework.kafka.core.KafkaTemplate.send(java.lang.String, K, V)", kafkaTopic),
                Map.entry("org.springframework.kafka.core.KafkaTemplate.send(java.lang.String, java.lang.Integer, K, V)",
                        kafkaTopic),
                Map.entry("org.springframework.kafka.core.KafkaTemplate.send(java.lang.String, java.lang.Integer, java.lang.Long, K, V)",
                        kafkaTopic),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.Object)",
                        rabbitDefaultExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.Object, org.springframework.amqp.core.MessagePostProcessor)",
                        rabbitDefaultExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.Object, org.springframework.amqp.rabbit.connection.CorrelationData)",
                        rabbitDefaultExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.Object, org.springframework.amqp.core.MessagePostProcessor, org.springframework.amqp.rabbit.connection.CorrelationData)",
                        rabbitDefaultExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.String, java.lang.Object)",
                        rabbitExplicitExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.String, java.lang.Object, org.springframework.amqp.core.MessagePostProcessor)",
                        rabbitExplicitExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.String, java.lang.Object, org.springframework.amqp.rabbit.connection.CorrelationData)",
                        rabbitExplicitExchange),
                Map.entry("org.springframework.amqp.rabbit.core.RabbitTemplate.convertAndSend(java.lang.String, java.lang.String, java.lang.Object, org.springframework.amqp.core.MessagePostProcessor, org.springframework.amqp.rabbit.connection.CorrelationData)",
                        rabbitExplicitExchange));
    }
}
