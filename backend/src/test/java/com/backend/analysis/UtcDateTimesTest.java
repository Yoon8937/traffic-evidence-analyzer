package com.backend.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UtcDateTimesTest {

    @Test
    void parsesZulu() {
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 0), UtcDateTimes.parse("2026-11-24T04:00:00Z"));
    }

    @Test
    void parsesPlusZeroOffset() {
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 0), UtcDateTimes.parse("2026-11-24T04:00:00+00:00"));
    }

    @Test
    void rejectsNonUtcOffset() {
        assertNull(UtcDateTimes.parse("2026-11-24T13:00:00+09:00"));
    }

    @Test
    void rejectsUnparseable() {
        assertNull(UtcDateTimes.parse("not-a-date"));
        assertNull(UtcDateTimes.parse(null));
    }
}
