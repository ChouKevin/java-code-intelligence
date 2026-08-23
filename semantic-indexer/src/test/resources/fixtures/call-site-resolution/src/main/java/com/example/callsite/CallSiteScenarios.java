package com.example.callsite;

import static com.example.callsite.TextTools.decorate;

import java.util.function.Consumer;
import java.util.function.Function;

public class CallSiteScenarios {

    private final Worker worker = new WorkerImpl();
    private final GenericChild genericChild = new GenericChild();

    public void exercise() {
        overloaded("first");
        overloaded("second");
        overloaded(7);
        worker.work("interface");
        genericChild.echo("generic");
        worker.work(decorate("chain"));
        Chain.open().close();
        new Created("created");
        Function<String, String> lambda = value -> normalize(value);
        Consumer<String> reference = this::consume;
        reference.accept("reference");
        Function<String, Created> creator = Created::new;
        creator.apply("creation-reference");
    }

    private void overloaded(String value) {
    }

    private void overloaded(int value) {
    }

    private String normalize(String value) {
        return value;
    }

    private void consume(String value) {
    }
}
