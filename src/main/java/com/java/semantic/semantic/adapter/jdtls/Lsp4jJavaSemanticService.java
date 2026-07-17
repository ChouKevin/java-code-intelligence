package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 以 JDT LS 精確回答語意問題的 adapter
 *
 * 所有 LSP4J 型別在此轉為 domain record,絕不外洩;位置一律以零基處理
 */
@Service
public class Lsp4jJavaSemanticService implements JavaSemanticService {

    private static final Logger LOGGER = LoggerFactory.getLogger(Lsp4jJavaSemanticService.class);
    private static final String JAVA_LANGUAGE_ID = "java";
    private static final String JDT_URI_SCHEME = "jdt:";
    private static final Set<SymbolKind> TYPE_KINDS =
            EnumSet.of(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Struct);
    private static final Set<SymbolKind> METHOD_KINDS =
            EnumSet.of(SymbolKind.Method, SymbolKind.Constructor);

    private final JdtWorkspaceManager workspaceManager;

    public Lsp4jJavaSemanticService(JdtWorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager is required");
    }

    @Override
    public SemanticMethod resolveMethod(
            RepositorySnapshot snapshot, String packageName, String className, String methodSignature) {
        Assert.notNull(snapshot, "snapshot is required");
        Objects.requireNonNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        Assert.hasText(methodSignature, "methodSignature is required");
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);

