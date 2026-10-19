package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A fractional ISO weekday must not close a different day by being truncated to an integer (D6). */
@AutoConfigureMockMvc
class EquipmentDayWeekdayInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "6.5", "7.5"})
    void fractionalFiltersAreRejectedWithoutChangingExistingDays(String literal) throws Exception {
        String equipment = equipment();
        save(equipment, "1").andExpect(status().isOk());
        var before = jdbc.queryForList("select * from equipment_day_override where equipment_id=? order by override_date", equipment);

        save(equipment, literal).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("weekDays")));

        assertEquals(before, jdbc.queryForList("select * from equipment_day_override where equipment_id=? order by override_date", equipment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"shifts\":[],\"weekDays\":[true]}"})
    void malformedJsonOrWrongFieldTypesAreInputErrorsWithoutChangingDays(String body) throws Exception {
        String equipment = equipment();
        save(equipment, "1").andExpect(status().isOk());
        var before = jdbc.queryForList("select * from equipment_day_override where equipment_id=? order by override_date", equipment);

        mvc.perform(put("/equipments/" + equipment + "/days/2041-03-04").header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());

        assertEquals(before, jdbc.queryForList("select * from equipment_day_override where equipment_id=? order by override_date", equipment));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "7,7", "1.0,1", "7.0,7", "\"1\",1", "\"7\",7"})
    void exactNumbersAndExistingIntegerStringsChooseTheExactDay(String literal, int day) throws Exception {
        String equipment = equipment();

        save(equipment, literal).andExpect(status().isOk());

        assertEquals(List.of(LocalDate.parse("2041-03-04").plusDays(day - 1)), jdbc.queryForList(
            "select override_date from equipment_day_override where equipment_id=? order by override_date", LocalDate.class, equipment));
    }

    private String equipment() throws Exception {
        String key = UUID.randomUUID().toString();
        var created = mvc.perform(post("/equipments").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "DAYS-" + key,
                "equipmentName", key, "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))))
            .andExpect(status().isOk()).andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("equipmentId").asText();
        mvc.perform(put("/equipments/" + id + "/calendar").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[1,2,3,4,5,6,7]}"))
            .andExpect(status().isOk());
        return id;
    }

    private ResultActions save(String equipment, String literal) throws Exception {
        return mvc.perform(put("/equipments/" + equipment + "/days/2041-03-04").header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON).content("{\"shifts\":[],\"reason\":\"Maintenance\","
                + "\"throughDate\":\"2041-03-10\",\"weekDays\":[" + literal + "]}"));
    }

    private String bearer() { return "Bearer " + jwt.generateAccessToken(DEMO_OWNER); }
}
