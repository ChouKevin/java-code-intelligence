package com.java.semantic.indexer.store;

import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;

/** Task-4 pointer mutation boundary injected into asynchronous workers. */
public interface PublicationPort {
    PublishedGenerationPointer publish(PublishGenerationCommand command);

    PublishedGenerationPointer rollback(RollbackGenerationCommand command);
}
