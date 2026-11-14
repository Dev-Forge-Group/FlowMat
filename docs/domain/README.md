# 도메인 계약·설계 문서

기능별 계약과 설계다. 구현과 같은 변경에서 갱신하며, 각 문서 맨 위의 `상태:` 줄이 구현 여부와 날짜를 말한다. 기능 상태의 전체 표는 [CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md)에 있다.

**이 폴더의 파일은 옮기거나 이름을 바꾸지 않는다.** 백엔드·프런트 코드 주석이 `docs/domain/<파일>.md` 경로로 이 문서들을 가리킨다.

재고·BOM·LOT 관련 규칙이 서로 다르면 [inventory-bom-lot-contract.md](inventory-bom-lot-contract.md)가 우선한다.

## 기본 계약

| 문서 | 내용 |
|---|---|
| [inventory-bom-lot-contract.md](inventory-bom-lot-contract.md) | 재고·BOM·LOT 1차 계약: 수량·단위, 거래 멱등성, 역분개, BOM 승인·revision, LOT 상태·계보 |
| [process-port-connection-contract.md](process-port-connection-contract.md) | 공정 포트(ProcessIo)·연결 계약, 조건식·스키마 검증, 발행 관문, Flow Run 그래프 실행 계약(V39) |
| [port-measurement.md](port-measurement.md) | 포트 수량·단위 선택(ADR-005 결정 1): 제조 재료·제품·품목 포트만 필수, `clearMeasure`(V68) |

## 정의와 실행 (Core)

| 문서 | 내용 |
|---|---|
| [workflow-revision.md](workflow-revision.md) | 워크플로 발행 revision: 스냅샷, 발행 관문, 폐기, 실행의 revision 고정 |
| [flow-run.md](flow-run.md) | Flow Run: 실행·단계·시도·이벤트, 상태, 그래프 실행, 생산 실행 연결 |
| [flow-run-execution-policy.md](flow-run-execution-policy.md) | 노드 실행 정책(ADR-004): 시간 제한·재시도 횟수와 간격·동시 실행 제한, 시간 제한 감시(V67) |

## 품목·원가

| 문서 | 내용 |
|---|---|
| [item-details.md](item-details.md) | 품목 상세: 그룹·규격·바코드·SKU·보관 조건·설명, 구매 단위 |
| [item-status.md](item-status.md) | 품목 상태 active·inactive·discontinued와 사용 제한 |
| [item-import.md](item-import.md) | 품목 CSV 내보내기·가져오기 |
| [material-cost.md](material-cost.md) | 재료비와 재고 금액(품목 단가) |

## 재고·창고·LOT

| 문서 | 내용 |
|---|---|
| [stock-ledger.md](stock-ledger.md) | 재고 이동 원장(Movements 탭), 기간 수불, 과거 시점 재고 |
| [stock-transfer.md](stock-transfer.md) | 위치 간 재고 이동 |
| [storage-location.md](storage-location.md) | 보관 위치 목록(site > warehouse > zone > location > bin) |
| [warehouse-task.md](warehouse-task.md) | 창고 작업 Putaway·Pick |
| [stock-allocation.md](stock-allocation.md) | 작업지시 재고 할당(예약 → 할당 → 사용) |
| [stock-count.md](stock-count.md) | 재고 실사와 실사표 CSV |
| [stock-import.md](stock-import.md) | 재고 일괄 입고 CSV(기초 재고, 새 LOT) |
| [stock-alert.md](stock-alert.md) | 재고 경보(최소·최대, 유효기한 임박), 재주문 목록 |
| [stock-analysis.md](stock-analysis.md) | 재고 흐름 분석: 소비, 소진 일수, 정체 재고, ABC |
| [lot-expiry.md](lot-expiry.md) | LOT 유효기한: 만료 차단, FEFO, 만료 재고 폐기 |
| [lot-recall.md](lot-recall.md) | LOT 리콜: 영향 범위와 일괄 격리 |

## BOM·계획

| 문서 | 내용 |
|---|---|
| [multi-level-bom.md](multi-level-bom.md) | 다단계 BOM: 반제품, 순환 거절, 전개, 다단계 역전개, 반제품 원가 누적 |
| [bom-by-products.md](bom-by-products.md) | BOM 부산물·폐기물 줄 |
| [material-requirements.md](material-requirements.md) | 열린 작업지시의 자재 소요(간이 MRP) |

## 생산 실행

| 문서 | 내용 |
|---|---|
| [work-order-readiness.md](work-order-readiness.md) | 작업지시 실행 준비 점검(Readiness) |
| [work-instruction.md](work-instruction.md) | 작업 지침 revision과 실행 체크리스트 |
| [production-run-recording.md](production-run-recording.md) | 실행 기록의 재전송: 응답을 잃은 기록을 다시 보내도 한 번만 반영(`requestId`) |
| [production-run-correction.md](production-run-correction.md) | 종료된 생산 실행의 보정 전표 |

## 품질

| 문서 | 내용 |
|---|---|
| [quality-inspection.md](quality-inspection.md) | 품질 검사와 불량 기록 |
| [inspection-standard.md](inspection-standard.md) | 품목별 검사 기준과 실행 품질 체크리스트 |
| [nonconformity.md](nonconformity.md) | 부적합(NCR)과 시정 조치(CAPA) |

## 알림

| 문서 | 내용 |
|---|---|
| [notification.md](notification.md) | 맡긴 일의 앱 안 알림(NCR 조치·창고 작업·작업지시 담당자) |

## 설비

| 문서 | 내용 |
|---|---|
| [equipment.md](equipment.md) | 설비 정보(제조사·모델·위치·시간당 산출 등) |
| [equipment-schedule.md](equipment-schedule.md) | 설비 달력·정지 시간·프로젝트 휴일과 작업지시 설비 점검 |
| [worker-calendar.md](worker-calendar.md) | 작업자(프로젝트 구성원) 주간 교대·쉬는 날·휴일과 일하는 시간, 작업지시에 필요한 사람(crew), 작업자 부하·겹침 |
| [equipment-changeover.md](equipment-changeover.md) | 설비 전환 시간(Setup/Changeover) |
| [equipment-load.md](equipment-load.md) | 설비 부하표(주 단위) |
