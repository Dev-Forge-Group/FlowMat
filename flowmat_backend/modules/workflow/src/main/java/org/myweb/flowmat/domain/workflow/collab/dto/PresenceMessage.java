package org.myweb.flowmat.domain.workflow.collab.dto;

/**
 * 협업 presence 브로드캐스트 메시지.
 * type 에 따라 cursorX/Y, editingProcessId 사용 여부가 달라진다.
 *
 * userId     : 서버가 JWT principal 에서 추출해 재설정한다.
 * clientId   : 탭 단위 고유 식별자. 서버가 그대로 릴레이한다 (에코 필터링에 사용).
 * timestamp  : 서버가 재설정한다.
 */
public record PresenceMessage(
    Type type,
    String userId,
    String clientId,
    String workflowId,
    Double cursorX,
    Double cursorY,
    String editingProcessId,
    AnnotationPresencePayload annotation,
    long timestamp
) {
    public enum Type {
        JOIN,
        LEAVE,
        HEARTBEAT,
        CURSOR_MOVED,
        NODE_EDITING,
        ANNOTATION_DRAWING
    }

    public static PresenceMessage join(String userId, String clientId, String workflowId) {
        return new PresenceMessage(Type.JOIN, userId, clientId, workflowId, null, null, null, null,
            System.currentTimeMillis());
    }

    public static PresenceMessage leave(String userId, String clientId, String workflowId) {
        return new PresenceMessage(Type.LEAVE, userId, clientId, workflowId, null, null, null, null,
            System.currentTimeMillis());
    }

    public PresenceMessage withServerValues(String userId, String workflowId) {
        return new PresenceMessage(type, userId, clientId, workflowId, cursorX, cursorY, editingProcessId, annotation,
            System.currentTimeMillis());
    }
}
