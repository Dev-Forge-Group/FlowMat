package org.myweb.flowmat.global.util;

import java.time.OffsetDateTime;

public final class DateTimeUtils {

    private DateTimeUtils() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
