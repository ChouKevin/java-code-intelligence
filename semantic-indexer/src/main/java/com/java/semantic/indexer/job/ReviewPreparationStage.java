package com.java.semantic.indexer.job;

/** Durable preparation state for the two immutable endpoints of a review. */
public enum ReviewPreparationStage {
    RESOLVING, PREPARING_BEFORE, BUILDING_BEFORE, PREPARING_AFTER, BUILDING_AFTER, PREPARING_GIT, VALIDATING, READY
}
