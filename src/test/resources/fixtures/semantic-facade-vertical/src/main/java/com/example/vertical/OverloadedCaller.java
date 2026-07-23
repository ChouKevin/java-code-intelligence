package com.example.vertical;

public final class OverloadedCaller {

    private final OverloadedTarget target = new OverloadedTarget();

    public void callString() {
        target.accept("string");
    }

    public void callInt() {
        target.accept(1);
    }

    public void callStringEntry() {
        callString();
    }
}
