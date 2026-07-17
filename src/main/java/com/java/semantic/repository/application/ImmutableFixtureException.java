package com.java.semantic.repository.application;

/** LOCAL_FIXTURE 不允許變更 */
public class ImmutableFixtureException extends RuntimeException {

    public ImmutableFixtureException() {
        super("local fixture repositories are immutable");
    }
}
