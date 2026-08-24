package com.example.video;

import org.springframework.kafka.core.KafkaTemplate;

public final class VideoEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>();

    public void publishUploaded(String videoId) {
        kafkaTemplate.send("video.uploaded", videoId);
    }
}
