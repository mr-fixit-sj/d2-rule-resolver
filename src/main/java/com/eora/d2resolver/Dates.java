package com.eora.d2resolver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Input dates are ISO (yyyy-MM-dd or yyyy-MM-dd HH:mm:ss / yyyy-MM-ddTHH:mm:ss). */
final class Dates {

    private Dates() {}

    static LocalDate parse(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDateTime parseDateTime(String s) {
        LocalDate d = parse(s);
        if (d == null) return null;
        LocalTime t = LocalTime.MIDNIGHT;
        if (s.length() >= 19) {
            try {
                t = LocalTime.parse(s.substring(11, 19));
            } catch (DateTimeParseException ignored) {
                // date-only precision is fine for folder paths and names
            }
        }
        return d.atTime(t);
    }

    /** Formats with java.text.SimpleDateFormat semantics, which is what D2 uses for $value(attr.pattern). */
    static String format(LocalDateTime value, String pattern) {
        Date date = Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
        return new SimpleDateFormat(pattern).format(date);
    }
}
