package com.example.video;

public final class DefaultVideoService implements VideoService {
    private final VideoEventPublisher eventPublisher = new VideoEventPublisher();
    private RemoteVideoCatalog catalog;

    @Override
    public void upload(String videoId, VideoFormat format) {
        catalog.createTranscodingJob(videoId);
        eventPublisher.publishUploaded(videoId + format.name());
    }
}
