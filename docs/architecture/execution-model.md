# 실행 모델

> **현행 문서** · 최종 확인 2026-10-02(코드 `45f5df0` + 작업 트리) · 정의·실행 모델이나 상태가 바뀌면 같은 변경에서 고친다.
> 이전 판(2026-09-24): [archive/2026-09-architecture/execution-model.md](../archive/2026-09-architecture/execution-model.md)

공정 정의가 계획과 실행 기록으로 이어지는 방식, 각 모델의 상태, 실행이 재고에 남기는 부수효과를 정리한다. 포트 계약은 [ADR-003](adr/ADR-003-resource-port-contract.md)과 [포트·연결 계약](../domain/process-port-connection-contract.md), 재고 규칙은 [재고·BOM·LOT 계약](../domain/inventory-bom-lot-contract.md)이 우선한다.

## 1. 네 계층

| 계층 | 묻는 것 | 모델(context) |
|---|---|---|
| 편집 문서 | 화면에 무엇을 그렸는가 | 도형 편집 문서·주석(`workflow.editor`, `workflow.annotation`). 공정 노드·연결은 아래 정의 계층과 같은 데이터다 |
| 정의 | 무엇을 어떻게 해야 하는가 | Workflow, Process, ProcessIo(포트), ProcessConnection, FlowRule, 발행 WorkflowRevision(`workflow`, `rule`) |
| 계획 | 언제, 얼마나, 무엇으로 할 것인가 | WorkOrder, BOM, 재고 할당, 준비 점검, 설비 달력·부하, 간이 MRP(`production`, `bom`, `catalog`) |
| 실행 기록 | 실제로 무엇이 일어났는가 | FlowRun·Step·Attempt·Event(`flowrun`), ProductionRun·RunItem·RunStateSnapshot·보정 전표(`production`), InventoryTransaction·LotTrace(`inventory`), 검사·불량(`quality`) |

정의는 고칠 수 있지만, 실행은 시작할 때의 정의를 고정한다(§2).

## 2. 정의 → 발행 revision → 실행

1. 워크플로를 편집한다. 검증 API(`GET /workflows/{id}/validation`)가 오류와 경고를 알려 준다.
2. 발행(`POST /workflows/{id}/revisions`)하면 공정·포트·연결이 revision 스냅샷으로 고정된다. 오류가 있으면 409로 막히고 경고만 있으면 발행된다.
3. 실행은 발행 revision을 고정한다.
   - 생산 실행은 `production_run.workflow_revision_id`(V21)에 고정한다.
   - Flow Run도 시작 때 revision을 고정한다.
4. BOM은 생산 실행 시작 때 revision과 기준 수량을 고정한다(재고·BOM·LOT 계약).
5. 폐기(retired)된 revision으로는 새 실행을 시작하지 않는다.

## 3. 포트의 세 층 (ADR-003)

| 층 | 질문 | 어디에 |
|---|---|---|
| 정의 | 무엇이 들어와야 하는가 | `ProcessIo`: `resourceType`, `schemaJson`, `validationRule`, 선택적 `itemId` |
| 실행 | 실제로 무엇이 들어왔는가 | `FlowRunStep.inputSnapshot`·`outputSnapshot` |
| 부수효과 | 현실에서 무엇이 바뀌는가 | 도메인 쪽: 생산 실행의 투입·산출이 재고 거래와 LOT 계보를 만든다 |

포트의 Item은 선택이다(V42, 2026-10-03). 데이터·파일·API 포트는 Item 없이 `schemaJson`으로 계약한다. Item 없는 포트로는 생산 실행의 투입·산출을 기록할 수 없다(400). `quantity`·`unit`은 아직 모든 포트에 필수다.

## 4. 상태

| 모델 | 상태와 흐름 | 비고 |
|---|---|---|
| WorkflowRevision | published → retired | 발행본은 바뀌지 않는다 |
| WorkOrder | draft → approved → in_progress → completed / cancelled | 완료·취소 시 남은 할당은 자동 반환 |
| BOM | draft → pending_approval → approved → retired | 실행·계획은 승인본만 쓴다 |
| ProductionRun | pending → running → finished | 끝난 실행은 고치지 않고 보정 전표로 바로잡는다(V22) |
| FlowRun | running → finished / failed / cancelled | 끝나면 진행 중 단계는 failed 또는 cancelled가 된다 |
| FlowRunStep | planned → running → completed / failed / skipped / cancelled | 시도(Attempt)는 running → completed / failed |
| LOT | available / reserved / quarantined / consumed / closed | 재고 행에서 다시 계산한다. 커밋 뒤 잠금 아래 한 번 더 맞춘다 |

## 5. Flow Run

