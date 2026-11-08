package org.myweb.flowmat.domain.workflow.collab.dto;

/**
 * 노드 드래그 "도중" 실시간 위치 릴레이 메시지. 영속화하지 않는 순수 브로드캐스트 페이로드.
 * 최종 위치 저장은 REST PATCH /api/processes/{id}/position 가 담당한다.
 *
 * userId     : 서버가 JWT principal 에서 추출해 재설정한다.
 * clientId   : 탭 단위 고유 식별자. 서버가 그대로 릴레이한다 (에코 필터링에 사용).
 * workflowId / timestamp : 서버가 재설정한다.
 */
public record NodeMoveMessage(
    String processId,
    double x,
    double y,
    String userId,
    String clientId,
    String workflowId,
    long timestamp
) {
    public NodeMoveMessage withServerValues(String userId, String workflowId) {
        return new NodeMoveMessage(processId, x, y, userId, clientId, workflowId, System.currentTimeMillis());
    }
}
