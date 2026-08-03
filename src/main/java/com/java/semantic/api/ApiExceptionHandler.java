package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.api.dto.ConceptKindUnavailableResponse;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.identity.MethodTarget;
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
import com.java.semantic.syntax.application.concept.ConceptKindUnavailableException;
import com.java.semantic.syntax.application.concept.ConceptIdentityNotFoundException;
import com.java.semantic.syntax.application.TypeMemberTypeNotFoundException;
import com.java.semantic.syntax.application.SourceSegmentNotFoundException;
import com.java.semantic.trie.ApiRouteIndexNotReadyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Maps typed semantic failures to the fixed, client-safe error envelope. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidRepositoryIdException.class)
    public ResponseEntity<ApiErrorResponse> invalidRepositoryId(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "repository id is invalid", request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "request body is invalid", request);
    }

    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> repositoryNotFound(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "repository is not configured", request);
    }

    @ExceptionHandler(EvidenceSourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> evidenceSourceNotFound(HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "EVIDENCE_SOURCE_NOT_FOUND",
                "typed evidence was not found",
                request);
    }

    @ExceptionHandler(SourceDeclarationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> sourceDeclarationNotFound(HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "SOURCE_DECLARATION_NOT_FOUND",
                "exact source declaration was not found",
                request);
    }

    @ExceptionHandler(SourceSegmentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> sourceSegmentNotFound(HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "SOURCE_SEGMENT_NOT_FOUND",
                "source segment was not found",
                request);
    }

    @ExceptionHandler(TypeMemberTypeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> typeMemberTypeNotFound(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "TYPE_MEMBER_TYPE_NOT_FOUND", "type was not found", request);
    }

    @ExceptionHandler(ConceptIdentityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> conceptIdentityNotFound(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "CONCEPT_IDENTITY_NOT_FOUND", "concept identity was not found", request);
    }

    @ExceptionHandler({RepositoryBusyException.class, RepositoryNotReadyException.class})
    public ResponseEntity<ApiErrorResponse> repositoryNotReady(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "repository is not ready", request);
    }

    @ExceptionHandler(ImmutableFixtureException.class)
    public ResponseEntity<ApiErrorResponse> immutableFixture(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "REPOSITORY_NOT_READY", "repository is not ready", request);
    }

    @ExceptionHandler(RepositoryRevisionMismatchException.class)
    public ResponseEntity<ApiErrorResponse> revisionMismatch(
            RepositoryRevisionMismatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.withContext(
                "REPOSITORY_REVISION_MISMATCH",
                "expected revision does not match current revision",
                null,
                exception.getExpectedRevision().value(),
                exception.getCurrentRevision().value(),
                null,
                List.of(),
                requestId(request)));
    }

    @ExceptionHandler(ApiRouteIndexNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> apiRouteIndexNotReady(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT,
                "API_ROUTE_INDEX_NOT_READY", "API route index is not ready", request);
    }

    @ExceptionHandler(RepositoryMutationException.class)
    public ResponseEntity<ApiErrorResponse> mutationFailed(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed", request);
    }

    @ExceptionHandler(SemanticTargetNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> semanticTargetNotFound(
            SemanticTargetNotFoundException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SEMANTIC_TARGET_NOT_FOUND",
                "exact semantic target was not found",
                exception.target(),
                request);
    }

    @ExceptionHandler(ImplementationTargetUnsupportedException.class)
    public ResponseEntity<ApiErrorResponse> implementationTargetUnsupported(
            ImplementationTargetUnsupportedException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IMPLEMENTATION_TARGET_UNSUPPORTED",
                "requested method does not support implementation discovery",
                exception.target(),
                request);
    }

    @ExceptionHandler(SemanticBindingUnresolvedException.class)
    public ResponseEntity<ApiErrorResponse> semanticBindingUnresolved(
            SemanticBindingUnresolvedException exception,
            HttpServletRequest request) {
        return targetResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SEMANTIC_BINDING_UNRESOLVED",
                "exact semantic target binding is unresolved",
                exception.target(),
                request);
    }

    @ExceptionHandler(SemanticBindingAmbiguousException.class)
    public ResponseEntity<ApiErrorResponse> semanticBindingAmbiguous(
            SemanticBindingAmbiguousException exception,
            HttpServletRequest request) {
        List<MethodTargetPayload> candidates = exception.candidates().stream()
                .sorted(Comparator.comparing(this::targetSortKey))
                .map(JavaSourceIdentityHttpMapper::toPayload)
                .toList();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.withContext(
                "SEMANTIC_BINDING_AMBIGUOUS",
                "exact semantic target binding is ambiguous",
                null,
                null,
                null,
                JavaSourceIdentityHttpMapper.toPayload(exception.target()),
                candidates,
                requestId(request)));
    }

    @ExceptionHandler({SemanticEngineNotReadyException.class, SemanticEngineStartFailedException.class})
    public ResponseEntity<ApiErrorResponse> semanticEngineStartFailed(HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "SEMANTIC_ENGINE_START_FAILED", "semantic engine failed to start", request);
    }

    @ExceptionHandler(SemanticRequestTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> semanticRequestTimeout(HttpServletRequest request) {
        return response(HttpStatus.GATEWAY_TIMEOUT,
                "SEMANTIC_REQUEST_TIMEOUT", "semantic request timed out", request);
    }

    @ExceptionHandler(SemanticProtocolException.class)
    public ResponseEntity<ApiErrorResponse> semanticProtocolError(HttpServletRequest request) {
        return response(HttpStatus.BAD_GATEWAY,
                "SEMANTIC_PROTOCOL_ERROR", "semantic protocol request failed", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "request body is invalid", request);
    }

    @ExceptionHandler(ConceptKindUnavailableException.class)
    public ResponseEntity<ConceptKindUnavailableResponse> conceptKindUnavailable(
            ConceptKindUnavailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ConceptKindUnavailableResponse(
                "CONCEPT_KIND_UNAVAILABLE",
                "requested concept kind is not active",
                exception.unavailableKinds().stream().map(Enum::name).toList(),
                exception.supportedKinds().stream().map(Enum::name).toList(),
                requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> internalFailure(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed", request);
    }

    private ResponseEntity<ApiErrorResponse> targetResponse(
            HttpStatus status,
            String errorCode,
            String message,
            MethodTarget target,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrorResponse.withContext(
                errorCode, message, null, null, null, JavaSourceIdentityHttpMapper.toPayload(target), List.of(), requestId(request)));
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, message, requestId(request)));
    }

    private String targetSortKey(MethodTarget target) {
        return target.sourceFile() + "|" + target.packageName() + "|" + target.className() + "|"
                + target.methodName() + "|" + String.join(",", target.parameterTypes());
    }

    private String requestId(HttpServletRequest request) {
        return Objects.toString(request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE), "");
    }
}
