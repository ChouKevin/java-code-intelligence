package com.example.callsite;

@Qualifier("fast")
public final class FastWorker implements PriorityWorker {

    @Override
    public void process(String value) {
    }
}
