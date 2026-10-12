package com.java.semantic.query.source;

import com.java.semantic.query.source.SourceQueryException.Code;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded glob policy 1 compiler. Native globs prune; only the scalar automaton selects paths. */
final class SourceGlob {
    private static final int MAX_STATES = 8192;
    private static final String ESCAPABLE = "*?[]{}!,\\";
    private static final String SCALAR_PROJECTION = "{?,??,???,????}";
    private static final int[] NO_RANGES = new int[0];
    private enum Kind { LITERAL, ONE, CLASS, STAR, ALL, DIRECTORIES, DIRECTORY_BODY, END }
    private record State(Kind kind, int literal, int[] ranges, boolean negated) {
        boolean accepts(int scalar) {
            if (kind == Kind.LITERAL) return scalar == literal;
            if (scalar == '/') return kind == Kind.ALL;
            if (kind != Kind.CLASS) return true;
            boolean contained = false;
            for (int index = 0; index < ranges.length; index += 2) {
                if (scalar >= ranges[index] && scalar <= ranges[index + 1]) { contained = true; break; }
            }
            return contained != negated;
        }
    }
    private final State[] states;
    private final int[] starts;
    private final List<String> nativeGlobs;

    private SourceGlob(List<State> states, List<Integer> starts, List<String> nativeGlobs) {
        this.states = states.toArray(State[]::new);
        this.starts = starts.stream().mapToInt(Integer::intValue).toArray();
        this.nativeGlobs = List.copyOf(nativeGlobs);
    }

    static SourceGlob compile(String pattern) {
        if (Objects.isNull(pattern) || pattern.isBlank()) throw invalid();
        int scalars = 0;
        for (int index = 0; index < pattern.length();) {
            int scalar = pattern.codePointAt(index);
            if (Character.isISOControl(scalar) || scalar >= 0xD800 && scalar <= 0xDFFF || ++scalars > 256) throw invalid();
            index += Character.charCount(scalar);
        }
        Expansion expansion = new Expansion(pattern);
        List<String> branches = expansion.sequence(0, false);
        if (expansion.offset != pattern.length()) throw invalid();
        List<State> states = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<String> nativeGlobs = new ArrayList<>();
        int nativeBytes = 0;
        for (String branch : branches) {
            starts.add(states.size());
            String nativeGlob = compileBranch(branch, states);
            nativeBytes += nativeGlob.getBytes(StandardCharsets.UTF_8).length + 1;
            if (states.size() > MAX_STATES || nativeBytes > 65536) throw invalid();
            nativeGlobs.add(nativeGlob);
        }
        return new SourceGlob(states, starts, nativeGlobs);
    }

    List<String> nativeGlobs() { return nativeGlobs; }
    Matcher matcher(long deadlineNanos) { return new Matcher(deadlineNanos); }

    final class Matcher {
        private final long deadline;
        private boolean[] active = new boolean[states.length];
        private boolean[] next = new boolean[states.length];
        private Matcher(long deadline) { this.deadline = deadline; }

        boolean matches(String path) {
            LocalSourceRevisionCatalog.check(deadline);
            Arrays.fill(active, false);
            for (int start : starts) active[start] = true;
            close();
            for (int offset = 0; offset < path.length();) {
                int scalar = path.codePointAt(offset);
                offset += Character.charCount(scalar);
                Arrays.fill(next, false);
                for (int index = 0; index < states.length; index++) {
                    if ((index & 255) == 0) LocalSourceRevisionCatalog.check(deadline);
                    if (!active[index]) continue;
                    State state = states[index];
                    switch (state.kind) {
                        case END -> { }
                        case DIRECTORIES -> { if (scalar != '/') next[index + 1] = true; }
                        case DIRECTORY_BODY -> next[scalar == '/' ? index - 1 : index] = true;
                        case STAR, ALL -> { if (state.accepts(scalar)) next[index] = true; }
                        default -> { if (state.accepts(scalar)) next[index + 1] = true; }
                    }
                }
                boolean[] swap = active; active = next; next = swap;
                close();
            }
            for (int index = 0; index < states.length; index++) {
                if (active[index] && states[index].kind == Kind.END) return true;
            }
            return false;
        }

        private void close() {
            for (int index = 0; index < states.length; index++) {
                if ((index & 255) == 0) LocalSourceRevisionCatalog.check(deadline);
                if (!active[index]) continue;
                Kind kind = states[index].kind;
                if (kind == Kind.STAR || kind == Kind.ALL) active[index + 1] = true;
                else if (kind == Kind.DIRECTORIES) active[index + 2] = true;
            }
        }
    }

