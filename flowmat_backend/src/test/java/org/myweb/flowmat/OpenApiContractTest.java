package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.config.OpenApiConfig;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public API contract (docs/api/README.md, ROADMAP-2026-11-23.md §12 P2 2): the OpenAPI document the server serves
 * must equal docs/api/openapi.json, so every change to the HTTP API shows up in review. When a published operation or a
 * schema field goes away, the major version ({@link OpenApiConfig#API_VERSION}) must go up as well. Run with
 * {@code -Dopenapi.update=true} to rewrite the file after a deliberate change.
 */
@AutoConfigureMockMvc
class OpenApiContractTest extends IntegrationTestSupport {

    static final Path CONTRACT = Path.of("..", "docs", "api", "openapi.json");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void theServedApiIsThePublishedContract() throws Exception {
        String served = mockMvc.perform(get("/v3/api-docs")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode current = normalized(JSON.readTree(served));
        String text = JSON.writeValueAsString(current) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, text);
            return;
        }
        assertThat(CONTRACT).as("docs/api/openapi.json; write it with -Dopenapi.update=true").exists();
        JsonNode published = JSON.readTree(Files.readString(CONTRACT));

        List<String> removed = removed(published, current);
        if (!removed.isEmpty()) {
            assertThat(major(current.path("info").path("version").asText()))
                .as("these went away, so OpenApiConfig.API_VERSION needs a higher major version than "
                    + published.path("info").path("version").asText() + ":\n  " + String.join("\n  ", removed))
                .isGreaterThan(major(published.path("info").path("version").asText()));
        }
        assertThat(text).as("the API changed; review it, then run the backend tests with -Dopenapi.update=true "
            + "(docs/api/README.md)").isEqualTo(Files.readString(CONTRACT));
    }

    @Test
    void anOperationOrFieldThatGoesAwayIsBreaking() throws Exception {
        JsonNode published = JSON.readTree("""
            {"paths": {"/items": {"get": {}, "post": {}}},
             "components": {"schemas": {"Item": {"properties": {"itemId": {}, "itemName": {}}}, "Old": {}}}}
            """);
        JsonNode current = JSON.readTree("""
            {"paths": {"/items": {"get": {}}, "/lots": {"get": {}}},
             "components": {"schemas": {"Item": {"properties": {"itemId": {}, "itemCode": {}}}}}}
            """);
        assertThat(removed(published, current)).containsExactlyInAnyOrder("POST /items", "field Item.itemName", "schema Old");
        // Only additions: nothing to bump.
        assertThat(removed(current, JSON.readTree("""
            {"paths": {"/items": {"get": {}, "put": {}}, "/lots": {"get": {}}},
             "components": {"schemas": {"Item": {"properties": {"itemId": {}, "itemCode": {}, "note": {}}}}}}
            """))).isEmpty();
    }

    /** Operations and schema fields in the published contract that the server no longer has. */
    static List<String> removed(JsonNode published, JsonNode current) {
        List<String> removed = new ArrayList<>();
        published.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames().forEachRemaining(method -> {
            if (current.path("paths").path(path.getKey()).path(method).isMissingNode()) {
                removed.add(method.toUpperCase() + " " + path.getKey());
            }
        }));
        published.path("components").path("schemas").fields().forEachRemaining(schema -> {
            JsonNode now = current.path("components").path("schemas").path(schema.getKey());
            if (now.isMissingNode()) {
                removed.add("schema " + schema.getKey());
                return;
            }
            schema.getValue().path("properties").fieldNames().forEachRemaining(field -> {
                if (now.path("properties").path(field).isMissingNode()) removed.add("field " + schema.getKey() + "." + field);
            });
        });
        return removed;
    }

    private static int major(String version) {
        return Integer.parseInt(version.split("\\.")[0]);
    }

    /**
     * The document without what changes between runs: the server address, and operation ids (springdoc numbers clashing
     * method names in scan order, so one new controller renames others). Keys sorted, tags sorted by name.
     */
    static JsonNode normalized(JsonNode document) {
        ObjectNode copy = document.deepCopy();
        copy.remove("servers");
        copy.path("paths").forEach(path -> path.forEach(operation -> {
            if (operation instanceof ObjectNode node) node.remove("operationId");
        }));
        if (copy.path("tags") instanceof ArrayNode tags) {
            TreeMap<String, JsonNode> byName = new TreeMap<>();
            tags.forEach(tag -> byName.put(tag.path("name").asText(), tag));
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            byName.values().forEach(sorted::add);
            copy.set("tags", sorted);
        }
        return sortedKeys(copy);
    }

    private static JsonNode sortedKeys(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeSet<String> names = new TreeSet<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) sorted.set(name, sortedKeys(object.get(name)));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            for (Iterator<JsonNode> items = array.elements(); items.hasNext();) sorted.add(sortedKeys(items.next()));
            return sorted;
        }
        return node;
    }
}
