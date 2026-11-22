package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.math.BigDecimal;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessRequirement;

/** One execution requirement of a node, as saved and as a published revision's snapshot keeps it. */
public record ProcessRequirementResponse(String processRequirementId, String processId, String kind, String ref,
    BigDecimal amount, String unit, String per) {

    public static ProcessRequirementResponse from(ProcessRequirement requirement) {
        return new ProcessRequirementResponse(requirement.getProcessRequirementId(), requirement.getProcessId(),
            requirement.getKind(), requirement.getRef(), requirement.getAmount(), requirement.getUnit(), requirement.getPer());
    }
}
