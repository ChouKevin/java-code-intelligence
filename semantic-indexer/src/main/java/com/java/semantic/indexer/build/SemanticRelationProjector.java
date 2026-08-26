package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.InvocationTarget;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projects code-evidenced calls and type relationships, preserving unresolved targets as external types. */
public final class SemanticRelationProjector {

    private static final Pattern VALUE_PLACEHOLDER = Pattern.compile("@Value\\s*\\(\\s*\\\"\\$\\{([^}:]+)(?::[^}]*)?}\\\"");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+([A-Za-z_][A-Za-z0-9_$.]*)");
    private static final String KAFKA_TEMPLATE = "org.springframework.kafka.core.KafkaTemplate";
    private static final String RABBIT_TEMPLATE = "org.springframework.amqp.rabbit.core.RabbitTemplate";
    private static final String FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final Map<String, String> HTTP_MAPPING_METHODS = Map.of(
            "org.springframework.web.bind.annotation.GetMapping", "GET",
            "org.springframework.web.bind.annotation.PostMapping", "POST",
            "org.springframework.web.bind.annotation.PutMapping", "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping", "PATCH");
    private static final Set<String> KAFKA_PUBLISH_METHODS = Set.of("send");
    private static final Set<String> RABBIT_PUBLISH_METHODS = Set.of("send", "convertAndSend");

    public java.util.List<RelationDocument> project(RepositoryId repositoryId, RepositoryRevision revision,
                                                    GenerationId generationId, RepositorySyntax syntax,
                                                    String sourcePath, com.java.semantic.model.index.SourceArtifactDocument artifact) {
        return project(repositoryId, revision, generationId, syntax, sourcePath, artifact, null);
    }

    public java.util.List<RelationDocument> project(RepositoryId repositoryId, RepositoryRevision revision,
                                                    GenerationId generationId, RepositorySyntax syntax,
                                                    String sourcePath, com.java.semantic.model.index.SourceArtifactDocument artifact,
                                                    Path repositoryRoot) {
        return project(repositoryId, revision, generationId, syntax, sourcePath, artifact, repositoryRoot, null,
                SemanticCallTargetResolver.syntaxOnly());
    }