    private static String compileBranch(String branch, List<State> states) {
        if (branch.isEmpty() || branch.startsWith("/") || branch.endsWith("/") || branch.startsWith("!")
                || branch.indexOf(':') >= 0) throw invalid();
        for (String segment : branch.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw invalid();
        }
        List<State> tokens = new ArrayList<>();
        StringBuilder nativeGlob = new StringBuilder("/");
        boolean wildcard = false;
        for (int offset = 0; offset < branch.length();) {
            int scalar = branch.codePointAt(offset);
            offset += Character.charCount(scalar);
            if (scalar == '\\') {
                if (offset == branch.length()) throw invalid();
                scalar = branch.codePointAt(offset);
                offset += Character.charCount(scalar);
                if (ESCAPABLE.indexOf(scalar) < 0 || scalar == '\\') throw invalid();
                tokens.add(literal(scalar)); appendLiteral(nativeGlob, scalar);
            } else if (scalar == '*') {
                if (offset < branch.length() && branch.charAt(offset) == '(') throw invalid();
                wildcard = true;
                if (offset < branch.length() && branch.charAt(offset) == '*') {
                    int beginning = offset - 1;
                    offset++;
                    if (beginning > 0 && branch.charAt(beginning - 1) != '/'
                            || offset < branch.length() && branch.charAt(offset) != '/') throw invalid();
                    nativeGlob.append("**");
                    if (offset < branch.length()) {
                        offset++; nativeGlob.append('/');
                        tokens.add(simple(Kind.DIRECTORIES)); tokens.add(simple(Kind.DIRECTORY_BODY));
                    } else tokens.add(simple(Kind.ALL));
                } else { tokens.add(simple(Kind.STAR)); nativeGlob.append('*'); }
            } else if (scalar == '?') {
                if (offset < branch.length() && branch.charAt(offset) == '(') throw invalid();
                wildcard = true; tokens.add(simple(Kind.ONE)); nativeGlob.append(SCALAR_PROJECTION);
            } else if (scalar == '[') {
                ClassParser parser = new ClassParser(branch, offset);
                tokens.add(parser.parse()); offset = parser.offset;
                wildcard = true; nativeGlob.append(SCALAR_PROJECTION);
            } else {
                if (scalar == ']' || scalar == '{' || scalar == '}'
                        || "@+*!".indexOf(scalar) >= 0 && offset < branch.length() && branch.charAt(offset) == '(') throw invalid();
                tokens.add(literal(scalar)); appendLiteral(nativeGlob, scalar);
            }
        }
        if (branch.indexOf('/') < 0 && wildcard && !branch.equals("**")) {
            states.add(simple(Kind.DIRECTORIES)); states.add(simple(Kind.DIRECTORY_BODY));
            nativeGlob.insert(1, "**/");
        }
        states.addAll(tokens); states.add(simple(Kind.END));
        return nativeGlob.toString();
    }

    private static State simple(Kind kind) { return new State(kind, 0, NO_RANGES, false); }
    private static State literal(int scalar) { return new State(Kind.LITERAL, scalar, NO_RANGES, false); }
    private static void appendLiteral(StringBuilder output, int scalar) {
        if ("*?[]{}!,\\".indexOf(scalar) >= 0) output.append('\\');
        output.appendCodePoint(scalar);
    }
    private static SourceQueryException invalid() { return new SourceQueryException(Code.INVALID_ARGUMENT); }

    private static final class ClassParser {
        private final String input;
        private int offset;
        private ClassParser(String input, int offset) { this.input = input; this.offset = offset; }
        private State parse() {
            boolean negated = offset < input.length() && input.charAt(offset) == '!';
            if (negated) offset++;
            List<Integer> ranges = new ArrayList<>();
            while (offset < input.length() && input.charAt(offset) != ']') {
                boolean escaped = input.charAt(offset) == '\\';
                int first = scalar();
                int last = first;
                if ((first != '-' || escaped) && offset + 1 < input.length() && input.charAt(offset) == '-'
                        && input.charAt(offset + 1) != ']') {
                    offset++; last = scalar();
                    if (last < first) throw invalid();
                }
                ranges.add(first); ranges.add(last);
            }
            if (ranges.isEmpty() || offset == input.length()) throw invalid();
            offset++;
            return new State(Kind.CLASS, 0, ranges.stream().mapToInt(Integer::intValue).toArray(), negated);
        }
        private int scalar() {
            if (offset == input.length()) throw invalid();
            int scalar = input.codePointAt(offset); offset += Character.charCount(scalar);
            if (scalar == '\\') {
                if (offset == input.length()) throw invalid();
                scalar = input.codePointAt(offset); offset += Character.charCount(scalar);
                if (ESCAPABLE.indexOf(scalar) < 0 && scalar != '-' || scalar == '\\') throw invalid();
            } else if (scalar == '[' || scalar == ']') throw invalid();
            if (scalar == '/') throw invalid();
            return scalar;
        }
    }

    private static final class Expansion {
        private final String input;
        private int offset;
        private Expansion(String input) { this.input = input; }
        private List<String> sequence(int depth, boolean grouped) {
            List<String> result = new ArrayList<>(List.of(""));
            while (offset < input.length()) {
                char character = input.charAt(offset);
                if (grouped && (character == ',' || character == '}')) break;
                if (character == '}') throw invalid();
                List<String> part;
                if (character == '{') {
                    if (depth == 4) throw invalid();
                    offset++;
                    part = new ArrayList<>();
                    int alternatives = 0;
                    while (true) {
                        List<String> alternative = sequence(depth + 1, true);
                        if (alternative.contains("")) throw invalid();
                        part.addAll(alternative);
                        if (part.size() > 64 || offset == input.length()) throw invalid();
                        alternatives++;
                        if (input.charAt(offset++) == '}') break;
                    }
                    if (alternatives < 2) throw invalid();
                } else {
                    int start = offset++;
                    if (character == '\\') {
                        if (offset == input.length()) throw invalid();
                        offset += Character.charCount(input.codePointAt(offset));
                    } else if (character == '[') {
                        ClassParser parser = new ClassParser(input, offset);
                        parser.parse(); offset = parser.offset;
                    }
                    part = List.of(input.substring(start, offset));
                }
                if (result.size() > 64 / part.size()) throw invalid();
                List<String> product = new ArrayList<>();
                for (String prefix : result) for (String suffix : part) product.add(prefix + suffix);
                result = product;
            }
            return result;
        }
    }
}
