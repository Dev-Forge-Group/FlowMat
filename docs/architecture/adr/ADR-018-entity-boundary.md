# ADR-018: 단계 C — 다른 도메인의 엔티티를 쓰지 않는다

작성일: 2026-10-18

## 상태

**Proposed (2026-10-18). 조사와 설계만.** 이번 주 사전 결정(WORKBOARD §3, "Stage C: 이번 주는 의존 현황 조사와 설계까지. 전역 Entity import 금지를 즉시 켜거나 대규모 치환하지 않는다")에 따른 초안이다. 방식, 순서, 규칙을 켜는 시점은 사용자가 정한다. 수용 기준을 닫기 전에는 코드를 바꾸지 않는다.

근거: [ADR-002](ADR-002-module-dependency.md)(모듈 의존, "Stage C는 별도 결정"), [ADR-012](ADR-012-workflow-access-boundary.md)(단계 B 끝, 동결 목록 0줄), [주간 계획](../../status/WEEK-PLAN-2026-10-19.md) "다음 기능 후보".

## 지금 (2026-10-18 조사)

다른 도메인의 `domain.entity`를 import하는 곳은 **37곳, 20파일**이다. 조사하면서 쓰이지 않던 import 2줄(flowrun → `Workflow`, inventory → `UnitMaster`)은 지웠다.

| 쓰는 쪽 → 가진 쪽 | import | 파일 |
|---|---|---|
| production → inventory | 11 | 6 |
| inventory → catalog | 8 | 8 |
| production → catalog | 6 | 6 |
| production → workflow | 4 | 2 |
| production → bom | 3 | 3 |
| workflow → project | 2 | 2 |
| bom → catalog | 1 | 1 |
| inventory → project | 1 | 1 |
| quality → project | 1 | 1 |

엔티티로는 catalog `Item`이 가장 많고(15), inventory `Inventory`·`LotMaster`, project `Project`, bom `BomHeader`, workflow `Workflow`·`Process`·`ProcessIo`가 뒤를 잇는다.

원인은 셋이다.

| # | 원인 | 곳 |
|---|---|---|
| R1 | Facts(json) 공개 API 4개(`CatalogItemFactsQuery`, `StockFactsQuery`, `BomPlanningQuery`, `WorkflowProductionQuery`)가 엔티티를 JSON으로 넘기고, 받는 쪽이 그 JSON을 가진 쪽의 엔티티 클래스로 되돌린다(`readFacts(…, Workflow.class)`, `mapper.readValue(…, Item.class)`) | production, inventory 대부분 |
| R2 | `ProjectAccessService.requireProjectReadAccess`·`requireProjectWriteAccess`가 `Project` 엔티티를 돌려준다 | workflow 2, inventory 1, quality 1 |
| R3 | catalog의 품목 삭제 검사 SPI `ItemUsageCheck.whyInUse(Item)`가 엔티티를 넘긴다 | bom 1, inventory 1 |

R1은 가진 쪽이 엔티티 필드를 바꾸면 받는 쪽이 컴파일은 되지만 조용히 빈 값을 읽을 수 있다. 단계 B가 막은 repository 사용과 같은 종류의 결합이다.

## 선택지

| 안 | 내용 | 좋은 점 | 나쁜 점 |
|---|---|---|---|
| A. 가진 쪽의 공개 view | Facts(json) 대신 가진 도메인이 typed view record를 준다(단계 B와 같은 방식). catalog에는 이미 `CatalogItemView`가 있다 | 필드가 바뀌면 컴파일에서 드러난다. 단계 B와 같은 모양 | 공개 API를 더 만든다 |
| B. 받는 쪽의 record | 받는 쪽마다 필요한 필드만 담은 record로 JSON을 읽는다 | 가진 쪽은 그대로 | JSON 모양을 받는 쪽이 알아야 하고, 필드가 바뀌어도 드러나지 않는다 |
| C. 그대로 둔다 | 문서로만 막는다 | 할 일이 없다 | 결합이 남는다 |

## 권장안

1. 방식은 **A**.
2. 순서는 작은 것부터다. (1) R2: 접근 검사는 확인만 하고, 프로젝트 값이 필요하면 project 공개 API에서 읽는다(4곳) (2) R3: `ItemUsageCheck`가 `CatalogItemView`를 받는다(2곳) (3) inventory → catalog: `CatalogItemFactsQuery`를 `CatalogQuery` view로(8파일) (4) production: bom·workflow·catalog·inventory(나머지). 가장 큰 파일은 `ProductionRunServiceImpl`이다.
3. 규칙: `ModuleBoundaryTest`에 "다른 도메인 `domain.entity` import 금지"를 **0곳이 된 뒤** 켠다. 동결 목록은 다시 만들지 않는다(ADR-012에서 지웠다).
4. 각 단계는 동작을 바꾸지 않는 리팩터다. 단계마다 전체 회귀를 돌린다. 받는 쪽이 모두 옮겨 간 Facts(json) API는 지운다.
5. 시점: 이번 주(10-19–10-25) 범위 밖이다. 다음 주 이후, 사용자가 정한 때 시작한다.

## 수용 기준 (미정)

- [ ] 방식: A / B / C
- [ ] 순서: 권장안 2 / 다른 순서
- [ ] 규칙을 켜는 시점: 0곳이 된 뒤 / 동결로 먼저
- [ ] 시작 시점

## 결정하지 않은 것

- api 계층(DTO)의 엔티티 사용 정리
- 테스트 코드의 다른 도메인 엔티티 사용

## 결과 (권장안대로 할 때)

- 좋은 점: 엔티티 필드를 바꿔도 다른 도메인이 조용히 깨지지 않는다. 모듈 경계가 repository에 이어 엔티티까지 닫힌다.
- 위험: production 쪽 변경이 크다. 리팩터 중 동작이 바뀌지 않게 단계마다 전체 회귀가 필요하다.
