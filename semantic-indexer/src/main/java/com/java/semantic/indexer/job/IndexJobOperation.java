package com.java.semantic.indexer.job;

/** Work intent; rollback reuses a retained generation and never invokes extraction. */
public enum IndexJobOperation { BUILD, ROLLBACK, RESET, NO_WORK }
