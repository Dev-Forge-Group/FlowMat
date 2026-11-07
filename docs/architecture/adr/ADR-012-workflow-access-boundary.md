# ADR-012: 워크플로 접근 검사와 남은 모듈 경계

작성일: 2026-10-18

## 상태

**Accepted (2026-10-18, 사용자 결정 "Accept as recommended"). 로컬 구현 2eq(2026-10-18, `9500678`에서 커밋).** 같은 날 사용자 요청("ADR 초안부터")으로 초안을 썼고, 사용자가 권장안 그대로 수용 기준 6개를 닫았다.

근거: [ADR-002](ADR-002-module-dependency.md) 결정 4와 "결정하지 않은 것", [주간 계획](../../status/WEEK-PLAN-2026-10-19.md) "남은 7줄", [`ProjectAccessService`](../../../flowmat_backend/modules/project/src/main/java/org/myweb/flowmat/domain/project/application/ProjectAccessService.java).

## 배경

| # | 지금 | 근거 |
|---|---|---|
| P1 | 모듈 경계 동결 목록은 105줄에서 7줄이 됐다(2026-10-18). 남은 7줄은 P2, P5, P6이다 | `archunit_store` |
| P2 | project의 `ProjectAccessService`가 workflow repository 4개로 워크플로, 공정, 포트, 연결을 찾고, 그 프로젝트의 접근을 검사한 뒤 찾은 엔티티를 돌려준다. 이런 검사가 8개다 | `requireWorkflowReadAccess` 등 |
| P3 | 그 8개를 부르는 파일은 대부분 workflow 안에 있다. 워크플로 읽기 검사는 16파일 중 13, 쓰기 검사는 12파일 중 10이다. 밖에서는 flowrun 2곳, production 1곳, websocket 인증 2곳이 부르고, 돌려받은 것에서 워크플로 id와 프로젝트 id만 쓴다 | |
| P4 | workflow는 project의 `ProjectAccessService`로 프로젝트 접근을 검사한다. 그래서 project와 workflow가 서로를 쓴다 | |
| P5 | `WorkflowServiceImpl`과 `WorkflowTemplateServiceImpl`은 첫 워크플로를 만들 때 프로젝트의 현재 워크플로(`current_workflow_id`)를 채우고, 그 워크플로를 지우면 비운다. `ProjectRepository`로 직접 저장한다 | 2줄 |
| P6 | `ProjectInviteServiceImpl`은 `UserRepository`로 초대할 메일 주소의 사용자, 초대한 사람 이름, 지금 사용자를 읽는다. user는 인증 쪽 모듈이고 project를 쓰지 않는다 | 1줄 |

## 선택지

### 1. 워크플로 단위 접근 검사를 둘 곳

| 안 | 내용 | 좋은 점 | 약점 |
|---|---|---|---|
| A. workflow로 옮김 | workflow 안에 `WorkflowAccessService`를 두고 8개 검사를 옮긴다. 대상을 찾은 뒤 `ProjectAccessService`의 프로젝트 읽기·쓰기 검사를 부른다 | project가 workflow를 쓰지 않게 되어 서로 쓰는 고리가 끊긴다. 대상을 찾는 일과 그 저장소가 한 모듈에 있다 | 부르는 파일 30곳가량의 주입 대상이 바뀐다 |
| B. project에 두고 공개 API로 | `ProjectAccessService`는 그대로 두고, workflow 공개 API로 대상의 프로젝트 id만 받는다 | 부르는 쪽이 그대로다 | project가 여전히 workflow를 쓴다. 엔티티를 돌려주는 메서드는 바꿔야 한다 |
| C. 그대로 동결 | 4줄을 남긴다 | 바꿀 것이 없다 | 단계 C(엔티티 import 금지)에서 다시 막힌다 |

### 2. 밖(flowrun, production, websocket)에서 부를 것

| 안 | 내용 |
|---|---|
| a. workflow 공개 API | `WorkflowAccess.requireRead(workflowId)`, `requireWrite(workflowId)`와 사용자 id를 받는 판. `WorkflowAccessView(workflowId, projectId)`를 돌려준다 |
| b. workflow 응용 서비스 직접 | `WorkflowAccessService`를 그대로 주입한다. 응용 클래스 직접 사용은 ADR-002에서 아직 막지 않는다 |

### 3. 현재 워크플로를 바꾸는 방법

| 안 | 내용 |
|---|---|
| a. project 명령 API | `ProjectWorkflowCommand.workflowAdded(projectId, workflowId)`는 비어 있으면 채우고, `workflowRemoved(projectId, workflowId)`는 그것이면 비운다. 같은 트랜잭션에서 돈다 |
| b. Domain Event | workflow가 만들고 지운 일을 알리고 project가 듣는다. ADR-002는 이벤트 형식을 아직 정하지 않았다 |

### 4. 초대의 사용자 조회

| 안 | 내용 |
|---|---|
| a. user 공개 API | `UserQuery.findByEmail(email)`, `findById(userId)`가 `UserView(userId, userEmail, userName)`를 돌려준다. 비밀번호, 역할, 상태는 넘기지 않는다 |
| b. 그대로 동결 | 인증 쪽을 정리할 때 같이 본다 |

## 결정 (2026-10-18, 권장안 그대로)

1. 접근 검사는 **A**. 메서드 이름, 순서, 오류(404, 403과 문구)를 그대로 옮긴다.
2. 밖에서는 **a**. 엔티티를 넘기지 않는다.
3. 현재 워크플로는 **a**.
4. 사용자 조회는 **a**.
5. 동결 목록이 0줄이 되면 freeze를 풀고 같은 규칙을 일반 ArchUnit 규칙으로 둔다. 저장소 파일은 지운다.
6. 응답과 상태 코드는 바뀌지 않는다.
7. 바꾼 뒤 오류 담당이 접근·보안 회귀(`ProjectAccessIntegrationTest`, `*SecurityRegression*`, `StompAuthChannelInterceptorTest`)를 다시 돌린다.

## 수용 기준 (2026-10-18 사용자 확정)

- [x] 접근 검사를 둘 곳: **A workflow로 옮김**
- [x] 밖에서 부를 것: **a workflow 공개 API의 view**
- [x] 현재 워크플로: **a project 명령 API**
- [x] 사용자 조회: **a user 공개 API**
- [x] 동결 목록 0줄 뒤: **freeze를 풀고 일반 규칙**
- [x] 보안 회귀: **구현 뒤 오류 담당이 돌린다**

## 결정하지 않은 것

- 단계 C(다른 도메인 엔티티 import 금지)의 시점과 범위. [ADR-018](ADR-018-entity-boundary.md)에서 정했다(2026-10-19, 기준선 동결)
- `UnitConverter`처럼 다른 도메인의 응용 클래스를 직접 쓰는 것
- `global`과 `batch`의 repository 사용(ADR-002)

## 결과

- 좋은 점
  - 동결 목록이 0줄이 되고, project와 workflow가 서로 쓰는 고리가 없어진다.
  - 새 cross-domain repository 사용은 지금처럼 테스트가 막는다.
- 위험
  - 접근 검사를 옮기다 하나라도 빠지면 권한 구멍이 생긴다. 그래서 그대로 옮기고 보안 회귀로 확인한다.
  - 30곳가량의 주입 대상이 바뀌어 다른 작업과 충돌하기 쉽다. 한 번에 짧게 끝낸다.
