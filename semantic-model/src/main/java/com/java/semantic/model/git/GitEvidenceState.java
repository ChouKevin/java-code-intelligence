package com.java.semantic.model.git;

/** Query reads only manifests that have been completely validated and published. */
public enum GitEvidenceState { PREPARING, READY, FAILED }
