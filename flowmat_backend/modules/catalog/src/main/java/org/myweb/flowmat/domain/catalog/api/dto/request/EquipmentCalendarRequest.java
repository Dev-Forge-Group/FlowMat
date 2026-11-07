package org.myweb.flowmat.domain.catalog.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import org.myweb.flowmat.global.util.WholeNumberDeserializer;

/**
 * Replaces the equipment's shifts with {@code shifts}. Times are local to the planning time zone, such as "08:30"; a shift
 * that ends at or before its start runs past midnight, and equal times mean the whole day. {@code workDays} are ISO days,
 * 1 (Monday) to 7. Without {@code shifts}, the top-level fields describe a single shift, as before multi-shift calendars.
 * {@code effectiveFrom} is the date they apply from, today when left out (docs/architecture/adr/ADR-014-equipment-calendar-history.md);
 * {@code reason} says why, at most 200 characters (docs/architecture/adr/ADR-017-version-reasons.md).
 */
public record EquipmentCalendarRequest(
    String shiftStart,
    String shiftEnd,
    @JsonDeserialize(contentUsing = WholeWeekday.class) List<Integer> workDays,
    List<Shift> shifts,
    String effectiveFrom,
    String reason
) {
    /** The shifts from today, as callers sent them before V94. */
    public EquipmentCalendarRequest(String shiftStart, String shiftEnd, List<Integer> workDays, List<Shift> shifts) {
        this(shiftStart, shiftEnd, workDays, shifts, null, null);
    }

    /** The shifts without a reason, as callers sent them before V95. */
    public EquipmentCalendarRequest(String shiftStart, String shiftEnd, List<Integer> workDays, List<Shift> shifts, String effectiveFrom) {
        this(shiftStart, shiftEnd, workDays, shifts, effectiveFrom, null);
    }

    public record Shift(String shiftStart, String shiftEnd, @JsonDeserialize(contentUsing = WholeWeekday.class) List<Integer> workDays) {
    }

    /**
     * Read each day of the week exactly: 1.0 and the integer strings this API already took stay, 1.5 is refused instead of
     * becoming 1. The service still checks the range and missing values.
     */
    public static class WholeWeekday extends WholeNumberDeserializer {
        public static final String MESSAGE = "workDays are ISO days of the week, 1 (Monday) to 7 (Sunday).";

        public WholeWeekday() {
            this(MESSAGE);
        }

        protected WholeWeekday(String message) {
            super(message);
        }
    }
}
