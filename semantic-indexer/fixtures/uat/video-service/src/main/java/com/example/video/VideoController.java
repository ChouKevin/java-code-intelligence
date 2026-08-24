package com.example.video;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class VideoController {
    private final VideoService videoService = new DefaultVideoService();

    @PostMapping("/videos")
    public void upload(String videoId, VideoFormat format) {
        videoService.upload(videoId, format);
    }
}
