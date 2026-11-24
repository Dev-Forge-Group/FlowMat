package org.myweb.flowmat.domain.workflow.application;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** Drafting is on only when it is turned on and a key is set (ADR-047 decision 2). */
class ProcessDraftConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return "true".equalsIgnoreCase(context.getEnvironment().getProperty("app.ai.process-draft.enabled", "false"))
            && StringUtils.hasText(context.getEnvironment().getProperty("app.ai.process-draft.api-key"));
    }
}
