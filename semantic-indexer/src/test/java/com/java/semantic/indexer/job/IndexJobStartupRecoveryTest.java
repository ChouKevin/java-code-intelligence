package com.java.semantic.indexer.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IndexJobStartupRecoveryTest {
    @Test
    void startup_expires_claims_then_reconciles_commits_before_recovering_revoked_claims() throws Exception {
        IndexJobWorker worker = mock(IndexJobWorker.class);
        IndexJobStore jobs = mock(IndexJobStore.class);

        new IndexJobStartupRecovery(worker, jobs).run(new DefaultApplicationArguments());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(worker, jobs);
        order.verify(worker).expireClaims();
        order.verify(jobs).reconcileCommittedJobs();
        order.verify(jobs).recoverRevokedClaims();
    }
}
