package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
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
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
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
        long deadline = LocalSourceRevisionCatalog.deadline(properties.searchTimeout());
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
                "--fixed-strings", "--case-sensitive", "--line-number", "--hidden", "--no-ignore", "--no-messages"));
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
        try { process.getOutputStream().close(); }
        catch (IOException exception) {
            process.destroyForcibly();
            process.onExit().join();
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
        List<TextMatch> matches = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean enough = new AtomicBoolean();
        Thread stdout = Thread.ofVirtual().name("source-rg-stdout").start(() -> {
            try { readMatches(process.getInputStream(), admitted, request, directory, glob, deadline, matches, enough); }
            catch (Throwable exception) { failure.compareAndSet(null, exception); }
        });
        Thread stderr = Thread.ofVirtual().name("source-rg-stderr").start(() -> {
            try { drain(process.getErrorStream(), STDERR_CAP); }
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
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) process.destroy();
                try {
                    if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    process.destroyForcibly();
                }
                while (process.isAlive()) {
                    try { process.waitFor(); }
                    catch (InterruptedException exception) { interrupted = true; process.destroyForcibly(); }
                }
                try { process.getInputStream().close(); process.getErrorStream().close(); }
                catch (IOException exception) { /* Pipes belong to the terminated child. */ }
                stdout.interrupt(); stderr.interrupt();
                for (Thread reader : List.of(stdout, stderr)) {
                    while (reader.isAlive()) {
                        try { reader.join(); }
                        catch (InterruptedException exception) { interrupted = true; }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
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

    private static void drain(InputStream stream, int cap) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            total += count;
            if (total > cap) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
    }

    private void readMatches(InputStream stream, AdmittedSourceRevision admitted, TextSearchRequest request,
            String directory, String glob, long deadline, List<TextMatch> matches, AtomicBoolean enough) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        Set<String> verified = new HashSet<>();
        InputStream buffered = new BufferedInputStream(stream);
        int total = 0;
        int value;
        while ((value = buffered.read()) != -1 && !enough.get()) {
            LocalSourceRevisionCatalog.check(deadline);
            if (++total > STDOUT_CAP) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            if (value != '\n') {
                if (frame.size() >= FRAME_CAP) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                frame.write(value);
            } else {
                parseMatch(frame.toByteArray(), admitted, request, directory, glob, deadline, matches, enough, verified);
                frame.reset();
            }
        }
        if (!enough.get() && frame.size() != 0) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
    }

    private void parseMatch(byte[] frame, AdmittedSourceRevision admitted, TextSearchRequest request,
            String directory, String glob, long deadline, List<TextMatch> matches, AtomicBoolean enough,
            Set<String> verified) {
        try {
            JsonNode event = mapper.readTree(frame);
            if (!"match".equals(event.path("type").asText())) return;
            JsonNode data = event.path("data");
            String path = data.path("path").path("text").asText();
            if (path.startsWith("./")) path = path.substring(2);
            SourcePathResolver.safeFile(path);
            if (!directory.isEmpty() && !path.startsWith(directory + "/")) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            if (!glob.isEmpty() && !SourcePathResolver.matches(path, glob)) return;
            SourceInventoryEntry entry;
            try { entry = resolver.entry(admitted, path, deadline); }
            catch (SourceQueryException exception) {
                if (exception.code() == Code.SOURCE_NOT_FOUND) return;
                throw exception;
            }
            Path physical = resolver.readable(admitted, entry);
            if (verified.add(path)) verifyContent(physical, entry, deadline);
            String matchedPath = path;
            String line = data.path("lines").path("text").asText();
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
                TextMatch match = new TextMatch(matchedPath, number, before.length() + 1, found,
                        admitted.manifest().projectGuide().state() == GuideState.AVAILABLE
                                && admitted.manifest().projectGuide().path().filter(matchedPath::equals).isPresent());
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
