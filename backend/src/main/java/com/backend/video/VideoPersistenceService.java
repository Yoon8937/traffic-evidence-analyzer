package com.backend.video;

import com.backend.analysis.AnalysisJob;
import com.backend.analysis.AnalysisJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoPersistenceService {

    private final VideoRepository videoRepository;
    private final AnalysisJobRepository analysisJobRepository;

    public VideoPersistenceService(
            VideoRepository videoRepository,
            AnalysisJobRepository analysisJobRepository
    ) {
        this.videoRepository = videoRepository;
        this.analysisJobRepository = analysisJobRepository;
    }

    @Transactional
    public Video save(Video video) {
        return videoRepository.save(video);
    }

    @Transactional
    public SavedVideoAndJob saveVideoAndJob(Video video, AnalysisJob job) {
        Video savedVideo = videoRepository.save(video);
        AnalysisJob savedJob = analysisJobRepository.save(job);
        return new SavedVideoAndJob(savedVideo, savedJob);
    }

    public static class SavedVideoAndJob {
        private final Video video;
        private final AnalysisJob job;

        public SavedVideoAndJob(Video video, AnalysisJob job) {
            this.video = video;
            this.job = job;
        }

        public Video getVideo() {
            return video;
        }

        public AnalysisJob getJob() {
            return job;
        }
    }
}
