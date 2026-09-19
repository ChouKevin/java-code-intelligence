package com.java.semantic.indexer.job;

/** Durable preparation state for the two immutable endpoints of a review. */
public enum ReviewPreparationStage {
    PREPARING_A, BUILDING_A, PREPARING_B, BUILDING_B, PREPARING_GIT, VALIDATING, READY
}
