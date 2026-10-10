package com.java.semantic.indexer.index;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistFactory;
import com.java.semantic.model.index.RelationContract;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javassist.ClassPool;
import javassist.ClassPath;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import javassist.CtClass;
import javassist.bytecode.ClassFile;

/** Reads selected class metadata only; approved JARs are never runtime classpath entries. */
public final class ApprovedJarTypes implements TypeSolver, AutoCloseable {
    public static final int MAX_CLASSES = 200_000;
    public static final int MAX_CLASS_BYTES = 1_048_576;
    public static final long MAX_EXPANDED_BYTES = 536_870_912L;
    private record Selected(ZipFile zip, ZipEntry entry, String binaryName, RelationContract.Artifact artifact, int version) { }
    private final List<ZipFile> archives = new ArrayList<>();
    private final Map<String, Selected> classes = new HashMap<>();
    private final Map<String, ResolvedReferenceTypeDeclaration> resolved = new HashMap<>();
    private final ClassPool pool = new ClassPool(false);
    private TypeSolver parent;
    private long expandedBytes;
    private int classCount;

    public ApprovedJarTypes(ResolutionPlan plan, Path inputDirectory) throws IOException {
        try {
            for (ResolutionPlan.Dependency dependency : plan.dependencies()) {
                Path jar = inputDirectory.resolve(dependency.snapshotFile());
                IndexFiles.safe(jar);
                if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.size(jar) != dependency.byteLength()
                        || !IndexFiles.digest(jar).equals(dependency.digest())) throw new IOException("approved dependency identity changed");
                RelationContract.Artifact artifact = new RelationContract.Artifact(dependency.id(), dependency.digest(), dependency.byteLength(), dependency.order());
                readArchive(jar, artifact);
            }
            pool.appendClassPath(new LoaderClassPath(ClassLoader.getPlatformClassLoader()));
            pool.appendClassPath(new ClassPath() {
                @Override public InputStream openClassfile(String name) throws NotFoundException {
                    Selected selected = classes.get(name);
                    if (Objects.isNull(selected)) return null;
                    try {
                        return new ByteArrayInputStream(bytes(selected.zip(), selected.entry(), MAX_CLASS_BYTES));
                    } catch (IOException failure) {
                        throw new NotFoundException("approved class metadata cannot be consumed", failure);
                    }
                }
                @Override public URL find(String name) {
                    Selected selected = classes.get(name);
                    if (Objects.isNull(selected)) return null;
                    try {
                        return URI.create("jar:" + Path.of(selected.zip().getName()).toUri()
                                + "!/" + selected.entry().getName()).toURL();
                    } catch (MalformedURLException failure) {
                        throw new IllegalStateException("invalid owned archive location", failure);
                    }
                }
            });
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void readArchive(Path path, RelationContract.Artifact artifact) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        archives.add(zip);
        ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
        boolean multiRelease = false;
        if (Objects.nonNull(manifestEntry)) {
            try (InputStream stream = new ByteArrayInputStream(bytes(zip, manifestEntry, MAX_CLASS_BYTES))) {
                multiRelease = "true".equalsIgnoreCase(new Manifest(stream).getMainAttributes().getValue("Multi-Release"));
            }
        }
        Map<String, Selected> selected = new HashMap<>();
        Set<String> entryNames = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entryNames.add(name) || name.startsWith("/") || name.contains("\\") || name.contains(":")
                    || name.contains("//") || name.codePoints().anyMatch(Character::isISOControl)) throw new IOException("unsafe dependency archive entry");
            for (String part : name.split("/")) if (part.equals(".") || part.equals("..")) throw new IOException("unsafe dependency archive path");
            if (name.startsWith("BOOT-INF/") || name.startsWith("WEB-INF/") || name.endsWith(".jar"))
                throw new IOException("nested dependency layout is unsupported");
            if (entry.isDirectory() || !name.endsWith(".class")) continue;
            int version = 0;
            String classPath = name;
            if (name.startsWith("META-INF/versions/")) {
                String suffix = name.substring("META-INF/versions/".length());
                int separator = suffix.indexOf('/');
                if (separator < 1) throw new IOException("invalid multi-release dependency entry");
                try { version = Integer.parseInt(suffix.substring(0, separator)); }
                catch (NumberFormatException failure) { throw new IOException("invalid multi-release version", failure); }
                if (version < 9) throw new IOException("invalid multi-release version");
                if (!multiRelease || version > 21) continue;
                classPath = suffix.substring(separator + 1);
            } else if (name.startsWith("META-INF/")) {
                throw new IOException("unsupported dependency class layout");
            }
            if (classPath.equals("module-info.class")) continue;
            if (entry.getSize() < 0 || entry.getSize() > MAX_CLASS_BYTES) throw new IOException("dependency class exceeds byte limit");
            String binaryName = classPath.substring(0, classPath.length() - 6).replace('/', '.');
            Selected previous = selected.get(binaryName);
            if (Objects.isNull(previous) || previous.version() < version) {
                selected.put(binaryName, new Selected(zip, entry, binaryName, artifact, version));
                if (selected.size() > MAX_CLASSES - classCount) throw new IOException("dependency class count exceeds limit");
            }
        }
        for (Selected value : selected.values()) {
            if (++classCount > MAX_CLASSES) throw new IOException("dependency class count exceeds limit");
            expandedBytes = Math.addExact(expandedBytes, value.entry().getSize());
            if (expandedBytes > MAX_EXPANDED_BYTES) throw new IOException("dependency expanded bytes exceed limit");
            byte[] content = bytes(value.zip(), value.entry(), MAX_CLASS_BYTES);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(content))) {
                ClassFile metadata = new ClassFile(input);
                if (!metadata.getName().equals(value.binaryName()) || metadata.getMajorVersion() > 65 || metadata.getMinorVersion() == 65535)
                    throw new IOException("dependency class identity or Java level is unsupported");
            }
            classes.putIfAbsent(value.binaryName(), value);
            classes.putIfAbsent(value.binaryName().replace('$', '.'), value);
        }
    }

    private static byte[] bytes(ZipFile archive, ZipEntry entry, int maximum) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > maximum) throw new IOException("dependency entry exceeds limit");
        try (InputStream stream = archive.getInputStream(entry)) {
            byte[] content = stream.readNBytes(maximum + 1);
            if (content.length > maximum || content.length != entry.getSize()) throw new IOException("dependency entry size mismatch");
            return content;
        }
    }

    public Optional<RelationContract.Artifact> artifact(String qualifiedName) {
        return Optional.ofNullable(classes.get(qualifiedName)).map(Selected::artifact);
    }

    @Override public TypeSolver getParent() { return parent; }
    @Override public void setParent(TypeSolver value) {
        if (Objects.nonNull(parent) || value == this) throw new IllegalStateException("dependency provider already bound");
        parent = Objects.requireNonNull(value);
    }
    @Override public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        Selected selected = classes.get(name);
        if (Objects.isNull(selected)) return SymbolReference.unsolved();
        ResolvedReferenceTypeDeclaration known = resolved.get(selected.binaryName());
        if (Objects.nonNull(known)) return SymbolReference.solved(known);
        try (InputStream input = new ByteArrayInputStream(bytes(selected.zip(), selected.entry(), MAX_CLASS_BYTES))) {
            CtClass metadata = pool.makeClassIfNew(input);
            ResolvedReferenceTypeDeclaration declaration = JavassistFactory.toTypeDeclaration(metadata, getRoot());
            resolved.put(selected.binaryName(), declaration);
            return SymbolReference.solved(declaration);
        } catch (IOException failure) {
            throw new UncheckedIOException("approved class metadata cannot be consumed", failure);
        }
    }
    @Override public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String name, String moduleName) {
        return tryToSolveType(name);
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        for (ZipFile archive : archives) {
            try { archive.close(); }
            catch (IOException exception) { if (Objects.isNull(failure)) failure = exception; else failure.addSuppressed(exception); }
        }
        archives.clear(); classes.clear(); resolved.clear();
        if (Objects.nonNull(failure)) throw failure;
    }
}
