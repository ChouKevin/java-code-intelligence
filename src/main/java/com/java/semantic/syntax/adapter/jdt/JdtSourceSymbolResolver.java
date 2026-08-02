package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.SourceContextCandidate;
import com.java.semantic.syntax.application.SourceMethodContextCandidate;
import com.java.semantic.syntax.application.SourceRange;
import com.java.semantic.syntax.application.SourceSymbolCandidate;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.application.SourceSymbolIssueCode;
import com.java.semantic.syntax.application.SourceSymbolIssueSummary;
import com.java.semantic.syntax.application.SourceSymbolKind;
import com.java.semantic.syntax.application.SourceSymbolResolution;
import com.java.semantic.syntax.application.SourceSymbolResolutionQuery;
import com.java.semantic.syntax.application.SourceSymbolResolutionStatus;
import com.java.semantic.syntax.application.SourceSymbolResolver;
import com.java.semantic.syntax.application.SourceTypeContextCandidate;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 不保留 request state 的 JDT source-only symbol resolver */
@Service
public final class JdtSourceSymbolResolver implements SourceSymbolResolver {

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, JdtSourceSymbolResolver::compareParameters);

    private final SourceRootLocator sourceRootLocator = new SourceRootLocator();

    private final SourceFileScanner sourceFileScanner = new SourceFileScanner();

    private final JdtSourceDeclarationLocator declarationLocator = new JdtSourceDeclarationLocator();

    @Override
    public SourceSymbolResolution resolve(RepositorySnapshot snapshot, SourceSymbolResolutionQuery query) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(query, "query is required");
        List<Path> sourceRoots = sourceRootLocator.sourceRootsOf(snapshot.root());
        if (CollectionUtils.isEmpty(sourceRoots)) {
            return empty(SourceSymbolResolutionStatus.CONTEXT_NOT_FOUND, List.of(), List.of());
        }

        List<SourceFile> files = sourceFileScanner.scan(snapshot.root(), sourceRoots);
        RequestIndex index = new RequestIndex();
        JdtAstParser parser = new JdtAstParser(sourceRoots);
        JdtParseContext parseContext = parser.parseWithContext(files, index::add);
        List<JdtSourceDeclarationLocator.LocatedContext> contexts = index.contexts(query.context());
        if (contexts.size() > 1) {
            return ambiguousContext(query.context(), contexts);
        }
        if (contexts.isEmpty()) {
            List<SourceSymbolIssueSummary> issues = index.hasUnsupportedContext(
                    query.context().javaType().fullyQualifiedName())
                    ? List.of(new SourceSymbolIssueSummary(SourceSymbolIssueCode.UNSUPPORTED_SOURCE_CONSTRUCT, 1))
                    : List.of();
            return empty(SourceSymbolResolutionStatus.CONTEXT_NOT_FOUND, List.of(), issues);
        }

        JdtSourceDeclarationLocator.LocatedContext selectedBatchContext = contexts.getFirst();
        ParsedSource reparsed = parser.parseSelected(selectedBatchContext.parsed().source(), parseContext);
        RequestIndex selectedIndex = new RequestIndex(index.sourceTypeNames(), index.declarationsByKey());
        selectedIndex.add(reparsed);
        List<JdtSourceDeclarationLocator.LocatedContext> selectedContexts = selectedIndex.contexts(query.context());
        if (selectedContexts.size() != 1) {
            return empty(SourceSymbolResolutionStatus.CONTEXT_NOT_FOUND, List.of(), List.of());
        }
        JdtSourceDeclarationLocator.LocatedContext selected = selectedContexts.getFirst();
        if (selected.method().isPresent()) {
            return resolveMethodContext(selected, query, selectedIndex);
        }
        return resolveTypeContext(selected, query, selectedIndex.sourceTypeNames());
    }

    private SourceSymbolResolution ambiguousContext(
            SourceSymbolContext requested,
            List<JdtSourceDeclarationLocator.LocatedContext> contexts) {
        List<SourceContextCandidate> candidates;
        if (requested.method().isPresent()) {
            candidates = contexts.stream()
                    .flatMap(context -> context.target().stream())
                    .distinct()
                    .sorted(TARGET_ORDER)
                    .map(target -> (SourceContextCandidate) new SourceMethodContextCandidate(target))
                    .toList();
        } else {
            candidates = contexts.stream()
                    .map(context -> context.parsed().source().repositoryRelativePath())
                    .distinct()
                    .sorted()
                    .map(sourceFile -> (SourceContextCandidate) new SourceTypeContextCandidate(sourceFile))
                    .toList();
        }
        return empty(SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT, candidates, List.of());
    }

    private SourceSymbolResolution resolveTypeContext(
            JdtSourceDeclarationLocator.LocatedContext context,
            SourceSymbolResolutionQuery query,
            Set<String> sourceTypeNames) {
        List<JdtSourceDeclarationLocator.LocatedDeclaration> declarations =
                declarationLocator.directDeclarations(context.type(), query.symbol());
        Optional<SyntaxPosition> requestedPosition = query.position();
        if (requestedPosition.isPresent()) {
            declarations = declarations.stream()
                    .filter(declaration -> contains(
                            context.parsed(), declaration.name(), requestedPosition.orElseThrow()))
                    .toList();
            if (declarations.isEmpty()) {
                return selectedEmpty(SourceSymbolResolutionStatus.POSITION_MISMATCH, context);
            }
        }
        if (declarations.isEmpty()) {
            return selectedEmpty(SourceSymbolResolutionStatus.SYMBOL_NOT_FOUND, context);
        }

        List<ResolutionAttempt> attempts = declarations.stream()
                .map(declaration -> candidateForDirectDeclaration(context, declaration, sourceTypeNames))
                .toList();
        List<SourceSymbolCandidate> candidates = attempts.stream()
                .flatMap(attempt -> attempt.candidate().stream())
                .sorted(candidateOrder())
                .toList();
        if (declarations.size() > 1) {
            return selected(SourceSymbolResolutionStatus.AMBIGUOUS_SYMBOL, context, candidates, issues(attempts),
                    declarations.size());
        }
        if (attempts.getFirst().candidate().isEmpty()) {
            return selected(SourceSymbolResolutionStatus.UNRESOLVED_BINDING, context, List.of(), issues(attempts), 1);
        }
        return selected(SourceSymbolResolutionStatus.RESOLVED, context, candidates, List.of(), 1);
    }

    private SourceSymbolResolution resolveMethodContext(
            JdtSourceDeclarationLocator.LocatedContext context,
            SourceSymbolResolutionQuery query,
            RequestIndex index) {
        MethodDeclaration method = context.method().orElseThrow();
        List<SimpleName> occurrences = matchingNames(method, query.symbol());
        if (query.position().isPresent()) {
            SyntaxPosition position = query.position().orElseThrow();
            occurrences = occurrences.stream()
                    .filter(name -> contains(context.parsed(), name, position))
                    .toList();
            if (occurrences.isEmpty()) {
                return selectedEmpty(SourceSymbolResolutionStatus.POSITION_MISMATCH, context);
            }
        }
        if (occurrences.isEmpty()) {
            return selectedEmpty(SourceSymbolResolutionStatus.SYMBOL_NOT_FOUND, context);
        }

        Map<String, List<SimpleName>> namesByDeclaration = new LinkedHashMap<>();
        EnumMap<SourceSymbolIssueCode, Integer> unresolved = new EnumMap<>(SourceSymbolIssueCode.class);
        for (SimpleName occurrence : occurrences) {
            Optional<JdtSourceDeclarationLocator.BindingIdentity> identity =
                    declarationLocator.bindingIdentity(occurrence.resolveBinding());
            if (identity.isEmpty()) {
                increment(unresolved, SourceSymbolIssueCode.SOURCE_BINDING_UNRESOLVED);
                continue;
            }
            JdtSourceDeclarationLocator.BindingIdentity binding = identity.orElseThrow();
            if (binding.unsupported()) {
                increment(unresolved, SourceSymbolIssueCode.UNSUPPORTED_SOURCE_CONSTRUCT);
                continue;
            }
            if (!index.declarationsByKey().containsKey(binding.key())) {
                increment(unresolved, SourceSymbolIssueCode.SOURCE_BINDING_UNRESOLVED);
                continue;
            }
            namesByDeclaration.computeIfAbsent(binding.key(), ignored -> new ArrayList<>()).add(occurrence);
        }

        List<SourceSymbolCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<SimpleName>> group : namesByDeclaration.entrySet()) {
            DeclarationRef declaration = index.declarationsByKey().get(group.getKey());
            candidateForDeclarationRef(declaration, group.getValue(), context.parsed(), index.sourceTypeNames())
                    .ifPresent(candidates::add);
        }
        candidates.sort(candidateOrder());
        List<SourceSymbolIssueSummary> issues = issueSummaries(unresolved);
        SourceSymbolResolutionStatus status;
        if (namesByDeclaration.size() > 1) {
            status = SourceSymbolResolutionStatus.AMBIGUOUS_OCCURRENCE;
        } else if (!unresolved.isEmpty() || candidates.isEmpty()) {
            status = SourceSymbolResolutionStatus.UNRESOLVED_BINDING;
        } else {
            status = SourceSymbolResolutionStatus.RESOLVED;
        }
        return selected(status, context, candidates, issues, occurrences.size());
    }

    private ResolutionAttempt candidateForDirectDeclaration(
            JdtSourceDeclarationLocator.LocatedContext context,
            JdtSourceDeclarationLocator.LocatedDeclaration declaration,
            Set<String> sourceTypeNames) {
        ASTNode node = declaration.identityNode();
        if (node instanceof VariableDeclarationFragment fragment) {
            return variableCandidate(context.parsed(), context.type(), context.target(), fragment.getName(),
                    declaration.rangeNode(), fieldWrittenType(fragment), fragment.resolveBinding(), sourceTypeNames,
                    Optional.of(fragment));
        }
        if (node instanceof SingleVariableDeclaration component) {
            return variableCandidate(context.parsed(), context.type(), context.target(), component.getName(),
                    declaration.rangeNode(), component.getType().toString(), component.resolveBinding(),
                    sourceTypeNames, Optional.empty());
        }
        if (node instanceof EnumConstantDeclaration constant) {
            return variableCandidate(context.parsed(), context.type(), context.target(), constant.getName(),
                    declaration.rangeNode(), context.type().getName().getIdentifier(), constant.resolveVariable(),
                    sourceTypeNames, Optional.empty());
        }
        if (node instanceof MethodDeclaration method) {
            MethodTargetResolution resolution = methodTarget(context.parsed(), context.type(), method);
            if (resolution.target().isEmpty()) {
                return ResolutionAttempt.unresolved(SourceSymbolIssueCode.SOURCE_BINDING_UNRESOLVED);
            }
            SourceRange declarationRange = range(context.parsed(), declaration.rangeNode());
            SourceRange occurrence = range(context.parsed(), declaration.name());
            return ResolutionAttempt.resolved(new SourceSymbolCandidate.Method(
                    resolution.target().orElseThrow(), declarationRange, occurrence, 1));
        }
        if (node instanceof AbstractTypeDeclaration nested) {
            SourceRange declarationRange = range(context.parsed(), declaration.rangeNode());
            SourceRange occurrence = range(context.parsed(), declaration.name());
            return ResolutionAttempt.resolved(new SourceSymbolCandidate.SourceType(
                    new SourceTypeIdentity(
                            new JavaTypeIdentity(
                                    packageName(context.parsed()),
                                    SourceTypes.nestedName(nested)),
                            context.parsed().source().repositoryRelativePath()),
                    declarationRange, occurrence, 1));
        }
        return ResolutionAttempt.unresolved(SourceSymbolIssueCode.UNSUPPORTED_SOURCE_CONSTRUCT);
    }

    private Optional<SourceSymbolCandidate> candidateForDeclarationRef(
            DeclarationRef declaration,
            List<SimpleName> occurrences,
            ParsedSource occurrenceSource,
            Set<String> sourceTypeNames) {
        SimpleName representative = occurrences.stream()
                .filter(name -> !name.isDeclaration())
                .findFirst()
                .orElse(occurrences.getFirst());
        SourceRange representativeOccurrence = range(occurrenceSource, representative);
        ASTNode identityNode = declaration.name().getParent();
        Optional<MethodTarget> declaringMethod = declaration.declaringMethod();
        if (identityNode instanceof VariableDeclarationFragment fragment) {
            ResolutionAttempt attempt = variableCandidate(
                    declaration.parsed(), declaration.ownerType(), declaringMethod, declaration.name(),
                    declaration.rangeNode(), fieldWrittenType(fragment), fragment.resolveBinding(), sourceTypeNames,
                    Optional.of(fragment), representativeOccurrence, occurrences.size());
            return attempt.candidate();
        }
        if (identityNode instanceof SingleVariableDeclaration variable) {
            ResolutionAttempt attempt = variableCandidate(
                    declaration.parsed(), declaration.ownerType(), declaringMethod, declaration.name(),
                    declaration.rangeNode(), variable.getType().toString(), variable.resolveBinding(), sourceTypeNames,
                    Optional.empty(), representativeOccurrence, occurrences.size());
            return attempt.candidate();
        }
        if (identityNode instanceof EnumConstantDeclaration constant) {
            ResolutionAttempt attempt = variableCandidate(
                    declaration.parsed(), declaration.ownerType(), declaringMethod, declaration.name(),
                    declaration.rangeNode(), declaration.ownerType().getName().getIdentifier(),
                    constant.resolveVariable(), sourceTypeNames, Optional.empty(), representativeOccurrence,
                    occurrences.size());
            return attempt.candidate();
        }
        if (identityNode instanceof MethodDeclaration method) {
            MethodTargetResolution resolution = methodTarget(declaration.parsed(), declaration.ownerType(), method);
            return resolution.target().map(target -> new SourceSymbolCandidate.Method(
                    target,
                    range(declaration.parsed(), declaration.rangeNode()),
                    representativeOccurrence,
                    occurrences.size()));
        }
        if (identityNode instanceof AbstractTypeDeclaration type) {
            return Optional.of(new SourceSymbolCandidate.SourceType(
                    new SourceTypeIdentity(
                            new JavaTypeIdentity(
                                    packageName(declaration.parsed()),
                                    SourceTypes.nestedName(type)),
                            declaration.parsed().source().repositoryRelativePath()),
                    range(declaration.parsed(), declaration.rangeNode()),
                    representativeOccurrence,
                    occurrences.size()));
        }
        return Optional.empty();
    }

    private ResolutionAttempt variableCandidate(
            ParsedSource parsed,
            AbstractTypeDeclaration ownerType,
            Optional<MethodTarget> declaringMethod,
            SimpleName declarationName,
            ASTNode rangeNode,
            String writtenType,
            IVariableBinding binding,
            Set<String> sourceTypeNames,
            Optional<VariableDeclarationFragment> fieldFragment) {
        return variableCandidate(
                parsed, ownerType, declaringMethod, declarationName, rangeNode, writtenType, binding,
                sourceTypeNames, fieldFragment, range(parsed, declarationName), 1);
    }

    private ResolutionAttempt variableCandidate(
            ParsedSource parsed,
            AbstractTypeDeclaration ownerType,
            Optional<MethodTarget> declaringMethod,
            SimpleName declarationName,
            ASTNode rangeNode,
            String writtenType,
            IVariableBinding binding,
            Set<String> sourceTypeNames,
            Optional<VariableDeclarationFragment> fieldFragment,
            SourceRange representativeOccurrence,
            int occurrenceCount) {
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return ResolutionAttempt.unresolved(SourceSymbolIssueCode.SOURCE_BINDING_UNRESOLVED);
        }
        SourceSymbolKind kind = variableKind(declarationName, binding);
        Optional<MethodTarget> methodTarget = declaringMethod;
        SourceMemberIdentity identity;
        if (kind == SourceSymbolKind.PARAMETER || kind == SourceSymbolKind.LOCAL_VARIABLE) {
            if (methodTarget.isEmpty()) {
                return ResolutionAttempt.unresolved(SourceSymbolIssueCode.SOURCE_BINDING_UNRESOLVED);
            }
            identity = new SourceMemberIdentity.MethodScoped(
                    methodTarget.orElseThrow(), new SourceSlices(parsed.unit(), parsed.text()).range(declarationName),
                    declarationName.getIdentifier());
        } else {
            identity = new SourceMemberIdentity.TypeMember(
                    new SourceTypeIdentity(
                            new JavaTypeIdentity(packageName(parsed),
                                    ownerType.getName().getIdentifier()),
                            parsed.source().repositoryRelativePath()),
                    declarationName.getIdentifier());
        }
        Optional<String> resolvedType = resolvedSourceType(binding.getType(), sourceTypeNames);
        SourceRange declarationRange = range(parsed, rangeNode);
        if (isCompileTimeConstant(fieldFragment)) {
            VariableDeclarationFragment fragment = fieldFragment.orElseThrow();
            String initializerSource = new SourceSlices(parsed.unit(), parsed.text())
                    .slice(fragment.getInitializer()).text();
            return ResolutionAttempt.resolved(new SourceSymbolCandidate.StaticConstant(
                    declarationName.getIdentifier(), identity, writtenType, resolvedType, initializerSource,
                    declarationRange, representativeOccurrence, occurrenceCount));
        }
        return ResolutionAttempt.resolved(new SourceSymbolCandidate.VariableLike(
                kind, declarationName.getIdentifier(), identity, writtenType, resolvedType,
                declarationRange, representativeOccurrence, occurrenceCount));
    }

    private boolean isCompileTimeConstant(Optional<VariableDeclarationFragment> fieldFragment) {
        if (fieldFragment.isEmpty()) {
            return false;
        }
        VariableDeclarationFragment fragment = fieldFragment.orElseThrow();
        ASTNode parent = fragment.getParent();
        if (!(parent instanceof FieldDeclaration field)) {
            return false;
        }
        return Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && Objects.nonNull(fragment.getInitializer())
                && Objects.nonNull(fragment.getInitializer().resolveConstantExpressionValue());
    }

    private SourceSymbolKind variableKind(SimpleName name, IVariableBinding binding) {
        ASTNode parent = name.getParent();
        if (parent instanceof EnumConstantDeclaration || binding.isEnumConstant()) {
            return SourceSymbolKind.ENUM_CONSTANT;
        }
        if (parent instanceof SingleVariableDeclaration declaration
                && declaration.getParent() instanceof RecordDeclaration) {
            return SourceSymbolKind.RECORD_COMPONENT;
        }
        if (binding.isParameter()) {
            return SourceSymbolKind.PARAMETER;
        }
        if (binding.isField()) {
            return SourceSymbolKind.FIELD;
        }
        return SourceSymbolKind.LOCAL_VARIABLE;
    }

    private String fieldWrittenType(VariableDeclarationFragment fragment) {
        ASTNode parent = fragment.getParent();
        if (parent instanceof FieldDeclaration field) {
            return field.getType().toString();
        }
        if (parent instanceof VariableDeclarationStatement statement) {
            return statement.getType().toString();
        }
        if (parent instanceof VariableDeclarationExpression expression) {
            return expression.getType().toString();
        }
        return "unknown";
    }

    private Optional<String> resolvedSourceType(ITypeBinding binding, Set<String> sourceTypeNames) {
        if (Objects.isNull(binding) || binding.isRecovered() || binding.isTypeVariable()) {
            return Optional.empty();
        }
        ITypeBinding erased = binding.getErasure();
        if (Objects.isNull(erased) || erased.isRecovered()) {
            return Optional.empty();
        }
        String qualifiedName = erased.getQualifiedName().replace('$', '.');
        return sourceTypeNames.contains(qualifiedName) ? Optional.of(qualifiedName) : Optional.empty();
    }

    private MethodTargetResolution methodTarget(
            ParsedSource parsed,
            AbstractTypeDeclaration ownerType,
            MethodDeclaration method) {
        return declarationLocator.methodTarget(parsed, ownerType, method);
    }

    private List<SimpleName> matchingNames(MethodDeclaration method, String symbol) {
        List<SimpleName> names = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (symbol.equals(name.getIdentifier())) {
                    names.add(name);
                }
                return true;
            }
        });
        names.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return List.copyOf(names);
    }

    private SourceRange range(ParsedSource parsed, ASTNode node) {
        return new SourceRange(
                parsed.source().repositoryRelativePath(),
                declarationLocator.range(parsed, node));
    }

    private boolean contains(ParsedSource parsed, ASTNode node, SyntaxPosition position) {
        return declarationLocator.contains(parsed, node, position);
    }

    private String packageName(ParsedSource parsed) {
        return declarationLocator.packageName(parsed);
    }

    private String fullyQualifiedName(ParsedSource parsed, AbstractTypeDeclaration type) {
        return declarationLocator.fullyQualifiedName(parsed, type);
    }

    private Comparator<SourceSymbolCandidate> candidateOrder() {
        return Comparator.comparing((SourceSymbolCandidate candidate) -> candidate.kind().ordinal())
                .thenComparing(this::declarationOwner)
                .thenComparing(candidate -> declaringMethod(candidate).map(MethodTarget::methodName).orElse(""))
                .thenComparing(
                        candidate -> declaringMethod(candidate).map(MethodTarget::parameterTypes).orElse(List.of()),
                        JdtSourceSymbolResolver::compareParameters)
                .thenComparing(candidate -> candidate.declarationRange().sourceFile())
                .thenComparing(candidate -> candidate.declarationRange().range().start().line())
                .thenComparing(candidate -> candidate.declarationRange().range().start().character());
    }

    private String declarationOwner(SourceSymbolCandidate candidate) {
        return switch (candidate) {
            case SourceSymbolCandidate.VariableLike variable -> declarationOwner(variable.identity());
            case SourceSymbolCandidate.StaticConstant constant -> declarationOwner(constant.identity());
            case SourceSymbolCandidate.Method method -> declarationOwner(method.identity());
            case SourceSymbolCandidate.SourceType type -> type.identity().fullyQualifiedName();
        };
    }

    private String declarationOwner(SourceMemberIdentity identity) {
        return switch (identity) {
            case SourceMemberIdentity.TypeMember member -> member.ownerType().fullyQualifiedName();
            case SourceMemberIdentity.MethodScoped local -> declarationOwner(local.declaringMethod());
        };
    }

    private String declarationOwner(MethodTarget target) {
        return target.packageName().isEmpty()
                ? target.className()
                : target.packageName() + "." + target.className();
    }

    private Optional<MethodTarget> declaringMethod(SourceSymbolCandidate candidate) {
        return switch (candidate) {
            case SourceSymbolCandidate.VariableLike variable -> declaringMethod(variable.identity());
            case SourceSymbolCandidate.StaticConstant constant -> declaringMethod(constant.identity());
            case SourceSymbolCandidate.Method method -> Optional.of(method.identity());
            case SourceSymbolCandidate.SourceType ignored -> Optional.empty();
        };
    }

    private Optional<MethodTarget> declaringMethod(SourceMemberIdentity identity) {
        return switch (identity) {
            case SourceMemberIdentity.TypeMember ignored -> Optional.empty();
            case SourceMemberIdentity.MethodScoped local -> Optional.of(local.declaringMethod());
        };
    }

    private List<SourceSymbolIssueSummary> issues(List<ResolutionAttempt> attempts) {
        EnumMap<SourceSymbolIssueCode, Integer> counts = new EnumMap<>(SourceSymbolIssueCode.class);
        attempts.stream().flatMap(attempt -> attempt.issue().stream()).forEach(code -> increment(counts, code));
        return issueSummaries(counts);
    }

    private List<SourceSymbolIssueSummary> issueSummaries(Map<SourceSymbolIssueCode, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SourceSymbolIssueSummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void increment(Map<SourceSymbolIssueCode, Integer> counts, SourceSymbolIssueCode code) {
        counts.merge(code, 1, Integer::sum);
    }

    private SourceSymbolResolution selectedEmpty(
            SourceSymbolResolutionStatus status,
            JdtSourceDeclarationLocator.LocatedContext context) {
        return selected(status, context, List.of(), List.of(), 0);
    }

    private SourceSymbolResolution selected(
            SourceSymbolResolutionStatus status,
            JdtSourceDeclarationLocator.LocatedContext context,
            List<SourceSymbolCandidate> candidates,
            List<SourceSymbolIssueSummary> issues,
            int matchingSymbolCount) {
        String sourceFile = context.parsed().source().repositoryRelativePath();
        return new SourceSymbolResolution(
                status,
                List.of(),
                candidates,
                issues,
                Optional.of(sourceFile),
                context.parsed().text().getBytes(StandardCharsets.UTF_8).length,
                matchingSymbolCount);
    }

    private SourceSymbolResolution empty(
            SourceSymbolResolutionStatus status,
            List<SourceContextCandidate> contexts,
            List<SourceSymbolIssueSummary> issues) {
        return new SourceSymbolResolution(status, contexts, List.of(), issues, Optional.empty(), 0, 0);
    }

    private static int compareParameters(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int compared = left.get(index).compareTo(right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record DeclarationRef(
            ParsedSource parsed,
            AbstractTypeDeclaration ownerType,
            SimpleName name,
            ASTNode rangeNode,
            Optional<MethodTarget> declaringMethod) {
    }

    private record ResolutionAttempt(
            Optional<SourceSymbolCandidate> candidate,
            Optional<SourceSymbolIssueCode> issue) {

        static ResolutionAttempt resolved(SourceSymbolCandidate candidate) {
            return new ResolutionAttempt(Optional.of(candidate), Optional.empty());
        }

        static ResolutionAttempt unresolved(SourceSymbolIssueCode issue) {
            return new ResolutionAttempt(Optional.empty(), Optional.of(issue));
        }
    }

    private final class RequestIndex {

        private final List<ParsedSource> parsedSources = new ArrayList<>();

        private final Map<String, DeclarationRef> declarationsByKey;

        private final LinkedHashSet<String> sourceTypeNames;

        private final LinkedHashSet<String> unsupportedContextNames = new LinkedHashSet<>();

        private RequestIndex() {
            this(Set.of(), Map.of());
        }

        private RequestIndex(Set<String> sourceTypeNames, Map<String, DeclarationRef> declarationsByKey) {
            this.sourceTypeNames = new LinkedHashSet<>(sourceTypeNames);
            this.declarationsByKey = new LinkedHashMap<>(declarationsByKey);
        }

        private void add(ParsedSource parsed) {
            parsedSources.add(parsed);
            for (AbstractTypeDeclaration type : SourceTypes.allTypesOf(parsed.unit())) {
                String fullyQualifiedName = fullyQualifiedName(parsed, type);
                if (type instanceof AnnotationTypeDeclaration) {
                    unsupportedContextNames.add(fullyQualifiedName);
                } else {
                    sourceTypeNames.add(fullyQualifiedName);
                }
            }
            indexDeclarations(parsed);
        }

        private void indexDeclarations(ParsedSource parsed) {
            parsed.unit().accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName name) {
                    if (!name.isDeclaration()) {
                        return true;
                    }
                    Optional<JdtSourceDeclarationLocator.BindingIdentity> identity =
                            declarationLocator.bindingIdentity(name.resolveBinding());
                    if (identity.isEmpty() || identity.orElseThrow().unsupported()) {
                        return true;
                    }
                    Optional<AbstractTypeDeclaration> ownerType = enclosingType(name);
                    if (ownerType.isEmpty()) {
                        return true;
                    }
                    ASTNode rangeNode = declarationLocator.declarationRangeNode(name);
                    Optional<MethodTarget> method = enclosingMethod(parsed, ownerType.orElseThrow(), name);
                    declarationsByKey.putIfAbsent(
                            identity.orElseThrow().key(),
                            new DeclarationRef(parsed, ownerType.orElseThrow(), name, rangeNode, method));
                    return true;
                }
            });
        }

        private List<JdtSourceDeclarationLocator.LocatedContext> contexts(SourceSymbolContext requested) {
            List<JdtSourceDeclarationLocator.LocatedContext> contexts = new ArrayList<>();
            for (ParsedSource parsed : parsedSources) {
                if (requested.sourceFile().isPresent()
                        && !requested.sourceFile().orElseThrow().equals(parsed.source().repositoryRelativePath())) {
                    continue;
                }
                contexts.addAll(declarationLocator.contexts(parsed, requested));
            }
            contexts.sort(Comparator.comparing(
                    context -> context.target().orElseGet(() -> new MethodTarget(
                            new SourceTypeIdentity(
                                    new JavaTypeIdentity(
                                            packageName(context.parsed()),
                                            SourceTypes.nestedName(context.type())),
                                    context.parsed().source().repositoryRelativePath()),
                            requested.method().map(SourceSymbolContext.MethodContext::name).orElse("type"),
                            List.of())), TARGET_ORDER));
            return List.copyOf(contexts);
        }

        private Optional<AbstractTypeDeclaration> enclosingType(ASTNode node) {
            ASTNode current = node;
            while (Objects.nonNull(current)) {
                if (current instanceof AbstractTypeDeclaration type) {
                    return Optional.of(type);
                }
                current = current.getParent();
            }
            return Optional.empty();
        }

        private Optional<MethodTarget> enclosingMethod(
                ParsedSource parsed,
                AbstractTypeDeclaration ownerType,
                ASTNode node) {
            ASTNode current = node;
            while (Objects.nonNull(current) && current != ownerType) {
                if (current instanceof MethodDeclaration method) {
                    return methodTarget(parsed, ownerType, method).target();
                }
                current = current.getParent();
            }
            return Optional.empty();
        }

        private Set<String> sourceTypeNames() {
            return Set.copyOf(sourceTypeNames);
        }

        private Map<String, DeclarationRef> declarationsByKey() {
            return Map.copyOf(declarationsByKey);
        }

        private boolean hasUnsupportedContext(String fullyQualifiedName) {
            return unsupportedContextNames.contains(fullyQualifiedName);
        }
    }
}
