package com.backend.video;

import com.backend.analysis.AnalysisJob;
import com.backend.analysis.AnalysisJobService;
import com.backend.exception.VideoSaveFailedException;
import com.backend.video.dto.VideoUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoServiceTest {

    @Mock
    private VideoFilePolicy videoFilePolicy;
    @Mock
    private S3StorageService s3StorageService;
    @Mock
    private VideoMetadataExtractor videoMetadataExtractor;
    @Mock
    private VideoPersistenceService videoPersistenceService;
    @Mock
    private AnalysisJobService analysisJobService;

    private VideoService videoService;

    @BeforeEach
    void setUp() {
        videoService = new VideoService(
                videoFilePolicy,
                s3StorageService,
                videoMetadataExtractor,
                videoPersistenceService,
                analysisJobService,
                200 * 1024 * 1024L
        );
        when(videoFilePolicy.isAllowedExtension(anyString())).thenReturn(true);
        when(videoFilePolicy.isAllowedContentType(anyString())).thenReturn(true);
    }

    @Test
    void upload_dbSaveFailed_deletesS3Object() {
        MockMultipartFile file = newFile();
        VideoMetadata metadata = validMetadata();
        when(videoMetadataExtractor.extract(any(), eq(1L), any(UUID.class), anyString())).thenReturn(metadata);
        when(videoPersistenceService.saveVideoAndJob(any(Video.class), any(AnalysisJob.class)))
                .thenThrow(new RuntimeException("db down"));
        when(analysisJobService.newPendingJob(any(Video.class))).thenReturn(AnalysisJob.pendingJob(UUID.randomUUID()));

        assertThrows(VideoSaveFailedException.class, () -> videoService.upload(file, 1L));
        verify(s3StorageService).delete(anyString());
        verify(analysisJobService, never()).dispatchToFastApi(any(AnalysisJob.class), anyString());
    }

    @Test
    void upload_fastapiFailure_stillReturnsUploadSuccess() {
        MockMultipartFile file = newFile();
        VideoMetadata metadata = validMetadata();
        Video video = new Video();
        video.setId(UUID.randomUUID());
        video.setOriginalFilename("video.MOV");
        video.setFileSize(file.getSize());
        video.setContentType("video/quicktime");
        video.setS3Key("videos/2026/11/19/" + video.getId() + ".MOV");
        AnalysisJob job = AnalysisJob.pendingJob(video.getId());
        when(videoMetadataExtractor.extract(any(), eq(1L), any(UUID.class), anyString())).thenReturn(metadata);
        when(analysisJobService.newPendingJob(any(Video.class))).thenReturn(job);
        when(videoPersistenceService.saveVideoAndJob(any(Video.class), any(AnalysisJob.class)))
                .thenReturn(new VideoPersistenceService.SavedVideoAndJob(video, job));
        doThrow(new RuntimeException("fastapi down"))
                .when(analysisJobService).dispatchToFastApi(any(AnalysisJob.class), anyString());

        VideoUploadResponse response = videoService.upload(file, 1L);

        assertEquals(video.getId(), response.getVideoId());
        verify(s3StorageService, never()).delete(anyString());
    }

    private MockMultipartFile newFile() {
        return new MockMultipartFile(
                "file",
                "video.MOV",
                "video/quicktime",
                new byte[] {1, 2, 3, 4}
        );
    }

    private VideoMetadata validMetadata() {
        VideoMetadata metadata = new VideoMetadata();
        metadata.setValidVideo(true);
        metadata.setWidth(1920);
        metadata.setHeight(1080);
        return metadata;
    }
}