    public java.util.List<RelationDocument> project(RepositoryId repositoryId, RepositoryRevision revision,
                                                    GenerationId generationId, RepositorySyntax syntax,
                                                    String sourcePath, com.java.semantic.model.index.SourceArtifactDocument artifact,
                                                    Path repositoryRoot, RepositorySnapshot snapshot,
                                                    SemanticCallTargetResolver semanticCallTargetResolver) {
        Map<String, SourceTypeIdentity> owned = syntax.sourceTypes().stream().map(metadata -> metadata.declaration().identity())
                .collect(Collectors.toMap(SourceTypeIdentity::fullyQualifiedName, Function.identity(), (left, right) -> left));
        Map<String, SourceTypeMetadata> typesByName = new HashMap<>();
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            typesByName.put(metadata.declaration().identity().fullyQualifiedName(), metadata);
        }
        Map<String, ExternalTarget.Endpoint> feignEndpoints = feignEndpoints(syntax);
        ArrayList<RelationDocument> documents = new ArrayList<>();
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            SourceTypeIdentity type = metadata.declaration().identity();
            if (!sourcePath.equals(type.sourceFile())) {
                continue;
            }
            CodeFactIdentity typeIdentity = SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.TYPE, type).identity();
            metadata.relationships().extendedTypes().forEach(reference -> relationForType(reference.resolvedNamedType(), typeIdentity,
                    RelationKind.EXTENDS, metadata.declaration().declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
            metadata.relationships().implementedTypes().forEach(reference -> relationForType(reference.resolvedNamedType(), typeIdentity,
                    RelationKind.IMPLEMENTS, metadata.declaration().declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
            metadata.frameworkFacts().annotations().forEach(annotation -> annotationRelation(annotation, typeIdentity,
                    metadata.declaration().declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
            for (com.java.semantic.syntax.domain.SourceFieldMetadata field : metadata.members().fields()) {
                CodeFactIdentity fieldIdentity = SyntaxSymbolProjector.fact(repositoryId, revision, field.declarationKind(),
                        new MemberIdentity(type, field.name())).identity();
                field.typeReference().resolvedNamedType().ifPresent(reference -> {
                    relationForType(Optional.of(reference), fieldIdentity, RelationKind.USES_TYPE, field.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                    relationForType(Optional.of(reference), fieldIdentity, RelationKind.REFERENCES, field.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                });
                field.annotationEvidence().forEach(annotation -> annotationRelation(annotation, fieldIdentity,
                        field.declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
            }
            for (SourceMethodMetadata method : metadata.members().methods()) {
                MethodTarget origin = method.analysisTarget().target().orElseGet(() -> new MethodTarget(type, method.name(), method.paramTypes()));
                CodeFactIdentity from = SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.METHOD, origin).identity();
                method.bodyTypeReferences().forEach(reference -> relationForType(Optional.of(reference), from,
                        RelationKind.USES_TYPE, method.declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
                method.bodyTypeReferences().forEach(reference -> relationForType(Optional.of(reference), from,
                        RelationKind.REFERENCES, method.declarationLocation(), repositoryId, revision, generationId, owned, artifact, documents));
                method.parameterTypeReferences().forEach(reference -> reference.resolvedNamedType().ifPresent(typeReference -> {
                    relationForType(Optional.of(typeReference), from, RelationKind.USES_TYPE, method.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                    relationForType(Optional.of(typeReference), from, RelationKind.REFERENCES, method.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                }));
                method.returnType().flatMap(com.java.semantic.syntax.domain.TypeReference::resolvedNamedType).ifPresent(typeReference -> {
                    relationForType(Optional.of(typeReference), from, RelationKind.USES_TYPE, method.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                    relationForType(Optional.of(typeReference), from, RelationKind.REFERENCES, method.declarationLocation(),
                            repositoryId, revision, generationId, owned, artifact, documents);
                });
                method.annotationEvidence().forEach(annotation -> annotationRelation(annotation, from, method.declarationLocation(),
                        repositoryId, revision, generationId, owned, artifact, documents));
                overrideRelations(metadata, method, from, typesByName, owned, repositoryId, revision, generationId, artifact, documents);
                method.invocations().forEach(invocation -> {
                    SourceRange invocationLocation = new SourceRange(method.declarationLocation().sourceFile(), invocation.range());
                    Optional<InvocationTarget> resolvedTarget = semanticCallTargetResolver.resolve(snapshot, method, invocation,
                            localTargetExpected(typesByName, invocation));
                    if (resolvedTarget.isPresent()) {
                        InvocationTarget target = resolvedTarget.orElseThrow();
                        addCall(from, target, invocationLocation, repositoryId, revision, generationId, typesByName, artifact, documents);
                    } else {
                        addUnresolvedCall(from, invocation, invocationLocation, repositoryId, revision, generationId, artifact, documents);
                    }
                    frameworkInvocationRelations(from, invocation, method.declarationLocation().sourceFile(), feignEndpoints,
                            repositoryId, revision, generationId, artifact, documents);
                });
            }
        }
        Optional.ofNullable(repositoryRoot).ifPresent(ignored -> addStructuralEvidence(repositoryId, revision, generationId, syntax,
                sourcePath, artifact, owned, documents));
        return documents.stream().sorted(java.util.Comparator.comparing(document -> document.fact().id().value())).toList();
    }

    private static boolean localTargetExpected(Map<String, SourceTypeMetadata> typesByName, SyntaxInvocation invocation) {
        return invocation.resolvedTarget().map(target -> {
            String qualifiedName = target.packageName().isBlank()
                    ? target.className() : target.packageName() + "." + target.className();
            return typesByName.containsKey(qualifiedName);
        }).orElse(false);
    }

    private static void addStructuralEvidence(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                              RepositorySyntax syntax, String sourcePath,
                                              com.java.semantic.model.index.SourceArtifactDocument artifact,
                                              Map<String, SourceTypeIdentity> owned,
                                              ArrayList<RelationDocument> documents) {
        String source = artifact.utf8Content();
        if (sourcePath.endsWith(".xml")) {
            addSqlIdentifierRelations(repositoryId, revision, generationId, syntax, sourcePath, artifact, source, documents);
            return;
        }
        for (SourceTypeMetadata typeMetadata : syntax.sourceTypes()) {
            SourceTypeIdentity type = typeMetadata.declaration().identity();
            if (!sourcePath.equals(type.sourceFile())) {
                continue;
            }
            for (com.java.semantic.syntax.domain.SourceFieldMetadata field : typeMetadata.members().fields()) {
                CodeFactIdentity from = SyntaxSymbolProjector.fact(repositoryId, revision, field.declarationKind(),
                        new MemberIdentity(type, field.name())).identity();
                Pattern fieldValue = Pattern.compile(VALUE_PLACEHOLDER.pattern() + "[\\s\\S]{0,240}?\\b"
                        + Pattern.quote(field.name()) + "\\b");
                Matcher matcher = fieldValue.matcher(source);
                while (matcher.find()) {
                    SourceRange range = offsetRange(sourcePath, source, matcher.start(1), matcher.end(1));
                    add(from, RelationKind.READS_CONFIGURATION,
                            new RelationTarget.External(new ExternalTarget.ConfigurationKey(matcher.group(1))), range,
                            CodeFactKind.CONFIGURATION_KEY, repositoryId, revision, generationId, artifact, documents);
                }
            }
            for (SourceMethodMetadata method : typeMetadata.members().methods()) {
                MethodTarget target = method.analysisTarget().target().orElseGet(
                        () -> new MethodTarget(type, method.name(), method.paramTypes()));
                CodeFactIdentity from = SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.METHOD, target).identity();
                method.thrownTypes().forEach(thrown -> relationForThrownType(thrown, from, repositoryId, revision,
                        generationId, owned, artifact, documents));
            }
        }
    }

    private static void addSqlIdentifierRelations(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                                   RepositorySyntax syntax, String sourcePath,
                                                   com.java.semantic.model.index.SourceArtifactDocument artifact, String source,
                                                   ArrayList<RelationDocument> documents) {
        syntax.mapperEvidenceIndex().stream().flatMap(index -> index.statements().stream())
                .filter(statement -> sourcePath.equals(statement.location().sourceFile()))
                .forEach(statement -> {
                    CodeFactIdentity from = SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.MAPPER_STATEMENT,
                            new MapperStatementIdentity(statement.identity().statementKey().namespace(),
                                    statement.identity().statementKey().statementId(), statement.identity().resourcePath())).identity();
                    int start = offsetOf(source, statement.location().range());
                    String statementSource = sourceSegment(source, statement.location().range());
                    Matcher matcher = SQL_IDENTIFIER.matcher(statementSource);
                    while (matcher.find()) {
                        SourceRange range = offsetRange(sourcePath, source, start + matcher.start(1), start + matcher.end(1));
                        add(from, RelationKind.USES_SQL_IDENTIFIER,
                                new RelationTarget.External(new ExternalTarget.SqlIdentifier(matcher.group(1))), range,
                                CodeFactKind.SQL_IDENTIFIER, repositoryId, revision, generationId, artifact, documents);
                    }
                });
    }

    private static String sourceSegment(String source, com.java.semantic.model.codefact.SyntaxRange range) {
        return source.substring(offsetOf(source, range.start()), offsetOf(source, range.end()));
    }

    private static int offsetOf(String source, com.java.semantic.model.codefact.SyntaxRange range) {
        return offsetOf(source, range.start());
    }

    private static int offsetOf(String source, com.java.semantic.model.codefact.SyntaxPosition position) {
        int line = 0;
        int index = 0;
        while (line < position.line() && index < source.length()) {
            char current = source.charAt(index++);
            if (current == '\n') {
                line++;
            }
        }
        return index + position.character();
    }

    private static SourceRange offsetRange(String sourcePath, String source, int start, int end) {
        return new SourceRange(sourcePath, new com.java.semantic.model.codefact.SyntaxRange(positionOf(source, start), positionOf(source, end)));
    }

    private static com.java.semantic.model.codefact.SyntaxPosition positionOf(String source, int offset) {
        int line = 0;
        int character = 0;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new com.java.semantic.model.codefact.SyntaxPosition(line, character);
    }

    private static void overrideRelations(SourceTypeMetadata sourceType, SourceMethodMetadata method, CodeFactIdentity from,
                                          Map<String, SourceTypeMetadata> typesByName, Map<String, SourceTypeIdentity> owned,
                                          RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                          com.java.semantic.model.index.SourceArtifactDocument artifact,
                                          ArrayList<RelationDocument> documents) {
        List<com.java.semantic.syntax.domain.NominalTypeReference> parents = new ArrayList<>();
        parents.addAll(sourceType.relationships().extendedTypes());
        parents.addAll(sourceType.relationships().implementedTypes());
        for (com.java.semantic.syntax.domain.NominalTypeReference parent : parents) {
            parent.resolvedNamedType().ifPresent(parentIdentity -> {
                SourceTypeMetadata parentMetadata = typesByName.get(parentIdentity.fullyQualifiedName());
                Optional<MethodTarget> parentMethod = Optional.ofNullable(parentMetadata).stream()
                        .flatMap(value -> value.members().methods().stream())
                        .filter(candidate -> candidate.name().equals(method.name()))
                        .filter(candidate -> candidate.paramTypes().equals(method.paramTypes()))
                        .map(candidate -> declaredMethodTarget(parentMetadata, candidate))
                        .findFirst();
                if (parentMethod.isEmpty()) {
                    return;
                }
                SourceTypeIdentity parentType = owned.get(parentIdentity.fullyQualifiedName());
                RelationTarget target = targetFor(parentType, () -> new RelationTarget.External(
                        new ExternalTarget.NominalType(new DeclaredType(parentIdentity.fullyQualifiedName()))), targetType ->
                        new RelationTarget.Internal(SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.METHOD,
                                parentMethod.orElseThrow()).identity()));
                add(from, RelationKind.OVERRIDES, target, method.declarationLocation(), CodeFactKind.TYPE_USAGE,
                        repositoryId, revision, generationId, artifact, documents);
            });
        }
    }

    private static void frameworkInvocationRelations(CodeFactIdentity from, SyntaxInvocation invocation, String sourceFile,
                                                     Map<String, ExternalTarget.Endpoint> feignEndpoints,
                                                     RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                                     com.java.semantic.model.index.SourceArtifactDocument artifact,
                                                     ArrayList<RelationDocument> documents) {
        invocation.resolvedTarget().ifPresent(target -> {
            String typeName = qualifiedName(target);
            if (!typeName.equals(invocation.receiverDeclaration())) {
                return;
            }
            messageDestination(invocation, typeName, target.methodName()).ifPresent(destination -> add(from,
                    RelationKind.PUBLISHES_MESSAGE, new RelationTarget.External(destination.target()),
                    new SourceRange(sourceFile, destination.range()), CodeFactKind.MQ_PUBLISHER,
                    repositoryId, revision, generationId, artifact, documents));
            Optional<ExternalTarget.Endpoint> endpoint = Optional.ofNullable(feignEndpoints.get(typeName + "#" + target.methodName()));
            endpoint.ifPresent(value -> add(from, RelationKind.CALLS_OUTBOUND_API, new RelationTarget.External(value),
                    new SourceRange(sourceFile, invocation.range()), CodeFactKind.OUTBOUND_API,
                    repositoryId, revision, generationId, artifact, documents));
        });
    }

    private static Optional<DestinationEvidence> messageDestination(SyntaxInvocation invocation, String receiverType,
                                                                     String methodName) {
        if (receiverType.equals(KAFKA_TEMPLATE) && KAFKA_PUBLISH_METHODS.contains(methodName)) {
            return literalDestination(invocation, 0).map(argument -> new DestinationEvidence(
                    new ExternalTarget.Destination("kafka", argument.literalValue().orElseThrow()), argument.range()));
        }
        if (receiverType.equals(RABBIT_TEMPLATE) && RABBIT_PUBLISH_METHODS.contains(methodName)) {
            int routingKeyIndex = invocation.arguments().size() >= 3 ? 1 : 0;
            return literalDestination(invocation, routingKeyIndex).map(argument -> new DestinationEvidence(
                    new ExternalTarget.Destination("rabbit", argument.literalValue().orElseThrow()), argument.range()));
        }
        return Optional.empty();
    }

    private static Optional<com.java.semantic.syntax.domain.SyntaxInvocationArgument> literalDestination(
            SyntaxInvocation invocation, int index) {
        if (index < 0 || index >= invocation.arguments().size()) {
            return Optional.empty();
        }
        com.java.semantic.syntax.domain.SyntaxInvocationArgument argument = invocation.arguments().get(index);
        return argument.literalValue().map(value -> argument);
    }

    private static Map<String, ExternalTarget.Endpoint> feignEndpoints(RepositorySyntax syntax) {
        Map<String, ExternalTarget.Endpoint> endpoints = new HashMap<>();
        for (SourceTypeMetadata type : syntax.sourceTypes()) {
            if (!hasResolvedAnnotation(type.frameworkFacts().annotations(), FEIGN_CLIENT)) {
                continue;
            }
            String typeName = type.declaration().identity().fullyQualifiedName();
            for (SourceMethodMetadata method : type.members().methods()) {
                endpointOf(method.annotationEvidence()).ifPresent(endpoint -> endpoints.put(typeName + "#" + method.name(), endpoint));
            }
        }
        return Map.copyOf(endpoints);
    }

    private static boolean hasResolvedAnnotation(List<AnnotationEvidence> annotations, String expectedType) {
        return annotations.stream().map(AnnotationEvidence::resolvedType).flatMap(Optional::stream)
                .map(com.java.semantic.model.codefact.JavaTypeIdentity::fullyQualifiedName).anyMatch(expectedType::equals);
    }

    private static Optional<ExternalTarget.Endpoint> endpointOf(List<AnnotationEvidence> annotations) {
        for (AnnotationEvidence annotation : annotations) {
            Optional<String> resolvedType = annotation.resolvedType()
                    .map(com.java.semantic.model.codefact.JavaTypeIdentity::fullyQualifiedName);
            if (resolvedType.isEmpty()) {
                continue;
            }
            String mappingType = resolvedType.orElseThrow();
            Optional<String> path = annotation.values().stream()
                    .filter(value -> value.memberName().equals("value") || value.memberName().equals("path"))
                    .map(com.java.semantic.syntax.domain.AnnotationValueEvidence::literalValue)
                    .flatMap(Optional::stream).findFirst();
            Optional<String> httpMethod = Optional.ofNullable(HTTP_MAPPING_METHODS.get(mappingType));
            if (mappingType.equals(REQUEST_MAPPING)) {
                httpMethod = annotation.values().stream().filter(value -> value.memberName().equals("method"))
                        .map(com.java.semantic.syntax.domain.AnnotationValueEvidence::writtenValue)
                        .map(SemanticRelationProjector::requestMethodName).flatMap(Optional::stream).findFirst();
            }
            if (path.isPresent() && httpMethod.isPresent()) {
                return Optional.of(new ExternalTarget.Endpoint(httpMethod.orElseThrow(), path.orElseThrow()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> requestMethodName(String writtenValue) {
        int separator = writtenValue.lastIndexOf('.');
        String value = separator < 0 ? writtenValue : writtenValue.substring(separator + 1);
        return Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(value) ? Optional.of(value) : Optional.empty();
    }

    private record DestinationEvidence(ExternalTarget.Destination target, com.java.semantic.model.codefact.SyntaxRange range) {
    }

    private static void annotationRelation(com.java.semantic.syntax.domain.AnnotationEvidence annotation, CodeFactIdentity from,
                                           SourceRange occurrence, RepositoryId repositoryId, RepositoryRevision revision,
                                           GenerationId generationId, Map<String, SourceTypeIdentity> owned,
                                           com.java.semantic.model.index.SourceArtifactDocument artifact,
                                           ArrayList<RelationDocument> documents) {
        String typeName = annotation.resolvedType().map(com.java.semantic.model.codefact.JavaTypeIdentity::fullyQualifiedName)
                .orElse(annotation.writtenName());
        RelationTarget target = targetFor(owned.get(typeName), () -> new RelationTarget.External(
                new ExternalTarget.NominalType(new DeclaredType(typeName))), type -> new RelationTarget.Internal(
                SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.TYPE, type).identity()));
        add(from, RelationKind.USES_ANNOTATION, target,
                occurrence, CodeFactKind.ANNOTATION_USAGE, repositoryId, revision, generationId, artifact, documents);
    }

    private static void addUnresolvedCall(CodeFactIdentity from, com.java.semantic.syntax.domain.SyntaxInvocation invocation,
                                          SourceRange occurrence, RepositoryId repositoryId, RepositoryRevision revision,
                                          GenerationId generationId,
                                          com.java.semantic.model.index.SourceArtifactDocument artifact,
                                          ArrayList<RelationDocument> documents) {
        String expression = invocation.expression();
        int openingParenthesis = expression.indexOf('(');
        String invocationHead = openingParenthesis < 0 ? expression : expression.substring(0, openingParenthesis);
        int methodSeparator = Math.max(invocationHead.lastIndexOf('.'), invocationHead.lastIndexOf(':'));
        String methodName = methodSeparator < 0 ? invocationHead.trim() : invocationHead.substring(methodSeparator + 1).trim();
        int arity = argumentCount(expression, openingParenthesis);
        RelationTarget target = new RelationTarget.External(new ExternalTarget.UnresolvedCall(expression, invocation.receiver(), methodName, arity));
        add(from, RelationKind.CALLS, target, occurrence, CodeFactKind.TYPE_USAGE, repositoryId, revision, generationId, artifact, documents);
    }

    private static int argumentCount(String expression, int openingParenthesis) {
        if (openingParenthesis < 0) {
            return 0;
        }
        int depth = 0;
        int arguments = 0;
        boolean hasArgument = false;
        for (int index = openingParenthesis + 1; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depth++;
                hasArgument = true;
            } else if (current == ')') {
                if (depth == 0) {
                    return hasArgument ? arguments + 1 : 0;
                }
                depth--;
            } else if (current == ',' && depth == 0) {
                arguments++;
            } else if (!Character.isWhitespace(current)) {
                hasArgument = true;
            }
        }
        return hasArgument ? arguments + 1 : 0;
    }

    private static void addCall(CodeFactIdentity from, InvocationTarget target, SourceRange occurrence, RepositoryId repositoryId,
                                RepositoryRevision revision, GenerationId generationId, Map<String, SourceTypeMetadata> typesByName,
                                com.java.semantic.model.index.SourceArtifactDocument artifact, ArrayList<RelationDocument> documents) {
        String qualifiedName = qualifiedName(target);
        RelationTarget relationTarget = declaredCallTarget(target, typesByName).<RelationTarget>map(declaredTarget ->
                new RelationTarget.Internal(SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.METHOD,
                        declaredTarget).identity())).orElseGet(() -> new RelationTarget.External(
                new ExternalTarget.NominalType(new DeclaredType(qualifiedName))));
        add(from, RelationKind.CALLS, relationTarget, occurrence, CodeFactKind.TYPE_USAGE, repositoryId, revision, generationId, artifact, documents);
    }

    private static Optional<MethodTarget> declaredCallTarget(InvocationTarget target,
                                                              Map<String, SourceTypeMetadata> typesByName) {
        SourceTypeMetadata owner = typesByName.get(qualifiedName(target));
        if (owner == null) { // cs-allow
            return Optional.empty();
        }
        List<MethodTarget> candidates = owner.members().methods().stream()
                .map(method -> declaredMethodTarget(owner, method))
                .filter(method -> method.methodName().equals(target.methodName()))
                .filter(method -> JavaIdentityNormalizer.parameterTypes(method.parameterTypes()).equals(target.parameterTypes()))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static MethodTarget declaredMethodTarget(SourceTypeMetadata owner, SourceMethodMetadata method) {
        return method.analysisTarget().target().orElseGet(() -> new MethodTarget(
                owner.declaration().identity(), method.name(), method.paramTypes()));
    }

    private static void relationForThrownType(com.java.semantic.syntax.domain.SourceThrownTypeMetadata thrown,
                                               CodeFactIdentity from, RepositoryId repositoryId, RepositoryRevision revision,
                                               GenerationId generationId, Map<String, SourceTypeIdentity> owned,
                                               com.java.semantic.model.index.SourceArtifactDocument artifact,
                                               ArrayList<RelationDocument> documents) {
        String qualifiedType = thrown.resolvedType().map(com.java.semantic.model.codefact.JavaTypeIdentity::fullyQualifiedName)
                .orElse(thrown.writtenName());
        RelationTarget target = targetFor(owned.get(qualifiedType), () -> new RelationTarget.External(
                new ExternalTarget.NominalType(new DeclaredType(qualifiedType))), type -> new RelationTarget.Internal(
                SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.TYPE, type).identity()));
        add(from, RelationKind.DECLARES_ERROR_CONTRACT, target, thrown.occurrence(),
                CodeFactKind.ERROR_CONTRACT, repositoryId, revision, generationId, artifact, documents);
    }

    private static void relationForType(Optional<com.java.semantic.model.codefact.JavaTypeIdentity> type,
                                        CodeFactIdentity from, RelationKind kind, SourceRange occurrence, RepositoryId repositoryId,
                                        RepositoryRevision revision, GenerationId generationId, Map<String, SourceTypeIdentity> owned,
                                        com.java.semantic.model.index.SourceArtifactDocument artifact, ArrayList<RelationDocument> documents) {
        relationForType(type, from, kind, occurrence, repositoryId, revision, generationId, owned, artifact, documents,
                CodeFactKind.TYPE_USAGE);
    }

    private static void relationForType(Optional<com.java.semantic.model.codefact.JavaTypeIdentity> type,
                                        CodeFactIdentity from, RelationKind kind, SourceRange occurrence, RepositoryId repositoryId,
                                        RepositoryRevision revision, GenerationId generationId, Map<String, SourceTypeIdentity> owned,
                                        com.java.semantic.model.index.SourceArtifactDocument artifact, ArrayList<RelationDocument> documents,
                                        CodeFactKind factKind) {
        type.ifPresent(identity -> {
            RelationTarget target = targetFor(owned.get(identity.fullyQualifiedName()), () -> new RelationTarget.External(
                    new ExternalTarget.NominalType(new DeclaredType(identity.fullyQualifiedName()))), targetType -> new RelationTarget.Internal(
                    SyntaxSymbolProjector.fact(repositoryId, revision, CodeFactKind.TYPE, targetType).identity()));
            add(from, kind, target, occurrence, factKind, repositoryId, revision, generationId, artifact, documents);
        });
    }

    private static String qualifiedName(InvocationTarget target) {
        return target.packageName().isBlank() ? target.className() : target.packageName() + "." + target.className();
    }

    private static RelationTarget targetFor(SourceTypeIdentity owned, java.util.function.Supplier<RelationTarget> external,
                                            java.util.function.Function<SourceTypeIdentity, RelationTarget> internal) {
        return Optional.ofNullable(owned).map(internal).orElseGet(external);
    }

    private static void add(CodeFactIdentity from, RelationKind kind, RelationTarget target, SourceRange range, CodeFactKind factKind,
                            RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                            com.java.semantic.model.index.SourceArtifactDocument artifact, ArrayList<RelationDocument> documents) {
        RelationIdentity relationIdentity = new RelationIdentity(from, kind, target, range);
        CodeFact fact = SyntaxSymbolProjector.fact(repositoryId, revision, factKind, relationIdentity);
        documents.add(new RelationDocument(repositoryId, generationId, fact, kind, from, target, artifact.id(), range));
    }
}
