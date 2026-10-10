package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import tools.jackson.databind.ObjectMapper;

/** Parent owns deadline and reap; only a fully stopped child may hand staging to publication. */
public final class IndexBuildExecutor {
    private static final int OUTPUT_LIMIT = 65_536;
    private final ObjectMapper mapper;
    private final ResolutionInputStore inputs;
    private final PlatformIdentity platform;
    private final RepositoryProperties properties;
    private final Duration deadline;

    public IndexBuildExecutor(ObjectMapper mapper, ResolutionInputStore inputs, PlatformIdentity platform,
            RepositoryProperties properties) {
        this(mapper, inputs, platform, properties, Duration.ofSeconds(300));
    }

    IndexBuildExecutor(ObjectMapper mapper, ResolutionInputStore inputs, PlatformIdentity platform,
            RepositoryProperties properties, Duration deadline) {
        this.mapper = Objects.requireNonNull(mapper); this.inputs = Objects.requireNonNull(inputs);
        this.platform = Objects.requireNonNull(platform); this.properties = Objects.requireNonNull(properties);
        this.deadline = Objects.requireNonNull(deadline);
    }

    public IndexManifest build(IndexBuildInput input, SemanticIndexJob job, Path stage, Runnable capacityCheck) {
        inputs.ensureWorkerInput(job);
        inputs.descriptor(job);
        Optional<ResolutionPlan> plan = IndexContract.supportsRelations(job.analysis().profile()) ? Optional.of(inputs.load(job)) : Optional.empty();
        capacityCheck.run();
        try (ResolutionInputStore.WorkerTemporary temporary = inputs.prepareWorkerTemporary(job)) {
            Path directory = inputs.directory(job);
            IndexWorkerMain.BuildRequest request = new IndexWorkerMain.BuildRequest(input, job, stage.toAbsolutePath().normalize().toString(),
                    Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize().toString(),
                    Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize().toString(),
                    Path.of(properties.getIndexPublishedRoot()).toAbsolutePath().normalize().toString(),
                    properties.getSourceCapacity().getMaxManagedBytes(), properties.getSourceCapacity().getMinFreeBytes());
            DurableSourceFiles.atomicBytes(directory.resolve("build-request.json"), mapper.writeValueAsBytes(request),
                    IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            ProcessBuilder launch = new ProcessBuilder(command(directory.resolve("descriptor.json"), temporary.path()));
            launch.environment().clear();
            launch.environment().put("LANG", "C.UTF-8");
            launch.directory(directory.toFile());
            run(launch, deadline, capacityCheck);
            if (plan.isPresent()) platform.requireSame(plan.orElseThrow().platform());
            IndexManifest manifest = mapper.readValue(IndexFiles.read(stage.resolve("manifest.json"), IndexFiles.MAX_MANIFEST), IndexManifest.class);
            Path database = stage.resolve("index.sqlite");
            SqliteIndexBuilder.noSidecars(database);
            if (!manifest.context().equals(input.context()) || !manifest.originFingerprint().equals(input.originFingerprint())
                    || !manifest.indexGeneration().equals(input.generation()) || !manifest.profile().equals(job.analysis().profile())
                    || !manifest.sourceManifestDigest().equals(input.sourceManifestDigest())
                    || !manifest.inventoryDigest().equals(input.sourceManifest().inventoryDigest())
                    || Files.size(database) != manifest.databaseBytes() || !IndexFiles.digest(database).equals(manifest.databaseDigest())
                    || !manifest.analysisPlanDigest().equals(job.analysis().analysisPlanDigest()))
                throw new IOException("worker output identity mismatch");
            capacityCheck.run();
            return manifest;
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    static List<String> command(Path descriptor, Path temporary) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toAbsolutePath().normalize().toString());
        command.add("-Xmx512m"); command.add("-XX:ActiveProcessorCount=1");
        command.add("-Djava.io.tmpdir=" + temporary); command.add("-Dorg.sqlite.tmpdir=" + temporary);
        String classpath = System.getProperty("java.class.path");
        String[] entries = classpath.split(java.io.File.pathSeparator);
        if (entries.length == 1 && entries[0].endsWith(".jar")) {
            Path jar = Path.of(entries[0]).toAbsolutePath().normalize();
            try (JarFile executable = new JarFile(jar.toFile())) {
                Manifest manifest = executable.getManifest();
                if (Objects.nonNull(manifest) && "com.java.semantic.SemanticIndexerApplication".equals(manifest.getMainAttributes().getValue("Start-Class"))) {
                    command.add("-jar"); command.add(jar.toString()); command.add("--index-worker"); command.add(descriptor.toString());
                    return List.copyOf(command);
                }
            }
        }
        List<String> absolute = new ArrayList<>();
        for (String entry : entries) absolute.add(Path.of(entry).toAbsolutePath().normalize().toString());
        command.add("-cp"); command.add(String.join(java.io.File.pathSeparator, absolute));
        command.add(IndexWorkerMain.class.getName()); command.add(descriptor.toString());
        return List.copyOf(command);
    }

    static void run(ProcessBuilder launch, Duration deadline, Runnable capacityCheck) throws IOException {
        long expires = System.nanoTime() + deadline.toNanos();
        Process process = launch.start();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean overflow = new AtomicBoolean();
        Thread stdout = drain(process.getInputStream(), ready, overflow, true);
        Thread stderr = drain(process.getErrorStream(), ready, overflow, false);
        boolean interrupted = false;
        try {
            while (!ready.await(50, TimeUnit.MILLISECONDS)) {
                if (!process.isAlive() || overflow.get() || System.nanoTime() >= expires) throw new IOException("worker handshake failed");
                capacityCheck.run();
            }
            if (overflow.get() || !process.isAlive() || System.nanoTime() >= expires) throw new IOException("worker handshake failed");
            process.getOutputStream().write(1); process.getOutputStream().flush();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (overflow.get() || System.nanoTime() >= expires) throw new IOException("worker deadline or output exceeded");
                capacityCheck.run();
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IOException("worker cancelled", failure);
        } finally {
            if (process.isAlive()) process.destroy();
            while (process.isAlive()) {
                try { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException failure) { interrupted = true; process.destroyForcibly(); }
            }
            try { process.getOutputStream().close(); } catch (IOException ignored) { /* Already reaped. */ }
            for (Thread drainer : List.of(stdout, stderr)) {
                while (drainer.isAlive()) {
                    try { drainer.join(); } catch (InterruptedException failure) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        // Drainers must reach EOF before accepting a successful exit: the last buffered
        // bytes can cross the cap after waitFor has already observed process exit.
        if (overflow.get()) throw new IOException("worker output overflow");
        if (System.nanoTime() >= expires) throw new IOException("worker deadline exceeded");
        String failureCode = switch (process.exitValue()) {
            case IndexWorkerMain.CAPACITY_EXCEEDED_EXIT -> "CAPACITY_EXCEEDED";
            case IndexWorkerMain.CAPACITY_UNAVAILABLE_EXIT -> "CAPACITY_UNAVAILABLE";
            case IndexWorkerMain.ENVIRONMENT_CHANGED_EXIT -> "ENVIRONMENT_CHANGED";
            default -> "";
        };
        if (!failureCode.isEmpty()) throw new WorkerFailure(failureCode);
        if (process.exitValue() != 0) throw new IOException("worker build failed");
    }
    /** Closed worker exit protocol; no child diagnostic text crosses the public boundary. */
    static final class WorkerFailure extends RuntimeException {
        private final String failureCode;
        private WorkerFailure(String failureCode) {
            super(failureCode);
            this.failureCode = failureCode;
        }
        String failureCode() { return failureCode; }
    }


    private static Thread drain(InputStream stream, CountDownLatch ready, AtomicBoolean overflow, boolean stdout) {
        Thread thread = new Thread(() -> {
            try (InputStream input = stream) {
                int count = 0;
                ByteArrayOutputStream prefix = new ByteArrayOutputStream(6);
                int next;
                while ((next = input.read()) >= 0) {
                    if (++count > OUTPUT_LIMIT) overflow.set(true);
                    if (stdout && count <= 6) {
                        prefix.write(next);
                        if (count == 6 && java.util.Arrays.equals(prefix.toByteArray(), "READY\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) ready.countDown();
                    }
                }
            } catch (IOException failure) { overflow.set(true); }
        }, stdout ? "index-worker-stdout" : "index-worker-stderr");
        thread.start();
        return thread;
    }
}
