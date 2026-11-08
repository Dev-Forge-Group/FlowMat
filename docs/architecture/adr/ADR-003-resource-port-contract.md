# ADR-003: Resource·Port 계약과 실행 Core

작성일: 2026-10-02

## 상태

**Accepted (원칙)** — 아래 결정 1~10.
**Experimental / Evolvable** — 아래 "Resource Type Registry"(정확한 `resourceType` 값 목록). ADR의 핵심 결정이 아니며 실증하면서 바꾼다.

상태 이력:
- 2026-10-02 작성: 전체 Accepted. 값 목록도 결정 3에 넣었다.
- 같은 날 의사결정자 검토: 원칙만 Accepted로 두고, 값 목록은 Experimental registry로 분리했다. `labor`는 목록에서 뺐다(registry 참조).

근거: 사용자가 최신 결정 문서로 지정한 [원문](../../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md) §26–45, 사용자가 직접 고친 FlowRun 문장, 사용자가 진행을 지시한 2026-10-02 작업 목록, 2026-10-02 의사결정자 검토. 데이터 흐름 검증 테스트(결정 9-(a))는 2026-10-03에 통과했다(아래 검증).

## 배경

- 공정 포트 `ProcessIo`의 필드는 다음과 같다: `itemId`, `ioName`, `direction`, `ioType`, `role`, `resourceType`, `quantity`, `unit`, `formula`, `schemaJson`, `validationRule`, `colorScheme`, `requiredYn`, `allowShortageYn`. 이 중 `role`·`resourceType`·`schemaJson`·`validationRule`은 V24에서 추가됐다.
- 포트·연결 검증은 [포트·연결 계약](../../domain/process-port-connection-contract.md)이 정한다.
  - 연결된 두 포트는 `resourceType`이 같아야 한다.
  - 스키마 호환과 조건식을 검사하고, 오류가 있으면 revision 발행을 막는다.
  - `WorkflowValidationService`는 `UNIT_UNKNOWN`·`SCHEMA_UNVERIFIED` 같은 경고를 낸다. 경고는 발행을 막지 않는다.
- `resourceType`은 자유 문자열이다. 비우면 `ioType`(기본 `material`)이 들어간다. 2026-10-02 세션 DB의 포트 14개는 모두 `material`이었다.
- Flow Run(`flowrun` 도메인)이 이미 하는 일:
  - 발행 revision을 고정한다.
  - Step·Attempt·Event를 기록한다.
  - 단계의 입력·출력을 `FlowRunStep.inputSnapshot`·`outputSnapshot`에 저장한다.
  - 출력에 포트 스키마와 `validationRule`을 적용하고, 연결 조건으로 다음 단계를 고른다(`FlowRunGraph`).
- Flow Run은 **노드를 스스로 실행하지 않는다.** 외부가 단계를 `start`·`complete`하며 결과를 보고해야 진행된다.
- 제조 실행은 `ProductionRun`이고, `flow_run.production_run_id`로 Flow Run과 1:1로 연결된다.
- 이 ADR을 쓸 때 포트는 반드시 Item에 묶여 있었다(`process_io.item_id` V1부터 NOT NULL, 생성 API `itemId` 필수). 2026-10-03 V42로 NOT NULL을 풀었다. 생성 API의 `itemId`는 선택이고, 수정 API의 `clearItem: true`가 연결을 지운다. `quantity`·`unit`은 여전히 필수라 데이터 포트는 `quantity 0`과 `ea` 같은 단위를 넣는다.
- `Item`에도 `resourceCategory`·`resourceType` 열이 있다. 예전 설계의 `resource_types`·`resources`·`resource_usage` 테이블은 DB에 있지만 쓰지 않는다.
- 2026-08 기획안은 Item을 범용 Resource Master로 바꾸자고 했다. 그러나 Item·Inventory·LOT·BOM은 이미 깊게 구현됐고, 파일·데이터셋은 수명주기와 검증 규칙이 다르다.

## 결정

1. **3계층으로 나눈다.**

   | 계층 | 질문 | 모델 |
   |---|---|---|
   | 정의 | 무엇이 들어와야 하는가 | `ProcessIo` |
   | 실행 | 실제로 무엇이 들어왔는가 | `FlowRunStep` |
   | 부수효과 | 이 실행이 현실에서 무엇을 바꾸는가 | 도메인 어댑터(재고·LOT 등) |

