package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADR-028 decision 16 and worker-calendar WC17/WC55: time before a worker's first schedule is unknown, and must not earn
 * rest-day premiums merely because a query includes a later schedule or ends at that schedule's first midnight.
 * Fixture writes use HTTP APIs against IntegrationTestSupport's disposable services; there is no SQL or historical clock edit.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LaborPayCalendarBoundaryRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private ProjectCalendarQuery calendars;
    @Autowired private WorkerCalendarQuery workerCalendars;

    private String projectId;

    private record Fixture(LocalDate today, ZoneId zone, String workerPath) {
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @Test
    void includingTheFirstScheduleTwoDaysLaterDoesNotChangeTodaysPay() throws Exception {
        Fixture fixture = fixtureWithFirstScheduleAfter(2);
        LocalDate today = fixture.today();

        // These reads use the same saved inputs. Only the pay query's last date changes.
        JsonNode todayAlone = pay(fixture, today);
        JsonNode withFutureDates = pay(fixture, today.plusDays(2));

        assertAll("A wider query must keep today's unknown calendar and pay unchanged",
            () -> assertPayWithoutRestPremium(todayAlone, today, "today only"),
            () -> assertPayWithoutRestPremium(withFutureDates, today, "today through the first schedule date"));
    }

    @Test
    void aFirstScheduleAtTheExclusiveEndDoesNotSetTodaysCalendarOrPremium() throws Exception {
        Fixture fixture = fixtureWithFirstScheduleAfter(1);
        LocalDate today = fixture.today();
        OffsetDateTime from = today.atStartOfDay(fixture.zone()).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(fixture.zone()).toOffsetDateTime();

        // Tomorrow's schedule starts exactly at this window's excluded end; today's calendar is still unknown.
        WorkerTime time = workerCalendars.workingTime(projectId, List.of(DEMO_OWNER), from, to).get(DEMO_OWNER);
        JsonNode todayPay = pay(fixture, today);

        assertAll("The next midnight must not make today's calendar known or award holiday pay",
            () -> {
                assertNotNull(time, "The public calendar query must include the requested worker");
                assertFalse(time.calendarSet(), "A schedule beginning at the excluded end must not set today's calendar");
                assertEquals(0L, time.seconds(), "There is no scheduled time in today's window");
            },
            () -> assertPayWithoutRestPremium(todayPay, today, "today with the first schedule starting tomorrow"));
    }

    private Fixture fixtureWithFirstScheduleAfter(int days) throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Pay calendar boundary " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        JsonNode previousZone = data(get("/projects/" + projectId + "/time-zone"), null);
        // Keep four past hours on today's project date at every UTC hour, away from midnight during the test.
        int offsetHours = 12 - OffsetDateTime.now(ZoneOffset.UTC).getHour();
        String zoneId = offsetHours == 0 ? "Etc/UTC" : "Etc/GMT" + (offsetHours > 0 ? "-" : "+") + Math.abs(offsetHours);
        data(put("/projects/" + projectId + "/time-zone"), Map.of("timeZone", zoneId,
            "expectedVersion", previousZone.path("version").asLong()));
        ZoneId zone = calendars.zone(projectId);
        LocalDate today = calendars.today(projectId);
        String worker = "/projects/" + projectId + "/workers/" + DEMO_OWNER;

        data(put(worker + "/labor-rates"), Map.of("hourlyRate", 60, "effectiveFrom", today.toString()));
        JsonNode rule = data(put("/projects/" + projectId + "/labor-premiums"), Map.of("effectiveFrom", today.toString(),
            "holidayPercent", 100, "restDaysAsHolidays", true)).path("current");
        assertTrue(rule.path("restDaysAsHolidays").asBoolean(), "The fixture must opt into rest-day holiday pay");

        // This new project's owner has no earlier schedule. Every weekday is named to avoid weekday-dependent fixtures.
        JsonNode calendar = data(put(worker + "/shifts"), Map.of("effectiveFrom", today.plusDays(days).toString(), "shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5, 6, 7)))));
        assertEquals(1, calendar.path("schedules").size(), "The fixture must have only its first, future schedule");
        assertEquals(today.plusDays(days).toString(), calendar.path("schedules").get(0).path("effectiveFrom").asText());

        OffsetDateTime clockOut = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2).withNano(0);
        OffsetDateTime clockIn = clockOut.minusHours(4);
        assertEquals(today, clockIn.atZoneSameInstant(zone).toLocalDate());
        assertEquals(today, clockOut.atZoneSameInstant(zone).toLocalDate());
        data(post(worker + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", clockIn.toString(), "clockOut", clockOut.toString()));
        return new Fixture(today, zone, worker);
    }

    private JsonNode pay(Fixture fixture, LocalDate through) throws Exception {
        return data(get(fixture.workerPath() + "/attendance-pay").param("from", fixture.today().toString())
            .param("to", through.toString()), null);
    }

    private static void assertPayWithoutRestPremium(JsonNode response, LocalDate today, String context) {
        assertEquals(1, response.path("days").size(), context + ": only today's attendance should be present");
        JsonNode day = response.path("days").get(0);
        assertAll(context,
            () -> assertEquals(today.toString(), day.path("workDate").asText()),
            () -> assertEquals(240, day.path("attendedMinutes").asInt()),
            () -> assertTrue(day.path("restDayHoliday").isBoolean(), "restDayHoliday must be an explicit boolean"),
            () -> assertFalse(day.path("restDayHoliday").asBoolean(), "No schedule applies today, so it is not a known rest day"),
            () -> assertFalse(day.path("holiday").asBoolean(), "The fixture has no project holidays"),
            () -> assertMoney("0", day.path("premiumMinutes"), "day premium minutes"),
            () -> assertMoney("240", day.path("paidMinutes"), "day paid minutes"),
            () -> assertMoney("240", day.path("amount"), "day amount"),
            () -> assertEquals(240, response.path("attendedMinutes").asInt()),
            () -> assertMoney("240", response.path("paidMinutes"), "total paid minutes"),
            () -> assertMoney("240", response.path("amount"), "total amount"),
            () -> assertTrue(response.path("costComplete").asBoolean(), "The fixture's hourly rate is known"));
    }

    private static void assertMoney(String expected, JsonNode actual, String field) {
        assertTrue(actual.isNumber(), field + " must be numeric");
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
            field + ": expected " + expected + " but was " + actual.asText());
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        return mvc.perform(request);
    }
}
