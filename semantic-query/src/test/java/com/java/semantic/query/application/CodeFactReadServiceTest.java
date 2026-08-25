package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactReadQuery;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeFactReadServiceTest {

    @Test
    void pointer_switch_after_search_selection_propagates_exact_revision_outdated_failure() {
        CurrentGeneration searchGeneration = current("a", "g1");
        RevisionOutdatedException expected = new RevisionOutdatedException(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40)));
        CurrentGenerationSelector selector = mock(CurrentGenerationSelector.class);
        SearchAccessPlan accessPlan = mock(SearchAccessPlan.class);
        MongoTemplate template = mock(MongoTemplate.class);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> searchCollection = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        FindIterable<Document> rows = mock(FindIterable.class);
        when(selector.searchAccessPlan("orders")).thenReturn(accessPlan);
        when(selector.select(eq("orders"), eq("a".repeat(40)), any(ProjectionRequirements.class)))
                .thenReturn(searchGeneration).thenThrow(expected);
        when(accessPlan.authorized(any(Bson.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(template.getCollection(IndexCollections.SEARCH)).thenReturn(searchCollection);
        when(searchCollection.find(any(Bson.class))).thenReturn(rows);
        when(rows.maxTime(eq(1000L), eq(TimeUnit.MILLISECONDS))).thenReturn(rows);
        when(rows.first()).thenReturn(searchRow());

        CodeFactReadService service = new CodeFactReadService(template, selector, Duration.ofSeconds(1));

        assertThatThrownBy(() -> service.get(new CodeFactReadQuery(new RepositoryId("orders"),
                new RepositoryRevision("a".repeat(40)), new CodeFactId("c".repeat(64)))))
                .isSameAs(expected);
    }

    private static CurrentGeneration current(String revisionCharacter, String generationId) {
        return new CurrentGeneration(new RepositoryId("orders"), new RepositoryRevision(revisionCharacter.repeat(40)),
                new GenerationId(generationId), new ManifestDigest("d".repeat(64)), Instant.parse("2026-08-25T00:00:00Z"));
    }

    private static Document searchRow() {
        return new Document("repoId", "orders").append("generationId", "g1").append("factId", "c".repeat(64))
                .append("kind", "METHOD").append("authority", "SYMBOLS").append("canonical", "method:test")
                .append("scopePackage", "example").append("scopeClass", "Test").append("scopeMethod", "test")
                .append("scopeParameters", java.util.List.of()).append("scopePath", "src/Test.java");
    }
}
