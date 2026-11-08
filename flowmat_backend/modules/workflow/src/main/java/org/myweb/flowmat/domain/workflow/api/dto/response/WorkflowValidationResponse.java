package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.util.List;

public record WorkflowValidationResponse(int errors, int warnings, List<WorkflowValidationIssue> issues) {}
