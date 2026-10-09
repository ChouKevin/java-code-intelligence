package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexFailure;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Injects failure at the JDBC connection boundary, without depending on a native library layout. */
@Isolated("Temporarily replaces the process-wide JDBC driver registry")
class IndexNativeBoundaryTest {
    @TempDir Path root;

    @Test
    void unavailable_native_driver_is_index_local_and_source_publication_remains_readable() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            UnsatisfiedLinkError failure = new UnsatisfiedLinkError("test-disabled native library");
            withDriver(new FailingDriver(failure), () -> {
                assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse)
                        .build(input, fixture.stage(input), () -> { })).isInstanceOf(IndexFailure.class)
                        .hasCause(failure);
            });
            assertThat(fixture.sources.manifest(input.context(), input.sourceManifestDigest())).isEqualTo(input.sourceManifest());
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
        }
    }

    @Test
    void initialization_wrapper_does_not_swallow_vm_fatal_cause() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            OutOfMemoryError fatal = new OutOfMemoryError("test-only fatal marker");
            withDriver(new FailingDriver(new ExceptionInInitializerError(fatal)), () -> {
                assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse)
                        .build(input, fixture.stage(input), () -> { })).isSameAs(fatal);
            });
        }
    }

    @Test
    void jdbc_connection_failure_is_mapped_without_invalidating_the_source() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SQLException failure = new SQLException("test-only JDBC refusal");
            withDriver(new FailingDriver(failure), () -> {
                assertThatThrownBy(() -> new SqliteIndexBuilder(fixture.mapper, IndexArtifactFixture::parse)
                        .build(input, fixture.stage(input), () -> { })).isInstanceOf(IndexFailure.class).hasCause(failure);
            });
            assertThat(fixture.sources.manifest(input.context(), input.sourceManifestDigest())).isEqualTo(input.sourceManifest());
        }
    }

    private static void withDriver(Driver driver, Runnable assertions) throws SQLException {
        List<Driver> previous = new ArrayList<>();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) previous.add(registered.nextElement());
        for (Driver existing : previous) DriverManager.deregisterDriver(existing);
        DriverManager.registerDriver(driver);
        try { assertions.run(); }
        finally {
            DriverManager.deregisterDriver(driver);
            for (Driver existing : previous) DriverManager.registerDriver(existing);
        }
    }

    private record FailingDriver(Throwable failure) implements Driver {
        @Override public Connection connect(String url, Properties properties) throws SQLException {
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof LinkageError linkage) throw linkage;
            throw new IllegalArgumentException("unsupported injected JDBC failure", failure);
        }
        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:sqlite:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("index-native-boundary-test"); }
    }
}
