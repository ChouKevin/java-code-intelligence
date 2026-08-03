package com.java.semantic.mcp;

import com.java.semantic.repository.application.ImmutableFixtureException;
import com.java.semantic.repository.application.RepositoryBusyException;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.InvalidRepositoryIdException;
import com.java.semantic.semantic.application.ImplementationTargetUnsupportedException;
import com.java.semantic.semantic.application.SourceDeclarationNotFoundException;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.application.EvidenceSourceNotFoundException;
import com.java.semantic.syntax.application.SourceSegmentNotFoundException;
import com.java.semantic.syntax.application.TypeMemberTypeNotFoundException;
import com.java.semantic.syntax.application.concept.ConceptIdentityNotFoundException;
import com.java.semantic.syntax.application.concept.ConceptKindUnavailableException;
import com.java.semantic.trie.ApiRouteIndexNotReadyException;

import java.util.Optional;

/** 將既有查詢失敗分類投影為 MCP 專用且不洩漏細節的工具回應 */
public final class McpToolFailureMapper {

    private static final McpToolFailure.Recovery RETRYABLE = new McpToolFailure.Recovery(true);
    private static final McpToolFailure.Recovery NOT_RETRYABLE = new McpToolFailure.Recovery(false);

    private McpToolFailureMapper() {
        throw new UnsupportedOperationException("utility class");
    }

    public static Optional<McpToolFailure> failureFor(RuntimeException exception) {
        if (exception instanceof InvalidRepositoryIdException) {
            return Optional.of(failure("REQUEST_INVALID", "repository id is invalid", NOT_RETRYABLE));
        }
        if (exception instanceof RepositoryNotFoundException) {
            return Optional.of(failure("REPOSITORY_NOT_FOUND", "repository is not configured", NOT_RETRYABLE));
        }
        if (exception instanceof EvidenceSourceNotFoundException) {
            return Optional.of(failure("EVIDENCE_SOURCE_NOT_FOUND", "typed evidence was not found", NOT_RETRYABLE));
        }
        if (exception instanceof SourceDeclarationNotFoundException) {
            return Optional.of(failure(
                    "SOURCE_DECLARATION_NOT_FOUND", "exact source declaration was not found", NOT_RETRYABLE));
        }
        if (exception instanceof SourceSegmentNotFoundException) {
            return Optional.of(failure("SOURCE_SEGMENT_NOT_FOUND", "source segment was not found", NOT_RETRYABLE));
        }
        if (exception instanceof TypeMemberTypeNotFoundException) {
            return Optional.of(failure("TYPE_MEMBER_TYPE_NOT_FOUND", "type was not found", NOT_RETRYABLE));
        }
        if (exception instanceof ConceptIdentityNotFoundException) {
            return Optional.of(failure("CONCEPT_IDENTITY_NOT_FOUND", "concept identity was not found", NOT_RETRYABLE));
        }
        if (exception instanceof RepositoryBusyException || exception instanceof RepositoryNotReadyException
                || exception instanceof ImmutableFixtureException) {
            return Optional.of(failure("REPOSITORY_NOT_READY", "repository is not ready", RETRYABLE));
        }
        if (exception instanceof RepositoryRevisionMismatchException mismatch) {
            return Optional.of(new McpToolFailure(
                    "REPOSITORY_REVISION_MISMATCH",
                    "expected revision does not match current revision",
                    RETRYABLE,
                    mismatch.getExpectedRevision().value(),
                    mismatch.getCurrentRevision().value()));
        }
        if (exception instanceof ApiRouteIndexNotReadyException) {
            return Optional.of(failure("API_ROUTE_INDEX_NOT_READY", "API route index is not ready", RETRYABLE));
        }
        if (exception instanceof RepositoryMutationException) {
            return Optional.of(failure("INTERNAL_ERROR", "request failed", NOT_RETRYABLE));
        }
        if (exception instanceof SemanticTargetNotFoundException) {
            return Optional.of(targetFailure(
                    "SEMANTIC_TARGET_NOT_FOUND", "exact semantic target was not found"));
        }
        if (exception instanceof ImplementationTargetUnsupportedException) {
            return Optional.of(targetFailure(
                    "IMPLEMENTATION_TARGET_UNSUPPORTED",
                    "requested method does not support implementation discovery"));
        }
        if (exception instanceof SemanticBindingUnresolvedException) {
            return Optional.of(targetFailure(
                    "SEMANTIC_BINDING_UNRESOLVED", "exact semantic target binding is unresolved"));
        }
        if (exception instanceof SemanticBindingAmbiguousException) {
            return Optional.of(failure(
                    "SEMANTIC_BINDING_AMBIGUOUS", "exact semantic target binding is ambiguous", NOT_RETRYABLE));
        }
        if (exception instanceof SemanticEngineNotReadyException || exception instanceof SemanticEngineStartFailedException) {
            return Optional.of(failure(
                    "SEMANTIC_ENGINE_START_FAILED", "semantic engine failed to start", RETRYABLE));
        }
        if (exception instanceof SemanticRequestTimeoutException) {
            return Optional.of(failure("SEMANTIC_REQUEST_TIMEOUT", "semantic request timed out", RETRYABLE));
        }
        if (exception instanceof SemanticProtocolException) {
            return Optional.of(failure("SEMANTIC_PROTOCOL_ERROR", "semantic protocol request failed", RETRYABLE));
        }
        if (exception instanceof ConceptKindUnavailableException) {
            return Optional.of(failure(
                    "CONCEPT_KIND_UNAVAILABLE", "requested concept kind is not active", NOT_RETRYABLE));
        }
        return Optional.empty();
    }

    public static boolean isKnownFailure(RuntimeException exception) {
        return failureFor(exception).isPresent();
    }

    public static McpToolFailure internalFailure() {
        return failure("INTERNAL_ERROR", "request failed", NOT_RETRYABLE);
    }

    private static McpToolFailure failure(String code, String message, McpToolFailure.Recovery recovery) {
        return new McpToolFailure(code, message, recovery, null, null);
    }

    private static McpToolFailure targetFailure(String code, String message) {
        return failure(code, message, NOT_RETRYABLE);
    }
}
