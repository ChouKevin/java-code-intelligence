package com.java.semantic.indexer.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.build.RepositoryBuildScopeFactory;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.indexer.uat.NoOpPublicationGate;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.semantic.adapter.jdtls.DefaultJdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtLsHomeRequirement;
import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceLifecycleMetrics;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

/** Exercises the production dispatcher build assembly against revisions admitted from a moving remote. */
@Tag("jdtls-it")
class DispatchedBuildIT {

    private static final Path PAYMENT_FIXTURE = Path.of("fixtures/uat/payment-service");
    private static final Path PAYMENT_V2_PATCH = Path.of("fixtures/uat/versions/payment-service-v2.patch");

    @TempDir
    Path temporaryDirectory;

    @Test
    void excludes_generated_build_files_from_the_remote_fixture() throws Exception {
        try (RemoteFixture remote = RemoteFixture.create(temporaryDirectory)) {
            assertThat(remote.r1Paths()).noneMatch(path -> path.startsWith("target/") || path.contains("/target/"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishes_the_admitted_revision_from_a_moving_remote_for_incremental_and_full_fallback_builds(boolean modifyPom)
            throws Exception {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        try (MongoDBContainer mongo = mongo(); RemoteFixture remote = RemoteFixture.create(temporaryDirectory);
                MongoClient mongoClient = MongoClients.create(mongo.getConnectionString())) {
            MongoTemplate template = new MongoTemplate(mongoClient, "dispatched_build");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            RepositoryId repositoryId = RepositoryId.of("payment-service");
            try (BuildHarness build = buildHarness(repositoryId, remote.remoteUrl(), jobs, template, jdtLsHome)) {
                RepositoryBuildRunner runner = build.runner();

                IndexJob admittedR1 = jobs.admit(repositoryId, remote.r1(), false);
                remote.moveMainToR2(modifyPom);
                IndexJob runningR1 = jobs.startNextAccepted().orElseThrow();
                assertThat(runningR1.id()).isEqualTo(admittedR1.id());

                runner.run(runningR1);

                assertPublishedRevision(template, repositoryId, remote.r1());
                assertThat(paymentMethodContent(template, repositoryId, runningR1)).doesNotContain("MOBILE_PAYMENT");
                assertThat(symbolNames(template, repositoryId, runningR1)).doesNotContain("MOBILE_PAYMENT");
                assertThat(jobs.complete(runningR1.id())).isTrue();

                IndexJob admittedR2 = jobs.admit(repositoryId, remote.r2(), false);
                IndexJob runningR2 = jobs.startNextAccepted().orElseThrow();
                assertThat(runningR2.id()).isEqualTo(admittedR2.id());

                runner.run(runningR2);

                assertPublishedRevision(template, repositoryId, remote.r2());
                assertThat(paymentMethodContent(template, repositoryId, runningR2)).contains("MOBILE_PAYMENT");
                assertThat(symbolNames(template, repositoryId, runningR2)).contains("MOBILE_PAYMENT");
                assertThat(jobs.complete(runningR2.id())).isTrue();
            }
        }
    }

    private BuildHarness buildHarness(RepositoryId repositoryId, String remoteUrl, IndexJobStore jobs, MongoTemplate template,
                                      Path jdtLsHome) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("checked-out").toString());
        RepositoryProperties.RepositoryConfig repository = new RepositoryProperties.RepositoryConfig();
        repository.setUrl(remoteUrl);
        repository.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), repository));
        JGitRepositoryAdapter git = new JGitRepositoryAdapter(properties);
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(new RepositoryRuntimeRegistry(properties), git);
        DefaultJdtWorkspaceManager workspaces = workspaceManager(jdtLsHome);
        RepositoryBuildScopeFactory scopes = new RepositoryBuildScopeFactory(checkout, template, jobs,
                new MongoPublicationWriter(template), new NoOpPublicationGate(), new Lsp4jJavaSemanticService(workspaces), workspaces);
        return new BuildHarness(new RepositoryBuildRunner(scopes), workspaces);
    }

    private static void assertPublishedRevision(MongoTemplate template, RepositoryId repositoryId, RepositoryRevision revision) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES)
                .find(new Document("repoId", repositoryId.value())).first();
        assertThat(repository).isNotNull();
        assertThat(repository.get("currentPointer", Document.class).getString("revision")).isEqualTo(revision.value());
    }

    private static List<String> symbolNames(MongoTemplate template, RepositoryId repositoryId, IndexJob job) {
        String generationId = job.target().orElseThrow().generationId().value();
        return template.getCollection(IndexCollections.SYMBOLS).find(new Document("repoId", repositoryId.value())
                        .append("generationId", generationId))
                .map(document -> document.getString("name")).into(new java.util.ArrayList<>());
    }

    private static String paymentMethodContent(MongoTemplate template, RepositoryId repositoryId, IndexJob job) {
        String generationId = job.target().orElseThrow().generationId().value();
        Document file = template.getCollection(IndexCollections.GENERATION_FILES).find(new Document("repoId", repositoryId.value())
                .append("generationId", generationId).append("sourcePath", "src/main/java/com/example/payment/PaymentMethod.java")).first();
        assertThat(file).isNotNull();
        String artifactId = file.get("sourceArtifactId", Document.class).getString("value");
        Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                .find(new Document("sourceArtifactId", artifactId)).first();
        assertThat(artifact).isNotNull();
        return artifact.getString("utf8Content");
    }

    private DefaultJdtWorkspaceManager workspaceManager(Path home) {
        JdtLsProperties properties = new JdtLsProperties(true, home, temporaryDirectory.resolve("workspace"), Duration.ofSeconds(180),
                Duration.ofSeconds(600), Duration.ofSeconds(60), 1, Duration.ofMinutes(30), Duration.ofMinutes(1), "2g");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties), new JdtLsReadinessProbe(properties), properties,
                metrics, System::nanoTime, new JdtWorkspaceLifecycleMetrics(metrics));
    }

    private static MongoDBContainer mongo() {
        MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        return container;
    }

    private static final class RemoteFixture implements AutoCloseable {
        private final Git bare;
        private final Git seed;
        private final Path root;
        private final RepositoryRevision r1;
        private RepositoryRevision r2;

        private RemoteFixture(Git bare, Git seed, Path root, RepositoryRevision r1) {
            this.bare = bare;
            this.seed = seed;
            this.root = root;
            this.r1 = r1;
        }

        static RemoteFixture create(Path temporaryDirectory) throws Exception {
            Path remoteRoot = temporaryDirectory.resolve("payment-remote.git");
            Git bare = Git.init().setBare(true).setDirectory(remoteRoot.toFile()).call();
            Path seedRoot = temporaryDirectory.resolve("payment-seed");
            Git seed = Git.init().setDirectory(seedRoot.toFile()).call();
            copyFixture(PAYMENT_FIXTURE, seedRoot);
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("payment v1").setAuthor("UAT", "uat@example.test").setCommitter("UAT", "uat@example.test").call();
            seed.branchRename().setNewName("main").call();
            seed.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(remoteRoot.toUri().toString())).call();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
            RefUpdate head = bare.getRepository().updateRef(Constants.HEAD, true);
            head.link(Constants.R_HEADS + "main");
            RepositoryRevision r1 = new RepositoryRevision(seed.getRepository().resolve("HEAD").getName());
            return new RemoteFixture(bare, seed, seedRoot, r1);
        }

        RepositoryRevision r1() {
            return r1;
        }

        RepositoryRevision r2() {
            return r2;
        }

        String remoteUrl() {
            return bare.getRepository().getDirectory().toURI().toString();
        }

        List<String> r1Paths() throws IOException {
            List<String> paths = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(seed.getRepository())) {
                tree.addTree(seed.getRepository().resolve(r1.value() + "^{tree}"));
                tree.setRecursive(true);
                while (tree.next()) {
                    paths.add(tree.getPathString());
                }
            }
            return List.copyOf(paths);
        }

        void moveMainToR2(boolean modifyPom) throws Exception {
            applyPatch(root, PAYMENT_V2_PATCH.toAbsolutePath());
            if (modifyPom) {
                Path pom = root.resolve("pom.xml");
                Files.writeString(pom, Files.readString(pom) + "\n<!-- payment v2 build input -->\n");
            }
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("payment v2").setAuthor("UAT", "uat@example.test").setCommitter("UAT", "uat@example.test").call();
            seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
            r2 = new RepositoryRevision(seed.getRepository().resolve("HEAD").getName());
        }

        @Override
        public void close() {
            seed.close();
            bare.close();
        }
    }

    private record BuildHarness(RepositoryBuildRunner runner, DefaultJdtWorkspaceManager workspaces) implements AutoCloseable {
        private BuildHarness {
            runner = java.util.Objects.requireNonNull(runner, "runner is required");
            workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces are required");
        }

        @Override
        public void close() {
            workspaces.shutdownAll();
        }
    }

    private static Path copyFixture(Path source, Path target) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relativePath = source.relativize(path);
                if (relativePath.startsWith(Path.of("target"))) {
                    continue;
                }
                Path destination = target.resolve(relativePath.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    private static void applyPatch(Path fixtureRoot, Path patch) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "apply", patch.toString()).directory(fixtureRoot.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new AssertionError("payment-service-v2.patch cannot be applied: " + output);
        }
    }
}
