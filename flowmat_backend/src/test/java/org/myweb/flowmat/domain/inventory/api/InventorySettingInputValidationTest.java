package org.myweb.flowmat.domain.inventory.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.application.InventorySettingService;
import org.myweb.flowmat.domain.inventory.domain.entity.InventorySetting;
import org.myweb.flowmat.domain.inventory.repository.InventorySettingRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real request binding and service validation, with no database or authentication fixture. */
class InventorySettingInputValidationTest {
    private static final String PROJECT = "setting-input-project";
    private final InventorySettingRepository settings = mock(InventorySettingRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InventorySettingController(new InventorySettingService(settings, access)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"projectId\":\"setting-input-project\"}",
        "{\"projectId\":\"setting-input-project\",\"storageConditionPolicy\":null}",
        "{\"projectId\":\"setting-input-project\",\"storageConditionPolicy\":\"  \"}",
        "{\"projectId\":\"setting-input-project\",\"storageConditionPolicy\":\"block\"}"
    })
    void aPolicyMustBeProvidedAsWarnOrRefuse(String body) throws Exception {
        var response = mvc.perform(put("/inventory-settings").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("storageConditionPolicy"));
        verify(access).requireProjectOwnerAccess(PROJECT);
        verifyNoInteractions(settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "-0.5", "0.000000000000000000000001"})
    void aFractionalVersionCannotBeTruncatedIntoTheVersionOfASavedSetting(String version) throws Exception {
        existingWarningSetting();
        var response = mvc.perform(put("/inventory-settings").contentType(MediaType.APPLICATION_JSON).content(body(version)))
            .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("expectedVersion"));
        verifyNoInteractions(settings, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "\"0\""})
    void anExactVersionRetainsTheExistingAcceptedNumberFormats(String version) throws Exception {
        existingWarningSetting();
        var response = mvc.perform(put("/inventory-settings").contentType(MediaType.APPLICATION_JSON).content(body(version)))
            .andReturn().getResponse();

        assertEquals(200, response.getStatus());
        JsonNode data = mapper.readTree(response.getContentAsString()).path("data");
        assertEquals("refuse", data.path("storageConditionPolicy").asText());
        assertEquals(1, data.path("version").asLong());
        verify(settings).saveAndFlush(any(InventorySetting.class));
    }

    private void existingWarningSetting() {
        InventorySetting current = new InventorySetting();
        current.setProjectId(PROJECT);
        current.setStorageConditionPolicy("warn");
        current.setVersion(0);
        when(settings.findById(PROJECT)).thenReturn(Optional.of(current));
        when(settings.saveAndFlush(any(InventorySetting.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static String body(String version) {
        return "{\"projectId\":\"" + PROJECT + "\",\"storageConditionPolicy\":\"refuse\",\"expectedVersion\":" + version + "}";
    }
}
