package com.example.callsite;

public final class Chain {

    private Chain() {
    }

    public static Chain open() {
        return new Chain();
    }

    public void close() {
    }
}
