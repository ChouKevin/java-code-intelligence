package com.java.semantic.indexer.uat;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobExecutor;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Executes only the destructive work; IndexJobExecutor remains the terminal-transition owner. */
@Component
@Profile("uat")
public final class UatResetJobHandler implements IndexJobExecutor.ResetJobHandler {
    private final UatRepositoryResetService resets;

    public UatResetJobHandler(UatRepositoryResetService resets) {
        this.resets = Objects.requireNonNull(resets, "reset service is required");
    }

    @Override
    public void reset(IndexJob job) {
        resets.reset(job);
    }
}
