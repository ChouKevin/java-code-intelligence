package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticEngineException;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

final class JdtLsSemanticExceptionNormalizer {

    private JdtLsSemanticExceptionNormalizer() {
    }

    static <T> T normalize(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation is required");
        try {
            return operation.get();
        } catch (SemanticAmbiguousTypeException
                 | SemanticTargetNotFoundException
                 | SemanticBindingUnresolvedException
                 | SemanticBindingAmbiguousException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw normalized(exception);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Error error) {
            throw new SemanticProtocolException();
        }
    }

    static void rethrowIfEngineFailure(RuntimeException exception) {
        Objects.requireNonNull(exception, "exception is required");
        RuntimeException normalized = normalizedOrOriginal(exception);
        if (normalized instanceof SemanticEngineException semanticEngineException) {
            throw semanticEngineException;
        }
    }

    private static RuntimeException normalized(RuntimeException exception) {
        RuntimeException normalized = normalizedOrOriginal(exception);
        return normalized instanceof SemanticEngineException
                || normalized instanceof SemanticAmbiguousTypeException
                || normalized instanceof SemanticTargetNotFoundException
                || normalized instanceof SemanticBindingUnresolvedException
                || normalized instanceof SemanticBindingAmbiguousException
                ? normalized
                : new SemanticProtocolException();
    }

    private static RuntimeException normalizedOrOriginal(RuntimeException exception) {
        Throwable candidate = exception;
        boolean completionWrapped = false;
        while (candidate instanceof CompletionException completion
                && Objects.nonNull(completion.getCause())) {
            completionWrapped = true;
            candidate = completion.getCause();
        }
        if (candidate instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (candidate instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (candidate instanceof Error) {
            return new SemanticProtocolException();
        }
        if (candidate instanceof SemanticEngineException semanticEngineException) {
            return semanticEngineException;
        }
        if (candidate instanceof SemanticAmbiguousTypeException semanticException) {
            return semanticException;
        }
        if (candidate instanceof SemanticTargetNotFoundException semanticException) {
            return semanticException;
        }
        if (candidate instanceof SemanticBindingUnresolvedException semanticException) {
            return semanticException;
        }
        if (candidate instanceof SemanticBindingAmbiguousException semanticException) {
            return semanticException;
        }
        if (candidate instanceof JdtWorkspaceSession.JdtRequestTimeoutException) {
            return new SemanticRequestTimeoutException();
        }
        if (candidate instanceof JdtWorkspaceSession.JdtWorkspaceClosingException) {
            return new SemanticEngineNotReadyException();
        }
        if (candidate instanceof JdtLsReadinessProbe.JdtWorkspaceStartupException) {
            return new SemanticEngineStartFailedException();
        }
        if (candidate instanceof DefaultJdtWorkspaceManager.JdtWorkspaceManagerStoppedException
                || candidate instanceof DefaultJdtWorkspaceManager.JdtWorkspaceCapacityException
                || candidate instanceof DefaultJdtWorkspaceManager.JdtWorkspaceTerminationPendingException) {
            return new SemanticEngineNotReadyException();
        }
        if (candidate instanceof JdtWorkspaceSession.JdtRequestFailedException
                || candidate instanceof ResponseErrorException
                || completionWrapped) {
            return new SemanticProtocolException();
        }
        return candidate instanceof RuntimeException runtimeException
                ? runtimeException
                : new SemanticProtocolException();
    }
}
