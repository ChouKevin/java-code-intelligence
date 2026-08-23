package com.java.semantic.callgraph.application;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.domain.SemanticSourceClassification;

import java.util.List;
import java.util.Optional;

final class SemanticGraphTestFixture {

    private SemanticGraphTestFixture() {
    }

    static MethodTarget target(String className, String methodName) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        className + ".java"),
                methodName,
                List.of());
    }

    static SemanticMethod outgoingMethod(MethodTarget target, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line + 2, 0));
        return new SemanticMethod(
                target.packageName(), target.className(), target.methodName(), target.parameterTypes(), "void",
                new SemanticLocation("file:///fixture/" + target.sourceFile(), range, range));
    }

    static SemanticMethod incomingMethod(MethodTarget target, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line + 1, 0));
        return new SemanticMethod(
                target.packageName(), target.className(), target.methodName(), target.parameterTypes(), "void",
                new SemanticLocation("file:///fixture/" + target.sourceFile(), range, range));
    }

    static SemanticCall resolvedCall(SemanticMethod target, int line) {
        SemanticRange range = new SemanticRange(new SemanticPosition(line, 0), new SemanticPosition(line, 4));
        return new SemanticCall(Optional.of(target), target.methodName() + "()", List.of(range), false,
                SemanticResolutionOrigin.CALL_HIERARCHY);
    }

    static SemanticSourceClassification sourceClassification(SemanticMethod method) {
        String marker = "file:///fixture/";
        String uri = method.location().uri();
        if (!uri.startsWith(marker)) {
            return SemanticSourceClassification.UnprovableUri.INSTANCE;
        }
        return new SemanticSourceClassification.LocalSource(uri.substring(marker.length()));
    }
}
