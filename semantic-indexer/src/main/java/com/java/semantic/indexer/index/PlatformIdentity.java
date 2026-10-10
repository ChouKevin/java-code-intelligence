package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.RelationContract;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

/** Content identity of the operator's immutable deployed Java 21, never application classpath. */
public final class PlatformIdentity {
    private final Path home;
    public PlatformIdentity() { this(Path.of(System.getProperty("java.home"))); }
    public PlatformIdentity(Path home) { this.home = home.toAbsolutePath().normalize(); }
    public RelationContract.Platform current() {
        try {
            byte[] release = IndexFiles.read(home.resolve("release"), 64 * 1024);
            Properties values = new Properties();
            values.load(new StringReader(new String(release, StandardCharsets.UTF_8)));
            String version = unquote(values.getProperty("JAVA_VERSION", ""));
            if (!version.matches("21(?:[. +\\-].*)?")) throw new IOException("deployment requires Java 21");
            return new RelationContract.Platform(version, unquote(values.getProperty("IMPLEMENTOR", "")),
                    com.java.semantic.indexer.source.DurableSourceFiles.sha256(release), IndexFiles.digest(home.resolve("lib/modules")));
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    public void requireSame(RelationContract.Platform accepted) {
        try {
            if (!current().equals(accepted)) throw new EnvironmentChangedException();
        } catch (IndexFailure unavailable) {
            throw new EnvironmentChangedException(unavailable);
        }
    }
    private static String unquote(String value) { return value.replace("\"", ""); }
    public static final class EnvironmentChangedException extends IllegalStateException {
        public EnvironmentChangedException() { super("ENVIRONMENT_CHANGED"); }
        private EnvironmentChangedException(IndexFailure cause) { super("ENVIRONMENT_CHANGED", cause); }
    }
}
