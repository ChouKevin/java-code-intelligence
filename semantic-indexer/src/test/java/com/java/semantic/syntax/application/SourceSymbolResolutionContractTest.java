package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** source symbol typed candidate 與位置證據的 application 契約 */
class SourceSymbolResolutionContractTest {

    private static final String SOURCE_FILE = "src/main/java/com/acme/OrderService.java";
    private static final SourceRange DECLARATION = new SourceRange(
            SOURCE_FILE, new SyntaxRange(new SyntaxPosition(2, 4), new SyntaxPosition(2, 30)));
    private static final SourceRange OCCURRENCE = new SourceRange(
            SOURCE_FILE, new SyntaxRange(new SyntaxPosition(6, 8), new SyntaxPosition(6, 19)));

    @Test
    void should_keep_each_identity_family_and_static_constant_payload_in_its_candidate_variant() {
        SourceMemberIdentity member = new SourceMemberIdentity.TypeMember(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"), SOURCE_FILE),
                "fraudPolicy");
        MethodTarget method = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        SOURCE_FILE),
                "confirm",
                List.of("com.acme.Order"));
        SourceTypeIdentity type = new SourceTypeIdentity(
                new JavaTypeIdentity("com.acme", "FraudPolicy"), SOURCE_FILE);

        List<SourceSymbolCandidate> candidates = List.of(
                new SourceSymbolCandidate.VariableLike(
                        SourceSymbolKind.FIELD, "fraudPolicy", member, "FraudPolicy",
                        Optional.of("com.acme.FraudPolicy"), DECLARATION, OCCURRENCE, 1),
                new SourceSymbolCandidate.StaticConstant(
                        "TOPIC", new SourceMemberIdentity.TypeMember(
                                new SourceTypeIdentity(
                                        new JavaTypeIdentity("com.acme", "Topics"), SOURCE_FILE),
                                "TOPIC"),
                        "String", Optional.of("java.lang.String"), "\"order.created\"",
                        DECLARATION, OCCURRENCE, 1),
                new SourceSymbolCandidate.Method(method, DECLARATION, OCCURRENCE, 1),
                new SourceSymbolCandidate.SourceType(type, DECLARATION, OCCURRENCE, 1));

        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.declarationRange()).isEqualTo(DECLARATION);
            assertThat(candidate.representativeOccurrence()).isEqualTo(OCCURRENCE);
            assertThat(candidate.occurrenceCount()).isEqualTo(1);
        });
        assertThat(candidates).extracting(SourceSymbolCandidate::kind).containsExactly(
                SourceSymbolKind.FIELD,
                SourceSymbolKind.STATIC_CONSTANT,
                SourceSymbolKind.METHOD,
                SourceSymbolKind.SOURCE_TYPE);
        assertThat(candidates.get(1)).isInstanceOfSatisfying(
                SourceSymbolCandidate.StaticConstant.class,
                constant -> assertThat(constant.initializerSource()).isEqualTo("\"order.created\""));
        assertThat(candidates.getFirst()).isNotInstanceOf(SourceSymbolCandidate.StaticConstant.class);

        List<SourceContextCandidate> contexts = List.of(
                new SourceTypeContextCandidate(SOURCE_FILE),
                new SourceMethodContextCandidate(method));
        assertThat(contexts).extracting(SourceContextCandidate::kind)
                .containsExactly(SourceSymbolKind.SOURCE_TYPE, SourceSymbolKind.METHOD);
        assertThat(contexts.get(1)).isInstanceOfSatisfying(
                SourceMethodContextCandidate.class,
                context -> assertThat(context.target()).isEqualTo(method));
    }
}
