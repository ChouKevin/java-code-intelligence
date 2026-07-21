package com.java.semantic.syntax.application;

import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public final class EntryPointDiscoveryFilter {

    private final ReadPolicy readPolicy;

    public EntryPointDiscoveryFilter(ReadPolicy readPolicy) {
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
    }

    public RepositorySyntax filter(
            RepositoryId repositoryId,
            RepositorySyntax syntax,
            Set<EntryPointType> requestedTypes) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(syntax, "syntax is required");
        Objects.requireNonNull(requestedTypes, "requestedTypes is required");
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                readPolicy.visibilityOfRepository(repositoryId.value()))) {
            return RepositorySyntax.empty();
        }
        List<EntryPointClass> entryPoints = syntax.entryPoints().stream()
                .map(entryPoint -> filterClass(repositoryId, entryPoint, requestedTypes))
                .flatMap(Optional::stream)
                .toList();
        return new RepositorySyntax(entryPoints, List.of());
    }

    private Optional<EntryPointClass> filterClass(
            RepositoryId repositoryId,
            EntryPointClass entryPoint,
            Set<EntryPointType> requestedTypes) {
        TypeId typeId = new TypeId(
                repositoryId.value(), entryPoint.packageName(), entryPoint.className());
        if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(readPolicy.visibilityOf(typeId))) {
            return Optional.empty();
        }
        List<EntryPointMethod> methods = entryPoint.methods().stream()
                .filter(method -> EvidenceVisibility.READABLE.equals(
                        readPolicy.visibilityOfDiscoveredMethod(typeId, method.name())))
                .filter(method -> requestedTypes.contains(method.type()))
                .toList();
        if (CollectionUtils.isEmpty(methods)) {
            return Optional.empty();
        }
        return Optional.of(new EntryPointClass(
                entryPoint.className(),
                entryPoint.packageName(),
                entryPoint.packagePath(),
                entryPoint.description(),
                entryPoint.basePaths(),
                methods));
    }
}
