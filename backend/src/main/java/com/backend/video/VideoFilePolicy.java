package com.backend.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class VideoFilePolicy {

    private final List<String> allowedExtensions;
    private final List<String> allowedContentTypes;

    public VideoFilePolicy(
            @Value("${app.video.allowed-extensions}") String allowedExtensions,
            @Value("${app.video.allowed-content-types}") String allowedContentTypes
    ) {
        this.allowedExtensions = splitValues(allowedExtensions);
        this.allowedContentTypes = splitValues(allowedContentTypes);
    }

    public boolean isAllowedExtension(String filename) {
        String extension = extractExtension(filename);
        if (extension == null) {
            return false;
        }
        return allowedExtensions.contains(extension);
    }

    public boolean isAllowedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return allowedContentTypes.contains(normalized);
    }

    public String extractExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return null;
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private List<String> splitValues(String raw) {
        List<String> values = new ArrayList<String>();
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String value = parts[i].trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
