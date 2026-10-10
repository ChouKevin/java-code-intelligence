package com.java.semantic.indexer.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.Declaration;
import com.java.semantic.model.index.IndexContract.DeclarationKind;
import com.java.semantic.model.index.IndexContract.SourceSpan;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.index.RelationContract.Reason;
import com.java.semantic.model.index.RelationContract.ScopeKind;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Generation-local resolver: platform, admitted source catalogue, then approved ordered bytecode. */
public final class SourceResolutionSession implements TypeSolver, AutoCloseable {
    private static final long CACHE_SOURCE_BYTES = 8L * 1024 * 1024;
    private static final DataKey<ParsedJavaUnit> UNIT = new DataKey<>() { };
    private static final DataKey<Map<Long, Declaration>> DECLARATIONS = new DataKey<>() { };
    private record Location(String path, long nameStart) { }
    private final IndexBuildInput input;
    private final ResolutionPlan plan;
    private final JavaSyntaxExtractor parser;
    private final ClassLoaderTypeSolver platform = new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader());
    private final ApprovedJarTypes jars;
    private final Map<String, Location> sourceTypes = new HashMap<>();
    private final Set<String> duplicates = new HashSet<>();
    private final Map<String, IndexContract.FileOutcome> files = new HashMap<>();
    private final LinkedHashMap<String, ParsedJavaUnit> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;
    private boolean sealed;

    public SourceResolutionSession(IndexBuildInput input, ResolutionPlan plan, Path inputDirectory,
            JavaSyntaxExtractor parser) throws IOException {
        this.input = Objects.requireNonNull(input);
        this.plan = Objects.requireNonNull(plan);
        this.parser = Objects.requireNonNull(parser);
        this.jars = new ApprovedJarTypes(plan, inputDirectory);
        platform.setParent(this);
        jars.setParent(this);
    }

    public String generation() { return input.generation(); }
    public ResolutionPlan plan() { return plan; }
    public boolean inScope(String path) {
        return plan.sourceRoots().stream().anyMatch(root -> root.isEmpty() || path.startsWith(root + "/"));
    }

    public void observe(ParsedJavaUnit unit) {
        if (sealed) throw new IllegalStateException("source catalogue already sealed");
        IndexContract.FileOutcome file = unit.syntax().file();
        if (!inScope(file.path()) || unit.ast().isEmpty()) return;
        if (Objects.nonNull(files.putIfAbsent(file.path(), file))) throw new IllegalStateException("source unit already observed");
        attach(unit);
        unit.ast().orElseThrow().walk(node -> {
            if (!(node instanceof TypeDeclaration<?> type)) return;
            Optional<String> qualified = qualifiedName(type);
            if (qualified.isEmpty()) return;
            SourceSpan name = span(unit, type.getName());
            String key = qualified.orElseThrow();
            if (Objects.nonNull(sourceTypes.putIfAbsent(key, new Location(file.path(), name.startByte())))) duplicates.add(key);
        });
        cache(unit);
    }

    private static Optional<String> qualifiedName(TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        Node cursor = type.getParentNode().orElseThrow();
        while (cursor instanceof TypeDeclaration<?> outer) {
            name = outer.getNameAsString() + "." + name;
            cursor = outer.getParentNode().orElseThrow();
        }
        if (!(cursor instanceof CompilationUnit unit)) return Optional.empty();
        String prefix = unit.getPackageDeclaration().map(value -> value.getNameAsString() + ".").orElse("");
        return Optional.of(prefix + name);
    }

    public void sealCatalogue() { sealed = true; }

    public ParsedJavaUnit unit(String path) {
        if (!sealed) throw new IllegalStateException("source catalogue must be complete before resolution");
        ParsedJavaUnit cached = cache.get(path);
        if (Objects.nonNull(cached)) return cached;
        IndexContract.FileOutcome file = files.get(path);
        if (Objects.isNull(file)) throw new IllegalArgumentException("source path is outside admitted resolver inventory");
        try {
            byte[] bytes = IndexFiles.read(input.tree().resolve(path), IndexContract.MAX_FILE_BYTES);
            SourceInventoryEntry entry = new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(EntryStatus.TEXT),
                    file.byteLength(), file.blobId(), file.contentDigest());
            ParsedJavaUnit parsed = parser.parseUnit(input.generation(), entry, bytes);
            if (!parsed.syntax().file().equals(file)) throw new IOException("admitted source outcome changed");
            attach(parsed);
            cache(parsed);
            return parsed;
        } catch (IOException failure) {
            throw new UncheckedIOException("admitted source cannot be consumed", failure);
        }
    }

    private void attach(ParsedJavaUnit unit) {
        CompilationUnit ast = unit.ast().orElseThrow();
        ast.setData(Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(this));
        ast.setData(UNIT, unit);
        Map<Long, Declaration> byName = new HashMap<>();
        for (Declaration declaration : unit.syntax().declarations()) byName.put(declaration.nameRange().startByte(), declaration);
        ast.setData(DECLARATIONS, Map.copyOf(byName));
    }

    private void cache(ParsedJavaUnit unit) {
        String path = unit.syntax().file().path();
        ParsedJavaUnit previous = cache.put(path, unit);
        if (Objects.nonNull(previous)) cachedBytes -= previous.syntax().file().byteLength();
        cachedBytes += unit.syntax().file().byteLength();
        Iterator<ParsedJavaUnit> iterator = cache.values().iterator();
        while ((cachedBytes > CACHE_SOURCE_BYTES || cache.size() > 128) && cache.size() > 1 && iterator.hasNext()) {
            cachedBytes -= iterator.next().syntax().file().byteLength();
            iterator.remove();
        }
    }

    public Optional<Declaration> sourceDeclaration(Node node) {
        if (!(node instanceof NodeWithSimpleName<?> named)) return Optional.empty();
        Optional<CompilationUnit> root = node.findCompilationUnit();
        if (root.isEmpty() || !root.orElseThrow().containsData(UNIT)) return Optional.empty();
        CompilationUnit ast = root.orElseThrow();
        SourceSpan name = span(ast.getData(UNIT), named.getName());
        return Optional.ofNullable(ast.getData(DECLARATIONS).get(name.startByte()));
    }

    public SourceSpan span(ParsedJavaUnit unit, Node node) {
        return node.getRange().flatMap(unit.coordinates().orElseThrow()::span)
                .orElseThrow(() -> new IllegalStateException("relation has no raw source mapping"));
    }

    ParsedJavaUnit owningUnit(Node node) {
        return node.findCompilationUnit().orElseThrow(() ->
                new IllegalStateException("source evidence has no admitted compilation unit")).getData(UNIT);
    }

    public RelationContract.SourceSite site(ParsedJavaUnit unit, Node occurrence) {
        SourceSpan range = span(unit, occurrence);
        Node cursor = occurrence;
        ScopeKind kind = ScopeKind.FILE;
        Node scope = unit.ast().orElseThrow();
        while (true) {
            if (cursor instanceof LambdaExpr) { kind = ScopeKind.LAMBDA; scope = cursor; break; }
            if (cursor instanceof MethodDeclaration) {
                kind = cursor.getParentNode().filter(parent -> parent instanceof ObjectCreationExpr).isPresent()
                        ? ScopeKind.ANONYMOUS_METHOD : ScopeKind.METHOD;
                scope = cursor; break;
            }
            if (cursor instanceof ConstructorDeclaration || cursor instanceof CompactConstructorDeclaration) {
                kind = ScopeKind.CONSTRUCTOR; scope = cursor; break;
            }
            if (cursor instanceof InitializerDeclaration initializer) {
                kind = initializer.isStatic() ? ScopeKind.STATIC_INITIALIZER : ScopeKind.INSTANCE_INITIALIZER;
                scope = cursor; break;
            }
            if (cursor instanceof VariableDeclarator && span(unit, cursor).contains(range)
                    && cursor.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent()) {
                kind = ScopeKind.FIELD_INITIALIZER; scope = cursor; break;
            }
            if (cursor instanceof TypeDeclaration<?>) { kind = ScopeKind.DECLARATION; scope = cursor; break; }
            if (cursor.getParentNode().isEmpty()) break;
            cursor = cursor.getParentNode().orElseThrow();
        }
        SourceSpan scopeRange = kind == ScopeKind.FILE
                ? unit.coordinates().orElseThrow().wholeFile() : span(unit, scope);
        Optional<String> container = Optional.empty();
        cursor = occurrence;
        while (true) {
            Optional<Declaration> declaration = sourceDeclaration(cursor);
            if (declaration.isPresent()) {
                Declaration candidate = declaration.orElseThrow();
                if (candidate.range().contains(range) && candidate.range().contains(scopeRange)) {
                    container = Optional.of(candidate.symbolId());
                    break;
                }
            }
            if (cursor.getParentNode().isEmpty()) break;
            cursor = cursor.getParentNode().orElseThrow();
        }
        IndexContract.FileOutcome file = unit.syntax().file();
        return new RelationContract.SourceSite(file.path(), file.blobId().orElseThrow(), file.contentDigest().orElseThrow(),
                file.byteLength(), range, container, new RelationContract.Scope(kind, scopeRange));
    }

    public RelationContract.Target target(ResolvedDeclaration resolved) {
        DeclarationKind kind = kind(resolved);
        String signature = signature(resolved);
        Optional<Node> ast = resolved instanceof JavaParserFieldDeclaration field
                ? Optional.of(field.getVariableDeclarator()) : resolved.toAst();
        if (ast.isPresent()) {
            Declaration declaration = sourceDeclaration(ast.orElseThrow()).filter(value -> value.kind() == kind)
                    .orElseThrow(() -> new UnresolvedRelationException(Reason.NO_WRITTEN_DECLARATION));
            requireUniqueOwner(resolved);
            return new RelationContract.Target(RelationContract.TargetKind.SOURCE_SYMBOL,
                    Optional.of(new RelationContract.SourceTarget(declaration, Optional.of(signature))), Optional.empty());
        }
        String owner = owner(resolved);
        if (sourceTypes.containsKey(owner) || duplicates.contains(owner))
            throw new UnresolvedRelationException(duplicates.contains(owner) ? Reason.DUPLICATE_SOURCE_TYPE : Reason.NO_WRITTEN_DECLARATION);
        String artifactId;
        String artifactDigest;
        Optional<String> module = platformModule(owner);
        if (module.isPresent()) {
            artifactId = "java21";
            artifactDigest = plan.platform().modulesDigest();
        } else {
            RelationContract.Artifact artifact = jars.artifact(owner)
                    .orElseThrow(() -> new UnresolvedRelationException(Reason.MISSING_TYPE));
            artifactId = artifact.id(); artifactDigest = artifact.digest();
        }
        RelationContract.ExternalTarget external = new RelationContract.ExternalTarget(
                RelationContract.externalIdentity(input.generation(), artifactId, artifactDigest, module, kind, owner, resolved.getName(), signature),
                artifactId, artifactDigest, module, kind, owner, resolved.getName(), signature);
        return new RelationContract.Target(RelationContract.TargetKind.EXTERNAL_TARGET, Optional.empty(), Optional.of(external));
    }

    private void requireUniqueOwner(ResolvedDeclaration declaration) {
        String owner = owner(declaration);
        for (String duplicate : duplicates) if (owner.equals(duplicate) || owner.startsWith(duplicate + "."))
            throw new UnresolvedRelationException(Reason.DUPLICATE_SOURCE_TYPE);
    }

    private static Optional<String> platformModule(String name) {
        String binary = name;
        while (true) {
            try {
                Class<?> type = Class.forName(binary, false, ClassLoader.getPlatformClassLoader());
                return Optional.ofNullable(type.getModule().getName());
            } catch (ClassNotFoundException missing) {
                int separator = binary.lastIndexOf('.');
                if (separator < 0) return Optional.empty();
                binary = binary.substring(0, separator) + "$" + binary.substring(separator + 1);
            }
        }
    }

    private static DeclarationKind kind(ResolvedDeclaration declaration) {
        if (declaration instanceof ResolvedConstructorDeclaration) return DeclarationKind.CONSTRUCTOR;
        if (declaration instanceof ResolvedMethodDeclaration) return DeclarationKind.METHOD;
        if (declaration.isField()) return DeclarationKind.FIELD;
        if (declaration.isEnumConstant()) return DeclarationKind.ENUM_CONSTANT;
        if (declaration instanceof ResolvedTypeDeclaration type) {
            if (type.isAnnotation()) return DeclarationKind.ANNOTATION_TYPE;
            if (type.isRecord()) return DeclarationKind.RECORD;
            if (type.isEnum()) return DeclarationKind.ENUM;
            if (type.isInterface()) return DeclarationKind.INTERFACE;
            return DeclarationKind.CLASS;
        }
        throw new UnresolvedRelationException(Reason.NO_WRITTEN_DECLARATION);
    }

    private static String owner(ResolvedDeclaration declaration) {
        if (declaration instanceof ResolvedMethodLikeDeclaration method) return method.declaringType().getQualifiedName();
        if (declaration instanceof ResolvedFieldDeclaration field) return field.declaringType().getQualifiedName();
        if (declaration instanceof ResolvedTypeDeclaration type) return type.getQualifiedName();
        if (declaration.isEnumConstant()) return declaration.asEnumConstant().getType().asReferenceType().getQualifiedName();
        throw new UnresolvedRelationException(Reason.NO_WRITTEN_DECLARATION);
    }

    private static String signature(ResolvedDeclaration declaration) {
        if (declaration instanceof ResolvedMethodLikeDeclaration method) return method.getQualifiedSignature();
        if (declaration instanceof ResolvedTypeDeclaration type) return type.getQualifiedName();
        if (declaration instanceof ResolvedFieldDeclaration field) return owner(field) + "." + field.getName() + ":" + field.getType().describe();
        if (declaration.isEnumConstant()) return owner(declaration) + "." + declaration.getName();
        throw new UnresolvedRelationException(Reason.NO_WRITTEN_DECLARATION);
    }

    @Override public TypeSolver getParent() { return null; }
    @Override public void setParent(TypeSolver parent) { throw new IllegalStateException("resolution session is the root provider"); }
    @Override public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        if (!sealed) throw new IllegalStateException("resolution before source catalogue completion");
        SymbolReference<ResolvedReferenceTypeDeclaration> platformType = platform.tryToSolveType(name);
        if (platformType.isSolved()) return platformType;
        if (duplicates.contains(name)) throw new UnresolvedRelationException(Reason.DUPLICATE_SOURCE_TYPE);
        Location location = sourceTypes.get(name);
        if (Objects.nonNull(location)) {
            ParsedJavaUnit unit = unit(location.path());
            Optional<Node> node = unit.ast().orElseThrow().findFirst(Node.TreeTraversal.PREORDER, candidate -> {
                if (candidate instanceof TypeDeclaration<?> type && span(unit, type.getName()).startByte() == location.nameStart()) return Optional.of(candidate);
                return Optional.empty();
            });
            return SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(node.orElseThrow()));
        }
        return jars.tryToSolveType(name);
    }
    @Override public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String name, String moduleName) {
        return tryToSolveType(name);
    }
    @Override public void close() throws IOException {
        cache.clear(); files.clear(); sourceTypes.clear(); duplicates.clear();
        jars.close();
    }
}
