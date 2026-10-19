package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Equipment calendars, downtime and the equipment check in work order readiness (docs/domain/equipment-schedule.md). */
@AutoConfigureMockMvc
class EquipmentScheduleIntegrationTest extends IntegrationTestSupport {

    // 2030-01-07 is a Monday; the planning zone defaults to Asia/Seoul (+09:00).
    private static final String MONDAY = "2030-01-07T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2030-01-14T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aCalendarAndDowntimeSetHowMuchTimeTheEquipmentHas() throws Exception {
        String mixer = equipment("MIX-" + tag(), 10);

        // Without a calendar the whole window is working time.
        availability(mixer, "2030-01-07T08:00:00+09:00", "2030-01-07T18:00:00+09:00")
            .andExpect(jsonPath("$.data.calendarSet").value(false))
            .andExpect(jsonPath("$.data.availableHours").value(10))
            .andExpect(jsonPath("$.data.capacity").value(100));

        call(put("/equipments/" + mixer + "/calendar"), json(calendar("09:00", "17:00", 5, 1, 2, 3, 4, 4)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.data.calendar.shifts.length()").value(1))
            .andExpect(jsonPath("$.data.calendar.shifts[0].workDays.length()").value(5))
            .andExpect(jsonPath("$.data.calendar.shifts[0].workDays[0]").value(1))
            .andExpect(jsonPath("$.data.calendar.shifts[0].shiftHours").value(8))
            .andExpect(jsonPath("$.data.calendar.weeklyHours").value(40));
        availability(mixer, MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath("$.data.calendarSet").value(true))
            .andExpect(jsonPath("$.data.workingHours").value(40))
            .andExpect(jsonPath("$.data.availableHours").value(40));

        // Down Tuesday 12:00-20:00: only 12:00-17:00 was working time.
        JsonNode schedule = data(call(post("/equipments/" + mixer + "/downtimes"), json(Map.of("downtimeType", "maintenance",
            "startsAt", "2030-01-08T12:00:00+09:00", "endsAt", "2030-01-08T20:00:00+09:00", "reason", "Belt change")))
            .andExpect(jsonPath("$.data.downtimes[0].hours").value(8))
            .andExpect(jsonPath("$.data.downtimes[0].reason").value("Belt change")));
        availability(mixer, MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath("$.data.workingHours").value(40))
            .andExpect(jsonPath("$.data.downtimeHours").value(5))
            .andExpect(jsonPath("$.data.availableHours").value(35))
            .andExpect(jsonPath("$.data.capacity").value(350));

        // A night shift runs past midnight into the next day's window.
        call(put("/equipments/" + mixer + "/calendar"), json(calendar("22:00", "06:00", 1)))
            .andExpect(jsonPath("$.data.calendar.shifts[0].shiftHours").value(8));
        availability(mixer, MONDAY, "2030-01-08T12:00:00+09:00").andExpect(jsonPath("$.data.availableHours").value(8));
        availability(mixer, "2030-01-08T00:00:00+09:00", "2030-01-08T12:00:00+09:00")
            .andExpect(jsonPath("$.data.availableHours").value(6));

        call(put("/equipments/" + mixer + "/calendar"), json(calendar("25:00", "06:00", 1))).andExpect(status().isBadRequest());
        call(put("/equipments/" + mixer + "/calendar"), json(calendar("08:00", "17:00"))).andExpect(status().isBadRequest());
        call(put("/equipments/" + mixer + "/calendar"), json(calendar("08:00", "17:00", 8))).andExpect(status().isBadRequest());
        call(post("/equipments/" + mixer + "/downtimes"), json(Map.of("startsAt", "2030-01-08T12:00:00+09:00",
            "endsAt", "2030-01-08T11:00:00+09:00"))).andExpect(status().isBadRequest());
        call(post("/equipments/" + mixer + "/downtimes"), json(Map.of("downtimeType", "coffee",
            "startsAt", "2030-01-08T12:00:00+09:00", "endsAt", "2030-01-08T13:00:00+09:00"))).andExpect(status().isBadRequest());
        availability(mixer, NEXT_MONDAY, MONDAY).andExpect(status().isBadRequest());
        availability(mixer, MONDAY, "2031-03-01T00:00:00+09:00").andExpect(status().isBadRequest());
        availability(mixer, "monday", NEXT_MONDAY)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("with an offset")));
        callAs("unrelated-user", get("/equipments/" + mixer + "/schedule")).andExpect(status().isForbidden());
        callAs("unrelated-user", put("/equipments/" + mixer + "/calendar").contentType(MediaType.APPLICATION_JSON)
            .content(json(calendar("08:00", "17:00", 1)))).andExpect(status().isForbidden());

