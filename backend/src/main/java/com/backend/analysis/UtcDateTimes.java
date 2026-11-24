package com.backend.analysis;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class UtcDateTimes {

    private UtcDateTimes() {
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (parsed.getOffset().getTotalSeconds() != 0) {
                return null;
            }
            return parsed.toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
