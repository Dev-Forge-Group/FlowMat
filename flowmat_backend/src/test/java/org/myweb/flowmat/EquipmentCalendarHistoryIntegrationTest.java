package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** Versions of an equipment calendar (docs/architecture/adr/ADR-014-equipment-calendar-history.md, V94). */
@AutoConfigureMockMvc
class EquipmentCalendarHistoryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void calendarsApplyFromTheirStartDateAndEarlierDatesKeepTheirs() throws Exception {
        String press = equipment("HIST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // Before any calendar the press runs around the clock, as before.
        JsonNode first = data(call(put("/equipments/" + press + "/calendar"), json(calendar("08:00", "16:00", null))));
        assertEquals(1, first.path("versions").size());
        assertTrue(first.path("versions").get(0).path("current").asBoolean());
        assertFalse(first.path("versions").get(0).path("alwaysRunning").asBoolean());
        String today = first.path("versions").get(0).path("effectiveFrom").asText();
        OffsetDateTime twoWeeksAgo = LocalDate.parse(today).minusDays(14).atStartOfDay().atOffset(ZoneOffset.ofHours(9));
        JsonNode past = availability(press, twoWeeksAgo);
        assertFalse(past.path("calendarSet").asBoolean());
        assertEquals(168.0, past.path("workingHours").asDouble());

        // From Monday 2041-09-09 it runs 06-16; the week before keeps 08-16, and today's calendar is still shown.
        JsonNode later = data(call(put("/equipments/" + press + "/calendar"), json(calendar("06:00", "16:00", "2041-09-09"))));
        assertEquals(2, later.path("versions").size());
        assertEquals("08:00", later.path("calendar").path("shifts").get(0).path("shiftStart").asText());
        assertEquals(40.0, hours(press, "2041-09-02"));
        assertEquals(50.0, hours(press, "2041-09-09"));
        // Saving again from the same date changes that version.
        data(call(put("/equipments/" + press + "/calendar"), json(calendar("06:00", "17:00", "2041-09-09"))));
        assertEquals(55.0, hours(press, "2041-09-09"));

        // Removing the calendar from 2041-09-16 runs it around the clock from then; earlier weeks keep their shifts.
        JsonNode cleared = data(call(delete("/equipments/" + press + "/calendar").param("effectiveFrom", "2041-09-16")));
        assertEquals(3, cleared.path("versions").size());
        assertTrue(cleared.path("versions").get(2).path("alwaysRunning").asBoolean());
        assertEquals(168.0, hours(press, "2041-09-16"));
        assertEquals(55.0, hours(press, "2041-09-09"));
        // A date's own shifts need a calendar on that date (D2).
        Map<String, Object> closed = new HashMap<>();
        closed.put("shifts", List.of());
        call(put("/equipments/" + press + "/days/2041-09-17"), json(closed)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Set the equipment's calendar first; a date's shifts take the place of the calendar's."));
        call(put("/equipments/" + press + "/days/2041-09-10"), json(closed)).andExpect(status().isOk());
        assertEquals(44.0, hours(press, "2041-09-09"));

        String middle = cleared.path("versions").get(1).path("scheduleId").asText();
        JsonNode version = data(call(get("/equipments/" + press + "/calendar/versions/" + middle)));
        assertFalse(version.path("current").asBoolean());
        assertEquals("2041-09-09", version.path("effectiveFrom").asText());
        assertEquals(55.0, version.path("weeklyHours").asDouble());

        // A start before today is refused.
        String yesterday = LocalDate.parse(today).minusDays(1).toString();
        call(put("/equipments/" + press + "/calendar"), json(calendar("08:00", "16:00", yesterday))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value("effectiveFrom must be today (" + today + ") or later; change a past date with that date's own shifts."));

        // A version that has started stays; one that has not can go.
        String current = cleared.path("versions").get(0).path("scheduleId").asText();
        call(delete("/equipments/" + press + "/calendar/versions/" + current)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This calendar version has started; save a new one from a later date instead."));
        String last = cleared.path("versions").get(2).path("scheduleId").asText();
        JsonNode removed = data(call(delete("/equipments/" + press + "/calendar/versions/" + last)));
        assertEquals(2, removed.path("versions").size());
        assertEquals(55.0, hours(press, "2041-09-16"));
        call(get("/equipments/" + press + "/calendar/versions/" + last)).andExpect(status().isNotFound());
    }

    private String equipment(String code) throws Exception {
        return data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10))))).path("equipmentId").asText();
    }

    /** Monday to Friday, from {@code effectiveFrom} (today when null). */
    private static Map<String, Object> calendar(String start, String end, String effectiveFrom) {
        Map<String, Object> calendar = new HashMap<>();
        calendar.put("shiftStart", start);
        calendar.put("shiftEnd", end);
        calendar.put("workDays", List.of(1, 2, 3, 4, 5));
        if (effectiveFrom != null) {
            calendar.put("effectiveFrom", effectiveFrom);
        }
        return calendar;
    }

    /** Working hours in the week from that Monday. */
    private double hours(String equipmentId, String monday) throws Exception {
        return availability(equipmentId, OffsetDateTime.parse(monday + "T00:00:00+09:00")).path("workingHours").asDouble();
    }

    private JsonNode availability(String equipmentId, OffsetDateTime from) throws Exception {
        return data(call(get("/equipments/" + equipmentId + "/availability").param("from", from.toString())
            .param("to", from.plusDays(7).toString())));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
