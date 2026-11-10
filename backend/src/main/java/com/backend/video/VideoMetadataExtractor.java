package com.backend.video;

import com.backend.exception.FfprobeExecutionException;
import com.backend.exception.InvalidVideoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class VideoMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(VideoMetadataExtractor.class);
    private static final long TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final String ffprobePath;

    public VideoMetadataExtractor(
            ObjectMapper objectMapper,
            @Value("${app.ffprobe.path}") String ffprobePath
    ) {
        this.objectMapper = objectMapper;
        this.ffprobePath = ffprobePath;
    }

    public VideoMetadata extract(File videoFile, Long userId, UUID videoId, String s3Key) {
        String output = runFfprobe(videoFile, userId, videoId, s3Key);
        return parseMetadata(output, userId, videoId, s3Key);
    }

    /**
     * 이전 구현은 stdout을 readAllBytes()로 먼저 읽었다.
     * readAllBytes()는 프로세스가 stdout을 닫을 때까지(보통 종료할 때까지) block되므로,
     * 그 다음에 있는 waitFor(30초)까지 도달하지 못해 timeout이 동작하지 않았다.
     *
     * 지금은 stdout을 별도 스레드에서 비우고, 메인 스레드는 waitFor로 30초만 기다린다.
     * stderr는 redirectErrorStream(true)로 stdout에 합쳐 파이프 deadlock을 피한다.
     */
    private String runFfprobe(File videoFile, Long userId, UUID videoId, String s3Key) {
        List<String> command = Arrays.asList(
                ffprobePath,
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                videoFile.getAbsolutePath()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = null;
        Thread stdoutReader = null;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            process = processBuilder.start();
            InputStream processStdout = process.getInputStream();
            stdoutReader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        processStdout.transferTo(stdout);
                    } catch (IOException ignored) {
                    }
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = waitForProcess(process, TIMEOUT_SECONDS * 1000);
            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(1000);
                log.error("ffprobe execution failed. timeout. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
                throw new FfprobeExecutionException("ffprobe timed out");
            }

            stdoutReader.join(1000);
            int exitCode = process.exitValue();
            String output = stdout.toString(StandardCharsets.UTF_8);
            if (exitCode != 0) {
                log.warn("invalid file. ffprobe could not read video. userId={}, videoId={}, s3Key={}",
                        userId, videoId, s3Key);
                throw new InvalidVideoException("정상적인 영상 파일로 확인할 수 없습니다.");
            }
            return output;
        } catch (FfprobeExecutionException e) {
            throw e;
        } catch (InvalidVideoException e) {
            throw e;
        } catch (Exception e) {
            log.error("ffprobe execution failed. userId={}, videoId={}, s3Key={}", userId, videoId, s3Key, e);
            throw new FfprobeExecutionException("ffprobe execution failed", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private VideoMetadata parseMetadata(String json, Long userId, UUID videoId, String s3Key) {
        VideoMetadata metadata = new VideoMetadata();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode videoStream = findVideoStream(root.get("streams"));
            if (videoStream == null) {
                metadata.setValidVideo(false);
                return metadata;
            }

            metadata.setValidVideo(true);
            metadata.setWidth(readInteger(videoStream.get("width"), userId, videoId, s3Key, "width"));
            metadata.setHeight(readInteger(videoStream.get("height"), userId, videoId, s3Key, "height"));
            metadata.setFrameRate(readFrameRate(videoStream, userId, videoId, s3Key));

            JsonNode format = root.get("format");
            if (format != null) {
                metadata.setDurationSeconds(readDecimal(format.get("duration"), userId, videoId, s3Key, "duration"));
                metadata.setRecordedAt(readRecordedAt(format, videoStream, userId, videoId, s3Key));
            } else {
                log.warn("metadata unavailable. field=duration, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
                log.warn("metadata unavailable. field=recorded_at, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
            }
            return metadata;
        } catch (Exception e) {
            log.error("ffprobe execution failed. metadata parsing failed. userId={}, videoId={}, s3Key={}",
                    userId, videoId, s3Key, e);
            throw new FfprobeExecutionException("ffprobe metadata parsing failed", e);
        }
    }

    private JsonNode findVideoStream(JsonNode streams) {
        if (streams == null || !streams.isArray()) {
            return null;
        }
        for (JsonNode stream : streams) {
            JsonNode codecType = stream.get("codec_type");
            if (codecType != null && "video".equalsIgnoreCase(codecType.asText())) {
                return stream;
            }
        }
        return null;
    }

    private Integer readInteger(JsonNode node, Long userId, UUID videoId, String s3Key, String field) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            log.warn("metadata unavailable. field={}, userId={}, videoId={}, s3Key={}", field, userId, videoId, s3Key);
            return null;
        }
        try {
            return Integer.valueOf(node.asText());
        } catch (NumberFormatException e) {
            log.warn("metadata unavailable. field={}, userId={}, videoId={}, s3Key={}", field, userId, videoId, s3Key);
            return null;
        }
    }

    private BigDecimal readDecimal(JsonNode node, Long userId, UUID videoId, String s3Key, String field) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            log.warn("metadata unavailable. field={}, userId={}, videoId={}, s3Key={}", field, userId, videoId, s3Key);
            return null;
        }
        try {
            return roundTo3(new BigDecimal(node.asText()));
        } catch (NumberFormatException e) {
            log.warn("metadata unavailable. field={}, userId={}, videoId={}, s3Key={}", field, userId, videoId, s3Key);
            return null;
        }
    }

    private BigDecimal readFrameRate(JsonNode videoStream, Long userId, UUID videoId, String s3Key) {
        String raw = null;
        if (videoStream.get("avg_frame_rate") != null && !"0/0".equals(videoStream.get("avg_frame_rate").asText())) {
            raw = videoStream.get("avg_frame_rate").asText();
        } else if (videoStream.get("r_frame_rate") != null) {
            raw = videoStream.get("r_frame_rate").asText();
        }

        if (raw == null || raw.isBlank() || "0/0".equals(raw)) {
            log.warn("metadata unavailable. field=frameRate, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
            return null;
        }

        try {
            if (raw.contains("/")) {
                String[] parts = raw.split("/");
                BigDecimal numerator = new BigDecimal(parts[0]);
                BigDecimal denominator = new BigDecimal(parts[1]);
                if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                    log.warn("metadata unavailable. field=frameRate, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
                    return null;
                }
                return roundTo3(numerator.divide(denominator, 6, BigDecimal.ROUND_HALF_UP));
            }
            return roundTo3(new BigDecimal(raw));
        } catch (Exception e) {
            log.warn("metadata unavailable. field=frameRate, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
            return null;
        }
    }

    private LocalDateTime readRecordedAt(
            JsonNode format,
            JsonNode videoStream,
            Long userId,
            UUID videoId,
            String s3Key
    ) {
        String raw = firstTagValue(format, "creation_time");
        if (raw == null) {
            raw = firstTagValue(format, "com.apple.quicktime.creationdate");
        }
        if (raw == null) {
            raw = firstTagValue(videoStream, "creation_time");
        }
        if (raw == null) {
            log.warn("metadata unavailable. field=recorded_at, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
            return null;
        }

        LocalDateTime parsed = parseDateTime(raw);
        if (parsed == null) {
            log.warn("metadata unavailable. field=recorded_at, userId={}, videoId={}, s3Key={}", userId, videoId, s3Key);
        }
        return parsed;
    }

    private String firstTagValue(JsonNode node, String tagName) {
        if (node == null || node.get("tags") == null) {
            return null;
        }
        JsonNode tag = node.get("tags").get(tagName);
        if (tag == null || tag.asText().isBlank()) {
            return null;
        }
        return tag.asText();
    }

    private boolean waitForProcess(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (process.isAlive()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            long sleepMillis = remaining < 100 ? remaining : 100;
            Thread.sleep(sleepMillis);
        }
        return true;
    }

    private BigDecimal roundTo3(BigDecimal value) {
        return value.setScale(3, BigDecimal.ROUND_HALF_UP);
    }

    private LocalDateTime parseDateTime(String raw) {
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }
}
