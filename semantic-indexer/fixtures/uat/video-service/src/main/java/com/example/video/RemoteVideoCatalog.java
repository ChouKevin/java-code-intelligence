package com.example.video;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "catalog")
public interface RemoteVideoCatalog {

    @PostMapping("/transcoding/jobs")
    void createTranscodingJob(String videoId);
}
