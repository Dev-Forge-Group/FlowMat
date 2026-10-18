# 포트 수량·단위 선택 (ADR-005 결정 1)

상태: **구현(2026-10-10, 세션 1 리드, §2 2cq) — 로컬 격리 검증만, 실제 API 브라우저 미검증.** 결정: [ADR-005](../architecture/adr/ADR-005-port-measurement-and-validation.md)(Accepted 2026-10-05) 결정 1과 [DECISIONS-2026-10-05](../status/DECISIONS-2026-10-05.md) §8. 새 마이그레이션 **V68**(2026-10-16 개발 DB 적용). [포트·연결 계약](process-port-connection-contract.md) PC-12의 "quantity는 0 이상"을 이 문서의 PM2–PM3으로 좁힌다.

## 바뀌지 않는 것

- 제조 재료·제품 포트(`resourceType` `material`·`product`, 또는 품목이 연결된 포트)는 지금처럼 수량(0 이상)과 단위가 필요하다.
- 값이 있으면 지금 규칙 그대로다: 0 이상, numeric(14,4), 단위 20자, 등록 단위 유형의 포트·연결 일치(PC-06), 모르는 단위는 검증 경고.
- 이미 저장된 포트와 발행 revision은 값을 그대로 둔다. 일괄 수정하지 않는다.
- 노동·설비·컴퓨팅·기술·처리 능력은 포트 자원이 아니라 실행 요건이다(ADR-005 결정 2). 이 문서는 그것을 다루지 않는다.

## 규칙

| # | 규칙 |
|---|---|
| PM1 | **저장:** `process_io.quantity`·`unit`의 NOT NULL을 푼다(V68). 기존 행은 그대로 |
| PM2 | **수량·단위가 필요한 포트:** `resourceType`(없으면 `ioType`)이 `material`·`product`이거나 품목이 연결된 포트. 둘 다 없으면 400 `Material and product ports need a quantity and unit.` |
| PM3 | **나머지 포트(energy·water·waste·file·data·api·parameter·signal·generic·비표준):** 수량·단위 모두 생략할 수 있다. 수량을 주면 단위도 필요하다(400 `A port quantity needs a unit.`). 단위만 있는 것은 허용한다(예: 계획량 없이 kWh로 계측할 energy 포트) |
| PM4 | **생성:** 요청의 `quantity`·`unit`은 선택이다. 생략하면 NULL로 저장한다(예전처럼 0을 채우지 않는다). PM2 포트는 생략 시 400 |
| PM5 | **수정:** 지금처럼 `quantity`·`unit`을 주면 바꾼다. 비우려면 `clearMeasure: true`: 수량·단위를 먼저 함께 NULL로 하고, 같이 보낸 `quantity`·`unit`만 다시 넣는다(예: 단위만 남기고 계획량 지우기). 수정 결과가 PM2·PM3을 어기면 400(예: data 포트를 material로 바꾸면서 수량이 없음). 단위 변경처럼 연결 재검증(PC-11)도 그대로 |
| PM6 | **검증 보고서·연결:** 단위가 없는 포트는 단위 경고·단위 유형 비교에서 빠진다. 연결 `capacity`는 출발 포트 단위가 없고 연결 단위도 없으면 단위 없이 수량만 비교한다(GR-03 그대로) |
| PM7 | **발행·실행:** 발행 snapshot은 NULL을 그대로 담는다. 실행(FlowRunGraph)은 단위가 없는 포트를 지금처럼 단위 일치 검사에서 건너뛴다. 생산 실행은 BOM 기준이라 포트 수량을 읽지 않는다 |
| PM8 | **화면:** 포트 편집에서 PM2 포트만 수량·단위를 필수로 표시한다. 그 밖의 포트는 둘 다 비워 저장할 수 있고, 비우면 `clearMeasure`로 보낸다. 캔버스·검사기는 값이 없으면 표시하지 않는다 |
| PM9 | 개발 DB(5434)에는 V68을 적용하지 않는다. Testcontainers와 격리 복사본으로만 검증한다. 새 테스트는 개발 DB에 BOM을 넣지 않는다 |

## 검증 결과 (2026-10-10, 격리 복사본)

- `ProcessIoMeasureTest` 2건(material·product·품목 포트는 둘 다 필요, 그 밖은 생략 가능·수량에는 단위), `PortMeasurementIntegrationTest` 3건(제조 포트 400·clearMeasure 거절 / energy 단위만·수량만 400·clearMeasure 뒤 단위만 다시 / 수량·단위 없는 file 포트 흐름의 검증 오류 0·발행 snapshot NULL·그래프 실행 완료).
- 첫 실행 1건 실패는 테스트의 기대값 오류였다: 스키마 없는 포트의 `SCHEMA_UNVERIFIED` 경고를 0으로 기대 → 단위 경고가 없는지로 고침.
- 프런트 `portPolicy.test.ts`(포트 종류별 필수·blank는 clearMeasure·옛 NULL 포트 폼). 포트 편집 모의 E2E는 없다(실제 API `data-ports` 스펙은 오류·테스트 담당이 V68 전용 환경에서).
- 전체 백엔드 격리 실행 **1,253건 실패·오류 0**(2cp–2cs 포함), 프런트 단위 615·타입·lint, 전체 모의 E2E 129 통과·19 의도적 제외.

## 검증 계획 (ADR-005 "구현·검증")

- 제조 필수값 거절: material·product·품목 연결 포트의 수량·단위 생략 400, 수정으로 비우기 400.
- 비제조 값 생략: data·file 포트를 수량·단위 없이 만들고 연결·발행·그래프 실행(File → Transform → Data)이 끝까지 간다. 수량만 있고 단위가 없으면 400.
- 기존 발행 revision 호환: 수량·단위가 있는 옛 snapshot의 실행이 그대로 돈다(`FlowRunGraphIntegrationTest`·`DataFlowRunIntegrationTest`).
- 화면: 모의 E2E로 data 포트를 수량·단위 없이 저장하고 material 포트는 저장 버튼이 막히는지.
