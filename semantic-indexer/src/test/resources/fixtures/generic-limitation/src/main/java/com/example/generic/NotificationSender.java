package com.example.generic;

import org.springframework.stereotype.Service;

/**
 * Leaf service called through a deeply-nested generic field.
 * Used to verify that stripGenerics correctly resolves
 * {@code GenericService<Map<String, List<NotificationSender>>>} → NotificationSender.
 */
@Service
public class NotificationSender {

    public void send(String message) {
        System.out.println("Sending: " + message);
    }
}
