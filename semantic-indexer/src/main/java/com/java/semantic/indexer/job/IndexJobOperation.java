package com.java.semantic.indexer.job;

/** Work intent; rollback reuses a retained generation and never invokes extraction. */
public enum IndexJobOperation { BUILD, REVIEW, ROLLBACK, RESET, GIT_METADATA, NO_WORK }
