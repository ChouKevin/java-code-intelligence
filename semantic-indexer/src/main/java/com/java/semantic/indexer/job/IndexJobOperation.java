package com.java.semantic.indexer.job;

/** Work intent; rollback reuses a retained generation and never invokes extraction. */
public enum IndexJobOperation { BUILD, REVIEW, ROLLBACK, RESET, GIT_REFS, GIT_HISTORY, GIT_COMPARISON, NO_WORK }
