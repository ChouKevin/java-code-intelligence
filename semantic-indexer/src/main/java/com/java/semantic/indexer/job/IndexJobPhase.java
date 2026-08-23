package com.java.semantic.indexer.job;

/** The durable phases of an asynchronous index request. */
public enum IndexJobPhase {
    ACCEPTED, CHECKOUT, PLAN, EXTRACT, WRITE, VALIDATE, PUBLISH, COMPLETE, FAILED
}
