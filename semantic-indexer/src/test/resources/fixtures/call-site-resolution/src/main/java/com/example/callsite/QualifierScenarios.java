package com.example.callsite;

public class QualifierScenarios {

    @Qualifier("fast")
    private final PriorityWorker priorityWorker = new FastWorker();

    public void route(String value) {
        priorityWorker.process(value);
    }
}
