package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexWorkerLifecycleTest {
    @TempDir Path root;

    @Test
    void actual_child_build_seals_staging_but_cannot_publish() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A { void run() {} }\n");
            fixture.admit(input, IndexArtifactFixture.request(), java.util.Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexManifest manifest = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties)
                    .build(input, running, fixture.stage(input), () -> { });
            assertThat(manifest.coverage().parsedFiles()).isEqualTo(1);
            assertThat(Files.exists(fixture.stage(input).resolve("index.sqlite"))).isTrue();
            assertThat(fixture.indexes.selected(input.context())).isEmpty();
            assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
        }
    }

    @Test
    void actual_worker_capacity_refusal_preserves_ready_and_returns_safe_durable_failure_code() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput original = fixture.prepare("class A {}\n");
            fixture.admit(original, IndexArtifactFixture.request(), java.util.Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            IndexBuildExecutor executor = new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties);
            IndexManifest manifest = executor.build(original, running, fixture.stage(original), () -> { });
            Receipt ready = fixture.indexes.publish(running, fixture.stage(original), manifest);
            fixture.inputs.cleanupTerminal(fixture.jobs.complete(fixture.git.repository, new IndexJobId(running.jobId()), ready));
            IndexBuildInput rebuild = fixture.input(original.context());
            fixture.admit(rebuild, IndexArtifactFixture.request(), java.util.Optional.of(original.generation()));
            SemanticIndexJob rebuilding = fixture.jobs.claimNext().orElseThrow();
            fixture.properties.getSourceCapacity().setMaxManagedBytes(1);
            // Bypass only parent checkpoints to exercise the real child's capacity boundary.
            assertThatThrownBy(() -> executor.build(rebuild, rebuilding, fixture.stage(rebuild), () -> { }))
                    .isInstanceOfSatisfying(IndexBuildExecutor.WorkerFailure.class,
                            failure -> assertThat(failure.failureCode()).isEqualTo("CAPACITY_EXCEEDED"));
            assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
            assertThat(fixture.indexes.selected(original.context())).contains(ready);
            assertThat(Files.exists(fixture.stage(rebuild).resolve("manifest.json"))).isFalse();
            fixture.inputs.cleanupTerminal(fixture.jobs.fail(fixture.git.repository, new IndexJobId(rebuilding.jobId()), "CAPACITY_EXCEEDED"));
            assertThat(fixture.jobs.find(fixture.git.repository, new IndexJobId(rebuilding.jobId()))
                    .orElseThrow().failureCode()).contains("CAPACITY_EXCEEDED");
        }
    }

    @Test
    void actual_parent_death_after_sqlite_initialization_recovers_owned_native_temp_and_preserves_ready() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            StringBuilder source = new StringBuilder("class A {\n");
            for (int index = 0; index < 20_000; index++) source.append("void method").append(index).append("() {}\n");
            source.append("}\n");
            IndexBuildInput original = fixture.prepare(source.toString());
            fixture.admit(original, IndexArtifactFixture.request(), java.util.Optional.empty());
            SemanticIndexJob first = fixture.jobs.claimNext().orElseThrow();
            // Produce genuine source coordinates for the multiline prior READY,
            // using the same production parser as the interrupted worker rebuild.
            IndexManifest manifest = new SqliteIndexBuilder(fixture.mapper, new JavaSyntaxExtractor())
                    .build(original, fixture.stage(original), () -> { });
            Receipt ready = fixture.indexes.publish(first, fixture.stage(original), manifest);
            fixture.inputs.cleanupTerminal(fixture.jobs.complete(fixture.git.repository, new IndexJobId(first.jobId()), ready));
            byte[] readyDatabase = Files.readAllBytes(fixture.generation(original).resolve("index.sqlite"));
            byte[] readyManifest = Files.readAllBytes(fixture.generation(original).resolve("manifest.json"));
            IndexBuildInput rebuild = fixture.input(original.context());
            fixture.admit(rebuild, IndexArtifactFixture.request(), java.util.Optional.of(original.generation()));
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Path parentTemp = Files.createDirectory(root.resolve("executable-parent-temp"));
            Path neighbor = Files.createDirectory(parentTemp.resolve("index-worker-unowned-neighbor"));
            Files.writeString(neighbor.resolve("keep"), "operator-owned");
            Path request = root.resolve("parent-request.json");
            Files.write(request, fixture.mapper.writeValueAsBytes(new ParentBuild(
                    fixture.properties, rebuild, running, fixture.stage(rebuild).toString())));
            ProcessBuilder launch = controllable(root.resolve("unused-pid"), "unused");
            launch.command(new ArrayList<>(launch.command()));
            launch.command().set(launch.command().size() - 3, ProductionParent.class.getName());
            launch.command().remove(launch.command().size() - 1);
            launch.command().set(launch.command().size() - 1, request.toString());
            launch.command().add(1, "-Djava.io.tmpdir=" + parentTemp);
            Process parent = launch.redirectErrorStream(true).redirectOutput(root.resolve("parent.log").toFile()).start();
            ProcessHandle worker = null;
            Path temporary = null;
            try {
                long expires = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                String descriptor = fixture.inputs.directory(running).resolve("descriptor.json").toString();
                while (Objects.isNull(worker) && parent.isAlive() && System.nanoTime() < expires) {
                    try (java.util.stream.Stream<ProcessHandle> children = parent.toHandle().children()) {
                        worker = children.filter(child -> ownsDescriptor(child, descriptor)).findFirst().orElse(null);
                    }
                    if (Objects.isNull(worker)) Thread.sleep(1);
                }
                assertThat(worker).as("production executor must launch a real worker").isNotNull();
                try (java.io.InputStream argv = Files.newInputStream(Path.of("/proc", Long.toString(worker.pid()), "cmdline"))) {
                    String[] arguments = new String(argv.readNBytes(65_536), java.nio.charset.StandardCharsets.UTF_8).split("\\x00");
                    temporary = java.util.Arrays.stream(arguments).filter(argument -> argument.startsWith("-Dorg.sqlite.tmpdir="))
                            .map(argument -> Path.of(argument.substring("-Dorg.sqlite.tmpdir=".length()))).findFirst().orElseThrow();
                }
                assertThat(temporary.getParent()).as("worker keeps the parent's executable temp filesystem").isEqualTo(parentTemp);
                while (worker.isAlive() && System.nanoTime() < expires
                        && !Files.exists(fixture.stage(rebuild).resolve("index.sqlite"))) Thread.sleep(1);
                assertThat(Files.exists(fixture.stage(rebuild).resolve("index.sqlite"))).as("real SQLite connection initializes").isTrue();
                signal(worker, "-STOP");
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isTrue();
                try (java.util.stream.Stream<Path> payloads = Files.list(temporary)) {
                    assertThat(payloads.anyMatch(path -> path.getFileName().toString().endsWith(".so")))
                            .as("real JDBC JNI payload extracted before parent death").isTrue();
                }
                parent.destroyForcibly();
                assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(Files.exists(temporary)).as("SIGKILL bypasses parent finally").isTrue();
                signal(worker, "-CONT");
                while (fixture.inputs.hasLiveWorker(fixture.git.repository) && System.nanoTime() < expires) Thread.sleep(5);
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).as("child OS lease physically settles").isFalse();
                Path status = Path.of("/proc", Long.toString(worker.pid()), "status");
                assertThat(!Files.exists(status) || Files.readString(status).contains("State:\tZ"))
                        .as("orphan child is exited, not merely detached").isTrue();
                FileSemanticIndexJobStore restartedJobs = new FileSemanticIndexJobStore(fixture.properties, fixture.mapper, fixture.owner);
                ResolutionInputStore restartedInputs = new ResolutionInputStore(fixture.properties, fixture.mapper,
                        new PlatformIdentity(), fixture.registry);
                restartedJobs.recover(fixture.indexes, restartedInputs);
                restartedInputs.recover(restartedJobs);
                assertThat(Files.exists(temporary)).as("restart reclaims only the validated job-owned native temp").isFalse();
                assertThat(Files.readString(neighbor.resolve("keep"))).isEqualTo("operator-owned");
                assertThat(fixture.indexes.selected(original.context())).contains(ready);
                assertThat(Files.readAllBytes(fixture.generation(original).resolve("index.sqlite"))).isEqualTo(readyDatabase);
                assertThat(Files.readAllBytes(fixture.generation(original).resolve("manifest.json"))).isEqualTo(readyManifest);
                assertThat(restartedJobs.find(fixture.git.repository, new IndexJobId(running.jobId())).orElseThrow().failureCode())
                        .contains("WORKER_INTERRUPTED");
            } finally {
                parent.destroyForcibly();
                parent.waitFor();
                if (Objects.nonNull(worker) && worker.isAlive()) {
                    signal(worker, "-CONT");
                    worker.destroyForcibly();
                }
            }
        }
    }

    @Test
    void deadline_waits_for_actual_child_exit_before_returning() {
        Path pid = root.resolve("pid");
        assertThatThrownBy(() -> IndexBuildExecutor.run(controllable(pid, "sleep"), Duration.ofMillis(700), () -> { }))
                .isInstanceOf(IOException.class);
        assertReaped(pid);
    }

    @Test
    void successful_exit_after_deadline_is_rejected_after_child_cleanup() {
        Path pid = root.resolve("pid");
        Path release = root.resolve("release");
        long releaseAfter = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        assertThatThrownBy(() -> IndexBuildExecutor.run(controllable(pid, "released-exit"),
                Duration.ofSeconds(1), () -> {
                    if (!Files.exists(pid.resolveSibling("go"))) return;
                    while (System.nanoTime() < releaseAfter) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    try { Files.writeString(release, "exit successfully"); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                })).isInstanceOf(IOException.class).hasMessageContaining("deadline");
        assertThat(Files.exists(release)).isTrue();
        assertReaped(pid);
    }

    @Test
    void capacity_refusal_after_go_reaps_child_before_failure_escapes() {
        Path pid = root.resolve("pid");
        assertThatThrownBy(() -> IndexBuildExecutor.run(controllable(pid, "sleep"), Duration.ofSeconds(10), () -> {
            if (Files.exists(pid)) throw new IllegalStateException("capacity refused");
        })).isInstanceOf(IllegalStateException.class);
        assertReaped(pid);
    }

    @Test
    void output_overflow_reaps_owned_real_child() {
        Path pid = root.resolve("pid");
        assertThatThrownBy(() -> IndexBuildExecutor.run(controllable(pid, "overflow"), Duration.ofSeconds(10), () -> { }))
                .isInstanceOf(IOException.class);
        assertReaped(pid);
    }

    @Test
    void parent_pipe_eof_stops_child_even_during_parent_death_interval() throws Exception {
        Path pid = root.resolve("pid");
        Process child = controllable(pid, "eof").start();
        try {
            assertThat(new String(child.getInputStream().readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("READY\n");
            child.getOutputStream().write(1); child.getOutputStream().flush();
            child.getOutputStream().close();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(2);
        } finally {
            child.destroyForcibly(); child.waitFor();
        }
        assertReaped(pid);
    }

    @Test
    void actual_worker_waiting_for_go_keeps_private_lease_until_parent_pipe_closes() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            SemanticIndexJob accepted = fixture.admit(input, IndexArtifactFixture.request(), java.util.Optional.empty());
            Path temporary = fixture.inputs.prepareWorkerTemporary(accepted).path();
            Process child = new ProcessBuilder(IndexBuildExecutor.command(
                    fixture.inputs.directory(accepted).resolve("descriptor.json"), temporary)).start();
            try {
                assertThat(new String(child.getInputStream().readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("READY\n");
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isTrue();
                // The parent can die before GO: this child must never touch source or staging.
                fixture.inputs.cleanupTerminal(accepted.failed("WORKER_INTERRUPTED"));
                assertThat(Files.exists(fixture.inputs.directory(accepted))).isTrue();
                assertThat(Files.exists(fixture.stage(input))).isFalse();
                assertThatThrownBy(() -> fixture.inputs.recover(fixture.jobs)).isInstanceOf(IllegalStateException.class);
                assertThat(Files.exists(fixture.inputs.directory(accepted).resolve("worker.lock"))).isTrue();
                child.getOutputStream().close();
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isZero();
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
                fixture.inputs.cleanupTerminal(accepted.failed("WORKER_INTERRUPTED"));
                assertThat(Files.exists(fixture.inputs.directory(accepted))).isFalse();
            } finally {
                child.destroyForcibly();
                child.waitFor();
                fixture.inputs.cleanupTerminal(accepted.failed("WORKER_INTERRUPTED"));
            }
        }
    }

    @Test
    void cancellation_reaps_real_child_and_restores_caller_interrupt() throws Exception {
        Path pid = root.resolve("pid");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                IndexBuildExecutor.run(controllable(pid, "sleep"), Duration.ofSeconds(10), () -> {
                    if (Files.exists(pid)) started.countDown();
                });
            } catch (Throwable caught) {
                failure.set(caught);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(5_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IOException.class);
            assertThat(interrupted).isTrue();
            assertReaped(pid);
        } finally {
            caller.interrupt();
            caller.join(5_000);
        }
    }

    @Test
    void overflow_written_immediately_before_successful_exit_is_not_accepted() {
        Path pid = root.resolve("pid");
        assertThatThrownBy(() -> IndexBuildExecutor.run(controllable(pid, "overflow-exit"), Duration.ofSeconds(10), () -> { }))
                .isInstanceOf(IOException.class);
        assertReaped(pid);
    }

    @Test
    void actual_worker_parent_eof_after_go_releases_lease_without_publishing_or_reading_source() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.admit(input, IndexArtifactFixture.request(), java.util.Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            Path directory = fixture.inputs.directory(running);
            IndexWorkerMain.BuildRequest request = new IndexWorkerMain.BuildRequest(input, running,
                    fixture.stage(input).toString(), fixture.properties.getSourcePublishedRoot(),
                    fixture.properties.getSourceAdminRoot(), fixture.properties.getIndexPublishedRoot(), 0, 0);
            Files.write(directory.resolve("build-request.json"), fixture.mapper.writeValueAsBytes(request));
            Path temporary = fixture.inputs.prepareWorkerTemporary(running).path();
            // Prevent the real worker from reaching source consumption while its parent dies.
            try (FileChannel channel = FileChannel.open(Path.of(fixture.properties.getSourcePublishedRoot()).resolve("orders/read.lock"),
                    StandardOpenOption.WRITE); FileLock exclusive = channel.lock()) {
                Process child = new ProcessBuilder(IndexBuildExecutor.command(directory.resolve("descriptor.json"), temporary)).start();
                try {
                    assertThat(new String(child.getInputStream().readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("READY\n");
                    child.getOutputStream().write(1);
                    child.getOutputStream().flush();
                    assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isTrue();
                    child.getOutputStream().close();
                    assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.exitValue()).isEqualTo(2);
                    assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
                    assertThat(Files.exists(fixture.stage(input))).isFalse();
                    assertThat(fixture.indexes.receipt(running)).isEmpty();
                    fixture.jobs.recover(fixture.indexes, fixture.inputs);
                    assertThat(fixture.jobs.find(fixture.git.repository, new IndexJobId(running.jobId()))
                            .orElseThrow().failureCode()).contains("WORKER_INTERRUPTED");
                } finally {
                    child.destroyForcibly();
                    child.waitFor();
                    fixture.inputs.cleanupTerminal(running.failed("WORKER_INTERRUPTED"));
                }
            }
        }
    }

    @Test
    void manager_source_guard_blocks_exclusive_contender_until_cancelled_child_physically_exits() throws Exception {
        try (IndexArtifactFixture fixture = new IndexArtifactFixture(root)) {
            IndexBuildInput input = fixture.prepare("class A {}\n");
            fixture.admit(input, IndexArtifactFixture.request(), java.util.Optional.empty());
            SemanticIndexJob running = fixture.jobs.claimNext().orElseThrow();
            SemanticIndexManager manager = new SemanticIndexManager(fixture.jobs,
                    new IndexSourceAccess(fixture.sources, fixture.registry, fixture.properties),
                    new IndexBuildExecutor(fixture.mapper, fixture.inputs, new PlatformIdentity(), fixture.properties),
                    fixture.indexes, fixture.properties, fixture.inputs);
            java.util.concurrent.atomic.AtomicReference<SemanticIndexJob> result = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            String descriptor = fixture.inputs.directory(running).resolve("descriptor.json").toString();
            Thread caller = new Thread(() -> {
                try { result.set(manager.execute(running)); }
                catch (Throwable caught) { failure.set(caught); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            ProcessHandle worker = null;
            Process contender = null;
            caller.start();
            try {
                long expires = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (Objects.isNull(worker) && caller.isAlive() && System.nanoTime() < expires) {
                    try (java.util.stream.Stream<ProcessHandle> children = ProcessHandle.current().children()) {
                        worker = children.filter(child -> ownsDescriptor(child, descriptor)).findFirst().orElse(null);
                    }
                    if (Objects.isNull(worker)) Thread.sleep(1);
                }
                assertThat(worker).as("manager-owned worker must launch; caller failure=%s, result=%s", failure.get(), result.get()).isNotNull();
                signal(worker, "-STOP");
                while (System.nanoTime() < expires
                        && !Files.readString(Path.of("/proc", Long.toString(worker.pid()), "status")).contains("State:\tT")) Thread.sleep(1);
                assertThat(Files.readString(Path.of("/proc", Long.toString(worker.pid()), "status"))).contains("State:\tT");
                // A free private lease proves the stopped child cannot yet own a source guard.
                try (ResolutionInputStore.WorkerLease lease = fixture.inputs.acquireWorker(running)) {
                    assertThat(lease).as("STOP must precede child lease acquisition").isNotNull();
                }
                Path contenderPid = root.resolve("contender-pid");
                ProcessBuilder launch = controllable(contenderPid, "exclusive");
                launch.command(new java.util.ArrayList<>(launch.command()));
                launch.command().add(Path.of(fixture.properties.getSourcePublishedRoot()).resolve("orders/read.lock").toString());
                launch.command().add(Long.toString(worker.pid()));
                contender = launch.start();
                assertThat(new String(contender.getInputStream().readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("READY\n");
                contender.getOutputStream().write(1);
                contender.getOutputStream().flush();
                Path acquired = contenderPid.resolveSibling("acquired");
                assertThat(Files.exists(acquired)).isFalse();
                caller.interrupt();
                // STOP prevents TERM handling: the guard must cover forced-kill/reap too.
                Thread.sleep(100);
                assertThat(worker.isAlive()).isTrue();
                assertThat(Files.exists(acquired)).isFalse();
                caller.join(5_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
                assertThat(result.get().phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
                assertThat(interrupted).as("durable cancellation settlement restores caller interrupt").isTrue();
                assertThat(fixture.jobs.find(fixture.git.repository, new IndexJobId(running.jobId())).orElseThrow())
                        .isEqualTo(result.get());
                assertThat(fixture.inputs.hasLiveWorker(fixture.git.repository)).isFalse();
                assertThat(Files.exists(fixture.inputs.directory(running))).isFalse();
                assertThat(worker.isAlive()).isFalse();
                assertThat(contender.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(contender.exitValue()).isZero();
                assertThat(Files.readString(acquired)).isEqualTo("child reaped");
                assertThat(fixture.indexes.selected(input.context())).isEmpty();
            } finally {
                caller.interrupt();
                if (Objects.nonNull(worker) && worker.isAlive()) {
                    try { signal(worker, "-CONT"); }
                    finally {
                        worker.destroyForcibly();
                        worker.onExit().get(5, TimeUnit.SECONDS);
                    }
                }
                caller.join(5_000);
                if (Objects.nonNull(contender)) {
                    contender.destroyForcibly();
                    contender.waitFor();
                }
            }
        }
    }

    private static boolean ownsDescriptor(ProcessHandle directChild, String descriptor) {
        // Read bounded kernel argv directly: ProcessHandle.Info may not retain the
        // final descriptor argument after the service's long classpath argument.
        try (java.io.InputStream arguments = Files.newInputStream(
                Path.of("/proc", Long.toString(directChild.pid()), "cmdline"))) {
            byte[] bytes = arguments.readNBytes(65_537);
            if (bytes.length > 65_536) throw new AssertionError("owned child argv exceeds fixture bound");
            String command = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return java.util.Arrays.asList(command.split("\\x00")).contains(descriptor);
        } catch (java.nio.file.NoSuchFileException exited) {
            return false;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("cannot inspect manager-owned child argv", failure);
        }
    }

    private static void signal(ProcessHandle ownedChild, String signal) throws Exception {
        Process command = new ProcessBuilder("kill", signal, Long.toString(ownedChild.pid())).start();
        try {
            assertThat(command.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(command.exitValue()).isZero();
        } finally {
            command.destroyForcibly();
            command.waitFor();
        }
    }

    private ProcessBuilder controllable(Path pid, String mode) {
        String[] entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        String classpath = java.util.Arrays.stream(entries).map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp", classpath, ControllableChild.class.getName(), pid.toString(), mode));
    }

    private static void assertReaped(Path pid) {
        try {
            long processId = Long.parseLong(Files.readString(pid));
            assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } catch (IOException failure) { throw new AssertionError("child never started", failure); }
    }

    public record ParentBuild(RepositoryProperties properties, IndexBuildInput input, SemanticIndexJob job, String stage) { }

    public static final class ProductionParent {
        public static void main(String[] args) throws Exception {
            ObjectMapper mapper = JsonMapper.builder().build();
            ParentBuild request = mapper.readValue(Files.readAllBytes(Path.of(args[0])), ParentBuild.class);
            ResolutionInputStore inputs = new ResolutionInputStore(request.properties(), mapper, new PlatformIdentity(),
                    new RepositoryRegistry(request.properties()));
            new IndexBuildExecutor(mapper, inputs, new PlatformIdentity(), request.properties())
                    .build(request.input(), request.job(), Path.of(request.stage()), () -> { });
        }
    }

    public static final class ControllableChild {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            System.out.print("READY\n"); System.out.flush();
            if (System.in.read() != 1) return;
            if (args[1].equals("exclusive")) {
                try (FileChannel channel = FileChannel.open(Path.of(args[2]), StandardOpenOption.WRITE);
                        FileLock lock = channel.lock()) {
                    boolean alive = ProcessHandle.of(Long.parseLong(args[3])).map(ProcessHandle::isAlive).orElse(false);
                    Files.writeString(Path.of(args[0]).resolveSibling("acquired"), alive ? "child still alive" : "child reaped");
                }
                return;
            }
            if (args[1].equals("eof")) {
                while (System.in.read() >= 0) { }
                Runtime.getRuntime().halt(2);
            }
            if (args[1].equals("released-exit")) {
                Files.writeString(Path.of(args[0]).resolveSibling("go"), "GO received");
                while (!Files.exists(Path.of(args[0]).resolveSibling("release"))) Thread.sleep(1);
                return;
            }
            if (args[1].equals("overflow") || args[1].equals("overflow-exit")) {
                System.out.write(new byte[65_537]); System.out.flush();
                if (args[1].equals("overflow-exit")) return;
            }
            Thread.sleep(60_000);
        }
    }
}
