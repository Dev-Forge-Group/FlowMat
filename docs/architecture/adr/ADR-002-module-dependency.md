# ADR-002: 모듈 의존 규칙

작성일: 2026-10-02

## 상태

**Accepted** (2026-10-02, 같은 날 의사결정자 검토에서 유지). 근거: 사용자가 최신 결정 문서로 지정한 [원문](../../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md) §18–25, 사용자가 진행을 지시한 2026-10-02 작업 목록. 검토 결론: "다른 도메인 Repository 직접 접근 금지 + public application API 경유"와 "기존 위반 = baseline, 신규 위반 = 금지, 수정한 기존 코드 = 가능한 범위에서 경계로 이동"은 충분히 닫혀 있다. Stage A는 적용됐다(아래 검증).

## 배경

- 백엔드는 하나의 Spring Boot 애플리케이션(modular monolith)이다. 도메인은 `org.myweb.flowmat.domain.<context>` 아래에 `api`·`application`·`domain`·`repository` 패키지로 나뉜다.
- 이 ADR을 쓸 때(2026-10-02), 다른 bounded context의 repository를 직접 import하는 줄은 도메인 간 105줄이었다. 결정 5의 기준 구현 뒤 100줄, Stage B로 `ProcessIoServiceImpl`(`ItemRepository`)·`WorkflowValidationService`(`UnitMasterRepository`)를 `CatalogQuery`로 바꾼 뒤(2026-10-03) 98줄이다. 작성 시점의 많은 순서:

  | 방향 | 줄 수 |
  |---|---|
  | inventory → catalog | 23 |
  | production → catalog | 19 |
  | production → inventory | 12 |
  | bom → catalog | 8 |
  | quality → catalog | 6 |
  | production → workflow | 6 |

- bom → catalog 8줄 중 4줄은 2026-10-02에 추가한 `BomWhereUsedTreeService`·`BomCostRollupService`의 `ItemRepository`·`UnitMasterRepository`다.
- `ProductionFlowRunAdapter`는 `FlowRunRepository`와 `FlowRun` 엔티티를 직접 다룬다. 생산 실행의 시작·종료를 Flow Run에 반영하는 `onStarted`·`onFinished` 두 곳이다.
- 지금은 동작하지만, 이대로 두면 A→B, B→C, C→A 같은 결합이 계속 늘어난다. 도메인을 따로 떼어 내거나 규칙을 바꾸기도 어려워진다.

## 결정

1. **Repository는 자기 bounded context 안에서만 쓴다.** 같은 `domain.<context>` 패키지가 "자기"다.
2. 다른 도메인은 그 도메인의 **`application.publicapi`** 패키지로만 접근한다.
   - 공개 API는 조회용 Query 인터페이스와 변경용 Command 인터페이스, 그리고 불변 View(record) DTO로 이루어진다.
   - 구현은 그 도메인의 `application` 안에 둔다.
   - 새 공개 API는 Entity를 밖으로 넘기지 않는다.
   - 실제로 호출되는 연산만 만든다. 미리 넓게 만들지 않는다.
   - 예: catalog의 `CatalogQuery`, flowrun의 `FlowRunCommand`.
3. 지금 결과가 필요한 조회·명령은 동기 Query/Command로 호출한다. 일어난 사건을 알리는 목적이면 Domain Event를 쓴다. Outbox와 메시지 브로커는 쓰지 않는다(재검토 조건 참조).
4. 기존 위반은 한 번에 고치지 않는다.
   - **Stage A**: ArchUnit 테스트로 새 cross-domain repository import를 막는다. 기존 위반은 목록으로 동결한다. 목록은 줄어들기만 해야 한다. ArchUnit의 freeze 기능(`FreezingArchRule`)을 우선 검토한다.
   - **Stage B**: 기존 코드를 고칠 때 그 파일의 위반을 공개 API로 바꾼다. 일괄 수정은 하지 않는다.
   - **Stage C**: 다른 도메인 Entity import 금지. 별도 결정이다. [ADR-018](ADR-018-entity-boundary.md)에서 정했다(2026-10-19 기준선 39줄 동결, 2026-10-25 0줄이 되어 동결을 풀었다).
