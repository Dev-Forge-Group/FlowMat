package org.myweb.flowmat.domain.workflow.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkflowExecutionBindingDifferTest {
    @Test
    void bindingOrderIsIgnoredAndChangesIdentifyTheNode() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var before = json.readTree("{\"nodeBindings\":[{\"processId\":\"b\",\"operationId\":\"old\"},{\"processId\":\"a\",\"operationId\":\"same\"}]}");
        var after = json.readTree("{\"nodeBindings\":[{\"processId\":\"a\",\"operationId\":\"same\"},{\"processId\":\"b\",\"operationId\":\"new\"}]}");
        var result = WorkflowRevisionDiffer.compare(before, after, 0, 10);
        assertEquals(1, result.totalChanges());
        assertEquals("b", result.changes().getFirst().entityId());
        assertEquals("/operationId", result.changes().getFirst().path());
        assertEquals(0, WorkflowRevisionDiffer.compare(json.readTree("{}"), json.readTree("{\"nodeBindings\":[]}"), 0, 10).totalChanges());
    }
}
