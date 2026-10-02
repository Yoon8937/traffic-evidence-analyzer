package com.backend.video;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoPersistenceService {

    private final VideoRepository videoRepository;

    public VideoPersistenceService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    @Transactional
    public Video save(Video video) {
        return videoRepository.save(video);
    }
}