2. **`ProcessIo`는 Process가 받거나 내보내는 Port Contract다.**
3. **`resourceType`은 포트로 흐르는 대상(Port Resource)의 의미다.**
   - 정확한 값 목록은 이 결정이 아니라 아래 [Resource Type Registry](#resource-type-registry-experimental--evolvable)(Experimental)에서 관리한다.
   - DB enum이나 CHECK 제약으로 고정하지 않고, 애플리케이션의 목록([`ResourceTypes`](../../../flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/domain/contract/ResourceTypes.java))으로 관리한다.
   - 목록 밖 값도 저장은 허용한다. 대신 워크플로 검증 API가 **경고**를 낸다. 거절하지 않으므로 저장된 그래프와 발행 revision이 깨지지 않는다.
   - "연결된 두 포트의 `resourceType`이 같아야 한다"는 기존 규칙은 유지한다.
   - 포트로 흐르지 않고 공정을 실행하는 데 필요한 것(노동, 설비, 연산, 용량, 기술, 시간)은 Port Resource가 아니라 **Execution Resource / Requirement**다. 포트 목록에 넣지 않는다. 그 모델은 아직 정하지 않았다.
4. **`itemId`는 범용 Resource ID가 아니다.** 포트가 Catalog Item과 연결될 때만 쓰는 선택적 binding이다. 데이터·파일·API 포트는 `itemId` 없이 `schemaJson`으로 계약한다.
5. **`schemaJson`은 정의 시점의 계약이다.** 실행값은 넣지 않는다. 실행값은 `FlowRunStep`의 input·output snapshot에 저장한다.
6. **`validationRule`은 포트 값에 대한 조건식이다.** 정의할 때 문법을 검사하고, Flow Run이 단계 출력에 적용한다. 문법은 포트·연결 계약을 따른다.
7. **Resource 슈퍼테이블을 만들지 않는다.**
   - 공통화는 의미가 실제로 같은 곳에서만 한다.
   - 옛 `resource_*` 테이블은 계속 쓰지 않는다.
   - `Item.resourceCategory`·`resourceType`은 품목의 속성일 뿐, 포트의 의미를 정하지 않는다.
   - Port + Binding(`bindingType`/`bindingId`)은 방향만 정한 개념이며, 저장 구조는 만들지 않는다.
8. **FlowRun을 장기적으로 도메인 독립적인 실행 Core로 사용한다. 현재 구조는 이를 지원할 기반을 갖추고 있으나, 비제조 vertical slice가 검증될 때까지 범용 실행 능력은 아키텍처 목표로 취급한다.** (사용자 확정 문장)
   - ProductionRun은 제조 확장이다.
   - `flow_run.production_run_id` 연결은 호환용으로 두고, DB 관계를 뒤집지 않는다.
   - 검증 전에는 문서나 코드 주석에 "FlowRun은 범용이다"를 증명된 사실로 쓰지 않는다.
9. **범용성은 두 단계로 검증한다.**
   - **(a) 지금 할 것** — 외부 실행자가 결과를 보고하는 통합 테스트(File → Transform → Data). 다음을 확인하면 "Core가 제조 독립적"이라는 것이 증명된다.
     - Item·Inventory·LOT·BOM·ProductionRun 행 없이 Flow Run이 끝난다.
     - Step·Attempt·Event가 기록된다.
     - 스키마나 `validationRule`을 어기면 막힌다.
   - **(b) 나중에** — 노드 종류별 실제 실행기. 별도 ADR에서 FM-RUN-005(시간 제한·재시도 간격·동시 실행 제한)와 함께 정한다.
10. **Core에는 업종 개념을 넣지 않는다.**

    | 구분 | 모델 |
    |---|---|
    | Core | Workflow, WorkflowRevision, Process, ProcessIo, ProcessConnection, Rule, FlowRun, Step, Attempt, Event |
    | 제조 도메인 | Item, Inventory, LOT, BOM, Equipment, Quality, WorkOrder, ProductionRun |

    새 업종 개념은 도메인 확장으로 둔다.

## Resource Type Registry (Experimental / Evolvable)

결정 3의 "포트로 흐르는 대상"에 쓰는 값이다. 실제 비제조 흐름으로 실증하면서 더하거나 뺀다. 이 표를 바꾸는 것은 ADR 변경이 아니며, `ResourceTypes`·이 표·[포트·연결 계약](../../domain/process-port-connection-contract.md)의 경고 설명을 같은 변경에서 고친다.

| 값 | 분류 | 메모 |
|---|---|---|
| `material`, `product`, `waste` | 물리 자원 | Catalog Item과 묶일 수 있다(`itemId`) |
| `energy`, `water` | 물리 자원(경계) | 공정이 소비하는 계측 가능한 투입으로 보고 포트에 둔다. 사용량 모델은 미정 |
| `file`, `data`, `api`, `parameter`, `signal` | 논리 자원 | `itemId` 없이 `schemaJson`으로 계약한다 |
| `generic` | 기타 | 아직 분류하지 않은 흐름 |

포트 목록에서 뺀 것:

| 값 | 이유 | 대신 |
|---|---|---|
| `labor` | 작업자는 다음 공정의 출력으로 흘러가지 않는다. 실행 요건·용량·배정·사용량에 가깝다 | Execution Requirement(미정). 예: 용접 공정의 입력 포트는 강재·부품, 실행 요건은 기술 `WELD_L2`·인원 2·작업 시간 30분 |

지금 포트 편집 화면의 I/O Type 선택지(`material, signal, energy, labor, data`)에는 `labor`가 남아 있다. `resourceType` 기본값이 I/O Type이라, `labor` 포트는 `RESOURCE_TYPE_UNKNOWN` 경고를 받는다(저장·발행은 된다). 선택지 정리는 Execution Requirement 모델을 정할 때 함께 한다.

## 결정하지 않은 것

- Port binding의 저장 구조(polymorphic `bindingType`/`bindingId`)
- 공통 Resource contract
- Execution Resource / Requirement 모델(노동·설비·연산·용량·기술·시간). 설비는 지금 `catalog`의 설비·달력·부하로 따로 있다
- `material`·`product`·`waste` 밖의 포트에도 `itemId`를 허용할지
- Registry 값을 확정(Accepted)하는 시점과 절차
- 데이터·파일·API 포트의 `quantity`·`unit`. 지금 둘 다 필수라 의미 없는 값(`quantity 0`, `ea`)을 넣는다
- 에너지·물 같은 계측 자원의 사용량 모델(로드맵 P2)
- 노드 실행기와 실행 정책(위 9-(b))

## 결과

- 좋은 점
  - 제조 모델을 그대로 두고 비제조 흐름을 같은 정의·실행 모델 위에 올릴 수 있다.
  - 포트의 의미가 `resourceType`과 `schemaJson`으로 드러난다.
- 비용
  - 자원 종류마다 검증·부수효과를 도메인 쪽에서 따로 구현해야 한다.
  - Flow Run의 범용성은 테스트로 증명하기 전까지 목표로만 남는다.

## 검증

- allow-list 도입 후 기존 그래프의 저장·발행이 그대로 통과해야 한다. 목록 밖 값은 경고 1건으로 보여야 한다. 2026-10-02에 구현했다. 경고 코드는 `RESOURCE_TYPE_UNKNOWN`이고, `WorkflowValidationIntegrationTest`가 `energy` 무경고, `labor`·비표준 값 각 경고 1건, 발행 성공을 확인한다.
- 위 9-(a) 통합 테스트: 2026-10-03 [`DataFlowRunIntegrationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/DataFlowRunIntegrationTest.java)로 통과했다.
  - 새 프로젝트에서 Item 없는 포트(`file`·`data`)로 File → Transform → Data 그래프를 발행하고 `actual` Flow Run을 끝까지 돌렸다.
  - 단계 3개, 단계마다 시도 1개, 이벤트(`run_started`, `step_created`·`step_started`·`step_completed` 각 3, `run_finished`)를 확인했다.
  - 필수 속성 누락, 타입 불일치, 출력·입력 포트 검증식 위반은 409로 막히고 아무것도 기록되지 않았다.
  - 그 프로젝트에 `item`·`inventory`·`inventory_transaction`·`lot_master`·`bom_header`·`work_order`·`production_run` 행이 하나도 생기지 않았다.
  - 이 결과로 결정 8의 "목표"를 "검증됨"으로 바꿀지는 사용자가 정한다. 사용자 확정 문장은 "비제조 vertical slice 검증"을 조건으로 두며, 화면까지 포함할지는 정하지 않았다.

## 재검토 조건

- 두 종류 이상의 비제조 흐름에서 같은 검증, 같은 수명주기, 같은 실행 의미가 반복되면 공통 Resource contract를 다시 검토한다(원문 §51).
- 9-(a)가 실패하거나, 비제조 흐름이 Flow Run에 제조 개념을 요구하면 E1(결정 8)을 다시 검토한다.

## 참고

- 원문 §26–45, §50 Invariant 3–7
- [결정 인계](../decision-handoff.md) R1–R5, E1·E2
- [실행 모델](../execution-model.md)
