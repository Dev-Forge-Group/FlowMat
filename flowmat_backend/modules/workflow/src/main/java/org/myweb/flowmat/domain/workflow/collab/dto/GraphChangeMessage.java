package org.myweb.flowmat.domain.workflow.collab.dto;

public record GraphChangeMessage(
    long seq,
    String changeType,
    String workflowId,
    String entityId,
    String userId,
    long timestamp,
    GraphEntityPayload payload
) {
    public enum Type {
        WORKFLOW_UPDATED,
        NODE_CREATED, NODE_UPDATED, NODE_DELETED,
        CONNECTION_CREATED, CONNECTION_UPDATED, CONNECTION_DELETED,
        PORT_CREATED, PORT_UPDATED, PORT_DELETED,
        ANNOTATION_CREATED, ANNOTATION_UPDATED, ANNOTATION_DELETED,
        EDITOR_DOCUMENT_UPDATED
    }

    public static GraphChangeMessage of(
        long seq,
        Type type,
        String workflowId,
        String entityId,
        String userId,
        long timestamp,
        GraphEntityPayload payload
    ) {
        return new GraphChangeMessage(seq, type.name(), workflowId, entityId, userId, timestamp, payload);
    }
}
