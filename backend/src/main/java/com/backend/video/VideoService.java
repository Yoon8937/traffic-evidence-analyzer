package com.backend.video;

import com.backend.exception.FfprobeExecutionException;
import com.backend.exception.InvalidVideoException;
import com.backend.exception.StorageException;
import com.backend.exception.VideoSaveFailedException;
import com.backend.exception.VideoValidationException;
import com.backend.video.dto.VideoUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final VideoFilePolicy videoFilePolicy;
    private final S3StorageService s3StorageService;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final VideoPersistenceService videoPersistenceService;
    private final long maxFileSizeBytes;

    public VideoService(
            VideoFilePolicy videoFilePolicy,
            S3StorageService s3StorageService,
            VideoMetadataExtractor videoMetadataExtractor,
            VideoPersistenceService videoPersistenceService,
            @Value("${app.video.max-file-size-bytes}") long maxFileSizeBytes
    ) {
        this.videoFilePolicy = videoFilePolicy;
        this.s3StorageService = s3StorageService;
        this.videoMetadataExtractor = videoMetadataExtractor;
        this.videoPersistenceService = videoPersistenceService;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public VideoUploadResponse upload(MultipartFile file, Long userId) {
        log.info("video upload started. userId={}", userId);

        validateFile(file, userId);

        UUID videoId = UUID.randomUUID();
        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String contentType = file.getContentType();
        String s3Key = buildS3Key(videoId);

        File tempFile = null;
        boolean s3Uploaded = false;
        try {
            tempFile = Files.createTempFile("video-upload-", ".MOV").toFile(); //os가 알아서 임시 디렉토리를 지정함. 메타데이터 추출하기 위해 임시로 저장
            file.transferTo(tempFile);

            s3StorageService.upload(s3Key, tempFile, contentType);
            s3Uploaded = true;
            log.info("S3 upload completed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);

            VideoMetadata metadata = videoMetadataExtractor.extract(tempFile, userId, videoId, s3Key);
            if (!metadata.isValidVideo()) {
                log.warn("invalid file. not a readable video. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
                throw new InvalidVideoException("정상적인 영상 파일로 확인할 수 없습니다.");
            }
            log.info("video metadata extraction completed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);

            Video saved = saveVideo(file, userId, videoId, originalFilename, contentType, s3Key, metadata);
            log.info("video saved successfully. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
            return toResponse(saved);
        } catch (StorageException e) {
            log.error("S3 upload failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
            throw e;
        } catch (InvalidVideoException e) {
            compensateS3Delete(s3Uploaded, userId, videoId, s3Key);
            throw e;
        } catch (FfprobeExecutionException e) {
            compensateS3Delete(s3Uploaded, userId, videoId, s3Key);
            throw e;
        } catch (VideoSaveFailedException e) {
            compensateS3Delete(s3Uploaded, userId, videoId, s3Key);
            throw e;
        } catch (IOException e) {
            log.error("video temp file handling failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
            compensateS3Delete(s3Uploaded, userId, videoId, s3Key);
            throw new VideoSaveFailedException("영상 저장에 실패했습니다.", e);
        } catch (RuntimeException e) {
            log.error("video DB save failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
            compensateS3Delete(s3Uploaded, userId, videoId, s3Key);
            throw new VideoSaveFailedException("영상 저장에 실패했습니다.", e);
        } finally {
            deleteTempFile(tempFile, userId, videoId, s3Key);
        }
    }

    private void validateFile(MultipartFile file, Long userId) {
        if (file == null) {
            log.warn("invalid file. file is missing. userId={}", userId);
            throw new VideoValidationException("FILE_MISSING", "업로드할 영상 파일이 없습니다.");
        }
        if (file.isEmpty()) {
            log.warn("invalid file. file is empty. userId={}", userId);
            throw new VideoValidationException("EMPTY_FILE", "빈 파일은 업로드할 수 없습니다.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            log.warn("file size exceeded. userId={}, fileSize={}", userId, file.getSize());
            throw new VideoValidationException("FILE_SIZE_EXCEEDED", "업로드 가능한 최대 파일 크기를 초과했습니다.");
        }

        String originalFilename = file.getOriginalFilename();
        if (!videoFilePolicy.isAllowedExtension(originalFilename)) {
            log.warn("invalid file. unsupported extension. userId={}", userId);
            throw new VideoValidationException("UNSUPPORTED_FILE_TYPE", "지원하지 않는 파일 형식입니다.");
        }
        if (!videoFilePolicy.isAllowedContentType(file.getContentType())) {
            log.warn("invalid file. unsupported content type. userId={}", userId);
            throw new VideoValidationException("UNSUPPORTED_FILE_TYPE", "지원하지 않는 파일 형식입니다.");
        }
    }

    private String buildS3Key(UUID videoId) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
        return "videos/" + datePath + "/" + videoId + ".MOV";
    }

    private Video saveVideo(MultipartFile file, Long userId, UUID videoId, String originalFilename, String contentType, String s3Key, VideoMetadata metadata) {
        try {
            Video video = new Video();
            video.setId(videoId);
            video.setUserId(userId);
            video.setOriginalFilename(originalFilename);
            video.setS3Key(s3Key);
            video.setFileSize(file.getSize());
            video.setContentType(contentType);
            video.setDurationSeconds(metadata.getDurationSeconds());
            video.setWidth(metadata.getWidth());
            video.setHeight(metadata.getHeight());
            video.setFrameRate(metadata.getFrameRate());
            video.setRecordedAt(metadata.getRecordedAt());
            return videoPersistenceService.save(video);
        } catch (RuntimeException e) {
            log.error("video DB save failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
            throw new VideoSaveFailedException("영상 저장에 실패했습니다.", e);
        }
    }

    private void compensateS3Delete(boolean s3Uploaded, Long userId, UUID videoId, String s3Key) {
        if (!s3Uploaded) {
            return;
        }
        try {
            s3StorageService.delete(s3Key);
            log.info("S3 compensation delete completed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
        } catch (Exception e) {
            log.error("S3 compensation delete failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
        }
    }

    private void deleteTempFile(File tempFile, Long userId, UUID videoId, String s3Key) {
        if (tempFile == null || !tempFile.exists()) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException e) {
            log.warn("temp file delete failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
        }
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "video.MOV";
        }
        String filename = originalFilename.replace("\\", "/");
        int slashIndex = filename.lastIndexOf('/');
        if (slashIndex >= 0) {
            filename = filename.substring(slashIndex + 1);
        }
        if (filename.length() > 255) {
            return filename.substring(filename.length() - 255);
        }
        return filename;
    }

    private VideoUploadResponse toResponse(Video video) {
        VideoUploadResponse response = new VideoUploadResponse();
        response.setVideoId(video.getId());
        response.setOriginalFilename(video.getOriginalFilename());
        response.setFileSize(video.getFileSize());
        response.setContentType(video.getContentType());
        response.setDurationSeconds(video.getDurationSeconds());
        response.setWidth(video.getWidth());
        response.setHeight(video.getHeight());
        response.setFrameRate(video.getFrameRate());
        response.setCreatedAt(video.getCreatedAt());
        return response;
    }
}
