package com.backend.video;

import com.backend.video.dto.VideoUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping
    public ResponseEntity<VideoUploadResponse> upload(@RequestParam(value = "file", required = false) MultipartFile file, Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());//return user's id(users table)
        log.info("tmp");
        VideoUploadResponse response = videoService.upload(file, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
