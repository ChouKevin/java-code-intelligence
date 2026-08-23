package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticEngineException;
import com.java.semantic.semantic.domain.SemanticIncomingCall;
import com.java.semantic.semantic.domain.SemanticIncomingCallIssue;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticImplementationIssueReason;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 以 JDT LS 精確回答語意問題的 adapter
 *
 * 所有 LSP4J 型別在此轉為 domain record,絕不外洩;位置一律以零基處理
 */
@Service
@Slf4j
public class Lsp4jJavaSemanticService implements JavaSemanticService {

    private static final String JAVA_LANGUAGE_ID = "java";
    private static final String UNAVAILABLE_TARGET_SIGNATURE = "semantic target identity unavailable";
    private static final int MAX_EXTERNAL_DISPLAY_SIGNATURE_LENGTH = 256;
    private static final Pattern SAFE_EXTERNAL_DISPLAY_SIGNATURE = Pattern.compile(
            "[A-Za-z_$<][A-Za-z0-9_$<>]*(\\([A-Za-z0-9_$.<>?, \\[\\]]*\\))?"
                    + "(\\s*:\\s*[A-Za-z0-9_$.<>?\\[\\]]+)?");
    private static final Set<SymbolKind> TYPE_KINDS =
            EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Struct);
    private static final Set<SymbolKind> METHOD_KINDS =
            EnumSet.of(SymbolKind.Method, SymbolKind.Constructor);
    private static final Comparator<SemanticMethod> INCOMING_CALLER_ORDER =
            Comparator.comparing(SemanticMethod::packageName)
                    .thenComparing(SemanticMethod::className)
                    .thenComparing(SemanticMethod::methodName)
                    .thenComparing(SemanticMethod::parameterTypes,
                            Lsp4jJavaSemanticService::compareParameterTypes)
                    .thenComparing(SemanticMethod::returnType)
                    .thenComparing(method -> method.location().uri())
                    .thenComparing(method -> method.location().range(),
                            Lsp4jJavaSemanticService::compareRanges)
                    .thenComparing(method -> method.location().selectionRange(),
                            Lsp4jJavaSemanticService::compareRanges);
    private static final Comparator<SemanticRange> INCOMING_RANGE_ORDER =
            Lsp4jJavaSemanticService::compareRanges;

    private final JdtWorkspaceManager workspaceManager;
    private final JdtWorkspaceSourceLocator sourceLocator;

    public Lsp4jJavaSemanticService(JdtWorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager is required");
        this.sourceLocator = new JdtWorkspaceSourceLocator();
    }

    @Override
    public SemanticSourceClassification classifySource(RepositorySnapshot snapshot, SemanticMethod method) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(method, "method is required");
        return sourceLocator.classify(snapshot, method.location().uri());
    }

    @Override
    public List<SemanticReferenceLocation> findReferences(
            RepositorySnapshot snapshot, SemanticReferenceAnchor anchor) {
        return JdtLsSemanticExceptionNormalizer.normalize(() -> findReferencesInternal(snapshot, anchor));
    }

    private List<SemanticReferenceLocation> findReferencesInternal(
            RepositorySnapshot snapshot, SemanticReferenceAnchor anchor) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(anchor, "anchor is required");
        String uri = sourceLocator.sourceUri(snapshot, anchor.sourceFile());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            ReferenceParams params = new ReferenceParams(
                    new TextDocumentIdentifier(uri),
                    toPosition(anchor.identifierPosition()),
                    new ReferenceContext(false));
            List<? extends Location> locations = session.call(
                    "textDocument/references",
                    server -> server.getTextDocumentService().references(params));
            return nullSafe(locations).stream()
                    .map(location -> toSemanticReferenceLocation(snapshot, location))
                    .toList();
        }));
    }

    private SemanticReferenceLocation toSemanticReferenceLocation(
            RepositorySnapshot snapshot, Location location) {
        Objects.requireNonNull(location, "location is required");
        return switch (sourceLocator.classify(snapshot, location.getUri())) {
            case SemanticSourceClassification.LocalSource local ->
                    new SemanticReferenceLocation.LocalSource(local.sourceFile(), toRange(location.getRange()));
            case SemanticSourceClassification.OutsideRepository ignored ->
                    SemanticReferenceLocation.OutsideRepository.INSTANCE;
            case SemanticSourceClassification.UnprovableUri ignored ->
                    SemanticReferenceLocation.UnprovableUri.INSTANCE;
        };
    }

    @Override
    public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
        return JdtLsSemanticExceptionNormalizer.normalize(() -> resolveExactMethodInternal(snapshot, anchor));
    }

    private SemanticMethod resolveExactMethodInternal(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(anchor, "anchor is required");
        String uri = sourceLocator.sourceUri(snapshot, anchor.target());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            CallHierarchyItem item = prepareExactCallHierarchy(session, uri, anchor);
            MethodTarget target = anchor.target();
            return new SemanticMethod(
                    target.packageName(),
                    target.className(),
                    target.methodName(),
                    target.parameterTypes(),
                    "",
                    new SemanticLocation(uri, toRange(item.getRange()), toRange(item.getSelectionRange())));
        }));
    }

    @Override
    public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
        return JdtLsSemanticExceptionNormalizer.normalize(
                () -> outgoingCallsInternal(snapshot, method));
    }

    private List<SemanticCall> outgoingCallsInternal(
            RepositorySnapshot snapshot, SemanticMethod method) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(method, "method is required");
        String uri = requireLocalInvocationUri(snapshot, method.location().uri());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        Position namePosition = toPosition(method.location().selectionRange().start());
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            Optional<CallHierarchyItem> root = prepareCallHierarchy(session, uri, namePosition, method.methodName());
            if (root.isEmpty()) {
                log.warn("phase=jdtls-outgoing outcome=failed reason=call-hierarchy-not-prepared repoId={}",
                        session.repositoryId().value());
                return List.of();
            }
            List<CallHierarchyOutgoingCall> calls = session.call(
                    "callHierarchy/outgoingCalls",
                    server -> server.getTextDocumentService()
                            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(root.orElseThrow())));
            List<CallHierarchyOutgoingCall> presentCalls = nullSafe(calls);
            List<SemanticCall> resolved = presentCalls.stream()
                    .map(call -> toSemanticCall(session, call, snapshot))
                    .flatMap(Optional::stream)
                    .toList();
            log.debug("phase=jdtls-outgoing outcome=completed repoId={} rawCallCount={} convertedCallCount={}",
                    session.repositoryId().value(), presentCalls.size(), resolved.size());
            return dedupeCalls(resolved);
        }));
    }

    @Override
    public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
        return JdtLsSemanticExceptionNormalizer.normalize(
                () -> incomingCallsInternal(snapshot, callee));
    }

    private SemanticIncomingCallResult incomingCallsInternal(
            RepositorySnapshot snapshot, SemanticMethod callee) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(callee, "callee is required");
        String uri = requireLocalInvocationUri(snapshot, callee.location().uri());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            Optional<CallHierarchyItem> prepared = prepareIncomingCallHierarchy(session, uri, callee);
            if (!prepared.isPresent()) {
                log.warn("phase=jdtls-incoming outcome=failed repoId={} rawCallCount={} issueCount={}",
                        session.repositoryId().value(), 0, 0);
                throw new SemanticProtocolException();
            }
            List<CallHierarchyIncomingCall> incoming = session.call(
                    "callHierarchy/incomingCalls",
                    server -> server.getTextDocumentService().callHierarchyIncomingCalls(
                            new CallHierarchyIncomingCallsParams(prepared.orElseThrow())));
            List<CallHierarchyIncomingCall> presentCalls = nullSafe(incoming);
            List<SemanticIncomingCall> converted = new ArrayList<>();
            List<SemanticIncomingCallIssue> issues = new ArrayList<>();
            for (CallHierarchyIncomingCall incomingCall : presentCalls) {
                try {
                    CallHierarchyItem caller = Objects.requireNonNull(
                            incomingCall.getFrom(), "incoming caller is required");
                    if (isJdtCaller(caller)) {
                        continue;
                    }
                    Optional<SemanticIncomingCall> resolved = toSemanticIncomingCall(
                            session, snapshot, incomingCall);
                    if (resolved.isPresent()) {
                        converted.add(resolved.orElseThrow());
                    } else {
                        issues.add(SemanticIncomingCallIssue.callerRejected());
                    }
                } catch (SemanticProtocolException exception) {
                    log.debug("phase=jdtls-incoming outcome=caller-rejected repoId={} issueCount={} exceptionType={}",
                            session.repositoryId().value(), issues.size() + 1,
                            exception.getClass().getSimpleName());
                    issues.add(SemanticIncomingCallIssue.callerRejected());
                } catch (RuntimeException exception) {
                    JdtLsSemanticExceptionNormalizer.rethrowIfEngineFailure(exception);
                    log.debug("phase=jdtls-incoming outcome=caller-rejected repoId={} issueCount={} exceptionType={}",
                            session.repositoryId().value(), issues.size() + 1,
                            exception.getClass().getSimpleName());
                    issues.add(SemanticIncomingCallIssue.callerRejected());
                }
            }
            SemanticIncomingCallResult result = dedupeIncomingCalls(converted, issues);
            log.debug("phase=jdtls-incoming outcome=completed repoId={} rawCallCount={} callerCount={} issueCount={}",
                    session.repositoryId().value(), presentCalls.size(), result.calls().size(), result.issues().size());
            return result;
        }));
    }

    @Override
    public SemanticCallResolution resolveCallResolutionAt(
            RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
        return JdtLsSemanticExceptionNormalizer.normalize(
                () -> resolveCallResolutionAtInternal(snapshot, caller, callSite));
    }

    private SemanticCallResolution resolveCallResolutionAtInternal(
            RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(caller, "caller is required");
        Assert.notNull(callSite, "callSite is required");
        String uri = requireLocalInvocationUri(snapshot, caller.location().uri());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            Either<List<? extends Location>, List<? extends LocationLink>> response = session.call(
                    "textDocument/definition",
                    server -> server.getTextDocumentService().definition(new DefinitionParams(
                            new TextDocumentIdentifier(uri), toPosition(callSite.anchor()))));
            Map<String, ResolvedCallTarget> resolved = new LinkedHashMap<>();
            for (TargetLocation target : implementationTargets(response).stream().distinct().toList()) {
                if (sourceLocator.localSource(snapshot, target.uri()).isEmpty()) {
                    continue;
                }
                try {
                    resolveLocalCallTarget(session, snapshot, target)
                            .ifPresent(candidate -> resolved.putIfAbsent(
                                    dedupeKey(candidate.method().location()), candidate));
                } catch (RuntimeException exception) {
                    JdtLsSemanticExceptionNormalizer.rethrowIfEngineFailure(exception);
                    log.debug("phase=jdtls-definition outcome=conversion-skipped repoId={} exceptionType={}",
                            session.repositoryId().value(),
                            exception.getClass().getSimpleName());
                }
            }
            if (resolved.isEmpty()) {
                return SemanticCallResolution.unresolved();
            }
            List<ResolvedCallTarget> candidates = List.copyOf(resolved.values());
            if (candidates.size() > 1) {
                log.warn("phase=jdtls-definition outcome=ambiguous repoId={} candidateCount={}",
                        session.repositoryId().value(), candidates.size());
                return SemanticCallResolution.ambiguous(candidates.stream()
                        .map(ResolvedCallTarget::method)
                        .toList());
            }
            ResolvedCallTarget target = candidates.getFirst();
            return SemanticCallResolution.resolved(new SemanticCall(
                    Optional.of(target.method()),
                    target.rawSignature(),
                    List.of(callSite.range()),
                    false,
                    SemanticResolutionOrigin.DEFINITION_FALLBACK));
        }));
    }

    @Override
    public SemanticImplementationResult implementations(RepositorySnapshot snapshot, SemanticMethod method) {
        return JdtLsSemanticExceptionNormalizer.normalize(
                () -> implementationsInternal(snapshot, method));
    }

    private SemanticImplementationResult implementationsInternal(
            RepositorySnapshot snapshot, SemanticMethod method) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(method, "method is required");
        String uri = requireLocalInvocationUri(snapshot, method.location().uri());
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        Position namePosition = toPosition(method.location().selectionRange().start());
        return session.withDocumentUri(uri, () -> withOpenedDocument(session, snapshot, uri, () -> {
            Either<List<? extends Location>, List<? extends LocationLink>> response = session.call(
                    "textDocument/implementation",
                    server -> server.getTextDocumentService()
                            .implementation(new ImplementationParams(new TextDocumentIdentifier(uri), namePosition)));
            Map<String, SemanticMethod> deduped = new LinkedHashMap<>();
            List<SemanticImplementationIssueReason> issues = new ArrayList<>();
            for (TargetLocation target : implementationTargets(response).stream().distinct().toList()) {
                if (isExternal(target.uri(), snapshot)) {
                    issues.add(SemanticImplementationIssueReason.EXTERNAL_TARGET);
                    continue;
                }
                try {
                    Optional<SemanticMethod> resolved = resolveImplementation(session, snapshot, target);
                    if (resolved.isPresent()) {
                        SemanticMethod semanticMethod = resolved.orElseThrow();
                        deduped.putIfAbsent(dedupeKey(semanticMethod.location()), semanticMethod);
                    } else {
                        issues.add(SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED);
                    }
                } catch (RuntimeException exception) {
                    JdtLsSemanticExceptionNormalizer.rethrowIfEngineFailure(exception);
                    log.debug("phase=jdtls-implementation outcome=conversion-failed repoId={} exceptionType={}",
                            session.repositoryId().value(),
                            exception.getClass().getSimpleName());
                    issues.add(SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED);
                }
            }
            return new SemanticImplementationResult(List.copyOf(deduped.values()), issues);
        }));
    }

    private Optional<CallHierarchyItem> prepareCallHierarchy(
            JdtWorkspaceSession session, String uri, Position position, String methodName) {
        List<CallHierarchyItem> items = session.call(
                "textDocument/prepareCallHierarchy",
                server -> server.getTextDocumentService()
                        .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position)));
        List<CallHierarchyItem> present = nullSafe(items);
        if (CollectionUtils.isEmpty(present)) {
            return Optional.empty();
        }
        return present.stream()
                .filter(item -> methodName.equals(MethodSignatures.bareName(item.getName())))
                .findFirst()
                .or(() -> Optional.of(present.getFirst()));
    }

    private Optional<CallHierarchyItem> prepareIncomingCallHierarchy(
            JdtWorkspaceSession session, String uri, SemanticMethod callee) {
        Position position = toPosition(callee.location().selectionRange().start());
        List<CallHierarchyItem> items = session.call(
                "textDocument/prepareCallHierarchy",
                server -> server.getTextDocumentService()
                        .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position)));
        List<CallHierarchyItem> exact = nullSafe(items).stream()
                .filter(Objects::nonNull)
                .filter(item -> uri.equals(item.getUri()))
                .filter(item -> callee.methodName().equals(MethodSignatures.bareName(item.getName())))
                .filter(item -> Objects.nonNull(item.getSelectionRange()))
                .filter(item -> Objects.nonNull(item.getSelectionRange().getStart()))
                .filter(item -> Objects.nonNull(item.getSelectionRange().getEnd()))
                .filter(item -> callee.location().selectionRange().equals(toRange(item.getSelectionRange())))
                .toList();
        if (exact.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(exact.getFirst());
    }

    private CallHierarchyItem prepareExactCallHierarchy(
            JdtWorkspaceSession session, String uri, SemanticDeclarationAnchor anchor) {
        Position position = toPosition(anchor.namePosition());
        List<CallHierarchyItem> items = session.call(
                "textDocument/prepareCallHierarchy",
                server -> server.getTextDocumentService()
                        .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position)));
        List<CallHierarchyItem> exact = nullSafe(items).stream()
                .filter(item -> uri.equals(item.getUri()))
                .filter(item -> Objects.nonNull(item.getSelectionRange()))
                .filter(item -> anchor.namePosition().equals(toSemanticPosition(item.getSelectionRange().getStart())))
                .toList();
        if (exact.size() != 1) {
            throw new SemanticBindingUnresolvedException(anchor.target());
        }
        return exact.getFirst();
    }

    private Optional<SemanticCall> toSemanticCall(
            JdtWorkspaceSession session, CallHierarchyOutgoingCall call, RepositorySnapshot snapshot) {
        CallHierarchyItem target = call.getTo();
        String rawSignature = target.getName();
        List<SemanticRange> callSites = nullSafe(call.getFromRanges()).stream()
                .map(this::toRange)
                .distinct()
                .toList();
        boolean external = isExternal(target.getUri(), snapshot);
        if (external) {
            return Optional.of(new SemanticCall(
                    Optional.empty(),
                    externalDisplaySignature(rawSignature),
                    callSites,
                    true,
                    SemanticResolutionOrigin.CALL_HIERARCHY));
        }
        try {
            Optional<SemanticMethod> resolved = resolveCallTarget(
                    session, snapshot, new TargetLocation(target.getUri(), target.getSelectionRange()))
                    .map(ResolvedCallTarget::method);
            if (resolved.isEmpty()) {
                return Optional.of(new SemanticCall(
                        Optional.empty(),
                        UNAVAILABLE_TARGET_SIGNATURE,
                        callSites,
                        external,
                        SemanticResolutionOrigin.CALL_HIERARCHY,
                        SemanticCallStatus.CONVERSION_FAILED));
            }
            return Optional.of(new SemanticCall(
                    resolved,
                    rawSignature,
                    callSites,
                    external,
                    SemanticResolutionOrigin.CALL_HIERARCHY));
        } catch (RuntimeException exception) {
            JdtLsSemanticExceptionNormalizer.rethrowIfEngineFailure(exception);
            log.debug("phase=jdtls-call outcome=conversion-failed repoId={} exceptionType={}",
                    session.repositoryId().value(),
                    exception.getClass().getSimpleName());
            return Optional.of(new SemanticCall(
                    Optional.empty(),
                    UNAVAILABLE_TARGET_SIGNATURE,
                    callSites,
                    external,
                    SemanticResolutionOrigin.CALL_HIERARCHY,
                    SemanticCallStatus.CONVERSION_FAILED));
        }
    }

    private Optional<SemanticIncomingCall> toSemanticIncomingCall(
            JdtWorkspaceSession session,
            RepositorySnapshot snapshot,
            CallHierarchyIncomingCall incomingCall) {
        CallHierarchyItem caller = Objects.requireNonNull(incomingCall.getFrom(), "incoming caller is required");
        if (!sourceLocator.localSource(snapshot, caller.getUri()).isPresent()) {
            return Optional.empty();
        }
        List<SemanticRange> callSites = nullSafe(incomingCall.getFromRanges()).stream()
                .map(this::toRange)
                .toList();
        return resolveLocalCallTarget(
                session, snapshot, new TargetLocation(caller.getUri(), caller.getSelectionRange()))
                .map(resolved -> new SemanticIncomingCall(
                        resolved.method(), caller.getName(), callSites));
    }

    private String externalDisplaySignature(String candidate) {
        if (StringUtils.hasText(candidate)
                && candidate.length() <= MAX_EXTERNAL_DISPLAY_SIGNATURE_LENGTH
                && SAFE_EXTERNAL_DISPLAY_SIGNATURE.matcher(candidate).matches()) {
            return candidate;
        }
        return UNAVAILABLE_TARGET_SIGNATURE;
    }

    private Optional<ResolvedCallTarget> resolveCallTarget(
            JdtWorkspaceSession session, RepositorySnapshot snapshot, TargetLocation target) {
        String uri = requireLocalInvocationUri(snapshot, target.uri());
        SemanticPosition position = toSemanticPosition(target.range().getStart());
        return findMethodAt(documentSymbols(session, uri), position)
                .flatMap(match -> semanticMethod(session, snapshot, uri, match)
                        .map(method -> new ResolvedCallTarget(method, match.method().rawSignature())));
    }

    private Optional<ResolvedCallTarget> resolveLocalCallTarget(
            JdtWorkspaceSession session, RepositorySnapshot snapshot, TargetLocation target) {
        String uri = requireLocalInvocationUri(snapshot, target.uri());
        SemanticPosition position = toSemanticPosition(target.range().getStart());
        return findMethodAt(documentSymbols(session, uri), position)
                .flatMap(match -> semanticMethod(session, snapshot, uri, match)
                        .map(method -> new ResolvedCallTarget(method, match.method().rawSignature())));
    }

    private Optional<SemanticMethod> semanticMethod(
            JdtWorkspaceSession session, RepositorySnapshot snapshot, String uri, MethodMatch match) {
        return packageOf(session, snapshot, uri).map(packageName -> new SemanticMethod(
                packageName,
                match.className(),
                match.method().methodName(),
                match.method().parameterTypes(),
                match.method().returnType(),
                new SemanticLocation(uri, match.method().range(), match.method().selectionRange())));
    }

    private Optional<SemanticMethod> resolveImplementation(
            JdtWorkspaceSession session, RepositorySnapshot snapshot, TargetLocation target) {
        String uri = requireLocalInvocationUri(snapshot, target.uri());
        SemanticPosition position = toSemanticPosition(target.range().getStart());
        return findMethodAt(documentSymbols(session, uri), position)
                .flatMap(match -> semanticMethod(session, snapshot, uri, match));
    }

    private Optional<MethodMatch> findMethodAt(
            List<Either<SymbolInformation, DocumentSymbol>> symbols, SemanticPosition position) {
        for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
            if (symbol.isRight()) {
                Optional<MethodMatch> match = findMethodAt(symbol.getRight(), position, "");
                if (match.isPresent()) {
                    return match;
                }
            } else if (isMethod(symbol.getLeft().getKind())
                    && containsPosition(symbol.getLeft().getLocation().getRange(), position)) {
                return Optional.of(new MethodMatch(
                        Objects.requireNonNullElse(symbol.getLeft().getContainerName(), ""),
                        flatMethodSymbol(symbol.getLeft())));
            }
        }
        return Optional.empty();
    }

    private Optional<MethodMatch> findMethodAt(
            DocumentSymbol symbol, SemanticPosition position, String enclosingClass) {
        if (isMethod(symbol.getKind()) && containsPosition(symbol.getRange(), position)) {
            return Optional.of(new MethodMatch(enclosingClass, hierarchicalMethodSymbol(symbol)));
        }
        String currentClass = enclosingClass;
        if (isType(symbol.getKind())) {
            String typeName = MethodSignatures.typeName(symbol.getName());
            currentClass = StringUtils.hasText(enclosingClass)
                    ? enclosingClass + "." + typeName
                    : typeName;
        }
        for (DocumentSymbol child : nullSafe(symbol.getChildren())) {
            Optional<MethodMatch> match = findMethodAt(child, position, currentClass);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private List<Either<SymbolInformation, DocumentSymbol>> documentSymbols(
            JdtWorkspaceSession session, String uri) {
        List<Either<SymbolInformation, DocumentSymbol>> symbols = session.call(
                "textDocument/documentSymbol",
                server -> server.getTextDocumentService()
                        .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri))));
        return nullSafe(symbols);
    }

    private List<TargetLocation> implementationTargets(
            Either<List<? extends Location>, List<? extends LocationLink>> response) {
        if (Objects.isNull(response)) {
            return List.of();
        }
        if (response.isLeft()) {
            return nullSafe(response.getLeft()).stream()
                    .map(location -> new TargetLocation(location.getUri(), location.getRange()))
                    .toList();
        }
        return nullSafe(response.getRight()).stream()
                .map(link -> new TargetLocation(
                        link.getTargetUri(),
                        Objects.requireNonNullElse(link.getTargetSelectionRange(), link.getTargetRange())))
                .toList();
    }

    private boolean openDocument(JdtWorkspaceSession session, RepositorySnapshot snapshot, String uri) {
        if (isExternal(uri, snapshot)) {
            return false;
        }
        Optional<String> text = readSource(snapshot, uri);
        if (text.isEmpty()) {
            return false;
        }
        session.call("textDocument/didOpen", server -> {
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, JAVA_LANGUAGE_ID, 1, text.get())));
            return CompletableFuture.completedFuture(null);
        });
        return true;
    }

    private void closeDocument(JdtWorkspaceSession session, String uri, boolean opened) {
        if (!opened) {
            return;
        }
        session.call("textDocument/didClose", server -> {
            server.getTextDocumentService().didClose(
                    new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
            return CompletableFuture.completedFuture(null);
        });
    }

    private <T> T withOpenedDocument(
            JdtWorkspaceSession session, RepositorySnapshot snapshot, String uri, Supplier<T> query) {
        boolean opened = false;
        Throwable primaryFailure = null;
        try {
            opened = openDocument(session, snapshot, uri);
            return query.get();
        } catch (RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                closeDocument(session, uri, opened);
            } catch (RuntimeException | Error exception) {
                session.invalidate();
                if (Objects.nonNull(primaryFailure)) {
                    primaryFailure.addSuppressed(new JdtDocumentCloseCleanupException());
                } else {
                    throw exception;
                }
            }
        }
    }

    private String requireLocalInvocationUri(RepositorySnapshot snapshot, String uri) {
        return sourceLocator.localSourceUri(snapshot, uri);
    }

    private static final class JdtDocumentCloseCleanupException extends RuntimeException {

        private JdtDocumentCloseCleanupException() {
            super("JDT document close confirmation failed");
        }
    }

    /** jdt: 協定結果沒有可分析的工作區原始碼，Task 7 不應遞迴進去。 */
    private boolean isExternal(String uri, RepositorySnapshot snapshot) {
        return sourceLocator.isExternal(snapshot, uri);
    }

    private Optional<String> readSource(RepositorySnapshot snapshot, String uri) {
        return sourceLocator.localSource(snapshot, uri).map(this::readSourceText);
    }

    private String readSourceText(java.nio.file.Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new SemanticProtocolException();
        }
    }

    private Optional<String> packageOf(JdtWorkspaceSession session, RepositorySnapshot snapshot, String uri) {
        try {
            Optional<String> source = sourceLocator.localSource(snapshot, uri).map(this::readSourceText);
            if (source.isEmpty()) {
                return Optional.empty();
            }
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.orElseThrow().toCharArray());
            CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            if (hasPackageRegionErrors(unit)) {
                return Optional.empty();
            }
            PackageDeclaration declaration = unit.getPackage();
            if (Objects.nonNull(declaration)) {
                String packageName = declaration.getName().getFullyQualifiedName();
                if (StringUtils.hasText(packageName)) {
                    return Optional.of(packageName);
                }
                return Optional.empty();
            }
            return Optional.of("");
        } catch (RuntimeException exception) {
            log.debug("phase=jdtls-package outcome=resolution-failed repoId={} exceptionType={}",
                    session.repositoryId().value(),
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean hasPackageRegionErrors(CompilationUnit unit) {
        int firstTypeStart = Integer.MAX_VALUE;
        for (Object candidate : unit.types()) {
            if (candidate instanceof ASTNode type) {
                firstTypeStart = Math.min(firstTypeStart, type.getStartPosition());
            }
        }
        int packageRegionEnd = firstTypeStart;
        return List.of(unit.getProblems()).stream()
                .filter(problem -> problem.isError())
                .anyMatch(problem -> problem.getSourceStart() < packageRegionEnd);
    }

    private MethodSymbol hierarchicalMethodSymbol(DocumentSymbol symbol) {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse(symbol.getName());
        return new MethodSymbol(
                symbol.getName(),
                parsed.methodName(),
                parsed.parameterTypes(),
                returnType(symbol.getDetail(), symbol.getName()),
                toRange(symbol.getRange()),
                toRange(symbol.getSelectionRange()));
    }

    private MethodSymbol flatMethodSymbol(SymbolInformation symbol) {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse(symbol.getName());
        Range range = symbol.getLocation().getRange();
        return new MethodSymbol(
                symbol.getName(),
                parsed.methodName(),
                parsed.parameterTypes(),
                returnType(null, symbol.getName()),
                toRange(range),
                toRange(range));
    }

    private String returnType(String detail, String symbolName) {
        String fromDetail = returnTypeFrom(detail);
        if (StringUtils.hasText(fromDetail)) {
            return fromDetail;
        }
        int close = symbolName.indexOf(')');
        return close >= 0 ? returnTypeFrom(symbolName.substring(close + 1)) : "";
    }

    private String returnTypeFrom(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith(":")) {
            value = value.substring(1).trim();
        }
        return MethodSignatures.normalizeType(value);
    }

    private List<SemanticCall> dedupeCalls(List<SemanticCall> calls) {
        Map<CallIdentity, SemanticCall> deduped = new LinkedHashMap<>();
        for (SemanticCall call : calls) {
            CallIdentity identity = new CallIdentity(
                    call.target(), call.rawSignature(), call.external(), call.origin(), call.status());
            SemanticCall existing = deduped.get(identity);
            if (Objects.isNull(existing)) {
                deduped.put(identity, call);
                continue;
            }
            List<SemanticRange> ranges = new ArrayList<>(existing.callSites());
            for (SemanticRange range : call.callSites()) {
                if (!ranges.contains(range)) {
                    ranges.add(range);
                }
            }
            deduped.put(identity, new SemanticCall(
                    existing.target(),
                    existing.rawSignature(),
                    ranges,
                    existing.external(),
                    existing.origin(),
                    existing.status()));
        }
        return List.copyOf(deduped.values());
    }

    private SemanticIncomingCallResult dedupeIncomingCalls(
            List<SemanticIncomingCall> calls,
            List<SemanticIncomingCallIssue> issues) {
        Map<SemanticMethod, IncomingCallAccumulator> merged = new LinkedHashMap<>();
        for (SemanticIncomingCall call : calls.stream()
                .sorted(Comparator.comparing(SemanticIncomingCall::caller, INCOMING_CALLER_ORDER)
                        .thenComparing(SemanticIncomingCall::rawSignature))
                .toList()) {
            IncomingCallAccumulator accumulator = merged.computeIfAbsent(
                    call.caller(), IncomingCallAccumulator::new);
            accumulator.add(call);
        }
        List<SemanticIncomingCall> normalized = merged.values().stream()
                .map(IncomingCallAccumulator::toIncomingCall)
                .toList();
        return new SemanticIncomingCallResult(normalized, issues);
    }

    private boolean isJdtCaller(CallHierarchyItem caller) {
        String scheme = URI.create(caller.getUri()).getScheme();
        return "jdt".equalsIgnoreCase(scheme);
    }

    private static int compareParameterTypes(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareRanges(SemanticRange left, SemanticRange right) {
        int start = comparePositions(left.start(), right.start());
        if (start != 0) {
            return start;
        }
        return comparePositions(left.end(), right.end());
    }

    private static int comparePositions(SemanticPosition left, SemanticPosition right) {
        int line = Integer.compare(left.line(), right.line());
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.character(), right.character());
    }

    private String dedupeKey(SemanticLocation location) {
        SemanticRange range = location.range();
        return location.uri()
                + "#" + range.start().line() + ":" + range.start().character()
                + "-" + range.end().line() + ":" + range.end().character();
    }

    private String signature(SemanticMethod method) {
        return method.methodName() + "(" + String.join(", ", method.parameterTypes()) + ")";
    }

    private boolean isType(SymbolKind kind) {
        return Objects.nonNull(kind) && TYPE_KINDS.contains(kind);
    }

    private boolean isMethod(SymbolKind kind) {
        return Objects.nonNull(kind) && METHOD_KINDS.contains(kind);
    }

    private boolean containsPosition(Range range, SemanticPosition position) {
        SemanticPosition start = toSemanticPosition(range.getStart());
        SemanticPosition end = toSemanticPosition(range.getEnd());
        return notBefore(position, start) && notAfter(position, end);
    }

    private boolean notBefore(SemanticPosition position, SemanticPosition start) {
        return position.line() > start.line()
                || (position.line() == start.line() && position.character() >= start.character());
    }

    private boolean notAfter(SemanticPosition position, SemanticPosition end) {
        return position.line() < end.line()
                || (position.line() == end.line() && position.character() <= end.character());
    }

    private SemanticRange toRange(Range range) {
        Objects.requireNonNull(range, "range is required");
        return new SemanticRange(toSemanticPosition(range.getStart()), toSemanticPosition(range.getEnd()));
    }

    private SemanticPosition toSemanticPosition(Position position) {
        Objects.requireNonNull(position, "position is required");
        return new SemanticPosition(position.getLine(), position.getCharacter());
    }

    private Position toPosition(SemanticPosition position) {
        return new Position(position.line(), position.character());
    }

    private <T> List<T> nullSafe(List<T> list) {
        return Objects.isNull(list) ? List.of() : list;
    }

    /** documentSymbol 解析出的方法摘要,座標為零基 */
    private record MethodSymbol(
            String rawSignature,
            String methodName,
            List<String> parameterTypes,
            String returnType,
            SemanticRange range,
            SemanticRange selectionRange) {
    }

    /** implementation 回傳的目標位置,統一兩種 Either 形態 */
    private record TargetLocation(String uri, Range range) {
    }

    /** 在檔案內定位到的方法與其外層類別 */
    private record MethodMatch(String className, MethodSymbol method) {
    }

    /** document symbol 保留的完整目標與未正規化名稱 */
    private record ResolvedCallTarget(SemanticMethod method, String rawSignature) {
    }

    /** 缺少完整 target 時仍以原始簽章區分不同外部呼叫 */
    private record CallIdentity(
            Optional<SemanticMethod> target,
            String rawSignature,
            boolean external,
            SemanticResolutionOrigin origin,
            SemanticCallStatus status) {
    }

    private static final class IncomingCallAccumulator {

        private final SemanticMethod caller;
        private String rawSignature;
        private final Set<SemanticRange> callSites = new TreeSet<>(INCOMING_RANGE_ORDER);

        private IncomingCallAccumulator(SemanticMethod caller) {
            this.caller = caller;
        }

        private void add(SemanticIncomingCall call) {
            if (Objects.isNull(rawSignature) || call.rawSignature().compareTo(rawSignature) < 0) {
                rawSignature = call.rawSignature();
            }
            callSites.addAll(call.callSites());
        }

        private SemanticIncomingCall toIncomingCall() {
            return new SemanticIncomingCall(caller, rawSignature, List.copyOf(callSites));
        }
    }
}
