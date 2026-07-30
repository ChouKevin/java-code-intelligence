package com.java.semantic.syntax.application;

/** 支援的 Spring 監聽器註解種類 */
public enum ListenerAnnotationKind {
    EVENT_LISTENER(0, "org.springframework.context.event", "EventListener"),
    TRANSACTIONAL_EVENT_LISTENER(1, "org.springframework.transaction.event", "TransactionalEventListener");

    private final int protocolOrder;
    private final String packageName;
    private final String className;

    ListenerAnnotationKind(int protocolOrder, String packageName, String className) {
        this.protocolOrder = protocolOrder;
        this.packageName = packageName;
        this.className = className;
    }

    int protocolOrder() {
        return protocolOrder;
    }

    boolean matchesResolvedType(String resolvedPackageName, String resolvedClassName) {
        return packageName.equals(resolvedPackageName) && className.equals(resolvedClassName);
    }

    boolean matchesWrittenName(String writtenName) {
        int separator = writtenName.lastIndexOf('.');
        String finalSegment = separator < 0 ? writtenName : writtenName.substring(separator + 1);
        return className.equals(finalSegment);
    }
}
