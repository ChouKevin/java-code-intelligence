package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the child, both pipes, reader threads, deadline, and slot on every exit path. */
public final class RipgrepTextSearch {
    private static final int STDOUT_CAP = 4 * 1024 * 1024;
    private static final int FRAME_CAP = 1024 * 1024;
    private static final int STDERR_CAP = 64 * 1024;
    private static final int RESPONSE_CAP = 64 * 1024;
    private final SourceAccessProperties properties;
    private final SourcePathResolver resolver;
    private final ObjectMapper mapper;
    private final Semaphore slots;

    public RipgrepTextSearch(SourceAccessProperties properties, SourcePathResolver resolver, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.resolver = Objects.requireNonNull(resolver);
        this.mapper = Objects.requireNonNull(mapper);
        this.slots = new Semaphore(properties.maxActiveSearches());
    }

    public TextSearchResult search(AdmittedSourceRevision admitted, TextSearchRequest request) {
        if (!admitted.context().equals(request.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        String directory = SourcePathResolver.safeDirectory(request.directory());
        String glob = request.filePattern().map(SourcePathResolver::safeGlob).orElse("");
        if (!slots.tryAcquire()) throw new SourceQueryException(Code.SOURCE_BUSY);
        try { return run(admitted, request, directory, glob); }
        finally { slots.release(); }
    }

    private TextSearchResult run(AdmittedSourceRevision admitted, TextSearchRequest request, String directory, String glob) {
        long operationDeadline = SourceOperationDeadline.cap(
                LocalSourceRevisionCatalog.deadline(properties.searchTimeout()));
        long remaining = Math.max(0, operationDeadline - System.nanoTime());
        long cleanupReserve = Math.min(TimeUnit.MILLISECONDS.toNanos(250), remaining / 4);
        long deadline = operationDeadline - cleanupReserve;
        LocalSourceRevisionCatalog.noLinks(admitted.tree());
        if (!Files.isDirectory(admitted.tree(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
        if (!directory.isEmpty()) {
            SourceInventoryEntry entry = resolver.entry(admitted, directory, deadline);
            if (entry.kind() != EntryKind.DIRECTORY) throw new SourceQueryException(Code.SOURCE_NOT_FOUND);
            resolver.physical(admitted, directory);
        }
        List<String> command = new ArrayList<>(List.of(properties.rgExecutable().toString(), "--no-config", "--json",
                "--fixed-strings", "--case-sensitive", "--line-number", "--hidden", "--no-ignore", "--no-messages",
                "--sort", "path", "--encoding", "none"));
        if (!glob.isEmpty()) { command.add("--glob"); command.add(glob); }
        command.addAll(List.of("--glob", "!.git/**", "--glob", "!target/**", "--glob", "!build/**",
                "--glob", "!.gradle/**", "--glob", "!node_modules/**", "--glob", "!generated/**",
                "--glob", "!*.class"));
        command.addAll(List.of("-e", request.query(), "--", directory.isEmpty() ? "." : directory));
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(admitted.tree().toFile());
            builder.environment().clear();
            builder.environment().put("LC_ALL", "C.UTF-8");
            builder.environment().put("RIPGREP_CONFIG_PATH", "/dev/null");
            process = builder.start();
        } catch (IOException exception) { throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception); }
        AtomicBoolean stopping = new AtomicBoolean();
        try { process.getOutputStream().close(); }
        catch (IOException exception) {
            cleanup(process, List.of(), stopping, operationDeadline);
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
        List<TextMatch> matches = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean enough = new AtomicBoolean();
        Thread stdout = Thread.ofVirtual().name("source-rg-stdout").start(() -> {
            try { readMatches(process.getInputStream(), process, stopping, admitted, request, directory, glob,
                    deadline, matches, enough); }
            catch (Throwable exception) { failure.compareAndSet(null, exception); }
        });
        Thread stderr = Thread.ofVirtual().name("source-rg-stderr").start(() -> {
            try { drain(process.getErrorStream(), process, stopping, deadline, STDERR_CAP); }
            catch (Throwable exception) { failure.compareAndSet(null, exception); }
        });
        boolean truncated = false;
        int exit = -1;
        try {
            while (true) {
                LocalSourceRevisionCatalog.check(deadline);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (Objects.nonNull(failure.get())) throw workerFailure(failure.get());
                if (enough.get()) { truncated = true; break; }
                if (process.waitFor(10, TimeUnit.MILLISECONDS)) { exit = process.exitValue(); break; }
            }
            if (!truncated) {
                join(stdout, deadline); join(stderr, deadline);
                if (Objects.nonNull(failure.get())) throw workerFailure(failure.get());
                if (exit > 1 || exit < 0) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                truncated = enough.get();
            }
            synchronized (matches) {
                if (truncated && matches.size() > request.limit()) matches.subList(request.limit(), matches.size()).clear();
                return new TextSearchResult(admitted.context(), matches, truncated, !truncated);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceQueryException(Code.SOURCE_TIMEOUT, exception);
        } finally {
            cleanup(process, List.of(stdout, stderr), stopping, operationDeadline);
        }
    }

    private static void cleanup(Process process, List<Thread> readers, AtomicBoolean stopping, long deadline) {
        boolean interrupted = Thread.interrupted();
        stopping.set(true);
        // Capture descendants before killing the parent: after reparenting they are no longer discoverable.
        List<ProcessHandle> descendants = List.of();
        try {
            descendants = process.descendants().toList();
            for (ProcessHandle descendant : descendants) {
                if (descendant.isAlive()) descendant.destroyForcibly();
            }
            if (process.isAlive()) process.destroyForcibly();
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    process.waitFor(Math.min(TimeUnit.MILLISECONDS.toNanos(10),
                            Math.max(1, deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) { interrupted = true; }
                if (process.isAlive()) process.destroyForcibly();
            }
            for (ProcessHandle descendant : descendants) {
                while (descendant.isAlive() && System.nanoTime() < deadline) {
                    descendant.destroyForcibly();
                    try { Thread.sleep(1); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
            }
            for (Thread reader : readers) {
                while (reader.isAlive() && System.nanoTime() < deadline) {
                    try { reader.join(1); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
                if (reader.isAlive()) reader.interrupt();
            }
            for (Thread reader : readers) {
                while (reader.isAlive() && System.nanoTime() < deadline) {
                    try { reader.join(1); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            for (ProcessHandle descendant : descendants) {
                if (descendant.isAlive()) descendant.destroyForcibly();
            }
            for (Thread reader : readers) {
                if (reader.isAlive()) reader.interrupt();
            }
            // Readers only perform available()-guarded reads; close each owned pipe even on error.
            try { process.getInputStream().close(); }
            catch (IOException exception) { /* The child is already terminated or forcibly terminating. */ }
            try { process.getErrorStream().close(); }
            catch (IOException exception) { /* Close the other pipe independently. */ }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)
                || readers.stream().anyMatch(Thread::isAlive)) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
    }

    private static SourceQueryException workerFailure(Throwable failure) {
        if (failure instanceof SourceQueryException error && error.code() == Code.SOURCE_TIMEOUT) return error;
        return new SourceQueryException(Code.SOURCE_UNAVAILABLE, failure);
    }

    private static void join(Thread thread, long deadline) throws InterruptedException {
        while (thread.isAlive()) {
            LocalSourceRevisionCatalog.check(deadline);
            thread.join(10);
        }
    }

    private static int available(InputStream stream, Process process, AtomicBoolean stopping, long deadline)
            throws IOException {
        while (!stopping.get()) {
            LocalSourceRevisionCatalog.check(deadline);
            int count = stream.available();
            if (count > 0) return count;
            if (!process.isAlive()) return -1;
            try { Thread.sleep(1); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SourceQueryException(Code.SOURCE_TIMEOUT, exception);
            }
        }
        return -1;
    }

    private static void drain(InputStream stream, Process process, AtomicBoolean stopping, long deadline, int cap)
            throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int ready;
        while ((ready = available(stream, process, stopping, deadline)) > 0) {
            int count = stream.read(buffer, 0, Math.min(ready, buffer.length));
            if (count < 0) break;
            total += count;
            if (total > cap) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
    }

    private void readMatches(InputStream stream, Process process, AtomicBoolean stopping,
            AdmittedSourceRevision admitted, TextSearchRequest request, String directory, String glob,
            long deadline, List<TextMatch> matches, AtomicBoolean enough) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        List<JsonNode> candidates = new ArrayList<>();
        Set<String> verified = new HashSet<>();
        byte[] buffer = new byte[8192];
        int total = 0;
        int window = request.limit() + 1;
        while (!enough.get()) {
            int ready = available(stream, process, stopping, deadline);
            if (ready < 0) break;
            int count = stream.read(buffer, 0, Math.min(ready, buffer.length));
            if (count < 0) break;
            for (int index = 0; index < count && !enough.get(); index++) {
                int value = Byte.toUnsignedInt(buffer[index]);
                if (++total > STDOUT_CAP) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                if (value != '\n') {
                    if (frame.size() >= FRAME_CAP) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                    frame.write(value);
                } else {
                    JsonNode event = candidate(frame.toByteArray(), directory, glob);
                    if (Objects.nonNull(event)) candidates.add(event);
                    frame.reset();
                    if (candidates.size() >= window) {
                        validateWindow(candidates, admitted, request, deadline, matches, enough, verified);
                        candidates.clear();
                    }
                }
            }
        }
        if (!stopping.get() && !enough.get()) {
            if (frame.size() != 0) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            if (!candidates.isEmpty()) validateWindow(candidates, admitted, request, deadline, matches, enough, verified);
        }
    }

    private JsonNode candidate(byte[] frame, String directory, String glob) {
        try {
            JsonNode event = mapper.readTree(frame);
            if (!"match".equals(event.path("type").asString())) return null;
            String path = matchPath(event);
            SourcePathResolver.safeFile(path);
            if (!directory.isEmpty() && !path.startsWith(directory + "/")) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            }
            if (!glob.isEmpty() && !SourcePathResolver.matches(path, glob)) return null;
            return event;
        } catch (SourceQueryException exception) { throw exception; }
        catch (RuntimeException exception) { throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception); }
    }

    private static String matchPath(JsonNode event) {
        String path = event.path("data").path("path").path("text").asString();
        return path.startsWith("./") ? path.substring(2) : path;
    }

    private void validateWindow(List<JsonNode> candidates, AdmittedSourceRevision admitted,
            TextSearchRequest request, long deadline, List<TextMatch> matches, AtomicBoolean enough,
            Set<String> verified) {
        Set<String> paths = new HashSet<>();
        for (JsonNode candidate : candidates) paths.add(matchPath(candidate));
        Map<String, SourceInventoryEntry> entries = resolver.entries(admitted, paths, deadline);
        for (JsonNode event : candidates) {
            String path = matchPath(event);
            SourceInventoryEntry entry = entries.get(path);
            if (Objects.isNull(entry)) continue;
            Path physical = resolver.readable(admitted, entry);
            if (verified.add(path)) verifyContent(physical, entry, deadline);
            addMatches(event, admitted, request, path, matches, enough);
            if (enough.get()) return;
        }
    }

    private void addMatches(JsonNode event, AdmittedSourceRevision admitted, TextSearchRequest request,
            String path, List<TextMatch> matches, AtomicBoolean enough) {
        try {
            JsonNode data = event.path("data");
            String line = data.path("lines").path("text").asString();
            int number = data.path("line_number").asInt();
            byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
            for (JsonNode submatch : data.path("submatches")) {
                int start = submatch.path("start").asInt(-1);
                int end = submatch.path("end").asInt(-1);
                if (start < 0 || end < start || end > lineBytes.length || number < 1) {
                    throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                }
                String before = strict(lineBytes, 0, start);
                String found = strict(lineBytes, start, end - start);
                if (!found.equals(request.query())) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                TextMatch match = new TextMatch(path, number, before.length() + 1, found,
                        admitted.manifest().projectGuide().state() == GuideState.AVAILABLE
                                && admitted.manifest().projectGuide().path().filter(path::equals).isPresent());
                synchronized (matches) {
                    matches.add(match);
                    if (matches.size() > request.limit()) { enough.set(true); return; }
                    if (mapper.writeValueAsBytes(new TextSearchResult(admitted.context(), matches, false, true)).length
                            > RESPONSE_CAP / 4) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                }
            }
        } catch (SourceQueryException exception) { throw exception; }
        catch (RuntimeException exception) { throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception); }
    }

    private static void verifyContent(Path file, SourceInventoryEntry entry, long deadline) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                LocalSourceRevisionCatalog.check(deadline);
                digest.update(buffer, 0, count);
            }
            if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(entry.contentDigest().orElseThrow())) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    private static String strict(byte[] bytes, int from, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, from, length)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }
}
