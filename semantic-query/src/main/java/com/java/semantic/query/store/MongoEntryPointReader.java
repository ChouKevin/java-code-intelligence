package com.java.semantic.query.store;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

public final class MongoEntryPointReader extends MongoProjectionReader {
    public MongoEntryPointReader(MongoTemplate template) {
        super(template);
    }

    public List<Document> read(RepositoryId repositoryId, RepositoryRevision expectedRevision) {
        return selected("entry_points", repositoryId, expectedRevision);
    }
}
