package com.java.semantic.query.store;

import com.java.semantic.model.repository.RepositoryId;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

public final class MongoRelationReader extends MongoProjectionReader {
    public MongoRelationReader(MongoTemplate template) {
        super(template);
    }

    public List<Document> read(RepositoryId repositoryId) {
        return selected("relations", repositoryId);
    }
}
