package org.myweb.flowmat.domain.workflow.collab.dto;

import java.util.List;

public record AnnotationPresencePayload(
    String annotationType,
    List<List<Double>> points,
    Boolean inProgress
) {
}