5. 첫 기준 구현은 다음 두 곳이며, 이것을 앞으로 cross-domain 호출의 표준 예제로 삼는다. 둘 다 2026-10-02에 구현했다.
   - bom → catalog 4줄(2026-10-02 신규): 동결하지 않고 [`CatalogQuery`](../../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/catalog/application/publicapi/CatalogQuery.java)로 바꾼다.
   - `ProductionFlowRunAdapter`: [`FlowRunCommand`](../../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/flowrun/application/publicapi/FlowRunCommand.java)로 바꿔 production이 Flow Run 저장 구현을 모르게 한다.
6. `domain/erp`·`mes`·`scm`·`wms` 같은 대형 패키지를 만들지 않는다. 새 bounded context는 기존 어디에도 속하지 않는 책임이 분명할 때만 만든다.

## 결정하지 않은 것

- Stage C(Entity import 금지)의 시점과 범위. [ADR-018](ADR-018-entity-boundary.md)에서 정했다(2026-10-19)
- 다른 도메인의 `application` 클래스를 직접 쓰는 것(예: bom·inventory·production이 쓰는 catalog의 `UnitConverter`, 여러 도메인이 쓰는 project의 `ProjectAccessService`)을 `publicapi`로 옮길지 여부. Stage A·B는 repository만 다룬다.
- `global`(보안·설정)과 `batch`의 repository 사용을 규칙에 넣을지 여부. 이 둘은 bounded context가 아니다. 지금 `global`→user 7줄, `batch`→inventory 2줄이 있다. Stage A 규칙은 `domain.<context>` 사이만 본다.
- Domain Event의 표준 형식과 발행 위치
- Outbox, 재시도, 멱등 처리 방식(첫 비동기 외부 연동 때)

## 결과

- 좋은 점
  - 새 결합이 더 늘지 않는다.
  - 도메인 사이 계약이 공개 API로 드러나 바꾸기 쉬워진다.
- 비용
  - 다른 도메인 데이터가 필요할 때마다 공개 API를 먼저 만들어야 한다.
  - 기존 위반(2026-10-03 기준 98줄)은 손댈 때까지 남는다.

## 검증

Stage A는 2026-10-02에 [`ModuleBoundaryTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)로 적용했다.

- 위반 하나는 "클래스 X가 다른 도메인의 repository R을 쓴다"이다. 시그니처와 줄 번호를 넣지 않아, 생성자 인자나 메서드 위치가 바뀌어도 동결된 위반과 계속 맞는다.
- 동결 목록은 `flowmat_backend/src/test/resources/archunit_store/`에 있었다. 고친 위반은 자동으로 빠졌고, 저장소가 없으면 `allowStoreCreation=false` 때문에 테스트가 실패했다. Stage A 목록은 2026-10-18([ADR-012](ADR-012-workflow-access-boundary.md)), Stage C 목록은 2026-10-25([ADR-018](ADR-018-entity-boundary.md))에 0줄이 되어 파일을 지웠다. 이제 두 규칙 모두 위반이 생기면 바로 실패한다.
- 새 cross-domain repository import를 넣으면 ArchUnit 테스트가 실패해야 한다. 2026-10-02에 임시 클래스로 실패를 확인했다.
- 동결 목록에 없는 위반은 0이어야 한다.
- 기준 구현 뒤 bom→catalog repository 4줄과 production→flowrun repository 1줄이 사라지고, 기존 테스트가 모두 통과해야 한다.

## 재검토 조건

- 새 기능에서도 cross-domain repository 참조가 계속 생기면 ArchUnit을 강화하고 공개 API를 넓히며 Entity 참조를 금지한다.
- 독립 배포·독립 확장·팀 소유권 분리·장애 격리·릴리스 주기 충돌이 실제 병목이 되면 microservice를 검토한다(원문 §51).

## 참고

- 원문 §18–25, §39, §48 6항, §50 Invariant 2
- [결정 인계](../decision-handoff.md) M1·M2·P1