        String typeUri = resolveTypeUri(session, packageName, className);
        List<MethodSymbol> methods = classMethods(session, typeUri, className);
        MethodSignatures.ParsedMethod requested = MethodSignatures.parse(methodSignature);
        MethodSymbol chosen = selectMethod(packageName, className, methods, requested);
        return new SemanticMethod(
                packageName,
                className,
                chosen.methodName(),
                chosen.parameterTypes(),
                chosen.returnType(),
                new SemanticLocation(typeUri, chosen.range(), chosen.selectionRange()));
    }

    @Override
    public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(method, "method is required");
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        String uri = method.location().uri();
        Position namePosition = toPosition(method.location().selectionRange().start());
        boolean opened = openDocument(session, snapshot, uri);
        try {
            CallHierarchyItem root = prepareCallHierarchy(session, uri, namePosition, method.methodName());
            List<CallHierarchyOutgoingCall> calls = session.call(
                    "callHierarchy/outgoingCalls",
                    server -> server.getTextDocumentService()
                            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(root)));
            List<SemanticCall> resolved = nullSafe(calls).stream()
                    .map(call -> toSemanticCall(call.getTo(), snapshot))
                    .toList();
            return dedupeCalls(resolved);
        } finally {
            closeDocument(session, uri, opened);
        }
    }

    @Override
    public List<SemanticMethod> implementations(RepositorySnapshot snapshot, SemanticMethod method) {
        Assert.notNull(snapshot, "snapshot is required");
        Assert.notNull(method, "method is required");
        JdtWorkspaceSession session = workspaceManager.getOrStart(snapshot);
        String uri = method.location().uri();
        Position namePosition = toPosition(method.location().selectionRange().start());
        boolean opened = openDocument(session, snapshot, uri);
        try {
            Either<List<? extends Location>, List<? extends LocationLink>> response = session.call(
                    "textDocument/implementation",
                    server -> server.getTextDocumentService()
                            .implementation(new ImplementationParams(new TextDocumentIdentifier(uri), namePosition)));
            Map<String, SemanticMethod> deduped = new LinkedHashMap<>();
            for (TargetLocation target : implementationTargets(response)) {
                if (isExternal(target.uri(), snapshot)) {
                    continue;
                }
                resolveImplementation(session, target)
                        .ifPresent(resolved -> deduped.putIfAbsent(dedupeKey(resolved.location()), resolved));
            }
            return List.copyOf(deduped.values());
        } finally {
            closeDocument(session, uri, opened);
        }
    }

    private String resolveTypeUri(JdtWorkspaceSession session, String packageName, String className) {
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response = session.call(
                "workspace/symbol",
                server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams(className)));
        List<String> uris = typeSymbols(response).stream()
                .filter(symbol -> className.equals(symbol.name()))
                .filter(symbol -> packageName.equals(Objects.requireNonNullElse(symbol.containerName(), "")))
                .filter(symbol -> Objects.isNull(symbol.kind()) || TYPE_KINDS.contains(symbol.kind()))
                .map(TypeSymbol::uri)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(uris)) {
            throw new SemanticSymbolNotFoundException(
                    "type " + qualified(packageName, className) + " was not found in the workspace");
        }
        return uris.getFirst();
    }

    private MethodSymbol selectMethod(
            String packageName,
            String className,
            List<MethodSymbol> methods,
            MethodSignatures.ParsedMethod requested) {
        List<MethodSymbol> byName = methods.stream()
                .filter(method -> method.methodName().equals(requested.methodName()))
                .toList();
        if (CollectionUtils.isEmpty(byName)) {
            throw new SemanticSymbolNotFoundException(
                    "method " + qualified(packageName, className) + "#" + requested.methodName()
                            + " was not found");
        }
        if (!requested.hasParameters()) {
            if (byName.size() > 1) {
                throw new SemanticAmbiguousMethodException(
                        packageName, className, requested.methodName(), signatures(byName));
            }
            return byName.getFirst();
        }
        List<MethodSymbol> exact = byName.stream()
                .filter(method -> method.parameterTypes().equals(requested.parameterTypes()))
                .toList();
        if (CollectionUtils.isEmpty(exact)) {
            throw new SemanticSymbolNotFoundException(
                    "method " + qualified(packageName, className) + "#" + signature(requested)
                            + " was not found");
        }
        if (exact.size() > 1) {
            throw new SemanticAmbiguousMethodException(
                    packageName, className, requested.methodName(), signatures(exact));
        }
        return exact.getFirst();
    }

    private List<MethodSymbol> classMethods(JdtWorkspaceSession session, String typeUri, String className) {
        List<MethodSymbol> methods = new ArrayList<>();
        for (Either<SymbolInformation, DocumentSymbol> symbol : documentSymbols(session, typeUri)) {
            if (symbol.isRight()) {
                collectClassMethods(symbol.getRight(), className, methods);
            } else if (isEnclosedMethod(symbol.getLeft(), className)) {
                methods.add(flatMethodSymbol(symbol.getLeft()));
            }
        }
        return methods;
    }

    private void collectClassMethods(DocumentSymbol symbol, String className, List<MethodSymbol> methods) {
        if (isType(symbol.getKind()) && className.equals(MethodSignatures.typeName(symbol.getName()))) {
            for (DocumentSymbol child : nullSafe(symbol.getChildren())) {
                if (isMethod(child.getKind())) {
                    methods.add(hierarchicalMethodSymbol(child));
                }
            }
            return;
        }
        for (DocumentSymbol child : nullSafe(symbol.getChildren())) {
            collectClassMethods(child, className, methods);
        }
    }

    private CallHierarchyItem prepareCallHierarchy(
            JdtWorkspaceSession session, String uri, Position position, String methodName) {
        List<CallHierarchyItem> items = session.call(
                "textDocument/prepareCallHierarchy",
                server -> server.getTextDocumentService()
                        .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position)));
        List<CallHierarchyItem> present = nullSafe(items);
        if (CollectionUtils.isEmpty(present)) {
            throw new SemanticSymbolNotFoundException(
                    "call hierarchy could not be prepared for method " + methodName);
        }
        return present.stream()
                .filter(item -> methodName.equals(MethodSignatures.bareName(item.getName())))
                .findFirst()
                .orElse(present.getFirst());
    }

    private SemanticCall toSemanticCall(CallHierarchyItem target, RepositorySnapshot snapshot) {
        String rawSignature = StringUtils.hasText(target.getName()) ? target.getName() : "<unknown>";
        return new SemanticCall(
                MethodSignatures.bareName(rawSignature),
                rawSignature,
                new SemanticLocation(target.getUri(), toRange(target.getRange()), toRange(target.getSelectionRange())),
                isExternal(target.getUri(), snapshot));
    }

    private Optional<SemanticMethod> resolveImplementation(JdtWorkspaceSession session, TargetLocation target) {
        SemanticPosition position = toSemanticPosition(target.range().getStart());
        return findMethodAt(documentSymbols(session, target.uri()), position)
                .map(match -> new SemanticMethod(
                        packageOf(target.uri()),
                        match.className(),
                        match.method().methodName(),
                        match.method().parameterTypes(),
                        match.method().returnType(),
                        new SemanticLocation(
                                target.uri(), match.method().range(), match.method().selectionRange())));
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
        String currentClass = isType(symbol.getKind())
                ? MethodSignatures.typeName(symbol.getName())
                : enclosingClass;
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

    private List<TypeSymbol> typeSymbols(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response) {
        if (Objects.isNull(response)) {
            return List.of();
        }
        if (response.isLeft()) {
            return nullSafe(response.getLeft()).stream()
                    .map(symbol -> new TypeSymbol(
                            symbol.getName(),
                            symbol.getContainerName(),
                            symbol.getKind(),
                            Objects.nonNull(symbol.getLocation()) ? symbol.getLocation().getUri() : null))
                    .toList();
        }
        return nullSafe(response.getRight()).stream()
                .map(symbol -> new TypeSymbol(
                        symbol.getName(),
                        symbol.getContainerName(),
                        symbol.getKind(),
                        workspaceSymbolUri(symbol.getLocation())))
                .toList();
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
        Optional<String> text = readSource(uri);
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

    /**
     * jdt: 協定或落在根目錄外的檔案皆視為外部
     *
     * 外部目標沒有可分析的原始碼,Task 7 不應遞迴進去
     */
    private boolean isExternal(String uri, RepositorySnapshot snapshot) {
        if (!StringUtils.hasText(uri) || uri.startsWith(JDT_URI_SCHEME)) {
            return true;
        }
        try {
            Path path = Path.of(URI.create(uri)).toAbsolutePath().normalize();
            return !path.startsWith(snapshot.root().toAbsolutePath().normalize());
        } catch (IllegalArgumentException | FileSystemNotFoundException exception) {
            return true;
        }
    }

    private Optional<String> readSource(String uri) {
        try {
            return Optional.of(Files.readString(Path.of(URI.create(uri))));
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Reading source for didOpen failed: uri={}", uri, exception);
            return Optional.empty();
        }
    }

    private String packageOf(String uri) {
        try {
            for (String line : Files.readAllLines(Path.of(URI.create(uri)))) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Reading package declaration failed: uri={}", uri, exception);
        }
        return "";
    }

    private MethodSymbol hierarchicalMethodSymbol(DocumentSymbol symbol) {
        MethodSignatures.ParsedMethod parsed = MethodSignatures.parse(symbol.getName());
        return new MethodSymbol(
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
                parsed.methodName(),
                parsed.parameterTypes(),
                returnType(null, symbol.getName()),
                toRange(range),
                toRange(range));
    }

    private boolean isEnclosedMethod(SymbolInformation symbol, String className) {
        return isMethod(symbol.getKind()) && className.equals(symbol.getContainerName());
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
        Map<String, SemanticCall> deduped = new LinkedHashMap<>();
        for (SemanticCall call : calls) {
            deduped.putIfAbsent(dedupeKey(call.target()), call);
        }
        return List.copyOf(deduped.values());
    }

    private String dedupeKey(SemanticLocation location) {
        SemanticRange range = location.range();
        return location.uri()
                + "#" + range.start().line() + ":" + range.start().character()
                + "-" + range.end().line() + ":" + range.end().character();
    }

    private List<String> signatures(List<MethodSymbol> methods) {
        return methods.stream().map(this::signature).distinct().toList();
    }

    private String signature(MethodSymbol method) {
        return method.methodName() + "(" + String.join(", ", method.parameterTypes()) + ")";
    }

    private String signature(MethodSignatures.ParsedMethod requested) {
        return requested.methodName() + "(" + String.join(", ", requested.parameterTypes()) + ")";
    }

    private String qualified(String packageName, String className) {
        return StringUtils.hasText(packageName) ? packageName + "." + className : className;
    }

    private String workspaceSymbolUri(Either<Location, WorkspaceSymbolLocation> location) {
        if (Objects.isNull(location)) {
            return null;
        }
        return location.isLeft() ? location.getLeft().getUri() : location.getRight().getUri();
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

    /** workspace/symbol 回傳的型別符號摘要 */
    private record TypeSymbol(String name, String containerName, SymbolKind kind, String uri) {
    }

    /** documentSymbol 解析出的方法摘要,座標為零基 */
    private record MethodSymbol(
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
}
