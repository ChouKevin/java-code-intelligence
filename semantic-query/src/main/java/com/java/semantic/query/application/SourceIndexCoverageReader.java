package com.java.semantic.query.application;

import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Reads only pre-authorized generation-file coverage rows. */
final class SourceIndexCoverageReader {
    private final MongoTemplate template;
    private final Duration storageTimeout;

    SourceIndexCoverageReader(MongoTemplate template, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    SourceIndexCoverage coverage(CurrentGeneration generation, SearchAccessPlan accessPlan, Optional<String> packagePrefix,
                                 Optional<String> sourcePath) {
        CurrentGeneration current = Objects.requireNonNull(generation, "generation is required");
        SearchAccessPlan requiredPlan = Objects.requireNonNull(accessPlan, "access plan is required");
        Optional<String> requiredPackagePrefix = Objects.requireNonNull(packagePrefix, "package prefix is required");
        Optional<String> requiredSourcePath = Objects.requireNonNull(sourcePath, "source path is required")
                .map(ModelValidation::repositoryRelativePath);
        try {
            Bson filter = requiredPlan.authorizedSource(filter(current, requiredPackagePrefix, requiredSourcePath));
            long count = template.getCollection(IndexCollections.GENERATION_FILES).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.GENERATION_FILES).find(filter)
                    .sort(Sorts.ascending("sourcePath")).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<SourceIndexIssue> issues = new ArrayList<>();
            for (Document row : rows) {
                GenerationFileDocument file = decode(row, current);
                if (!file.extractionIssueCode().isEmpty()) {
                    issues.add(new SourceIndexIssue(file.sourcePath(), file.extractionIssueCode()));
                }
            }
            return new SourceIndexCoverage(count, issues);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static Bson filter(CurrentGeneration current, Optional<String> packagePrefix, Optional<String> sourcePath) {
        List<Bson> predicates = new ArrayList<>();
        predicates.add(Filters.eq("repoId", current.repositoryId().value()));
        predicates.add(Filters.eq("generationId", current.generationId().value()));
        packagePrefix.ifPresent(prefix -> predicates.add(Filters.regex("scopePackages",
                "^" + Pattern.quote(prefix) + "(?:\\.|$)")));
        sourcePath.ifPresent(path -> predicates.add(Filters.eq("sourcePath", path)));
        return Filters.and(predicates);
    }

    private GenerationFileDocument decode(Document stored, CurrentGeneration current) {
        try {
            Document converterDocument = new Document(stored);
            converterDocument.put("generationId", new Document("value", current.generationId().value()));
            GenerationFileDocument decoded = template.getConverter().read(GenerationFileDocument.class, converterDocument);
            if (!current.repositoryId().equals(decoded.repositoryId()) || !current.generationId().equals(decoded.generationId())
                    || !decoded.sourcePath().equals(requiredText(stored, "sourcePath"))
                    || !decoded.extractionIssueCode().equals(requiredTextAllowEmpty(stored, "extractionIssueCode"))
                    || !decoded.scope().equals(new SourceIndexScope(requiredBoolean(stored, "scopeUsable"),
                    requiredTextList(stored, "scopePackages", true), requiredTextList(stored, "scopeClassKeys", false),
                    requiredTextList(stored, "scopeMethodKeys", false)))) {
                throw new IndexContractMismatchException();
            }
            return decoded;
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static String requiredText(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IndexContractMismatchException();
        }
        return text;
    }

    private static String requiredTextAllowEmpty(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text)) {
            throw new IndexContractMismatchException();
        }
        return text;
    }

    private static boolean requiredBoolean(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Boolean flag)) {
            throw new IndexContractMismatchException();
        }
        return flag;
    }

    private static List<String> requiredTextList(Document document, String field, boolean allowEmptyValue) {
        Object value = document.get(field);
        if (!(value instanceof List<?> values)) {
            throw new IndexContractMismatchException();
        }
        List<String> text = new ArrayList<>();
        for (Object element : values) {
            if (!(element instanceof String item) || (!StringUtils.hasText(item) && (!allowEmptyValue || !item.isEmpty()))) {
                throw new IndexContractMismatchException();
            }
            text.add(item);
        }
        return List.copyOf(text);
    }
}