        String downtimeId = schedule.path("downtimes").get(0).path("downtimeId").asText();
        call(delete("/equipments/" + mixer + "/downtimes/" + downtimeId))
            .andExpect(jsonPath("$.data.downtimes.length()").value(0));
        call(delete("/equipments/" + mixer + "/downtimes/" + downtimeId)).andExpect(status().isNotFound());
        call(delete("/equipments/" + mixer + "/calendar")).andExpect(jsonPath("$.data.calendar").isEmpty());
        availability(mixer, MONDAY, NEXT_MONDAY).andExpect(jsonPath("$.data.availableHours").value(168));
    }

    @Test
    void readinessChecksWhetherTheAssignedEquipmentHasTheTime() throws Exception {
        String tag = tag();
        String oven = equipment("OVEN-" + tag, 10);
        call(put("/equipments/" + oven + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        // 100 to make at 10 an hour needs 10 h; Monday 09:00 to Tuesday 17:00 has two 8 h shifts.
        String order = workOrder("Bake " + tag, "2030-01-07T09:00:00+09:00", "2030-01-08T17:00:00+09:00");
        readiness(order).andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')]").isEmpty());

        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", oven)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.equipmentId").value(oven));
        readiness(order)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].status").value(hasItem("ok")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message")
                .value(hasItem(containsString("needs 10 h of the 16 h available in the planned window"))));

        // Down all Tuesday: 8 h left for 10 h of work.
        call(post("/equipments/" + oven + "/downtimes"), json(Map.of("downtimeType", "breakdown",
            "startsAt", "2030-01-08T00:00:00+09:00", "endsAt", "2030-01-09T00:00:00+09:00"))).andExpect(status().isOk());
        readiness(order)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message")
                .value(hasItem(containsString("needs 10 h for 100 but has only 8 h available in the planned window (8 h down)"))));

        // Another approved order on the same oven at the same time is called out.
        String other = workOrder("Other " + tag, "2030-01-08T09:00:00+09:00", "2030-01-09T17:00:00+09:00");
        call(put("/work-orders/" + other + "/equipment"), json(Map.of("equipmentId", oven))).andExpect(status().isOk());
        readiness(order).andExpect(jsonPath("$.data.checks[?(@.code == 'schedule')]").isEmpty());
        String otherNumber = data(call(post("/work-orders/" + other + "/approve"))).path("workOrderNumber").asText();
        readiness(order)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'schedule')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'schedule')].message").value(hasItem(containsString(otherNumber))));

        call(put("/equipments/" + oven), json(Map.of("equipmentStatus", "maintenance"))).andExpect(status().isOk());
        readiness(order)
            .andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message").value(hasItem(containsString("under maintenance"))));

        Map<String, Object> none = new HashMap<>();
        none.put("equipmentId", null);
        call(put("/work-orders/" + order + "/equipment"), json(none)).andExpect(jsonPath("$.data.equipmentId").isEmpty());
        readiness(order).andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')]").isEmpty());

        String retired = equipment("OLD-" + tag, 5);
        call(put("/equipments/" + retired), json(Map.of("equipmentStatus", "inactive"))).andExpect(status().isOk());
        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", retired))).andExpect(status().isConflict());
        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", "no-such-equipment")))
            .andExpect(status().isBadRequest());
        call(post("/work-orders/" + other + "/cancel")).andExpect(status().isOk());
        call(put("/work-orders/" + other + "/equipment"), json(Map.of("equipmentId", oven))).andExpect(status().isConflict());
        callAs("unrelated-user", put("/work-orders/" + order + "/equipment").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("equipmentId", oven)))).andExpect(status().isForbidden());
    }

    @Test
    void noShiftStartsOnAProjectHoliday() throws Exception {
        // 2032-03-01 is a Monday; no other test plans in that week, and the holidays are removed again at the end.
        String monday = "2032-03-01T00:00:00+09:00";
        String nextMonday = "2032-03-08T00:00:00+09:00";
        String press = equipment("HOL-" + tag(), 10);
        call(put("/equipments/" + press + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        availability(press, monday, nextMonday).andExpect(jsonPath("$.data.workingHours").value(40));

        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2032-03-03", "name", "Founding day")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.date == '2032-03-03')].name").value(hasItem("Founding day")));
        // A Saturday holiday takes nothing away: no shift starts that day.
        JsonNode holidays = data(call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2032-03-06"))));
        availability(press, monday, nextMonday)
            .andExpect(jsonPath("$.data.workingHours").value(32))
            .andExpect(jsonPath("$.data.availableHours").value(32))
            .andExpect(jsonPath("$.data.capacity").value(320))
            .andExpect(jsonPath("$.data.holidays.length()").value(1))
            .andExpect(jsonPath("$.data.holidays[0]").value("2032-03-03"));

        // A night shift that starts the evening before still runs into the holiday; the holiday's own shift does not start.
        call(put("/equipments/" + press + "/calendar"), json(calendar("22:00", "06:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        availability(press, "2032-03-03T00:00:00+09:00", "2032-03-04T00:00:00+09:00").andExpect(jsonPath("$.data.workingHours").value(6));

        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2032-03-03"))).andExpect(status().isConflict());
        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "03/03/2032"))).andExpect(status().isBadRequest());
        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT))).andExpect(status().isBadRequest());
        callAs("hol-outsider", get("/holidays").param("projectId", DEMO_PROJECT)).andExpect(status().isForbidden());

        List<String> ours = new java.util.ArrayList<>();
        for (JsonNode holiday : holidays) {
            if (holiday.path("date").asText().startsWith("2032-03-0")) {
                ours.add(holiday.path("holidayId").asText());
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, ours.size());
        for (String holidayId : ours) {
            call(delete("/holidays/" + holidayId)).andExpect(status().isOk());
        }
        call(delete("/holidays/" + ours.get(0))).andExpect(status().isNotFound());
        call(put("/equipments/" + press + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        availability(press, monday, nextMonday)
            .andExpect(jsonPath("$.data.workingHours").value(40))
            .andExpect(jsonPath("$.data.holidays.length()").value(0));
    }

    @Test
    void aCalendarCanHoldSeveralShiftsThatDoNotOverlap() throws Exception {
        String line = equipment("SHF-" + tag(), 10);
        // Early and late shifts on weekdays, which only touch at 14:00, and a short Saturday shift.
        call(put("/equipments/" + line + "/calendar"), json(shifts(calendar("06:00", "14:00", 1, 2, 3, 4, 5),
            calendar("14:00", "22:00", 1, 2, 3, 4, 5), calendar("08:00", "12:00", 6))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.calendar.shifts.length()").value(3))
            .andExpect(jsonPath("$.data.calendar.shifts[0].shiftStart").value("06:00"))
            .andExpect(jsonPath("$.data.calendar.shifts[1].shiftStart").value("08:00"))
            .andExpect(jsonPath("$.data.calendar.shifts[1].workDays[0]").value(6))
            .andExpect(jsonPath("$.data.calendar.shifts[2].shiftStart").value("14:00"))
            .andExpect(jsonPath("$.data.calendar.weeklyHours").value(84));
        availability(line, MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath("$.data.workingHours").value(84))
            .andExpect(jsonPath("$.data.capacity").value(840));
        availability(line, "2030-01-07T12:00:00+09:00", "2030-01-07T16:00:00+09:00")
            .andExpect(jsonPath("$.data.workingHours").value(4));

        // Overlaps are refused, naming the day; a Sunday night shift wraps into Monday morning.
        call(put("/equipments/" + line + "/calendar"), json(shifts(calendar("06:00", "14:00", 1, 2, 3, 4, 5),
            calendar("12:00", "20:00", 3))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap on Wed."));
        call(put("/equipments/" + line + "/calendar"), json(shifts(calendar("22:00", "06:00", 7), calendar("05:00", "09:00", 1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap on Mon."));
        call(put("/equipments/" + line + "/calendar"), json(shifts(calendar("08:00", "17:00", 1), calendar("25:00", "06:00", 2))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Shift 2: shiftStart must be a time such as 08:30."));
        call(put("/equipments/" + line + "/calendar"), json(shifts())).andExpect(status().isBadRequest());
        call(put("/equipments/" + line + "/calendar"), json(shifts(calendar("00:00", "01:00", 1), calendar("01:00", "02:00", 1),
            calendar("02:00", "03:00", 1), calendar("03:00", "04:00", 1), calendar("04:00", "05:00", 1),
            calendar("05:00", "06:00", 1), calendar("06:00", "07:00", 1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A calendar can have at most 6 shifts."));
        availability(line, MONDAY, NEXT_MONDAY).andExpect(jsonPath("$.data.workingHours").value(84));

        // A holiday that takes out both weekday shifts is listed once. 2031-06-04 is a Wednesday no other test uses.
        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2031-06-04"))).andExpect(status().isOk());
        try {
            availability(line, "2031-06-02T00:00:00+09:00", "2031-06-09T00:00:00+09:00")
                .andExpect(jsonPath("$.data.workingHours").value(68))
                .andExpect(jsonPath("$.data.holidays.length()").value(1))
                .andExpect(jsonPath("$.data.holidays[0]").value("2031-06-04"));
        } finally {
            for (JsonNode holiday : data(call(get("/holidays").param("projectId", DEMO_PROJECT)))) {
                if ("2031-06-04".equals(holiday.path("date").asText())) {
                    call(delete("/holidays/" + holiday.path("holidayId").asText())).andExpect(status().isOk());
                }
            }
        }

        // The single-shift body still replaces everything with one shift.
        call(put("/equipments/" + line + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5)))
            .andExpect(jsonPath("$.data.calendar.shifts.length()").value(1))
            .andExpect(jsonPath("$.data.calendar.weeklyHours").value(40));
        call(delete("/equipments/" + line + "/calendar")).andExpect(jsonPath("$.data.calendar").isEmpty());
    }

    @Test
    void aDateCanHaveItsOwnShifts() throws Exception {
        String press = equipment("DAY-" + tag(), 10);
        // 2031-03-03 is a Monday; no other test plans in that week, and the holiday is removed again at the end.
        String week = "2031-03-03T00:00:00+09:00";
        String nextWeek = "2031-03-10T00:00:00+09:00";
        call(put("/equipments/" + press + "/days/2031-03-05"), json(Map.of("shifts", List.of())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("calendar first")));
        call(put("/equipments/" + press + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(40));

        // Wednesday closed, Saturday worked 08:00-12:00, Tuesday longer in two shifts that touch.
        call(put("/equipments/" + press + "/days/2031-03-05"), json(Map.of("shifts", List.of(), "reason", "Stock take")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].date").value("2031-03-05"))
            .andExpect(jsonPath("$.data.days[0].closed").value(true))
            .andExpect(jsonPath("$.data.days[0].hours").value(0))
            .andExpect(jsonPath("$.data.days[0].reason").value("Stock take"));
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(32));
        call(put("/equipments/" + press + "/days/2031-03-08"), json(Map.of("shifts", List.of(dayShift("08:00", "12:00")))))
            .andExpect(status().isOk());
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(36));
        call(put("/equipments/" + press + "/days/2031-03-04"), json(Map.of("shifts",
                List.of(dayShift("06:00", "14:00"), dayShift("14:00", "18:00")))))
            .andExpect(jsonPath("$.data.days.length()").value(3))
            .andExpect(jsonPath("$.data.days[0].date").value("2031-03-04"))
            .andExpect(jsonPath("$.data.days[0].hours").value(12))
            .andExpect(jsonPath("$.data.days[0].shifts[1].shiftStart").value("14:00"))
            .andExpect(jsonPath("$.data.days[0].closed").value(false));
        // Mon 8 + Tue 12 + Wed 0 + Thu 8 + Fri 8 + Sat 4.
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(40));

        // A project holiday takes out the calendar's Thursday, but not a Thursday of its own.
        call(post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2031-03-06"))).andExpect(status().isOk());
        try {
            availability(press, week, nextWeek)
                .andExpect(jsonPath("$.data.availableHours").value(32))
                .andExpect(jsonPath("$.data.holidays[0]").value("2031-03-06"));
            call(put("/equipments/" + press + "/days/2031-03-06"), json(Map.of("shifts", List.of(dayShift("10:00", "12:00")))))
                .andExpect(status().isOk());
            availability(press, week, nextWeek)
                .andExpect(jsonPath("$.data.availableHours").value(34))
                .andExpect(jsonPath("$.data.holidays.length()").value(0));
        } finally {
            for (JsonNode holiday : data(call(get("/holidays").param("projectId", DEMO_PROJECT)))) {
                if ("2031-03-06".equals(holiday.path("date").asText())) {
                    call(delete("/holidays/" + holiday.path("holidayId").asText())).andExpect(status().isOk());
                }
            }
        }

        call(put("/equipments/" + press + "/days/2031-03-07"), json(Map.of("shifts",
                List.of(dayShift("09:00", "12:00"), dayShift("11:00", "13:00")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap."));
        call(put("/equipments/" + press + "/days/2031-03-07"), json(Map.of("shifts", List.of(dayShift("25:00", "12:00")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("shiftStart must be a time such as 08:30."));
        call(put("/equipments/" + press + "/days/2031-03-07"), json(Map.of("shifts", List.of(dayShift("00:00", "02:00"),
                dayShift("02:00", "04:00"), dayShift("04:00", "06:00"), dayShift("06:00", "08:00"), dayShift("08:00", "10:00"),
                dayShift("10:00", "12:00"), dayShift("12:00", "14:00")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A day can have at most 6 shifts."));
        call(put("/equipments/" + press + "/days/07-03-2031"), json(Map.of("shifts", List.of())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("such as 2030-01-09")));
        callAs("day-outsider", put("/equipments/" + press + "/days/2031-03-07").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("shifts", List.of())))).andExpect(status().isForbidden());

        // Back to the calendar's Saturday (none): Mon 8 + Tue 12 + Wed 0 + Thu 2 + Fri 8.
        call(delete("/equipments/" + press + "/days/2031-03-08")).andExpect(jsonPath("$.data.days.length()").value(3));
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(30));
        call(delete("/equipments/" + press + "/days/2031-03-08")).andExpect(status().isNotFound());
    }

    @Test
    void daysChangeOverARangeAndCopyToOtherEquipment() throws Exception {
        String press = equipment("RANGE-" + tag(), 10);
        String otherCode = "COPY-" + tag();
        String other = equipment(otherCode, 10);
        // 2031-04-07 is a Monday; no other test plans in that week.
        String week = "2031-04-07T00:00:00+09:00";
        String nextWeek = "2031-04-14T00:00:00+09:00";
        call(put("/equipments/" + press + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());

        // Monday to Wednesday closed in one change (D6).
        call(put("/equipments/" + press + "/days/2031-04-07"), json(Map.of("shifts", List.of(), "reason", "Line move",
                "throughDate", "2031-04-09")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(3))
            .andExpect(jsonPath("$.data.days[2].date").value("2031-04-09"))
            .andExpect(jsonPath("$.data.days[1].closed").value(true))
            .andExpect(jsonPath("$.data.days[1].reason").value("Line move"));
        availability(press, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(16));
        call(put("/equipments/" + press + "/days/2031-04-07"), json(Map.of("shifts", List.of(), "throughDate", "2031-04-06")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("throughDate must not be before the date."));
        call(put("/equipments/" + press + "/days/2031-04-07"), json(Map.of("shifts", List.of(), "reason", "Line move",
                "throughDate", "2031-06-07")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(62));
        call(delete("/equipments/" + press + "/days/2031-04-10").param("through", "2031-06-07"))
            .andExpect(jsonPath("$.data.days.length()").value(3));
        call(put("/equipments/" + press + "/days/2031-04-07"), json(Map.of("shifts", List.of(), "throughDate", "2031-06-08")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A range can cover at most 62 days."));
        call(put("/equipments/" + press + "/days/2031-04-07"), json(Map.of("shifts", List.of(), "throughDate", "09-04-2031")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("throughDate must be a date such as 2030-01-09."));
        call(put("/equipments/" + press + "/days/2031-04-12"), json(shifts(dayShift("08:00", "12:00")))).andExpect(status().isOk());

        // Copied to another equipment of the project (D7): it needs a calendar, and its other dates stay.
        Map<String, Object> copy = Map.of("toEquipmentId", other, "fromDate", "2031-04-09");
        call(post("/equipments/" + press + "/days/copy"), json(copy))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Set the calendar of " + otherCode + " first; a date's shifts take the place of the calendar's."));
        call(put("/equipments/" + other + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        call(put("/equipments/" + other + "/days/2031-04-10"), json(Map.of("shifts", List.of()))).andExpect(status().isOk());
        call(put("/equipments/" + other + "/days/2031-04-12"), json(shifts(dayShift("13:00", "15:00")))).andExpect(status().isOk());
        call(post("/equipments/" + press + "/days/copy"), json(copy))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.equipmentId").value(other))
            .andExpect(jsonPath("$.data.days.length()").value(3))
            .andExpect(jsonPath("$.data.days[0].date").value("2031-04-09"))
            .andExpect(jsonPath("$.data.days[0].reason").value("Line move"))
            .andExpect(jsonPath("$.data.days[2].shifts[0].shiftStart").value("08:00"));
        // Mon 8 + Tue 8 + Wed 0 (copied) + Thu 0 (its own) + Fri 8 + Sat 4 (copied over its 13-15).
        availability(other, week, nextWeek).andExpect(jsonPath("$.data.availableHours").value(28));
        call(post("/equipments/" + press + "/days/copy"), json(Map.of("toEquipmentId", other, "fromDate", "2031-05-01")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("There are no day changes to copy."));
        call(post("/equipments/" + press + "/days/copy"), json(Map.of("toEquipmentId", other, "fromDate", "2031-04-09",
                "throughDate", "2031-04-08")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("throughDate must not be before fromDate."));
        call(post("/equipments/" + press + "/days/copy"), json(Map.of("toEquipmentId", press)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose another equipment to copy to."));
        call(post("/equipments/" + press + "/days/copy"), json(Map.of("toEquipmentId", "no-such-equipment")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("toEquipmentId is not an equipment of this project."));
        callAs("day-outsider", post("/equipments/" + press + "/days/copy").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("toEquipmentId", other)))).andExpect(status().isForbidden());

        // A range back to the calendar at once, Saturday left (D6).
        call(delete("/equipments/" + press + "/days/2031-04-07").param("through", "2031-04-11"))
            .andExpect(jsonPath("$.data.days.length()").value(1))
            .andExpect(jsonPath("$.data.days[0].date").value("2031-04-12"));
        call(delete("/equipments/" + press + "/days/2031-04-07").param("through", "2031-04-11")).andExpect(status().isNotFound());
        call(delete("/equipments/" + press + "/days/2031-04-07").param("through", "2031-04-06"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("through must not be before the date."));
    }

    @Test
    void aRangeCanTakeOnlySomeDaysOfTheWeek() throws Exception {
        String press = equipment("WEEKDAY-" + tag(), 10);
        call(put("/equipments/" + press + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        // 2031-05-05 is a Monday: Mondays, Wednesdays and Fridays of two weeks are closed (D6).
        call(put("/equipments/" + press + "/days/2031-05-05"), json(Map.of("shifts", List.of(), "throughDate", "2031-05-18",
                "weekDays", List.of(1, 3, 5))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(6))
            .andExpect(jsonPath("$.data.days[1].date").value("2031-05-07"))
            .andExpect(jsonPath("$.data.days[5].date").value("2031-05-16"));
        // Tuesday and Thursday are left: 16 hours that week.
        availability(press, "2031-05-05T00:00:00+09:00", "2031-05-12T00:00:00+09:00")
            .andExpect(jsonPath("$.data.availableHours").value(16));
        call(put("/equipments/" + press + "/days/2031-05-05"), json(Map.of("shifts", List.of(), "throughDate", "2031-05-18",
                "weekDays", List.of(8))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("weekDays are 1 (Monday) to 7 (Sunday)."));
        call(put("/equipments/" + press + "/days/2031-05-10"), json(Map.of("shifts", List.of(), "throughDate", "2031-05-11",
                "weekDays", List.of(1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("None of the dates fall on the chosen days of the week."));
    }

    @Test
    void daysOfTheWeekAreWholeNumbers() throws Exception {
        String press = equipment("WHOLE-" + tag(), 10);
        // 1.5 used to be read as 1 and saved as Monday (error agent report, 2026-10-18).
        call(put("/equipments/" + press + "/calendar"), "{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[1.5]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("workDays are ISO days of the week, 1 (Monday) to 7 (Sunday)."));
        call(put("/equipments/" + press + "/calendar"),
                "{\"shifts\":[{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[6.5]}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("workDays are ISO days of the week, 1 (Monday) to 7 (Sunday)."));
        call(put("/equipments/" + press + "/days/2031-05-05"), "{\"shifts\":[],\"throughDate\":\"2031-05-18\",\"weekDays\":[7.5]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("weekDays are 1 (Monday) to 7 (Sunday)."));
        call(put("/equipments/" + press + "/calendar"), "{\"shiftStart\":\"09:00\"")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("as JSON")));
        // Exact numbers and the integer strings the API took before still choose their day; nothing above was saved.
        call(put("/equipments/" + press + "/calendar"), "{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[1.0,\"7\"]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.calendar.shifts.length()").value(1))
            .andExpect(jsonPath("$.data.calendar.shifts[0].workDays.length()").value(2))
            .andExpect(jsonPath("$.data.calendar.shifts[0].workDays[0]").value(1))
            .andExpect(jsonPath("$.data.calendar.shifts[0].workDays[1]").value(7))
            .andExpect(jsonPath("$.data.days.length()").value(0));
    }

    private static Map<String, Object> dayShift(String start, String end) {
        return Map.of("shiftStart", start, "shiftEnd", end);
    }

    @SafeVarargs
    private static Map<String, Object> shifts(Map<String, Object>... shifts) {
        return Map.of("shifts", List.of(shifts));
    }

    private String equipment(String code, int capacityPerHour) throws Exception {
        return id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine",
            "details", Map.of("capacityPerHour", capacityPerHour)))), "equipmentId");
    }

    private String workOrder(String title, String start, String end) throws Exception {
        return id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetQuantity", 100, "plannedStartAt", start, "plannedEndAt", end))), "workOrderId");
    }

    private static Map<String, Object> calendar(String start, String end, Integer... days) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(days));
    }

    private ResultActions availability(String equipmentId, String from, String to) throws Exception {
        return call(get("/equipments/" + equipmentId + "/availability").param("from", from).param("to", to));
    }

    private ResultActions readiness(String workOrderId) throws Exception {
        return call(get("/work-orders/" + workOrderId + "/readiness")).andExpect(status().isOk());
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
