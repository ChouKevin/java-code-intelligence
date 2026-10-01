package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticRelationProjectorTest {

    @Test
    void should_identify_each_unresolved_call_in_a_chained_receiver(@TempDir Path repository) throws IOException {
        List<SyntaxInvocation> invocations = extractInvocations(repository, """
                package com.example;
                class Caller {
                    void call(Context context, String value) {
                        context.getMessages().add(new SimpleMessage(value));
                    }
                }
                """);
        SyntaxInvocation outer = invocation(invocations, "context.getMessages().add(new SimpleMessage(value))");
        SyntaxInvocation inner = invocation(invocations, "context.getMessages()");

        ExternalTarget.UnresolvedCall outerTarget = SemanticRelationProjector.unresolvedCall(outer);
        ExternalTarget.UnresolvedCall innerTarget = SemanticRelationProjector.unresolvedCall(inner);

        assertThat(outerTarget.methodName()).isEqualTo("add");
        assertThat(outerTarget.arity()).isEqualTo(1);
        assertThat(outerTarget.expression()).isEqualTo("context.getMessages().add(new SimpleMessage(value))");
        assertThat(outerTarget.receiver()).isEqualTo("context.getMessages()");
        assertThat(innerTarget.methodName()).isEqualTo("getMessages");
        assertThat(innerTarget.arity()).isZero();
        assertThat(innerTarget.expression()).isEqualTo("context.getMessages()");
        assertThat(innerTarget.receiver()).isEqualTo("context");
        assertThat(outer.range()).isEqualTo(new SyntaxRange(new SyntaxPosition(3, 8), new SyntaxPosition(3, 59)));
        assertThat(inner.range()).isEqualTo(new SyntaxRange(new SyntaxPosition(3, 8), new SyntaxPosition(3, 29)));
        assertThat(outer.resolutionAnchor()).isEqualTo(new SyntaxPosition(3, 30));
        assertThat(inner.resolutionAnchor()).isEqualTo(new SyntaxPosition(3, 16));
        assertThat(outer.resolvedTarget()).isEmpty();
        assertThat(inner.resolvedTarget()).isEmpty();
    }

    @Test
    void should_count_a_comma_inside_a_string_as_one_unresolved_argument(@TempDir Path repository) throws IOException {
        List<SyntaxInvocation> invocations = extractInvocations(repository, """
                package com.example;
                class Caller {
                    void call(Context context) {
                        context.accept("first,second");
                    }
                }
                """);

        ExternalTarget.UnresolvedCall target = SemanticRelationProjector.unresolvedCall(
                invocation(invocations, "context.accept(\"first,second\")"));

        assertThat(target.methodName()).isEqualTo("accept");
        assertThat(target.arity()).isEqualTo(1);
    }

    @Test
    void should_keep_reference_and_inline_body_call_labels_compact(@TempDir Path repository) throws IOException {
        List<SyntaxInvocation> invocations = extractInvocations(repository, """
                package com.example;
                class Caller {
                    void call(Context context) {
                        Runnable reference = context.getWorker()::consume;
                        Object creation = new Missing("first,second") {
                            void inlineBody() { }
                        };
                        context.accept((left, right) -> { context.consume(left); });
                    }
                }
                """);
        SyntaxInvocation reference = invocations.stream()
                .filter(call -> call.kind() == SyntaxInvocation.InvocationKind.METHOD_REFERENCE)
                .findFirst().orElseThrow();
        SyntaxInvocation constructor = invocations.stream()
                .filter(call -> call.kind() == SyntaxInvocation.InvocationKind.CONSTRUCTOR)
                .findFirst().orElseThrow();
        SyntaxInvocation lambdaConsumer = invocations.stream()
                .filter(call -> call.expression().startsWith("context.accept("))
                .findFirst().orElseThrow();

        ExternalTarget.UnresolvedCall referenceTarget = SemanticRelationProjector.unresolvedCall(reference);
        ExternalTarget.UnresolvedCall constructorTarget = SemanticRelationProjector.unresolvedCall(constructor);
        ExternalTarget.UnresolvedCall lambdaTarget = SemanticRelationProjector.unresolvedCall(lambdaConsumer);

        assertThat(referenceTarget.methodName()).isEqualTo("consume");
        assertThat(referenceTarget.arity()).isZero();
        assertThat(constructorTarget.methodName()).isEqualTo("Missing");
        assertThat(constructorTarget.arity()).isEqualTo(1);
        assertThat(lambdaTarget.methodName()).isEqualTo("accept");
        assertThat(lambdaTarget.arity()).isEqualTo(1);
        assertThat(invocations).noneMatch(call -> call.kind() == SyntaxInvocation.InvocationKind.LAMBDA);
    }


    private static List<SyntaxInvocation> extractInvocations(Path repository, String source) throws IOException {
        Path sourceRoot = repository.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Caller.java"), source);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(repository);
        return syntax.sourceTypes().stream()
                .flatMap(type -> type.members().methods().stream())
                .filter(method -> method.name().equals("call"))
                .findFirst().orElseThrow().invocations();
    }

    private static SyntaxInvocation invocation(List<SyntaxInvocation> invocations, String expression) {
        return invocations.stream().filter(invocation -> invocation.expression().equals(expression))
                .findFirst().orElseThrow();
    }
}
