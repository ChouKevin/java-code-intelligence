package com.example.generic;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/**
 * Scenario 1: Nested generics
 *
 * The field type is {@code Map<String, List<NotificationSender>>} — a 2-level nested generic.
 * stripGenerics cuts at the first {@code <}, producing "Map", NOT "NotificationSender".
 * Therefore {@code senderMap.get("key")} resolves to {@code Map#get}, not to NotificationSender.
 *
 * However, a direct field like {@code NotificationSender sender} resolves correctly.
 * This class demonstrates both paths.
 */
@Service
public class NestedGenericCaller {

    private final NotificationSender sender;
    private final Map<String, List<NotificationSender>> senderMap;

    public NestedGenericCaller(NotificationSender sender,
                               Map<String, List<NotificationSender>> senderMap) {
        this.sender = sender;
        this.senderMap = senderMap;
    }

    /** Direct field — type inference works: sender → NotificationSender */
    public void directCall() {
        sender.send("direct");
    }

    /** Nested generic field — type inference loses generic args: senderMap → Map */
    public void nestedGenericCall() {
        senderMap.get("key");
    }
}
