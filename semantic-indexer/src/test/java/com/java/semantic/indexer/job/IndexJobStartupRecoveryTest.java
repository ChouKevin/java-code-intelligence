package com.java.semantic.indexer.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IndexJobStartupRecoveryTest {
    @Test
    void startup_reconciles_commits_then_fails_unreconciled_running_jobs() throws Exception {
        IndexJobStore jobs = mock(IndexJobStore.class);

        new IndexJobStartupRecovery(jobs).run(new DefaultApplicationArguments());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(jobs);
        order.verify(jobs).reconcileCommittedJobs();
        order.verify(jobs).failUnreconciledRunningJobs();
    }
}