- 시작: `POST /flow-runs`(수동), `POST /flow-runs/graph`(발행 revision의 그래프). 순환이 있는 그래프는 실행을 거절한다.
- 단계는 스스로 실행되지 않는다. 외부(사용자나 API 호출자)가 `start`·`complete`·`fail`·`retry`로 결과를 보고한다.
- Flow Run은 결과를 받으면 다음을 한다.
  - 포트 스키마와 `validationRule`로 검사한다.
  - 연결 조건·용량으로 다음 단계를 고르고, 출처 단계를 기록한다(V40).
  - 완료 전에는 `preview`로 경로를 미리 볼 수 있다.
- 실패 정책은 연결마다 stop·skip·retry 중 하나다. retry는 즉시 최대 3회 다시 시도한다.
- 그 뒤 생긴 것(2026-10-17 기준): 시간 제한·재시도 간격·동시 실행 제한([ADR-004](adr/ADR-004-flow-run-execution-policy.md), [노드 실행 정책](../domain/flow-run-execution-policy.md)), Exclusive 게이트웨이([ADR-006](adr/ADR-006-operation-types.md), 2eb), Join 게이트웨이([ADR-007](adr/ADR-007-join-gateway.md), 2ef), Split 게이트웨이([ADR-008](adr/ADR-008-quantity-split.md), 2eh: 비율·고정·나머지로 수량을 나눔), 연결 손실률([ADR-009](adr/ADR-009-connection-loss-rate.md), 2ei: 새 발행본부터 연결을 지날 때 뺌), 연결 지연([ADR-010](adr/ADR-010-connection-delay.md), 2ej: 새 발행본부터 다음 단계의 예정 시각). 게이트웨이 단계만 서버가 지나간다.
- 아직 없는 것
  - 노드 자동 실행기
  - 개수 단위의 정수 나눔([ADR-008](adr/ADR-008-quantity-split.md)), 흐름률 적용과 손실의 재고 기록([ADR-009](adr/ADR-009-connection-loss-rate.md) "결정하지 않은 것"), 근무 시간에 맞춘 예정 시각([ADR-010](adr/ADR-010-connection-delay.md) "결정하지 않은 것")
- 범용성: 비제조 흐름으로 검증될 때까지 목표로 취급한다(ADR-003 결정 8·9). 결정 9-(a)의 통합 테스트(`DataFlowRunIntegrationTest`, File → Transform → Data)는 2026-10-03에 통과했고, "검증됨"으로 바꿀지는 사용자 결정 대기다.

## 6. 생산 실행과 Flow Run의 연결

- 발행 revision이 있는 생산 실행은 같은 트랜잭션에서 연결된 Flow Run을 만든다.
  - 생산 실행이 시작되면 Flow Run이 running으로 시작한다.
  - 2026-10-17부터 이 Flow Run은 그래프로 시작해 공정마다 단계를 둔다. 작업자는 생산 실행 API로 단계를 시작하고 완료한다([ADR-011](adr/ADR-011-production-steps.md)). revision이 그래프로 돌 수 없으면 단계가 없다.
  - 생산 실행이 끝나면 Flow Run은 finished가 되고, 실제 산출량이 기록된다.
- 연결은 `flow_run.production_run_id`이다. production은 `FlowRunCommand`(flowrun 공개 API)만 호출하고, Flow Run 저장 구현은 모른다(ADR-002).
- revision이 없는 생산 실행에는 Flow Run이 없다.
- 공용 실행 종류가 생기기 전의 옛 실행은 연결 없이 끝낼 수 있다.

## 7. 시뮬레이션과 실제 실행

| 실행 종류(`runType`) | 재고·LOT 변경 | 실행 기록 |
|---|---|---|
| `actual` | 있음 | 있음 |
| `simulation`, `test`, `dry_run` | 없음(`ProductionRun.affectsPhysicalState()`가 false) | 있음 |

같은 정의를 쓰고 부수효과만 다르다. 새 실행 API도 이 구분을 따른다.

## 8. 재고 부수효과의 규칙

- 재고는 `InventoryTransaction`으로만 바뀐다. 입고, 출고, 생산 투입·산출, 예약·해제, 조정, 역분개, 격리·해제, 이동 출·입이 모두 같은 원장에 남는다.
- 클라이언트가 보낸 멱등 키는 프로젝트 안에서 유일하다(V17). 같은 명령을 다시 보내도 두 번 반영되지 않는다.
- 거래는 지우지 않고 역분개한다. 끝난 생산 실행은 보정 전표로 바로잡는다.
- 투입·산출은 LOT 계보(LotTrace)를 남긴다.

## 9. 새 실행 기능을 만들기 전에

- 정의, 계획, 실행 기록 중 어느 것인가?
- 시작할 때 고정해야 하는 값(revision, BOM, 지침)이 있는가?
- 재고 부수효과가 있는가? 있다면 시뮬레이션에서는 막히는가?
- 멱등 키가 필요한가?
- LOT 계보에 영향을 주는가?
- 포트(ProcessIo)나 Flow Run 단계와 연결되는가?
- 감사 이력이 필요한가? 실행 기록은 고치지 않고 이벤트나 보정으로 남긴다.
