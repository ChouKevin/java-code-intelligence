package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.CandidateKind;
import com.java.semantic.model.index.IndexContract.Declaration;
import com.java.semantic.model.index.IndexContract.DeclarationKind;
import com.java.semantic.model.index.IndexContract.EntryCandidate;
import com.java.semantic.model.index.IndexContract.EvidenceKind;
import com.java.semantic.model.index.IndexContract.ParsedFile;
import com.java.semantic.model.index.IndexContract.ParseStatus;
import com.java.semantic.model.index.IndexContract.SourceSpan;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JavaSyntaxExtractorTest {
    private static final String GENERATION = "00000000-0000-0000-0000-000000000001";

    @Test
    void nested_callable_facts_keep_distinct_interiors_and_exclusive_raw_end_lines() {
        String sourceText = "class A { void outer() {\n"
                + " class Inner { void inner() {\n"
                + "  System.out.println(\"{}\");\n"
                + "}\n }\n"
                + "}\n}\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);
        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("A.java", source), source);
        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(parsed.folds()).hasSize(2);
        Declaration outer = declaration(parsed, "outer", DeclarationKind.METHOD);
        Declaration inner = declaration(parsed, "inner", DeclarationKind.METHOD);
        IndexContract.Fold outerFold = parsed.folds().stream().filter(fold -> fold.declarationId().equals(outer.symbolId())).findFirst().orElseThrow();
        IndexContract.Fold innerFold = parsed.folds().stream().filter(fold -> fold.declarationId().equals(inner.symbolId())).findFirst().orElseThrow();
        assertThat(outerFold.interior().contains(innerFold.interior())).isTrue();
        assertThat(innerFold.interior().endLine()).isEqualTo(3);
        assertThat(new String(source, Math.toIntExact(innerFold.interior().startByte()),
                Math.toIntExact(innerFold.interior().endByte() - innerFold.interior().startByte()), StandardCharsets.UTF_8))
                .isEqualTo("\n  System.out.println(\"{}\");\n");
    }

    @Test
    void callable_folds_exclude_raw_braces_and_preserve_escaped_spelling_and_crlf() {
        String escape = "\\";
        String sourceText = "record A(int value) {\r\n"
                + " A " + escape + "u007b\r\n  if (value < 0) throw new IllegalArgumentException();\r\n " + escape + "u007d\r\n"
                + " A() {\r\n  this(0);\r\n }\r\n"
                + " void " + escape + "u006d() " + escape + "u007b" + escape + "u000a  System.out.println(\"漢{}\");\r\n " + escape + "u007d\r\n"
                + " void empty() {}\r\n}\r\ninterface B { void absent(); }\r\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);
        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("A.java", source), source);
        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(parsed.folds()).hasSize(3);
        List<String> interiors = parsed.folds().stream().map(fold -> new String(source,
                Math.toIntExact(fold.interior().startByte()), Math.toIntExact(fold.interior().endByte() - fold.interior().startByte()),
                StandardCharsets.UTF_8)).toList();
        assertThat(interiors).containsExactly(
                "\r\n  if (value < 0) throw new IllegalArgumentException();\r\n ",
                "\r\n  this(0);\r\n ",
                escape + "u000a  System.out.println(\"漢{}\");\r\n ");
        Declaration method = declaration(parsed, "m", DeclarationKind.METHOD);
        assertRawSlice(method, source, escape + "u006d");
        assertThat(parsed.folds()).anySatisfy(fold -> {
            assertThat(fold.declarationId()).isEqualTo(method.symbolId());
            assertThat(fold.kind()).isEqualTo("METHOD");
            assertThat(method.range().contains(fold.interior())).isTrue();
            assertThat(fold.interior().startLine()).isEqualTo(8);
        });
        byte[] broken = "class A { void bad() {".getBytes(StandardCharsets.UTF_8);
        assertThat(new JavaSyntaxExtractor().parse(GENERATION, textEntry("A.java", broken), broken).folds()).isEmpty();
    }

    @Test
    void explicit_declarations_keep_overloads_components_and_exact_unicode_name_slices() {
        String sourceText = "package sample;\n"
                + "@interface Flags { String value() default \"enabled\"; }\n"
                + "interface Contract { void accept(); }\n"
                + "enum Mode { FAST, SLOW(1); Mode() {} Mode(int speed) {} }\n"
                + "record Person(String 名称, int 𐐀) { Person {} }\n"
                + "class Outer { int first = 1, second = 2; Outer() {} void run(int x) {} void run(String x) {} class Inner {} }\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);

        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("src/Outer.java", source), source);

        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(parsed.declarations().stream().map(Declaration::kind).collect(Collectors.toSet()))
                .contains(DeclarationKind.CLASS, DeclarationKind.INTERFACE, DeclarationKind.RECORD,
                        DeclarationKind.ENUM, DeclarationKind.ANNOTATION_TYPE, DeclarationKind.METHOD,
                        DeclarationKind.CONSTRUCTOR, DeclarationKind.FIELD, DeclarationKind.RECORD_COMPONENT,
                        DeclarationKind.ENUM_CONSTANT, DeclarationKind.ANNOTATION_MEMBER);
        List<Declaration> overloads = parsed.declarations().stream()
                .filter(declaration -> declaration.name().equals("run"))
                .toList();
        assertThat(overloads).hasSize(2);
        assertThat(overloads).extracting(Declaration::symbolId).doesNotHaveDuplicates();
        assertThat(overloads).extracting(Declaration::parentSymbolId)
                .containsOnly(Optional.of(declaration(parsed, "Outer", DeclarationKind.CLASS).symbolId()));
        assertThat(parsed.declarations()).filteredOn(declaration -> declaration.name().equals("first")
                || declaration.name().equals("second")).hasSize(2);
        assertThat(parsed.declarations()).filteredOn(declaration -> declaration.kind() == DeclarationKind.CONSTRUCTOR)
                .hasSize(4);
        assertThat(parsed.declarations()).noneMatch(declaration -> declaration.kind() == DeclarationKind.METHOD
                && (declaration.name().equals("getFirst") || declaration.name().equals("getSecond")
                        || declaration.name().equals("名称")));

        Declaration first = declaration(parsed, "first", DeclarationKind.FIELD);
        Declaration chineseComponent = declaration(parsed, "名称", DeclarationKind.RECORD_COMPONENT);
        Declaration supplementaryComponent = declaration(parsed, "𐐀", DeclarationKind.RECORD_COMPONENT);
        Declaration compactConstructor = declaration(parsed, "Person", DeclarationKind.CONSTRUCTOR);
        assertThat(chineseComponent.parentSymbolId())
                .contains(declaration(parsed, "Person", DeclarationKind.RECORD).symbolId());
        assertThat(supplementaryComponent.parentSymbolId()).isEqualTo(chineseComponent.parentSymbolId());
        assertThat(compactConstructor.parentSymbolId()).isEqualTo(chineseComponent.parentSymbolId());
        assertRawSlice(compactConstructor, source, "Person");
        assertRawSlice(first, source, "first");
        assertRawSlice(chineseComponent, source, "名称");
        assertRawSlice(supplementaryComponent, source, "𐐀");
    }

    @Test
    void unicode_preprocessing_maps_escapes_tabs_crlf_and_escaped_newlines_to_raw_bytes() {
        String sourceText = "package mapping;\r\n"
                + "class\tEscaped {\r\n"
                + "\tvoid \\u0061() {}\\u000a\tvoid second() {}\r\n"
                + "\tvoid \\uD801\\uDC00() {}\r\n"
                + "}\r\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);

        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("src/Escaped.java", source), source);

        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        Declaration escapedType = declaration(parsed, "Escaped", DeclarationKind.CLASS);
        Declaration escapedIdentifier = declaration(parsed, "a", DeclarationKind.METHOD);
        Declaration afterEscapedNewline = declaration(parsed, "second", DeclarationKind.METHOD);
        Declaration escapedSupplementaryIdentifier = declaration(parsed, "𐐀", DeclarationKind.METHOD);
        assertRawSlice(escapedType, source, "Escaped");
        assertRawSlice(escapedIdentifier, source, "\\u0061");
        assertRawSlice(escapedSupplementaryIdentifier, source, "\\uD801\\uDC00");
        assertThat(escapedIdentifier.nameRange().startLine()).isEqualTo(3);
        assertThat(afterEscapedNewline.nameRange().startLine()).isEqualTo(3);
        assertThat(escapedSupplementaryIdentifier.nameRange().startLine()).isEqualTo(4);
        assertThat(escapedType.nameRange().startByte()).isGreaterThan(0);
    }

    @Test
    void syntax_candidates_keep_annotation_evidence_separate_and_literal_values_unresolved() {
        String sourceText = "package candidates;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "@GetMapping(\"/api\")\n"
                + "class Api {\n"
                + "  @GetMapping(value = {\"/items\", \"/users\"}, headers = HEADERS)\n"
                + "  public String list() { return \"\"; }\n"
                + "  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5, zone = ZONE)\n"
                + "  void refresh() {}\n"
                + "  @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = {\"one\", \"two\"})\n"
                + "  void consume() {}\n"
                + "  @GetMapping(\"/main\")\n"
                + "  public static void main(java.lang.String... args) {}\n"
                + "}\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);

        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("src/Api.java", source), source);

        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        List<EntryCandidate> httpMappings = parsed.candidates().stream()
                .filter(candidate -> candidate.kind() == CandidateKind.HTTP_MAPPING)
                .toList();
        assertThat(httpMappings).hasSize(3);
        assertThat(httpMappings).extracting(candidate -> candidate.declaration().name())
                .containsExactlyInAnyOrder("Api", "list", "main");
        EntryCandidate classMapping = candidate(parsed, CandidateKind.HTTP_MAPPING, "Api");
        assertThat(classMapping.evidence().kind()).isEqualTo(EvidenceKind.DIRECT_IMPORT);
        assertThat(classMapping.evidence().literalValues()).containsEntry("value", List.of("/api"));
        EntryCandidate methodMapping = candidate(parsed, CandidateKind.HTTP_MAPPING, "list");
        assertThat(methodMapping.evidence().literalValues()).containsEntry("value", List.of("/items", "/users"));
        assertThat(methodMapping.evidence().unresolvedMembers()).containsExactly("headers");

        EntryCandidate scheduled = candidate(parsed, CandidateKind.SCHEDULED, "refresh");
        assertThat(scheduled.evidence().kind()).isEqualTo(EvidenceKind.FULLY_QUALIFIED);
        assertThat(scheduled.evidence().literalValues()).containsEntry("fixedDelay", List.of("5"));
        assertThat(scheduled.evidence().unresolvedMembers()).containsExactly("zone");
        EntryCandidate rabbitListener = candidate(parsed, CandidateKind.RABBIT_LISTENER, "consume");
        assertThat(rabbitListener.evidence().literalValues()).containsEntry("queues", List.of("one", "two"));
        EntryCandidate mainMapping = candidate(parsed, CandidateKind.HTTP_MAPPING, "main");
        assertThat(mainMapping.evidence().kind()).isEqualTo(EvidenceKind.DIRECT_IMPORT);
        EntryCandidate main = candidate(parsed, CandidateKind.MAIN, "main");
        assertThat(main.evidence().kind()).isEqualTo(EvidenceKind.MAIN_SIGNATURE);
        assertThat(main.evidence().annotation()).isEmpty();
        assertThat(parsed.candidates()).hasSize(6);
    }

    @Test
    void main_candidate_requires_written_public_even_in_an_interface() {
        byte[] source = """
                interface Implicit { static void main(String[] args) {} }
                interface Explicit { public static void main(String... args) {} }
                """.getBytes(StandardCharsets.UTF_8);
        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("Main.java", source), source);
        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(parsed.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.kind()).isEqualTo(CandidateKind.MAIN);
            assertThat(candidate.declaration().owner()).isEqualTo("Explicit");
        });
    }

    @Test
    void simple_annotation_candidates_are_ambiguous_when_local_or_wildcard_names_are_unresolved() {
        String sourceText = "package ambiguity;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.scheduling.annotation.*;\n"
                + "import com.example.RabbitListener;\n"
                + "class Routes {\n"
                + "  @GetMapping(\"/items\") void route() {}\n"
                + "  @interface GetMapping {}\n"
                + "  @Scheduled(fixedDelay = DELAY) void scheduled() {}\n"
                + "  @org.springframework.web.bind.annotation.GetMapping(\"/qualified\") void qualified() {}\n"
                + "  @com.example.GetMapping(\"/ignored\") void unsupported() {}\n"
                + "  @RabbitListener(queues = \"ignored\") void unsupportedImport() {}\n"
                + "}\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);

        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("src/Routes.java", source), source);

        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(parsed.candidates()).hasSize(3);
        assertThat(candidate(parsed, CandidateKind.HTTP_MAPPING, "route").evidence().kind())
                .isEqualTo(EvidenceKind.AMBIGUOUS_SIMPLE);
        assertThat(candidate(parsed, CandidateKind.SCHEDULED, "scheduled").evidence().kind())
                .isEqualTo(EvidenceKind.AMBIGUOUS_SIMPLE);
        assertThat(candidate(parsed, CandidateKind.HTTP_MAPPING, "qualified").evidence().kind())
                .isEqualTo(EvidenceKind.FULLY_QUALIFIED);
        assertThat(parsed.candidates()).noneMatch(candidate -> candidate.declaration().name().equals("unsupported"));
        assertThat(parsed.candidates()).noneMatch(candidate ->
                candidate.declaration().name().equals("unsupportedImport"));
    }

    @Test
    void malformed_source_is_excluded_and_oversized_java_is_not_parsed() {
        byte[] malformedSource = "class Broken { void exposed( }".getBytes(StandardCharsets.UTF_8);
        ParsedFile malformed = new JavaSyntaxExtractor().parse(GENERATION,
                textEntry("src/Broken.java", malformedSource), malformedSource);

        assertThat(malformed.file().status()).isEqualTo(ParseStatus.PARSE_FAILED);
        assertThat(malformed.declarations()).isEmpty();
        assertThat(malformed.candidates()).isEmpty();
        assertThat(malformed.file().diagnostics()).isNotEmpty().hasSizeLessThanOrEqualTo(IndexContract.MAX_DIAGNOSTICS);
        assertThat(malformed.file().diagnostics()).allSatisfy(diagnostic ->
                assertThat(diagnostic.code()).matches("[A-Z][A-Z0-9_]{0,63}"));
        byte[] invalidUtf8Source = {(byte) 0xc3, 0x28};
        ParsedFile invalidUtf8 = new JavaSyntaxExtractor().parse(GENERATION,
                textEntry("src/InvalidUtf8.java", invalidUtf8Source), invalidUtf8Source);
        assertThat(invalidUtf8.file().status()).isEqualTo(ParseStatus.PARSE_FAILED);
        assertThat(invalidUtf8.file().diagnostics()).hasSize(1);
        assertThat(invalidUtf8.file().diagnostics().get(0).code()).isEqualTo("INVALID_UTF8");
        assertThat(invalidUtf8.declarations()).isEmpty();
        assertThat(invalidUtf8.candidates()).isEmpty();

        byte[] oversizedSource = new byte[IndexContract.MAX_FILE_BYTES + 1];
        SourceInventoryEntry oversizedEntry = new SourceInventoryEntry("src/Large.java", EntryKind.FILE,
                Optional.of(EntryStatus.BINARY), oversizedSource.length, Optional.of("a".repeat(40)),
                Optional.of(sha256(oversizedSource)));
        ParsedFile oversized = new JavaSyntaxExtractor().parse(GENERATION, oversizedEntry, oversizedSource);
        assertThat(oversized.file().status()).isEqualTo(ParseStatus.TOO_LARGE);
        assertThat(oversized.declarations()).isEmpty();
        assertThat(oversized.candidates()).isEmpty();
    }

    @Test
    void local_and_anonymous_owners_have_source_position_segments_and_nearest_declaration_parents() {
        String sourceText = "package ownership;\n"
                + "class Outer {\n"
                + "  void run() {\n"
                + "    class Local { void local() {} }\n"
                + "    Object value = new Object() { void anonymous() {} };\n"
                + "  }\n"
                + "}\n";
        byte[] source = sourceText.getBytes(StandardCharsets.UTF_8);

        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("src/Outer.java", source), source);

        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        Declaration run = declaration(parsed, "run", DeclarationKind.METHOD);
        Declaration localType = declaration(parsed, "Local", DeclarationKind.CLASS);
        Declaration localMethod = declaration(parsed, "local", DeclarationKind.METHOD);
        Declaration anonymousMethod = declaration(parsed, "anonymous", DeclarationKind.METHOD);
        assertThat(localType.parentSymbolId()).contains(run.symbolId());
        assertThat(localMethod.parentSymbolId()).contains(localType.symbolId());
        assertThat(anonymousMethod.parentSymbolId()).contains(run.symbolId());
        assertThat(localType.owner()).matches("ownership\\.Outer#method:run@\\d+");
        assertThat(localMethod.owner()).containsPattern("#local:Local@\\d+");
        assertThat(anonymousMethod.owner()).containsPattern("#anonymous@\\d+");
    }

    @Test
    void java21_record_patterns_and_guarded_switch_are_supported_but_preview_templates_are_not() {
        byte[] supported = """
                record Point(int x, int y) {}
                class Match {
                    int sum(Object value) {
                        return switch (value) {
                            case Point(int x, int y) when x > 0 -> x + y;
                            case Point(int x, int y) -> y;
                            case null -> 0;
                            default -> -1;
                        };
                    }
                }
                """.getBytes(StandardCharsets.UTF_8);
        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("Match.java", supported), supported);
        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        assertRawSlice(declaration(parsed, "sum", DeclarationKind.METHOD), supported, "sum");
        byte[] preview = """
                class Preview { String value(String name) { return STR."hello \\{name}"; } }
                """.getBytes(StandardCharsets.UTF_8);
        ParsedFile rejected = new JavaSyntaxExtractor().parse(GENERATION, textEntry("Preview.java", preview), preview);
        assertThat(rejected.file().status()).isEqualTo(ParseStatus.PARSE_FAILED);
        assertThat(rejected.declarations()).isEmpty();
    }

    @Test
    void ordinary_escapes_and_unicode_backslash_eligibility_preserve_raw_evidence() {
        byte[] source = ("class Escapes {\r\n"
                + " @org.springframework.amqp.rabbit.annotation.RabbitListener(queues={"
                + "\"quote\\\"tail\", \"slash\\\\tail\", \"line\\nend\", \"even\\\\u0061\", "
                + "\"escaped\\uuuu0061\", \"twice\\u005c\\u005c\"})\r\n"
                + " void \\u0061() {}\r\n int following;\r\n}\r\n").getBytes(StandardCharsets.UTF_8);
        ParsedFile parsed = new JavaSyntaxExtractor().parse(GENERATION, textEntry("Escapes.java", source), source);
        assertThat(parsed.file().status()).isEqualTo(ParseStatus.PARSED);
        EntryCandidate listener = candidate(parsed, CandidateKind.RABBIT_LISTENER, "a");
        assertThat(listener.evidence().literalValues().get("queues"))
                .containsExactly("quote\"tail", "slash\\tail", "line\nend", "even\\u0061", "escapeda", "twice\\");
        assertRawSlice(listener.declaration(), source, "\\u0061");
        assertRawSlice(declaration(parsed, "following", DeclarationKind.FIELD), source, "following");
        SourceSpan evidence = listener.evidence().range();
        String rawAnnotation = new String(source, Math.toIntExact(evidence.startByte()),
                Math.toIntExact(evidence.endByte() - evidence.startByte()), StandardCharsets.UTF_8);
        assertThat(rawAnnotation).startsWith("@org.springframework").endsWith("\"twice\\u005c\\u005c\"})");
        assertThat(evidence.startLine()).isEqualTo(2);
        assertThat(evidence.endLine()).isEqualTo(2);
    }

    @Test
    void malformed_eligible_unicode_escapes_fail_without_facts_even_inside_comments() {
        JavaSyntaxExtractor parser = new JavaSyntaxExtractor();
        for (String escape : List.of("\\u00G0", "\\uu123", "\\u１２３４")) {
            byte[] source = ("class Broken {} // " + escape + "\n").getBytes(StandardCharsets.UTF_8);
            ParsedFile failed = parser.parse(GENERATION, textEntry("Broken.java", source), source);
            assertThat(failed.file().status()).isEqualTo(ParseStatus.PARSE_FAILED);
            assertThat(failed.declarations()).isEmpty();
            assertThat(failed.candidates()).isEmpty();
        }
        byte[] valid = "class Valid { String literal=\"\\\\u00G0\"; }".getBytes(StandardCharsets.UTF_8);
        ParsedFile following = parser.parse(GENERATION, textEntry("Valid.java", valid), valid);
        assertThat(following.file().status()).isEqualTo(ParseStatus.PARSED);
        assertRawSlice(declaration(following, "literal", DeclarationKind.FIELD), valid, "literal");
    }

    @Test
    void translated_backslashes_cannot_start_a_second_unicode_translation() {
        JavaSyntaxExtractor parser = new JavaSyntaxExtractor();
        for (String text : List.of(
                "class Broken { String value=\"\\u005cu005a\"; }",
                "class Broken { char value='\\u005cu005a'; }",
                "class Broken { String value=\"\"\"\n\\u005cu005a\n\"\"\"; }",
                "class \\u005cu0042 {}",
                "package \\u005cu0070; class Broken {}")) {
            byte[] source = text.getBytes(StandardCharsets.UTF_8);
            ParsedFile failed = parser.parse(GENERATION, textEntry("Broken.java", source), source);
            assertThat(failed.file().status()).isEqualTo(ParseStatus.PARSE_FAILED);
            assertThat(failed.declarations()).isEmpty();
            assertThat(failed.candidates()).isEmpty();
        }
        byte[] valid = ("class Valid { @org.springframework.amqp.rabbit.annotation.RabbitListener("
                + "queues=\"\\u005c\\u005cu005a\") void listen() {} }").getBytes(StandardCharsets.UTF_8);
        ParsedFile following = parser.parse(GENERATION, textEntry("Valid.java", valid), valid);
        assertThat(following.file().status()).isEqualTo(ParseStatus.PARSED);
        assertThat(candidate(following, CandidateKind.RABBIT_LISTENER, "listen").evidence().literalValues().get("queues"))
                .containsExactly("\\u005a");
    }

    @Test
    void unrepresentable_utf16_values_are_file_local_and_valid_pairs_remain_lossless() {
        JavaSyntaxExtractor parser = new JavaSyntaxExtractor();
        for (String text : List.of(
                "class A { @org.springframework.amqp.rabbit.annotation.RabbitListener(queues=\"\\uD800\") void listen() {} }",
                "class A { @org.springframework.amqp.rabbit.annotation.RabbitListener(queues=\"\\uDC00\") void listen() {} }",
                "class A { @org.springframework.amqp.rabbit.annotation.RabbitListener(queues='\\uD800') void listen() {} }",
                "class A { @org.springframework.amqp.rabbit.annotation.RabbitListener(queues=\"\"\"\n\\uD800\n\"\"\") void listen() {} }",
                "@interface A { String value() default \"\\uD800\"; }",
                "enum A { ITEM(\"\\uD800\"); A(String value) {} }")) {
            byte[] source = text.getBytes(StandardCharsets.UTF_8);
            ParsedFile unsupported = parser.parse(GENERATION, textEntry("A.java", source), source);
            assertThat(unsupported.file().status()).isEqualTo(ParseStatus.UNSUPPORTED);
            assertThat(unsupported.file().diagnostics()).extracting(IndexContract.Diagnostic::code)
                    .containsExactly("UNSUPPORTED_UNICODE_VALUE");
            assertThat(unsupported.declarations()).isEmpty();
            assertThat(unsupported.candidates()).isEmpty();
        }
        byte[] valid = ("class Following { @org.springframework.amqp.rabbit.annotation.RabbitListener("
                + "queues=\"\\uD83D\\uDE00\") void listen() {} }").getBytes(StandardCharsets.UTF_8);
        ParsedFile following = parser.parse(GENERATION, textEntry("Following.java", valid), valid);
        assertThat(following.file().status()).isEqualTo(ParseStatus.PARSED);
        EntryCandidate listener = candidate(following, CandidateKind.RABBIT_LISTENER, "listen");
        assertThat(listener.evidence().literalValues().get("queues")).containsExactly(Character.toString(0x1f600));
        assertRawSlice(listener.declaration(), valid, "listen");
    }

    @Test
    void deeply_nested_input_never_leaks_partial_facts_or_poisoning_into_the_next_file() {
        JavaSyntaxExtractor parser = new JavaSyntaxExtractor();
        byte[] nested = ("class Nested { int value() { return " + "(".repeat(4096)
                + "1" + ")".repeat(4096) + "; } }").getBytes(StandardCharsets.UTF_8);
        ParsedFile outcome = parser.parse(GENERATION, textEntry("Nested.java", nested), nested);
        assertThat(outcome.file().status()).isIn(ParseStatus.PARSED, ParseStatus.PARSE_FAILED);
        if (outcome.file().status() == ParseStatus.PARSE_FAILED) {
            assertThat(outcome.declarations()).isEmpty();
            assertThat(outcome.candidates()).isEmpty();
        } else {
            assertRawSlice(declaration(outcome, "value", DeclarationKind.METHOD), nested, "value");
        }

        byte[] following = "class Following { int intact; }".getBytes(StandardCharsets.UTF_8);
        ParsedFile recovered = parser.parse(GENERATION, textEntry("Following.java", following), following);
        assertThat(recovered.file().status()).isEqualTo(ParseStatus.PARSED);
        assertRawSlice(declaration(recovered, "intact", DeclarationKind.FIELD), following, "intact");
        assertThat(recovered.declarations()).extracting(Declaration::name).containsExactly("Following", "intact");
    }

    private static Declaration declaration(ParsedFile parsed, String name, DeclarationKind kind) {
        return parsed.declarations().stream()
                .filter(declaration -> declaration.name().equals(name) && declaration.kind() == kind)
                .findFirst().orElseThrow();
    }

    private static EntryCandidate candidate(ParsedFile parsed, CandidateKind kind, String declarationName) {
        return parsed.candidates().stream()
                .filter(candidate -> candidate.kind() == kind
                        && candidate.declaration().name().equals(declarationName))
                .findFirst().orElseThrow();
    }

    private static SourceInventoryEntry textEntry(String path, byte[] source) {
        return new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(EntryStatus.TEXT), source.length,
                Optional.of("a".repeat(40)), Optional.of(sha256(source)));
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertRawSlice(Declaration declaration, byte[] source, String expected) {
        SourceSpan span = declaration.nameRange();
        byte[] rawName = Arrays.copyOfRange(source, Math.toIntExact(span.startByte()), Math.toIntExact(span.endByte()));
        assertThat(rawName).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    }
}
