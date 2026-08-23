package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoIndexSchemaWriterTest {
    @SuppressWarnings("unchecked")
    @Test
    void verifies_indexes_after_a_concurrent_collection_creation_translates_namespace_exists() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class);
        when(template.collectionExists("repositories")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("collection already exists", namespaceExists())).when(template).createCollection("repositories");
        when(template.getCollection("repositories")).thenReturn(collection);
        when(collection.listIndexes()).thenReturn(indexes);
        when(indexes.into(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new MongoIndexSchemaWriter(template).createOrVerify(IndexSchemaContract.collections().getFirst());

        verify(collection).listIndexes();
    }

    @Test
    void rethrows_a_non_namespace_data_access_failure_during_collection_creation() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.collectionExists("repositories")).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("mongo unavailable")).when(template).createCollection("repositories");

        assertThatThrownBy(() -> new MongoIndexSchemaWriter(template).createOrVerify(IndexSchemaContract.collections().getFirst()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static MongoCommandException namespaceExists() {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0.0))
                .append("code", new BsonInt32(48))
                .append("codeName", new BsonString("NamespaceExists"))
                .append("errmsg", new BsonString("collection already exists"));
        return new MongoCommandException(response, new ServerAddress());
    }
}
