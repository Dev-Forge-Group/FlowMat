package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One finding of a workflow check. Besides where it is and what is wrong, it says why ({@code evidence}: the stored values
 * and counts the rule looked at), what edits would clear it ({@code supportedFixes}: only edits that clear it by the rule's
 * own logic, never general advice) and which derived warnings it explains and so hides ({@code suppresses}: their codes).
 * The three are always present and may be empty.
 */
public record WorkflowValidationIssue(
    String severity,
    String code,
    String processId,
    String ioId,
    String connectionId,
    String message,
    Map<String, Object> evidence,
    List<String> supportedFixes,
    List<String> suppresses
) {
    public WorkflowValidationIssue {
        evidence = evidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        supportedFixes = supportedFixes == null ? List.of() : List.copyOf(supportedFixes);
        suppresses = suppresses == null ? List.of() : List.copyOf(suppresses);
    }

    public WorkflowValidationIssue(String severity, String code, String processId, String ioId, String connectionId,
        String message) {
        this(severity, code, processId, ioId, connectionId, message, Map.of(), List.of(), List.of());
    }
}
