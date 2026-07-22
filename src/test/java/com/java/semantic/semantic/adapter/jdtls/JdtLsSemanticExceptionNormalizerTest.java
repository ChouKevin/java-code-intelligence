package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticEngineException;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class JdtLsSemanticExceptionNormalizerTest {

    private static final String SECRET = "SECRET_SERVER_PAYLOAD";

    @ParameterizedTest
    @MethodSource("adapterFailures")
    void should_classify_each_adapter_failure_without_retaining_unsafe_throwable(
            RuntimeException adapterFailure,
            Class<? extends SemanticEngineException> expectedType,
            String expectedCode) {
        adapterFailure.addSuppressed(new IllegalStateException(SECRET));

        Throwable thrown = catchThrowable(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw adapterFailure;
        }));

        assertThat(thrown).isExactlyInstanceOf(expectedType);
        SemanticEngineException normalized = (SemanticEngineException) thrown;
        assertThat(normalized.errorCode()).isEqualTo(expectedCode);
        assertThat(normalized.getCause()).isNull();
        assertThat(normalized.getSuppressed()).isEmpty();
        assertThat(normalized.toString()).doesNotContain(SECRET);
    }

    static Stream<Arguments> adapterFailures() {
        return Stream.of(
                Arguments.of(
                        new JdtLsReadinessProbe.JdtWorkspaceStartupException(
                                RepositoryId.of("orders"), SECRET, SECRET, SECRET),
                        SemanticEngineStartFailedException.class,
                        "SEMANTIC_ENGINE_START_FAILED"),
                Arguments.of(
                        new DefaultJdtWorkspaceManager.JdtWorkspaceManagerStoppedException(
                                RepositoryId.of("orders")),
                        SemanticEngineNotReadyException.class,
                        "SEMANTIC_ENGINE_NOT_READY"),
                Arguments.of(
                        new DefaultJdtWorkspaceManager.JdtWorkspaceCapacityException(
                                RepositoryId.of("orders")),
                        SemanticEngineNotReadyException.class,
                        "SEMANTIC_ENGINE_NOT_READY"),
                Arguments.of(
                        new DefaultJdtWorkspaceManager.JdtWorkspaceTerminationPendingException(
                                RepositoryId.of("orders")),
                        SemanticEngineNotReadyException.class,
                        "SEMANTIC_ENGINE_NOT_READY"),
                Arguments.of(
                        new JdtWorkspaceSession.JdtWorkspaceClosingException(SECRET),
                        SemanticEngineNotReadyException.class,
                        "SEMANTIC_ENGINE_NOT_READY"),
                Arguments.of(
                        new JdtWorkspaceSession.JdtRequestTimeoutException(
                                SECRET, new IllegalStateException(SECRET)),
                        SemanticRequestTimeoutException.class,
                        "SEMANTIC_REQUEST_TIMEOUT"),
                Arguments.of(
                        new JdtWorkspaceSession.JdtRequestFailedException(
                                SECRET, new IllegalStateException(SECRET)),
                        SemanticProtocolException.class,
                        "SEMANTIC_PROTOCOL_ERROR"));
    }

    @Test
    void should_classify_timeout_and_closing_before_request_failure_base_type() {
        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw new JdtWorkspaceSession.JdtRequestTimeoutException("secret", null);
        })).isExactlyInstanceOf(SemanticRequestTimeoutException.class);

        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw new JdtWorkspaceSession.JdtWorkspaceClosingException("secret");
        })).isExactlyInstanceOf(SemanticEngineNotReadyException.class);
    }

    @Test
    void should_unwrap_completion_and_response_errors_as_protocol_failures() {
        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw new CompletionException(new ResponseErrorException(
                    new ResponseError(ResponseErrorCode.InternalError, SECRET, SECRET)));
        })).isExactlyInstanceOf(SemanticProtocolException.class)
                .hasNoCause();
    }

    @Test
    void should_preserve_exact_target_contract_failures_without_exposing_target_text() {
        SemanticTargetNotFoundException failure = new SemanticTargetNotFoundException(new MethodTarget(
                "src/main/java/com/acme/Secret.java",
                "com.acme",
                "Secret",
                "run",
                List.of()));

        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(failure.getMessage()).doesNotContain("Secret.java");
    }

    @Test
    void should_preserve_direct_and_completion_wrapped_ambiguous_type_identity() {
        SemanticAmbiguousTypeException direct =
                new SemanticAmbiguousTypeException("com.acme", "Duplicate");
        SemanticAmbiguousTypeException wrapped =
                new SemanticAmbiguousTypeException("com.acme", "Duplicate");

        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw direct;
        })).isSameAs(direct);
        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw new CompletionException(wrapped);
        })).isSameAs(wrapped);
    }

    @Test
    void should_not_intercept_direct_or_completion_wrapped_fatal_jvm_errors() {
        OutOfMemoryError direct = new OutOfMemoryError("fatal direct sentinel");
        OutOfMemoryError wrapped = new OutOfMemoryError("fatal wrapped sentinel");

        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw direct;
        })).isSameAs(direct);
        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw new CompletionException(wrapped);
        })).isSameAs(wrapped);
    }

    @Test
    void should_sanitize_nonfatal_errors_as_protocol_failures() {
        ServiceConfigurationError failure =
                new ServiceConfigurationError("SECRET_NONFATAL_ERROR");

        assertThatThrownBy(() -> JdtLsSemanticExceptionNormalizer.normalize(() -> {
            throw failure;
        })).isExactlyInstanceOf(SemanticProtocolException.class)
                .hasNoCause()
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty())
                .satisfies(thrown -> assertThat(thrown.toString())
                        .doesNotContain("SECRET_NONFATAL_ERROR"));
    }
}
