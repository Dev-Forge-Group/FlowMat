package org.myweb.flowmat.domain.catalog.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * The shifts that start on one date in place of the calendar's (docs/domain/equipment-schedule.md "날짜별 교대"); none or
 * an empty list closes the day. Times are local to the planning time zone, such as "08:30"; a shift that ends at or
 * before its start runs past midnight, and equal times mean the whole day. {@code throughDate} (YYYY-MM-DD) gives every
 * date from the one in the path through it the same shifts (D6); {@code weekDays} (ISO, 1 = Monday) keeps only the dates
 * on those days of the week, all of them when empty.
 */
public record EquipmentDayRequest(List<Shift> shifts, String reason, String throughDate,
                                  @JsonDeserialize(contentUsing = WholeDay.class) List<Integer> weekDays) {

    public record Shift(String shiftStart, String shiftEnd) {
    }

    /** Read each filter day exactly, as the calendar's work days. */
    public static final class WholeDay extends EquipmentCalendarRequest.WholeWeekday {
        public static final String MESSAGE = "weekDays are 1 (Monday) to 7 (Sunday).";

        public WholeDay() {
            super(MESSAGE);
        }
    }
}
