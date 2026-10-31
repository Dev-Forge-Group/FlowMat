package org.myweb.flowmat.domain.workflow.api.dto.response;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse.Change;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse.Revision;

class WorkflowRevisionComparisonExactJsonTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @Test
    void comparisonWireIncludesExactNumbersAsStringsAndKeepsExistingNumberFields() throws Exception {
        JsonNode before = JsonNodeFactory.instance.numberNode(new BigInteger("9007199254740993"));
        JsonNode after = DecimalNode.valueOf(new BigDecimal("9007199254740993.123456789"));
        var response = new WorkflowRevisionComparisonResponse("workflow", new Revision("old", 1, 3, "retired"),
            new Revision("new", 2, 3, "published"), 1,
            List.of(new Change("nodeBindings", "process", "/requestMapping/amount", "modified", before, after)), null);

        JsonNode wire = mapper.readTree(mapper.writeValueAsString(response));
        JsonNode change = wire.path("changes").get(0);
        assertTrue(change.path("beforeJson").isTextual(), "beforeJson must be an exact JSON string on the wire");
        assertTrue(change.path("afterJson").isTextual(), "afterJson must be an exact JSON string on the wire");
        assertEquals("9007199254740993", change.path("beforeJson").textValue());
        assertEquals("9007199254740993.123456789", change.path("afterJson").textValue());
        assertTrue(change.path("before").isNumber());
        assertTrue(change.path("after").isNumber());
        assertEquals(before, change.path("before"));
        assertEquals("workflow", wire.path("workflowId").textValue());
        assertEquals("retired", wire.path("fromRevision").path("status").textValue());
        assertTrue(wire.path("nextOffset").isNull());
    }

    @Test
    void addedAndRemovedValuesKeepTheirKindWhileExplicitNullRemainsJsonNull() throws Exception {
        JsonNode added = wire(new Change("nodeBindings", "p", "", "added", NullNode.instance, NullNode.instance));
        JsonNode removed = wire(new Change("nodeBindings", "p", "", "removed", NullNode.instance, NullNode.instance));
        JsonNode explicit = wire(new Change("nodeBindings", "p", "/requestMapping/key", "modified",
            new TextNode("previous"), NullNode.instance));

        assertAll(
            () -> assertEquals("added", added.path("kind").textValue()),
            () -> assertEquals("null", added.path("afterJson").textValue()),
            () -> assertEquals("removed", removed.path("kind").textValue()),
            () -> assertEquals("null", removed.path("beforeJson").textValue()),
            () -> assertEquals("modified", explicit.path("kind").textValue()),
            () -> assertEquals("null", explicit.path("afterJson").textValue()),
            () -> assertTrue(explicit.path("after").isNull()));
    }

    @Test
    void nestedBindingJsonRetainsLargeNumbersAndCanBeParsedAsJson() throws Exception {
        var binding = mapper.createObjectNode();
        binding.put("profileVersionId", "version-2");
        binding.put("operationId", "fetch");
        binding.putObject("requestMapping").set("amount", DecimalNode.valueOf(new BigDecimal("9007199254740993.123456789")));
        binding.putArray("responseMapping").add(new BigInteger("9007199254740995"));
        JsonNode wire = wire(new Change("nodeBindings", "p", "", "added", NullNode.instance, binding));

        assertTrue(wire.path("afterJson").isTextual());
        String exact = wire.path("afterJson").textValue();
        assertTrue(exact.contains("9007199254740993.123456789"));
        assertTrue(exact.contains("9007199254740995"));
        assertEquals("version-2", mapper.readTree(exact).path("profileVersionId").textValue());
        assertEquals(binding, wire.path("after"));
    }

    @Test
    void stringJsonIsEscapedOnceForTheJsonValueAndOnceForTheResponse() throws Exception {
        String text = "quoted \"value\"\nnext\tline\\path\rreturn";
        JsonNode wire = wire(new Change("nodeBindings", "p", "/requestMapping/text", "modified",
            new TextNode(text), new TextNode("")));

        assertTrue(wire.path("beforeJson").isTextual());
        assertEquals(mapper.writeValueAsString(text), wire.path("beforeJson").textValue());
        assertEquals(text, mapper.readTree(wire.path("beforeJson").textValue()).textValue());
        assertEquals("\"\"", wire.path("afterJson").textValue());
        assertEquals(text, wire.path("before").textValue());
    }

    private JsonNode wire(Change change) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(change));
    }
}
