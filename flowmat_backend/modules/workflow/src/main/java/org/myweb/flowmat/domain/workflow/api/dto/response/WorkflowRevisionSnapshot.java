package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.editor.api.dto.response.EditorDocumentResponse;

/** Data fixed when a workflow draft is published; session state and Redis cursors are excluded. */
public record WorkflowRevisionSnapshot(
    int schemaVersion,
    WorkflowResponse workflow,
    JsonNode canvasSnapshot,
    JsonNode simulationConfig,
    List<ProcessResponse> processes,
    List<ProcessIoResponse> processIos,
    List<ProcessConnectionResponse> connections,
    List<CanvasAnnotationResponse> annotations,
    EditorDocumentResponse editorDocument,
    /** Nodes with an execution policy, as fixed at publish (docs/domain/flow-run-execution-policy.md EP3). */
    List<NodeExecutionPolicyResponse> nodePolicies,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<NodeExecutionBindingResponse> nodeBindings
) {
}
