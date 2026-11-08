package org.myweb.flowmat.domain.workflow.editor.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.editor.api.dto.request.EditorDocumentSaveRequest;
import org.myweb.flowmat.domain.workflow.editor.api.dto.response.EditorDocumentResponse;
import org.myweb.flowmat.domain.workflow.editor.application.WorkflowEditorDocumentService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/editor-document")
public class WorkflowEditorDocumentController {

    private final WorkflowEditorDocumentService workflowEditorDocumentService;

    @GetMapping
    public ApiResponse<EditorDocumentResponse> getDocument(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(workflowEditorDocumentService.getDocument(workflowId));
    }

    @PutMapping
    public ApiResponse<EditorDocumentResponse> saveDocument(
        @PathVariable("workflowId") String workflowId,
        @Valid @RequestBody EditorDocumentSaveRequest request
    ) {
        return ApiResponse.ok(workflowEditorDocumentService.saveDocument(workflowId, request));
    }
}
