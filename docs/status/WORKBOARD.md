# 작업 현황판

> **현행 문서** · 최종 갱신 2026-11-02 · 작업을 시작·끝내거나 결정이 나면 같은 변경에서 고친다. 무엇이 구현됐는지는 [CURRENT_CAPABILITIES](CURRENT_CAPABILITIES.md), 아키텍처 결정은 [결정 인계](../architecture/decision-handoff.md)가 기준이다. 이 판은 "지금 무엇을 하고, 다음에 무엇을 하며, 무엇을 기다리는가"만 모은다.

## 1. 진행 중

> 2026-11-01 리드 정리: 끝난 항목 52개(2차 28개는 "리드 반영 대기"를 코드와 대조한 뒤)와 리드 기록의 구현 줄 64개를 [보관 문서](../archive/2026-10-workboard/WORKBOARD-done-2026-10.md) §1로 옮겼다. 여기 남은 항목은 끝 부분에 대기·남은 일·요청 같은 열린 표시가 있는 것이다.

### 2026-11-02 리드: BOM 줄 실제 손실 집계(2hy, ADR-034) — 완료(로컬)

사용자 "ㄱ"(2hx 뒤 다음 작업)로 시작했다. ADR-031의 마지막 미정 항목이다. 결정은 2026-10-24 위임에 따른 권장안 A다([ADR-034](../architecture/adr/ADR-034-bom-scrap-performance.md)). 읽기 전용이고 마이그레이션이 없다.

**무엇이 바뀌나**
- `GET /boms/{bomId}/scrap-performance`: 그 revision의 끝난 물리 실행을 모아, 자재마다 순량(계획 ÷ (1 + 스크랩률))·계획량·실제량을 실제 생산량에 맞춰 더하고 실제 손실률(실제 ÷ 순량 − 1)을 계획 스크랩률과 함께 준다. 대체 자재 투입은 주 자재 몫으로 센다.
- 화면: BOMs 탭 승인·은퇴 revision의 **Scrap: planned vs actual** 표.

**파일**(모두 2hy만의 것, 공유 파일은 덧붙이기만)
- 새 파일: `BomScrapPerformanceService.java`, `BomScrapPerformanceController.java`, `BomScrapPerformanceResponse.java`, `BomScrapPerformanceIntegrationTest.java`, 프런트 `BomScrapPerformance.tsx`, `ADR-034-bom-scrap-performance.md`.
- 고친 파일: `ProductionRunRepository`(파생 쿼리 하나), 프런트 `api.ts`·`useBoms.ts`·`bomModel.ts`(+test)·`BomPanel.tsx`·`e2e/bom-scrap-rate.spec.ts`(표 단계), 문서 `material-cost.md`(새 절)·ADR-031(정정)·CURRENT_CAPABILITIES. 2hv–2hx와 한 커밋으로 묶는 것을 권한다.

**검증**(격리 사본 `E:\claude-data\flowmat-work\scrap-2hv`)
- 백엔드 새 테스트 1건(끝난 실행 두 건: 밀가루 순량 6 kg에 6.5 kg, 손실률 8.33%, 대체 호밀 2.5 kg은 밀가루 2.1 kg으로, 진행 중 실행 제외)과 모듈 경계 테스트 통과.
- 프런트: 단위 972건(새 2건), 타입 검사, lint(오류 0, 경고 4건은 손대지 않은 파일), 빌드, BOM·작업지시 BOM 날짜·다단계 브라우저 32건 통과.
- 백엔드 전체(2hv–2hy 포함): 442개 클래스 3,246건, 실패 0. ADR-033·ADR-034를 Accepted로 올렸다.

### 2026-11-02 리드: BOM 선택 자재(2hx, ADR-033) — 완료(로컬), V133 개발 DB 미적용

사용자 "ㄱ"(2hw 뒤 다음 작업, 선택 자재 범위 정리부터)로 시작했다. 결정은 2026-10-24 위임에 따른 권장안 A다([ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md)).

**무엇이 바뀌나**
- `optional_yn = 'Y'` 자재 줄은 기본으로 계획에 넣지 않는다. 작업지시가 넣을 선택 자재를 **품목으로** 고른다. 작업지시의 BOM revision은 승인 때 다시 정해질 수 있어 줄 id가 아니라 품목이다.
- 새 표 `work_order_optional_item`(**V133**)에 저장한다. 초안 작업지시에서만 바꾸고(수정 요청에 없으면 그대로, 빈 목록이면 모두 뺌), 저장 때와 승인 때 작업지시 BOM(팬텀 안 포함)의 선택 자재인지 검사한다.
- 작업지시의 준비 점검·간이 MRP·자동 할당·피킹·실행 계획 투입·폐기 처리는 그 선택을 쓴다. 작업지시가 없는 계산(BOM 소요량·만들 수 있는 양·원가 누적·역전개 합계·작업지시 없는 실행)은 넣지 않고, 소요량은 `optionals`로 따로 싣는다.
- 승인: 선택 자재는 자재 줄만, 대체 묶음에 들 수 없다. 사용량 차이: 고르지 않은 선택 자재를 쓰면 `optional`("optional, not picked").
- 화면: BOM 줄 추가의 "Optional", BOM 소요량의 "Optional, per work order" 표, 작업지시 폼의 "Optional materials" 체크와 목록의 "with BOX". 자재 CSV의 `optional` 열은 파일째 거절한다.

**V133 적용 순서(지키기)**
- V133은 새 표만 만든다(기존 표·행 그대로, 작업지시 삭제 때 함께 삭제). 일회용 테스트 DB에서만 돌았다.
- 개발 DB 5434에는 사용자가 커밋한 뒤 복제본 리허설을 거쳐 적용한다(10-18 결정·D2와 같은 순서). 그 전에 이 작업 트리로 5434에 서버를 띄우면 Flyway가 V133을 바로 적용하니 주의한다.

**커밋 나누기**
- 2hx만의 새 파일: `V133__work_order_optional_item.sql`, `WorkOrderOptionalItem.java`, `WorkOrderOptionalItemRepository.java`, `WorkOrderOptionalItems.java`, `WorkOrderOptionalItemCheck.java`, `BomOptionalIntegrationTest.java`, `e2e/bom-optional.spec.ts`, `ADR-033-bom-optional-materials.md`.
- 2hx만 고친 파일(앞 묶음·2hv·2hw가 건드리지 않음, `next-push-20261101` 사본과 HEAD로 대조): `WorkOrderCreateRequest`·`WorkOrderUpdateRequest`·`WorkOrderResponse`·`WorkOrderServiceImpl`·`WorkOrderEquipmentService`·`WorkOrderRescheduleService`·`publicapi/WorkOrderView`·`WorkOrderQueryImpl`·`WorkOrderWasteDisposalService`·`ProductionRunServiceImpl`·inventory `WarehouseTaskService`·`BomService`, 테스트 `WorkOrderServiceImplTest`·`ProductionRunServiceImplTest`·`StockAllocationServiceTest`, 프런트 `useWorkOrders.ts`·`WorkOrdersPanel.tsx`.
- 2hv·2hw와 섞인 파일: `BomServiceImpl`, `BomRequirementResponse`, `BomApprovalServiceImpl`, `BomStructureQuery`(+Impl), `BomCostRollupService`, `BomWhereUsedTreeService`, `BuildableQuantityService`, `StockAllocationService`, `WorkOrderReadinessService`, `MaterialRequirementService`, `RunMaterialUsageService`(+Response), `BomLine`, `BomLineCreateRequest`, `BomIntegrationTest`(승인 거절 예시를 "부산물은 선택 자재일 수 없다"로), 프런트 `api.ts`·`useBoms.ts`·`bomModel.ts`(+test)·`BomPanel.tsx`·`BomRevisionCompare.tsx`·`RunCostPanel.tsx`, 문서 domain 7개·ADR-031·ADR-032. **2hv·2hw·2hx를 한 커밋으로 묶는 것을 권한다.** V133만 따로 늦출 수는 없다(코드가 표를 쓴다).

**검증**(격리 사본 `E:\claude-data\flowmat-work\scrap-2hv`)
- 백엔드 새 테스트 3건(작업지시마다 고른 선택 자재가 준비 점검·MRP·할당·실행 계획에 들어가고 아닌 것은 빠짐·사용량 표시 / 초안 선택 유지·비우기·검사·BOM 없는 승인 거절 / 대체 묶음 거절)과 BOM·작업지시·할당·실행 단위 테스트 통과.
- BOM·할당·준비 점검·MRP·만들 수 있는 양·실행 원가·사용량·창고·피킹·생산 실행·작업지시·마이그레이션·폐기 92개 클래스 521건 통과.
- 프런트: 단위 970건(새 4건), 타입 검사, lint(오류 0, 경고 4건은 손대지 않은 파일), 빌드, BOM·작업지시·실행 원가 브라우저 50건 통과(3건은 실 API 전용이라 건너뜀).
- 백엔드 전체(2hv·2hw·2hx 포함): 441개 클래스 3,245건, 실패 0.

### 2026-11-02 리드: BOM 대체 자재(2hw, ADR-032) — 완료(로컬)

사용자 "ㄱ"(2hv 뒤 다음 작업, 대체 자재 범위 정리부터)로 시작했다. 결정은 2026-10-24 위임에 따른 권장안 A다([ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md)).

**무엇이 바뀌나**
- 같은 `substitute_group`의 자재 줄이 한 묶음이다. 우선순위(`priority`)가 가장 작은 줄이 주 자재다. 마이그레이션은 없다(V1 열). 개발 DB 줄 48개는 묶음이 없다.
- 계획·원가는 주 자재로만 한다: 소요량, 재료비, 원가 누적, 만들 수 있는 양, 다단계 역전개 합계. 소요량의 주 자재 줄이 `substitutes`(전부 대체할 때의 양·원가)를 싣는다.
- 대체는 사람이 고른다(자동 대체 없음). 열린 실행의 대체 투입과 열린 대체 할당은 주 자재 몫(주 자재 소요량 ÷ 대체 자재 소요량)으로 센다: 준비 점검, 간이 MRP, 자동 할당.
- 준비 점검: 대체 재고가 모자람을 덮으면 자재 점검이 WARN이다. 사용량 차이: 대체 자재는 BOM 안 품목이고, 주 자재 기준을 실제 사용 비율로 나눈다.
- 화면: 줄 추가의 "Substitute for", 줄·소요량·판 비교·사용량 차이의 대체 표시. 자재 CSV의 대체 열은 파일째 거절한다.
- 같이 고친 2hv 누락: "만들 수 있는 양"이 팬텀 없는 BOM에서 스크랩을 빼고 있었다(ADR-031 결정 4 정정).

**커밋 나누기**
- 2hw만 고친 파일(앞 묶음·2hv가 건드리지 않음, `next-push-20261101` 사본과 HEAD로 대조):
  - 백엔드: `BomLineResponse.java`, `BomCopyService.java`, `BomCostRollupService.java`, `BomWhereUsedTreeService.java`, `publicapi/BomStructureQuery.java`, `BomStructureQueryImpl.java`, `StockAllocationService.java`, `MaterialRequirementService.java`, `WorkOrderReadinessService.java`, `RunMaterialUsageService.java`, `RunMaterialUsageResponse.java`
  - 백엔드 테스트: `BomSubstituteIntegrationTest.java`(새 파일)
  - 프런트: `pages/runs/ui/RunCostPanel.tsx`, 테스트 `e2e/bom-substitute.spec.ts`(새 파일)
  - 문서: `ADR-032-bom-substitute-materials.md`(새 파일), `docs/domain/material-cost.md`, `stock-allocation.md`, `work-order-readiness.md`, `material-requirements.md`
- 2hv와 섞인 파일: `BomTree.java`, `BomServiceImpl.java`, `BomApprovalServiceImpl.java`, `BomRequirementResponse.java`, `BomLine.java`, `BomLineCreateRequest.java`, `BuildableQuantityService.java`(2hv 스크랩 누락 수정도 여기), `BomScrapRateIntegrationTest.java`(만들 수 있는 양 검사), 프런트 `shared/types/api.ts`, `useBoms.ts`, `bomModel.ts`, `bomModel.test.ts`, `BomPanel.tsx`, `BomRevisionCompare.tsx`, 문서 ADR-031, `multi-level-bom.md`, `inventory-bom-lot-contract.md`, `item-import.md`. 2hv와 2hw는 한 커밋으로 묶는 것을 권한다(나누려면 `git add -p`).
- 앞 묶음과도 섞인 문서: `adr/README.md`, `decision-handoff.md`, `CURRENT_CAPABILITIES.md`, 이 판.

**검증**(격리 사본 `E:\claude-data\flowmat-work\scrap-2hv`)
- 백엔드 새 테스트 3건(묶음 만들기·승인·소요량·원가·만들 수 있는 양·판 복사 / 준비 점검·할당·MRP·사용량 차이 / 승인 거절 조건)과 2hv 스크랩 테스트 3건 통과.
- BOM·할당·준비 점검·MRP·만들 수 있는 양·실행 원가·사용량·창고·피킹·생산 실행·작업지시 78개 클래스 494건 통과.
- 프런트: 단위 966건(새 4건), 타입 검사, lint(오류 0, 경고 4건은 손대지 않은 파일), 빌드, 모의 BOM 브라우저 27건(새 1건), 실행 원가 관련 브라우저 16건 통과.
- 백엔드 전체(2hv·CSV 열·이벤트 순서 수정·2hw 포함): 440개 클래스 3,242건, 실패 0. ADR-032를 Accepted로 올렸다.

### 2026-11-02 리드: BOM 줄 스크랩률(2hv, ADR-031) — 완료(로컬)

사용자 "ㄱ"(JVM 수정 뒤 다음 작업)로 시작했다. 결정은 2026-10-24 위임에 따른 권장안 A다([ADR-031](../architecture/adr/ADR-031-bom-scrap-rate.md) Accepted).

**무엇이 바뀌나**
- 필요량 = 순량 × (1 + 스크랩률)이다. 스크랩률은 0–1, 소수 넷째 자리까지, 자재 줄에만 둔다.
- 팬텀 줄의 스크랩은 팬텀 BOM 안의 스크랩과 곱해진다.
- 모든 소요량·원가 누적·실행 계획 투입에 적용된다. 실제 투입은 바뀌지 않는다.
- 마이그레이션은 없다. V1의 `bom_line.scrap_rate`를 쓴다.
- 개발 DB 줄 48개는 모두 0이라 기존 숫자는 바뀌지 않는다.

**커밋 나누기**
- 아래 코드·테스트 15개 파일은 이 일(2hv) 것뿐이다. HEAD와 검증 사본을 대조해 앞 묶음이 건드리지 않은 것을 확인했다.
  - 백엔드: `BomTree.java`, `BomServiceImpl.java`, `BomApprovalServiceImpl.java`, `BomRequirementResponse.java`, `BomLine.java`(주석), `BomLineCreateRequest.java`(주석)
  - 백엔드 테스트: `BomScrapRateIntegrationTest.java`(새 파일), `BomIntegrationTest.java`(승인 거절 예시를 선택 자재로 바꿈)
  - 프런트: `entities/bom/api/useBoms.ts`, `shared/types/api.ts`, `pages/inventory/model/bomModel.ts`, `bomModel.test.ts`, `pages/inventory/ui/BomPanel.tsx`, `BomRevisionCompare.tsx`
  - 프런트 테스트: `e2e/bom-scrap-rate.spec.ts`(새 파일)
- 이 일만의 문서: `docs/architecture/adr/ADR-031-bom-scrap-rate.md`(새 파일), `docs/domain/inventory-bom-lot-contract.md`, `docs/domain/multi-level-bom.md`.
- 앞 묶음(CI 4건·D1–D4·B1–B9)과 섞인 문서는 넷이다. 따로 커밋하려면 `git add -p`로 나눈다.
  - `docs/architecture/adr/README.md`·`decision-handoff.md`: ADR-031 행 하나씩
  - `docs/status/CURRENT_CAPABILITIES.md`: FM-MFG-001 행
  - `docs/status/WORKBOARD.md`: 이 항목
- 앞 묶음은 2026-11-02에 CI 네 가지 초록을 확인한 상태 그대로다. 그 사본은 `E:\claude-data\flowmat-work\next-push-20261101`에 있다.

**검증**(격리 사본 `E:\claude-data\flowmat-work\scrap-2hv`)
- 백엔드: BOM·할당·MRP·준비 점검·창고·전개·원가 19개 클래스 82건 통과.
- 백엔드 전체: 438개 클래스 3,236건 중 실패 1건이었다. 실패는 아래 "Flow Run 이벤트 같은 시각 순서"의 간헐 실패이고(같은 날 고침), 따로 세 번 돌려 모두 통과했다.
- 프런트:
  - 모의 BOM 브라우저 27건(새 스펙 포함), 단위 테스트 961건(새 4건), 타입·lint(오류 0, 경고 4건은 손대지 않은 파일)·빌드 통과.

**뒤이은 일: CSV 스크랩 열(ADR-031 결정 9, 같은 날)**
- 초안 BOM 자재 CSV가 선택 열 `scrap_percent`(별칭 `scrap`, `scrap_pct`, `scrap %`)를 화면처럼 퍼센트로 받는다. `scrap_rate`·`scrap_factor` 열은 0–1로 읽힐 수 있어 파일째 거절한다.
- 파일(모두 2hv 묶음에 넣는다):
  - 백엔드: `BomLineImportRequest.java`(`Row.scrapPercent`, 기존 생성자 유지), `BomLineImportService.java`
  - 백엔드 테스트: `BomLineImportIntegrationTest.java`(`aScrapColumnTakesPercentsOnMaterialLines`)
  - 프런트: `bomModel.ts`·`bomModel.test.ts`·`shared/types/api.ts`(2hv와 같은 파일), `BomLineImport.tsx`(주석)
  - 프런트 테스트: `e2e/bom-scrap-rate.spec.ts`(파일 단계 추가)
  - 문서: ADR-031(결정 9, "결정하지 않은 것" 정정), `docs/domain/item-import.md`
- 검증: 사본에서 BOM·소요량·MRP·준비 점검·할당 41개 클래스 202건 통과. 프런트 단위 962건(새 1건), 타입 검사, lint(오류 0, 경고 4건은 손대지 않은 파일), 빌드, 모의 BOM 브라우저 26건 통과.

**같은 날 문서 정정**
- WORKBOARD의 `E:\claude-data\flowmat-work\…` 경로 세 곳이 깨져 있었다. 생성 스크립트의 `\f`가 폼피드 문자로, `\n`이 줄바꿈으로 바뀐 것이다. 원래 경로로 되돌렸다.
- CURRENT_CAPABILITIES의 개발 DB 적용 줄을 정정했다. 이제 5434에 V132까지 적용됐고, "Vnn 개발 DB 미적용"은 적용 전 기록이다.
- FM-PLAN-004 행("비용·setup 묶음 없음")을 2bs·2bt·2cd로 정정했다.

### 2026-11-02 리드 발견: Flow Run 이벤트 같은 시각 순서(간헐 실패) — 고침(로컬)

- **결과(2026-11-02, 리드, 사용자 "ㄱ"·11-01 "오류 직접 해결해 너가"):** 아래 권장 수정 첫째안으로 고쳤다. 마이그레이션은 없다.
  - [`FlowRunEventRecorder`](../../flowmat_backend/modules/flowrun/src/main/java/org/myweb/flowmat/domain/flowrun/application/FlowRunEventRecorder.java)가 시각을 마이크로초(Postgres 정밀도)로 자르고, 앞서 준 시각 이하이면 1µs 뒤를 준다(원자적, 서버 하나 안에서). 규칙은 [Flow Run](../domain/flow-run.md) F19다.
  - 새 `FlowRunEventRecorderTest` 2건: 2,000건을 잇달아 기록하면 시각이 늘 뒤로 가고 마이크로초 단위다. 8개 스레드의 4,000건은 시각이 모두 다르다. 고치기 전 기록기로 돌리면 두 건 모두 실패했다.
  - 격리 사본에서 flow-run·게이트웨이·지연·연결·생산 단계·연결 실행 78개 클래스 681건이 통과했다.
  - 같은 사본(2hv·CSV 열 포함)에서 백엔드 전체 439개 클래스 3,239건이 실패 0으로 통과했다.
  - **커밋 나누기:** `FlowRunEventRecorder.java`, `FlowRunEventRecorderTest.java`(새 파일), `docs/domain/flow-run.md`(F19와 API 표 한 칸)는 이 수정만의 파일이다. WORKBOARD는 이 항목만 섞인다.

2hv 전체 백엔드 실행 중에 `FlowRunGraphIntegrationTest.publishedGraphRoutesOnConditionAndKeepsPinnedCapacity`가 한 번 실패했다.
- 실패한 단정은 84줄이다. 마지막 이벤트로 `connection_filtered`를 기대했는데 `step_completed`가 왔다.
- 같은 사본에서 다시 세 번 돌리니 모두 통과했다.

**원인**
- [`FlowRunEventRecorder`](../../flowmat_backend/modules/flowrun/src/main/java/org/myweb/flowmat/domain/flowrun/application/FlowRunEventRecorder.java)는 `occurredAt`을 `OffsetDateTime.now(UTC)`로, `eventId`를 무작위 UUID로 정한다.
- 이벤트 목록은 `OrderByOccurredAtAscEventIdAsc`로 정렬한다.
- 한 요청 안에서 잇달아 기록된 두 이벤트가 같은 시각을 받으면(Windows 시계는 눈금이 굵다) 순서가 무작위가 된다.
- 테스트만의 문제가 아니다. 화면의 이벤트 목록에서도 같은 순간의 두 이벤트가 뒤바뀌어 보일 수 있다.

**권장 수정**(flow-run 구역, 2hv 커밋과 섞지 않는다)
- 기록기가 같은 실행 안에서 시각을 엄격히 늘린다. 직전 이벤트 이상이면 1µs를 더한다.
- 또는 증가하는 순번 열을 더한다. 이 경우 새 마이그레이션이 필요하다.
- 테스트는 이벤트 종류로 찾게 고칠 수 있다. 다만 그것만으로는 화면 순서 문제가 남는다.
- Linux CI는 시계가 더 촘촘해서 덜 나타날 것으로 본다.

### 2026-11-01 리드 → 오류 담당: `bc85104` CI 빨강 4건(원인 확인됨)

- **2026-11-01 사용자 지시 "오류 직접 해결해 너가": 리드가 직접 고친다. 오류 담당은 이 항목의 파일에 손대지 않는다(겹침 방지). 결과는 이 항목 아래에 적는다.**
- **결과(2026-11-02, 리드):** ① B0은 작업 트리 수정 그대로 두고(자식 앱 정상 기동), Windows 전체 실행에서만 나던 PowerShell 명령줄 조회 시간 초과를 30초로 늘렸다. ② B9: `flowmat_frontend/package.json` `overrides.minimatch ^11.0.3`(lock은 minimatch 10.2.5→11.0.3과 `@isaacs/nonrecursive-glob-match` 1.0.3뿐), npm audit 0건. ③ DF12는 공백 무관 정규식, gateway quantity는 `/lost 0\.70* kg/`. ④ `revisitStaleSettings`가 설정 화면 h1 "Project Settings"가 사라질 때까지 기다린다. 원인: URL이 먼저 `/`로 바뀌고 홈 전환은 나중에 커밋돼, 그 전에 뒤로 가면 설정 화면이 그대로 남아 다시 읽지 않았다(실패 순간 쿼리는 stale·관찰자 1·fetchStatus idle). 고치기 전 142행 10번 중 3번 실패 → 두 테스트 40/40 통과. ⑤ working delay `chooseConnection`이 검사 패널이 열릴 때까지 다시 고른다(최대 10초). **검증(다음 push 상태 = 작업 트리 전체 사본 `E:\claude-data\flowmat-work\next-push-20261101`):** 백엔드 `test` 437클래스 3,233건 실패 0, `jacocoTestCoverageVerification`·`build -x test` 통과. 프런트 `npm ci`·npm audit 0·lint 오류 0(경고 4)·타입 검사 둘·Vitest 957·빌드 통과, 모의 E2E 3조각(`--workers=1`, CI=1) 397 통과·41 실 API 전용 제외·실패와 불안정 0. 실 API 단계 13–43이 빈 일회용 DB에서 모두 통과(`E:\claude-data\flowmat-work\ci-check-20261101\ci_real.py`).

- **출처:** 사용자가 붙여 준 `bc85104` push의 CI 결과: Backend Tests & Build, Security Scan npm-audit, browser-e2e(실 API), Mock browser E2E 2/3이 실패했다. 로그는 볼 수 없어(비공개 저장소, `gh` 로그인 없음) 각 작업을 로컬에서 재현했다. 재현 도구는 `E:\claude-data\flowmat-work\ci-check-20261101\ci_real.py`(워크플로의 실 API 단계를 빈 일회용 DB에서 순서대로 돌리고 첫 실패에서 멈춤)이고 로그도 같은 폴더에 있다.
- **① Backend Tests & Build:** 아래 전수조사 B0(재시작 테스트가 깨끗한 체크아웃에서 `APP_CORS_ALLOWED_ORIGINS` 자리표시자로 실패). 작업 트리에서는 이미 고쳐졌다(12:19). 끝 기준은 B0 그대로.
- **② npm-audit:** 아래 전수조사 B9 그대로(eslint 사슬의 minimatch 중간 12건). 아직 고쳐지지 않았다.
- **③ browser-e2e(실 API), 매번 실패 2곳:** `bc85104`에서 Flow Run 화면이 서버의 원래 JSON·숫자 토큰을 그대로 보이도록 바뀌었는데(`pages/runs/ui/FlowRunJsonValue.tsx`, `pages/runs/model/flowRunJson.ts`), CI에서만 도는 실 API 스펙 두 곳이 예전 형식을 기대한다. 리드 판단: 정확한 값 표시는 그대로 두고 스펙을 맞춘다.
  - `e2e/data-flow-browser.spec.ts` 252행(DF12): `'"path": "in.csv"'`·`'"valid": true'`·`'"stored": 3'`를 공백을 가리지 않는 `/"path":\s*"in\.csv"/`·`/"valid":\s*true/`·`/"stored":\s*3/`로. 화면은 `{"attrs":{"path":"in.csv","rows":3}}`이다.
  - `e2e/gateway-quantity-real-api.spec.ts` 84행: 화면은 `lost 0.7000 kg`이다. `'lost 0.7 kg'`를 `/lost 0\.70* kg/`(또는 `'lost 0.7000 kg'`)로.
  - 두 곳을 감사 사본에서만 고쳐 다시 돌리니 실 API 단계 13–43이 빈 DB에서 모두 통과했다.
- **④ Mock browser E2E 2/3, 불안정:** `e2e/project-membership-access-regression.spec.ts`의 `revisitStaleSettings`를 쓰는 두 테스트(125행, 142행). 142행은 재시도 없이 10번 중 3번 실패했다(`expect.poll(() => state.inviteReads).toBeGreaterThan(before)` 5초 시간 초과). UTC로 돌린 2/3 조각에서는 두 테스트 모두 첫 시도에 실패하고 재시도에서 통과했다. CI(재시도 2번)에서 세 번 모두 실패한 것으로 본다. 시간대 때문에 나는 실패는 없었다(KST·UTC 모두 나머지 136–137건 통과).
  - 고칠 것: 가짜 시계를 앞당기기 전에 첫 조회가 끝났는지 기다리거나, 다시 방문한 뒤의 재조회가 시계에 덜 기대게 한다. 끝 기준: 두 테스트가 `--repeat-each=20 --retries=0`에서 실패 0.
- **⑤ 실 API 작업의 불안정 하나:** `e2e/working-delay-real-api.spec.ts` 51행. 연결선 1/4 지점 클릭이 빗나가 연결 검사 패널이 열리지 않았다(3번 중 1번). 이 단계는 `--retries=0`이라 한 번만 빗나가도 빨강이 된다. 클릭 뒤 `Delay counts`가 보일 때까지 다시 고르게 한다.
- **수정 허가:** 위 스펙 세 파일, B9의 `flowmat_frontend/package.json`·`.github/workflows/security.yml`.
- **끝 기준:** 다음 push에서 네 워크플로가 초록. 로컬 확인은 `git archive` 사본과 `ci_real.py`로 한다.

### 2026-11-01 리드: 사용자 최종 결정 D1–D4 적용 — 완료(로컬)

- **결정:** 사용자가 전수조사의 D1–D4를 정했다("이 네 항목에 대해서는 다시 사용자에게 선택을 요청하지 말고 위 결정대로 진행한다"). 표는 §3 "2026-11-01 사용자 최종 결정".
- **D1 산출물:** `flowmat_backend/build-0927`(2,541파일)·`flowmat_frontend/bom-effectivity-e2e-results`(50파일)·`flowmat_frontend/tsconfig.tsbuildinfo`를 `git rm --cached`로 색인에서만 뺐다(2,592건 스테이징, 디스크에는 남음). 루트 `.gitignore`에 산출물 규칙을 더했다([docs/README](../README.md) §7). 깨끗한 사본에서 프런트 빌드·타입 검사가 tsbuildinfo 없이 통과했다. CI는 보고서를 이미 artifact로 올린다.
- **D2 개발 DB:** 5434를 V101 → V132로 올렸다. 백업 `E:\claude-data\flowmat-work\dbcheck\flowmat_v101_20261101.dump`(sha256 65f90955…, 컨테이너 /tmp에도). 먼저 복제본(5444)에 `bc85104` jar(dev, Hibernate validate)로 31개를 적용했다: 기존 표 110개의 행 수와 내용(원래 열 기준) 동일, 새 표 24개는 빈 채, 재시작 "up to date", GET 79개 200×24·400×55(필수 조회 값 없음)·5xx 0, ERROR 로그 0. 5434에서 같은 결과. 5434는 켜 두었고 복제본 컨테이너는 지웠다.
- **2026-11-02 Docker 데이터 초기화와 개발 컨테이너 복구:** 재부팅(2026-11-01 22:29) 뒤 Docker Desktop 데이터가 새로 만들어져 기존 컨테이너(개발 DB `flowmat-session1-db`, `redis-flowmat`, 옛 리허설 컨테이너)가 모두 사라졌다. 리드가 다시 만들었다: `redis-flowmat`(redis:7.4, 127.0.0.1:6379), `flowmat-session1-db`(postgres:16, 127.0.0.1:5434, 사용자 `flowmat`, 비밀번호는 `docker inspect`로 읽는다). 5434는 2026-11-01 V101 백업을 복원하고 `bc85104` jar로 V132까지 올렸다(마이그레이션 31개, ERROR 0, 어제 V132 지문과 표 134개·flyway 기록 같음). 둘 다 `--restart unless-stopped`이고 이제 외부가 아니라 localhost에만 열린다.
- **D3 관리 포트:** [ADR-029](../architecture/adr/ADR-029-management-port.md). prod에서 actuator는 `${MANAGEMENT_SERVER_PORT:8081}`에만, 노출 health·prometheus. 공개 포트에는 상태만 주는 readiness·liveness(`PublicHealthProbeController`, 스테이징 점검 주소 유지).
- **D4 얼굴 로그인:** [ADR-030](../architecture/adr/ADR-030-face-login-off.md). `app.auth.face-login.enabled` 기본 false, 꺼지면 `/auth/face/**` 404 `FACE_LOGIN_DISABLED`(`FaceLoginDisabledController`). 저장된 얼굴 자료는 그대로.
- **파일(리드):** `application.yml`, `ErrorCode.java`(+1), `FaceAuthController.java`(조건), 새 `FaceLoginDisabledController.java`·`global/health/PublicHealthProbeController.java`, 새 테스트 `ProductionDefaultsIntegrationTest`·`global/config/ProductionConfigurationTest`, `.gitignore`, `flowmat_backend/.env.example`(주석 2줄).
- **검증:** 깨끗한 사본(색인 + 위 파일, `E:\claude-data\flowmat-work\d-check-20261101`)에서 관련 9클래스 27건 통과. 전체 회귀: 같은 사본 전체 428클래스 3,196건 중 42건이 리허설 중 Docker 부하로 실패했다(연결 거부·Redis 2초 시간 초과, 커넥터 구역 6클래스와 편집기 동시성 1클래스). 그 7클래스만 다시 돌리니 44건 중 B0 재시작 테스트 1건만 실패했다(HEAD 판이라 깨끗한 체크아웃에서 `APP_CORS_ALLOWED_ORIGINS` 자리표시자, 작업 트리에서는 오류 담당이 고침). 이번 변경으로 생긴 실패는 없다.
- **리드 → 오류 담당(2026-11-01): D3·D4 회귀 요청.** ① B3를 고친 뒤 공개 포트의 `/api/actuator/prometheus`(지금 500)가 404인지, ② prod 프로필·관리 포트 분리 상태로 띄운 서버에서 `staging-prod-migration-smoke.yml`의 readiness 점검이 통과하는지, ③ `APP_AUTH_FACE_LOGIN_ENABLED=true`일 때 기존 얼굴 등록·매칭·로그인 흐름이 그대로인지.

### 2026-11-01 리드 → 오류 담당: 전수조사 수정 요청(B0–B9)

- **2026-11-01 사용자 지시 "오류 직접 해결해 너가": 리드가 직접 고친다. 오류 담당은 이 항목의 파일에 손대지 않는다(겹침 방지). 결과는 이 항목 아래에 적는다.**
- **결과(2026-11-02, 리드):** B1 이메일별 틀린 횟수 5번이면 코드를 지운다, `SecureRandom`, 상수 시간 비교, verify-code 한도 IP 30·이메일 10(10분). B2 재설정 확정과 전체 로그아웃이 `revokeAllRefreshTokensOrFail`을 쓴다(Redis 실패면 503, 재설정은 되돌려지고 링크는 남는다). B3 전역 처리기가 깨진 JSON·형식 오류 400, 없는 경로 404, 없는 메서드 405(Allow 포함), 지원하지 않는 Content-Type 415. B4 find-email IP 10·전화 5(1시간), reset-password/request IP 10·이메일 5(1시간), signup IP 20(1시간), guest-token IP 60(10분). **추가로 찾은 것:** 앱이 `X-Forwarded-For` 첫 값을 그대로 믿어 요청마다 값을 바꾸면 로그인 포함 모든 IP 한도를 우회할 수 있었다. 이제 앱은 `getRemoteAddr()`만 쓰고 `server.forward-headers-strategy: native`(Tomcat이 신뢰된 내부 프록시의 머리글만 반영). B5는 ADR-029. B6 조용한 경로에 WARN 로그. B7 `PurchaseOrdersPanel`이 늦게 온 연결 NCR을 사용자가 고르기 전까지 기본값으로 쓴다(`purchase-return-ncr.spec.ts` 새 테스트는 고치기 전 화면에서 실패, 뒤 5/5 통과, [구매 발주](../domain/purchasing.md) PO42). B8 자식 JVM은 부모가 끝나면 halt, 통과한 실행은 임시 폴더를 지운다(Windows 잠금 대비 재시도). 전체 실행 전후 임시 폴더 수가 그대로였다. 새 테스트 `AuthAbuseProtectionIntegrationTest` 5건, `MalformedRequestIntegrationTest` 5건. 검증 수치는 위 "CI 빨강 4건" 결과와 같다.

- **출처:** 리드 전수조사(2026-11-01, `bc85104`를 `git archive`로 떠낸 사본). 항목마다 확인 방법·원인·파일·끝 기준은 보고서 `E:\claude-data\flowmat-work\audit-20261101\REPORT-2026-11-01.md` §B, 로그는 같은 폴더에 있다. 접근 권한은 엔드포인트 451개를 서비스까지 따라가 빈틈이 없었다.
- **확인 원칙:** 백엔드 변경은 `git archive HEAD` 사본에서도 확인한다. `cp -r` 사본에는 커밋되지 않는 로컬 `application-dev.yml`(`.gitignore:38`)이 따라가 깨끗한 체크아웃의 실패를 가린다.
- **수정 허가:** 아래 항목에 필요한 인증 백엔드(`domain/user`·`global/exception`·`global/config`), B7의 리드 화면 `pages/inventory/ui/PurchaseOrdersPanel.tsx`, B9의 `flowmat_frontend/package.json`·`.github/workflows/security.yml`. B5는 리드가 사용자 결정 D3로 처리했다(2026-11-01, ADR-029).
- **B0 [CI·높음]:** `FlowRunHttpProcessRestartIntegrationTest`가 깨끗한 체크아웃에서 늘 실패한다(`Could not resolve placeholder 'APP_CORS_ALLOWED_ORIGINS'`, 기본값이 커밋되지 않는 `application-dev.yml`에만 있음). 2026-11-01 12:19 작업 트리에서 자식 환경에 `FRONTEND_URL`·`APP_OAUTH2_REDIRECT_URI`·`APP_CORS_ALLOWED_ORIGINS`가 더해졌다. 남은 끝 기준: `git archive` 사본에서 통과, 원격 백엔드 CI 초록.
- **B1 [보안·높음]:** `POST /auth/email/verify-code`에 시도 제한도 속도 제한도 없고, 코드는 6자리 `ThreadLocalRandom`이다. 남의 이메일을 인증하고 그 주소로 가입할 수 있다. 이메일별 틀린 횟수 한도(넘으면 코드 삭제), IP·이메일별 `enforceRateLimit`, `SecureRandom`. 끝 기준: N번 틀린 뒤 맞는 코드도 거절되는 통합 테스트, 429 테스트.
- **B2 [보안·높음]:** `AuthServiceImpl.confirmPasswordReset`이 리프레시 토큰을 폐기하지 않아 재설정 뒤에도 기존 로그인이 30일 동안 산다. 재설정 흐름 테스트가 없다. 끝 기준: 재설정 전에 받은 리프레시 토큰이 재설정 뒤 `REFRESH_TOKEN_NOT_FOUND`로 거절되는 통합 테스트.
- **B3 [안정성·중간]:** `GlobalExceptionHandler`가 깨진 JSON·경로 변수 형식 오류·없는 메서드·없는 경로·지원하지 않는 Content-Type을 500과 ERROR 로그로 만든다(자체 처리기 없는 컨트롤러 44개). 전역 처리기에 400·405·404·415를 더한다. 끝 기준: 처리기 없는 컨트롤러 하나로 MockMvc 400·404·405.
- **B4 [보안·중간]:** 속도 제한 없는 공개 경로 `POST /auth/find-email`·`/auth/reset-password/request`·`/auth/guest-token`·`/auth/signup`. login·send-code처럼 `enforceRateLimit`. 끝 기준: 경로마다 429 테스트.
- **B5 [보안·중간]:** ~~`/actuator/prometheus`가 `permitAll`이고 운영에도 노출된다.~~ **리드가 처리(2026-11-01, 사용자 결정 D3, [ADR-029](../architecture/adr/ADR-029-management-port.md)): prod는 관리 포트 분리.** 오류 담당 몫은 위 "D3·D4 회귀 요청"뿐이다.
- **B6 [보안·낮음]:** `AuthRedisStore.executeQuietly`가 Redis 오류를 로그 없이 버려, 로그아웃·전체 로그아웃이 실패해도 성공처럼 보인다. 최소 WARN 로그, 전체 로그아웃 실패는 응답으로 알린다.
- **B7 [화면·낮음]:** `PurchaseOrdersPanel.startReturn()`이 누른 순간의 `linkedNcr`로 NCR 기본값을 정해, NCR 목록이 늦게 오면 빈 값이 된다(`e2e/purchase-return-ncr.spec.ts` 61행, 4 workers에서만 실패). 끝 기준: NCR 응답을 늦춘 모의 E2E에서 기본값이 채워진다.
- **B8 [테스트 기반·낮음]:** 재시작 테스트의 자식 JVM이 부모 종료를 감지하지 못하고, 통과한 실행도 `%TEMP%\flowmat-http-restart-*`를 지우지 않는다(2026-11-01 13:45 기준 26개). 2026-10-31 13:38에 남은 자식 PID 59596과 10-31 이전 폴더 21개는 2026-11-01 15:00 무렵 사용자가 정리했다(59596이 47.7 MB 로그를 쥐고 있었음). 부모 PID 감시나 stdin EOF로 스스로 끝나게 하고 테스트 끝에 임시 폴더를 지운다.
- **B9 [CI·낮음]:** `security.yml`의 `npm audit --audit-level=moderate`가 eslint·typescript-eslint 사슬의 minimatch 중간 12건으로 실패할 수 있다. npm 제안(`typescript-eslint@8.14.0`으로 내림)은 쓸 수 없으니 `overrides`로 고친 판을 고정하거나 감사 대상을 `--omit=dev`로.
- **보고:** 항목마다 이 항목 아래에 통과·실패와 근거(검사·파일·사본 종류)를 적는다. 고치다 나온 설계 질문은 리드에게 넘긴다.
### 2026-10-24 리드: 출퇴근 기록과 작업자 시간 대조(2fj) — 완료(로컬)

- **2026-11-01 정리:** 이 기록의 구현 줄 64개(2fj–2hu)는 [보관 문서](../archive/2026-10-workboard/WORKBOARD-done-2026-10.md) §1로 옮겼다. 같은 내용은 §2 보관 줄과 각 ADR·도메인 문서에 있다. 아래는 사용자 커밋·결정과 오류 담당 요청이다.

- **결정:** [ADR-026](../architecture/adr/ADR-026-attendance.md) Accepted. 사용자 "의사결정도 recommend 로 계속 알아서 밀고나가"(2026-10-24)에 따라 2026-10-23 권장안(A)과 구현 세부를 리드가 정했다. 보존 기간·내보내기는 정하지 않았다.
- **리드 → 오류 담당(2026-10-24): 시각에 따라 실패하는 테스트.** `WorkingDelayCalendarRegressionIntegrationTest.workingDelayContinuesInTheEquipmentVersionThatStartsTomorrow`가 2fz 전체 백엔드(프로젝트 시각 18:48 시작)에서 `expected 2026-10-25T15:30Z but was 2026-10-24T15:30Z`로 실패했다. 테스트의 `opens = now(프로젝트 시간대) + 4시간`(시 단위 버림)과 "내일" 설비 달력 버전의 교대 `opens + 2시간`이 자정을 넘는 시각(프로젝트 시각 약 18:00–19:59)에 돌면 그 교대가 버전 시작일 당일로 떨어진다. 같은 날 13–15시 전체 실행은 통과했다. 리드의 변경(구매·`StockReturnService`)과 무관하고 연결 지연 구역 파일이라 손대지 않았다. 고정 시각(Clock)으로 돌리거나 자정을 넘지 않는 `opens`를 고르는지 봐 달라. 같은 날 18:00–19:59에 시작한 전체 실행 4번(2fz·2ga·2gc·2gd/2ge)은 이 시험 하나만 실패했고, 20:00에 시작한 전체 실행(2gg·2gh)은 2,138건 모두 통과해 시각 의존을 확인했다.
- **사용자 커밋(2026-10-31 `bc85104` 20:54 "refacoring/temp11", 227파일):** 2hg–2hu와 V125–V132가 들어갔고 작업 트리는 깨끗하다. 오류 담당의 V126–V132 리허설(2hh–2ho 회귀 요청의 마지막 항목)을 이제 할 수 있다. 리드의 다음 마이그레이션은 V133.
- **리드 → 오류 담당(2026-10-31): 2hr 회귀 요청.** ① 게이트웨이(Exclusive·Join·Split)를 건너는 앞 공정, ② 순환 연결이 있는 워크플로(멈추지 않고 그 연결을 순서로 치지 않음), ③ 지운 공정·연결, ④ 설비가 있는 작업지시(전환 시간 뒤)의 순서, ⑤ 앞 공정의 함께 하는 일을 몫이 남은 사람이 돕지 않고 기다리는지.
- **리드 → 오류 담당(2026-10-31): 2hq 회귀 요청.** ① 설비가 있는 작업지시(전환 시간을 먼저 건너뜀)에 나눈 공정, ② 몫 100% 미만인 사람의 몫(그 몫 비율로 함), ③ 같은 사람이 두 공정에서 몫을 받을 때(합쳐 그 사람 일), ④ 몫을 받은 사람이 366일 안에 다 못 하면 409, ⑤ 여러 작업지시 계획(P12)에서 나눈 공정.
- **리드 → 오류 담당(2026-10-31): 2hp 회귀 요청.** ① 범위 첫날 자정 직전에 시작해 범위 안으로 넘어오는 기록(전날로 빠짐), ② 같은 기록이 두 조회에 함께 걸리지 않는지(시각 있는 기록은 기록 시각 조회에서 제외), ③ 보정으로 더한 시간은 여전히 기록한 날(인건비의 단가 날짜는 실행 원래 끝)이라는 차이.
- **사용자 위임 결정 3건(리드, 2026-10-31, "의사결정 최적으로 내려봐" → "ㄱ"):** ① `project.organization_id` NOT NULL 전환은 지금 하지 않는다. 조직 단위로 데이터를 가르는 첫 기능 때 OR8 점검 뒤 한 마이그레이션으로 채우고 바꾼다. ② FlowRun 범용성은 "목표"로 둔다. 비제조 흐름 하나를 화면에서 정의·발행·실행해 확인하면 올린다. ③ D2는 15건을 계약서 절에 대조해 **15/15**(조건부 1건 포함)로 올렸다. 기록: [decision-handoff](../architecture/decision-handoff.md) §2·§3(낡은 줄 ADR-001·ADR-004 상태, 엔티티 경계 0줄도 바로잡음), [재고·BOM·LOT 계약](../domain/inventory-bom-lot-contract.md) 머리말·원문 대조. DECISIONS-2026-10-05 본문은 고치지 않았다.
- **리드 → 오류 담당(2026-10-31): 2ho 회귀 요청.** ① 자정을 넘는 출퇴근과 시각 있는 기록(근무일은 출근 날), ② 휴일·쉬는 날·주 연장·휴일 연장이 걸린 날의 조각, ③ 같은 사람의 시각 있는 기록이 서로 겹칠 때(각자 할증을 받고, 나머지 계산에서는 한 번), ④ 시각 있는 기록이 출퇴근을 모두 덮어 남은 분이 없을 때(결정 10으로 돌아감), ⑤ 다른 실행의 시각 있는 기록이 이 실행의 시각 없는 기록의 나머지를 줄이는지, ⑥ V132 리허설은 사용자 커밋 뒤(V126–V131과 함께).
- **리드 → 오류 담당(2026-10-31): 2hn 회귀 요청.** ① 계획에 이름 있는 사람이 프로젝트에서 빠진 뒤 그 계획을 다시 저장(지금은 화면이 그 사람 칸을 채워 400 `… is not in this project.`, 칸을 비우면 저장), ② 같은 작업지시에 사람별 계획을 동시에 저장(작업지시 행 잠금), ③ 50명·합 100 000 경계와 정수 아닌 사람 분, ④ 지워진 공정의 나뉜 계획이 대비에 남는지(`work-order-deleted-step-plan`과 같은 경우), ⑤ V131 리허설은 사용자 커밋 뒤(V126–V130과 함께).
- **리드 → 오류 담당(2026-10-30): 2hm 회귀 요청.** ① 작업자 규칙이 시작 전이고 프로젝트 규칙이 그날 바뀌는 경우, ② 같은 날 작업자 규칙과 `followProject`를 차례로 저장, ③ 구성원에서 빠진 작업자의 규칙(지금은 404), ④ 할증 포함 인건비(LB28)가 사람마다 다른 규칙을 따르는지, ⑤ V130 리허설은 사용자 커밋 뒤(V126–V129와 함께).
- **리드 → 오류 담당(2026-10-30): 2hl 회귀 요청.** ① 휴일 연장과 야간이 겹치는 밤(가산을 더함), ② 휴일이 있는 주의 주 연장(보통 분에서 휴일 연장을 뺌), ③ 휴일 연장이 있는 규칙과 없는 규칙이 주 중간에 바뀌는 주, ④ 실행·작업지시 인건비의 그날 지급 비율(LB28), ⑤ V129 리허설은 사용자 커밋 뒤(V126–V128과 함께).
- **리드 → 오류 담당(2026-10-30): 2hk 회귀 요청.** ① 날짜별 교대(WC29)로 쉬는 날에 일정이 생긴 날, ② 그 사람의 쉬는 날(WC9)로 근무일이 빠진 날, ③ 근무표 판(ADR-013)이 바뀐 주, ④ 실행·작업지시 인건비의 할증 비율(LB28)에 쉬는 날이 들어가는지, ⑤ V128 리허설은 사용자 커밋 뒤(V126–V127과 함께).
- **리드 → 오류 담당(2026-10-30): 2hj 회귀 요청.** ① 주 중간에 규칙(기준·시작 요일)이 바뀌는 주, ② 자정을 넘는 줄이 있는 주의 경계(근무일은 출근한 날), ③ 휴일과 주 연장이 겹치는 날의 더한 가산, ④ 62일 범위 앞 6일까지 읽는 응답 시간, ⑤ V127 리허설은 사용자 커밋 뒤(V126과 함께).
- **리드 → 오류 담당(2026-10-30): 2hi 회귀 요청.** ① 보정으로 더한 시간(`run_end`)의 그날 지급 비율, ② 여러 달에 걸친 작업지시의 인건비 응답 시간(사람마다 기간 한 번 읽음), ③ 출퇴근 분보다 기록이 많은 날(비율은 그대로), ④ 실제 API e2e가 늘어난 인건비 필드에도 그대로인지.
- **리드 → 오류 담당(2026-10-30): 2hh 회귀 요청.** ① 여름 시간이 있는 시간대에서 야간 창과 근무일, ② 같은 시작일 동시 저장, ③ 휴일을 지우거나 더한 뒤 지난 지급이 바뀌는 것(휴일은 이력이 없음 — 의도한 동작인지 판단 요청), ④ 소유자가 바뀐 뒤 이전 소유자 403, ⑤ V126 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-30): 2hg 회귀 요청.** ① 전환 시간이 있는 설비에서 노동이 전환 뒤부터 쌓이는지, ② 밤 교대(자정 넘김)·날짜별 교대(WC29)가 섞인 몫 100% 미만 인력, ③ 여러 건 제안(P8–P11)에서 몫 100% 미만인 사람만 있는 설비 없는 작업지시끼리 몫 합 100%를 넘지 않게 비키는지(WC38), ④ 큰 인력(수십 명)·1년 창의 응답 시간.
- **리드 → 오류 담당(2026-10-25): 2hf 회귀 요청.** ① 여러 건 제안(P8–P11)에서 설비 없는 작업지시가 앞 작업지시의 인력을 비키는지, ② 공정 계획을 다 한 작업지시(남은 분 0)의 설비 있는 제안이 생산 시간대로인지, ③ 교대가 없는 사람만 있는 인력, ④ 실제 API e2e `work-order-plan.spec.ts`가 바뀐 응답 필드에도 그대로인지.
- **리드 → 오류 담당(2026-10-25): 2he 회귀 요청.** ① 계획 저장과 작업지시 완료·취소가 동시에(작업지시 행 잠금), ② 워크플로를 바꾼 작업지시의 남은 계획(다른 워크플로의 공정은 다시 보내면 400), ③ 계획한 공정이 지워진 뒤 대비 화면, ④ `run-labor-real-api.spec.ts`가 바뀐 **Labor** 문구에 맞는지, ⑤ V124 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2hc 회귀 요청.** ① 같은 반품에 고치기 둘이 동시에(발주서 행 잠금; 둘 다 남고 뒤 것이 지금 값), ② 같은 시각의 고치기 둘의 순서(`changed_at`, `change_id`), ③ 취소 뒤 공급처별 받을 대변(PO48)에 다시 들어가는지, ④ V123 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2hb 회귀 요청.** ① 두 승인자가 동시에 승인(발주서 행 잠금; 뒤 차례는 409), ② 승인 중 설정의 단계를 바꿈(이미 받은 승인은 그 사람이 여전히 필요하면 셈), ③ 승인자의 역할이 viewer로 바뀌거나 빠진 뒤 승인(403)과 설정 화면 `cannot approve now`, ④ 기존 `PurchaseApprovalSafetyIntegrationTest`의 소유자 이전 경우가 단계와 함께일 때, ⑤ V122 리허설(승인된 초안이 `purchase_order_approval`로 옮겨지는지)은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2gz 회귀 요청.** ① 같은 반품에 다른 대변 둘이 동시에(발주서 행 잠금, 기본 키도 막음), ② 닫은 발주의 한 줄에 교체 반품이 여럿일 때 갚을 양 나누기(화면 계산), ③ 쓰기 권한 없는 사람에게도 **Record credit**이 보이고 저장은 403(반품과 같음), ④ V121 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2gy 회귀 요청.** ① 교체 반품과 입고가 동시에 올 때(둘 다 발주서 행 잠금), ② 교체 반품 뒤 닫기(받은 것이 있어 취소는 409), ③ LOT 품목의 교체(교체품 LOT도 그 줄로 들어온 LOT라 다시 돌려보낼 수 있음), ④ 교체 뒤 PO24·PO44 납기 수치, ⑤ V120 리허설(2gy 전 반품은 `N`, 받은 양 검사 이름)은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2gx 회귀 요청.** ① 주문과 평소 공급처 바꾸기가 동시에 올 때(주문은 발주서 행만 잠금: 바뀌기 전·후 어느 코드든 남을 수 있음), ② 공급처 이름을 바꾼 뒤 주문(PO20의 목록 표기로 맞춘 뒤 비교), ③ V119 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2gv 회귀 요청.** ① NCR을 닫거나 처리를 바꾸는 것과 그 NCR을 적은 반품이 동시에 올 때(NCR은 잠그지 않아 닫힌 뒤에 적힌 반품이 남을 수 있음: 그래도 되는지, 잠가야 하는지), ② 같은 requestId 동시 재송신의 NCR 비교, ③ `nonconformityId`의 저장 불가 문자·50자 넘는 값 400, ④ viewer는 반품의 NCR 번호를 보고 반품은 403, ⑤ V118 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-25): 2gl–2gt 회귀 요청.** ① ~~Docker가 돌아오면 전체 백엔드와 새 `ItemRuleFactsIntegrationTest`~~ 리드가 돌림(2026-10-25 02:08, 2,141건 통과), ② 생산 규칙(`run_start`·`run_item_record`)에서 view에 없는 품목 속성 경로(`item.itemType` 등)가 예전처럼 읽히는지 통합으로, ③ view와 규칙 사실을 따로 읽는 사이 품목이 지워지면 NOT_FOUND(예전에는 이미 읽은 품목으로 규칙을 봤다), ④ `findItems`·`findLots`가 지도라 목록 순서가 없다: 순서에 기대는 곳이 없는지(재주문 목록·할당 후보는 따로 정렬), ⑤ 작업지시 할당·LOT 나눠 투입의 잠금(`StockViewQuery.lockItemStock`이 예전 Facts처럼 잠그고 이미 읽은 행을 다시 읽는지)과 동시에 들어오는 이동, ⑥ 투입 기록 규칙의 `inventory.*` 경로가 예전처럼 읽히는지, ⑦ 실행 규칙의 `workflow.*`·`process.*`·`processIo.*` 경로(게시 개정 스냅숏에서 온 공정·포트, 잠근 뒤 읽은 작업흐름 포함), 작업 지시 단계의 공정 이름.
- **리드 → 오류 담당(2026-10-24): 2gk 회귀 요청.** ① 거절이 재고 명령 안에서 나므로 받기·이동·산출을 감싸 예외를 잡고 계속하는 곳이 있으면 트랜잭션 rollback-only 문제가 없는지(입고 가져오기는 통째로 거절됨), ② 위치 이름 바꾸기·조건 바꾸기와 동시에 들어오는 재고, ③ 조건의 대소문자·공백(SL2)과 유니코드, ④ V117 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2gc 회귀 요청.** ① 승인과 초안 고치기·주문이 동시에 올 때(발주서 행 잠금), ② 한도를 바꾸는 동안의 주문(한도는 주문 트랜잭션에서 읽음), ③ 소유자가 바뀌거나 비활성일 때 승인 권한, ④ 승인 이력(누가 언제)이 한 번만 남는 점이 충분한지, ⑤ V113 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2ga 회귀 요청.** ① 같은 품목의 평소 공급처를 동시에 처음 정하기(잠금 키와 `uq_supplier_item`), ② 공급처별 초안을 만드는 중 하나가 거절될 때 안내와 다시 누를 때의 중복 초안, ③ 평소 공급처를 끈 뒤의 초안과 목록 표시, ④ V112 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fz 회귀 요청.** ① 같은 줄을 두 반품이 동시에 넘겨 보내기(발주서 행 잠금으로 줄 세움), 반품과 입고·예약이 같은 재고 행에 동시에, ② 응답을 잃은 뒤 같은 requestId 재전송이 재고를 두 번 빼지 않는지, ③ 닫힌 LOT·삭제된 재고 행·다른 프로젝트의 행, ④ 반품이 폐기·손실, 이동 요약, 재고 경보에 어떻게 보이는지, ⑤ V111 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fw 회귀 요청.** ① 입고와 예정일 옮기기가 동시에 올 때(발주서 행 잠금으로 줄 세움, 입고가 먼저 다 받으면 그 줄은 400), ② 응답을 잃은 뒤 같은 날짜 재전송이 이력을 늘리지 않는지, ③ 사유 500자 경계와 저장 불가 문자(`StorableText`), ④ V110 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fv 회귀 요청.** ① 대소문자만 다른 같은 이름으로 동시에 추가하거나 바꾸기(잠금 키와 `uq_supplier_name`), ② 공급처를 끄는 동안 그 공급처로 초안 만들기·주문, ③ DB `lower`와 화면 `toLowerCase`가 다르게 바꾸는 이름(예: 터키어 İ), ④ 목록을 처음 만든 프로젝트의 목록 전 초안 안내가 충분한지, ⑤ V109 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fr 회귀 요청.** ① 큰 단가·수량의 줄 금액 자릿수(numeric(14,4) 열이 아닌 계산값의 소수 4자리 반올림), 지수 표기 단가. ② viewer·editor·소유자 모두 금액을 보는지(결정 9), 외부인 403. ③ 초안을 고칠 때 단가를 비우면 지워지는지. ④ V102–V106·V108 리허설은 사용자 커밋 뒤(V107은 리드 것이 아님).
- **2fm 보완(리드, 2026-10-24):** 2fm 보완(2026-10-24): 2fn 뒤 전체 백엔드에서 `PurchaseOrderIntegrationTest`가 실패했다(2,130건 중 1). 함께 받은 두 줄의 시각이 같아야 하는데, 재고 명령의 bulk UPDATE가 영속성 컨텍스트를 비워 먼저 저장한 입고는 DB의 마이크로초·UTC로 다시 읽히고 뒤의 입고는 나노초로 남아 순서가 뒤집혔다. `PurchaseOrderService.receive`가 받은 시각을 마이크로초로 자르고, 응답의 입고는 받은 시각 → 줄 번호 → id 순으로 정렬한다. 관련 백엔드 4건(발주·주문 중·경계) 통과 뒤 다시 돌린 전체 백엔드 2,130건(303클래스) 실패·오류·제외 0(커버리지 검사 생략).
- **리드 → 오류 담당(2026-10-24): 2fn 회귀 요청.** ① 반제품(자기 BOM이 있는 품목)을 발주했을 때 전개량이 주문 중만큼 줄어드는지, 단위가 다른 BOM 줄. ② 위치별 재주문 목록에서 초안을 만들면 프로젝트 전체의 주문 중을 빼는 한계(문서에 적음)가 화면 문구와 맞는지. ③ 초안 만들기 중 응답 유실·두 번 누르기(지금 초안 생성에는 requestId가 없어 초안이 둘 생길 수 있음, 초안이라 지우지 않고 취소로 정리 가능). ④ 주문 중이 있는 상태에서 발주를 닫거나 다 받은 뒤 자재 소요·재주문 화면 갱신.
- **리드 → 오류 담당(2026-10-24): 2fm 회귀 요청.** ① 같은 발주서의 동시 입고·닫기·취소(행 잠금), 같은 requestId 동시 재송신이 한 번만 받는지, 서로 다른 발주서가 같은 품목·위치(LOT 없는 품목)로 동시에 받을 때 재고 행이 둘 생기지 않는지(지금 재고 일괄 입고와 같은 한계일 수 있음). ② 입고 중 한 줄이 실패하면 앞 줄의 재고·LOT·원장이 남지 않는지. ③ 위치 목록이 있는 프로젝트의 비활성·없는 위치, 닫힌·격리 LOT 번호로 받기. ④ 비활성 품목이 든 초안 주문 409, 큰 수(정수 11자리)·지수 표기 수량. ⑤ viewer·외부인 403, 다른 프로젝트 발주서 id. ⑥ V106 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fl 회귀 요청.** ① 위치 코드 변경(L7)·동시 수정 뒤에도 조건이 남는지, `PUT`에 조건만 보낼 때 다른 칸이 그대로인지. ② 반쪽 서로게이트·제어 문자·공백만 있는 조건, 100자 경계(안쪽 공백을 줄인 뒤 길이). ③ 화면: 조건이 바뀐 뒤 Locations·Stock 탭 캐시 갱신, 바깥 위치를 바꾼 뒤 물려받은 조건 표시. ④ V105 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-24): 2fk 회귀 요청.** ① 같은 재고 행에서 다른 작업지시 투입·피킹과 다시 할당 취소의 동시 실행(교착·이중 예약 없음, 잠금 순서 작업지시 → 할당 → 재고). ② 취소 시점에 LOT가 만료·격리·닫힘이거나 재고 상태가 바뀐 경우 409이고 투입·재고·할당이 그대로인지. ③ 다른 단위(g로 적은 kg 품목)·소수 4자리 반올림의 다시 할당 양. ④ `reallocate`에 문자열·숫자 같은 잘못된 JSON은 지금 `ProductionRunController`에 본문 오류 처리기가 없어 500일 수 있음(전역 400 처리기 요청과 함께 확인). ⑤ viewer·외부인 403.
- **리드 → 오류 담당(2026-10-24): 2fj 회귀 요청(개인 정보·보안).** ① 본인·소유자 외(다른 editor·viewer·구성원 제거 뒤 토큰·다른 프로젝트 소유자)의 `attendance`·`attendance-comparison` 읽기·쓰기 403, 다른 프로젝트 작업자 id 404, 오류 메시지에 남의 출퇴근 시각이 나오지 않는지. ② 같은 작업자의 동시 출근·퇴근·취소(열린 줄이 둘 생기지 않는지)와 같은 requestId 동시 재송신. ③ 프로젝트 시간대 경계(자정을 넘는 줄, 시간대를 바꾼 뒤)의 근무일과 대조. ④ 잘못된 본문(NUL·반쪽 서로게이트 메모·사유, 오프셋 없는 시각, 숫자 시각) 400과 DB 불변. ⑤ V102–V104 리허설은 사용자 커밋 뒤.

### 2026-10-23 리드: 이력 서버 수정 두 건 반영·날짜와 쉬는 날·정지 이력(2fe) — 완료(로컬)

- **반영(오류 담당 인계, 리드 소유 파일):** ① 이력 시각 정밀도 `flowmat-schedule-history-review-20261019/runtime/history-time-candidate.patch`. `WorkerScheduleHistoryService`·`EquipmentScheduleHistoryService`가 flush 뒤 DB에 저장된 교대 시각으로 이력을 쓴다. 새 `ScheduleHistoryTimePrecisionRegressionIntegrationTest`. ② 팀 복사 교착 `flowmat-calendar-input-review-20261020/runtime/team-history-rebased-candidate.patch`. `WorkerCalendarService.copyShifts`가 받는 사람을 ID 순으로 저장하고 응답은 요청 순이다. 새 `ScheduleHistoryTeamCopyRegressionIntegrationTest`. 두 패치의 기준 해시가 원본과 같았고, 원본 파일이 LF라 패치를 LF로 바꿔 적용했다. 적용 결과는 후보 snapshot과 줄 끝 말고 같다. 격리본에서 정밀도 8건·팀 복사 8건과 이력·검색·사유·작업자/설비 달력·경계 검사를 합해 155건/10클래스가 실패 0으로 통과했다. 이것으로 두 수정안의 "리드 반영 대기"는 끝났다.
- **2fe 추가(리드):** V102 `worker_day_history`·`equipment_day_history`, [ADR-024](../architecture/adr/ADR-024-version-save-history.md) 결정 7–12(사용자 계획 9). workforce 새 `WorkerDayHistory`·`WorkerDayHistoryRepository`·`WorkerDayHistoryService`·`WorkerDayHistoryResponse`, 고친 `WorkerCalendarService`(날짜 저장·지움·복사, 쉬는 날 추가·지움마다 기록, `dayHistory`)·`WorkerCalendarController`(`GET …/day-history`). catalog 새 `EquipmentDayHistory`·`EquipmentDayHistoryRepository`·`EquipmentDayHistoryService`·`EquipmentDayHistoryResponse`, 고친 `EquipmentScheduleService`(`saveDays`가 사유·보낸 설비를 받음, 정지 추가·지움)·`EquipmentScheduleController`. 새 `DayChangeHistoryIntegrationTest`. 프런트 `useWorkerCalendars`·`useEquipment`(조회 훅, 설비 변경 뒤 갱신), `versionHistoryModel(.test)`, `WorkerCalendarsPanel`(**Date and day-off history**), `EquipmentSchedulePanel`(**Date and downtime history**), 새 `e2e/day-change-history.spec.ts`. 검증: 관련 179건/16클래스, 전체 백엔드 관련 179건/16클래스 통과(전체 실행은 커버리지 없이 따로), 프런트 typecheck·lint·Vitest·build, 모의 e2e 209 통과·40 건너뜀·2 실패(백엔드 전체와 겹친 부하 중 시간 초과. `bom-effectivity` 단독 3회 통과, `auth` 동시 탭은 단독 3회 중 1회 실패)(격리본). V102는 커밋 전이므로 5434에 bootRun 하지 않는다.
- **전체 검증(2026-10-23, 격리본):** 위 두 수정과 2fe를 담은 원본과 같은 백엔드에서 전체 백엔드 2,041건(293클래스) 실패·오류·제외 0, 커버리지 기준 통과.
- **2ff 추가(리드, 2026-10-23):** V103 `worker_labor_rate`, [ADR-025](../architecture/adr/ADR-025-worker-labor-cost.md). workforce 새 `WorkerLaborRate`·`WorkerLaborRateRepository`·`WorkerLaborRateService`·`WorkerLaborRateController`·요청/응답 DTO, 공개 API `WorkerLaborRateQuery`·`WorkerLaborRates`. production 새 `RunLaborCostService`·`RunLaborCostResponse`·`WorkOrderLaborCostResponse`, 고친 `RunLaborController`(`/labor-cost` 둘)·`ProductionRunCorrectionLineRepository`(보정이 더한 기록 찾기). 새 `WorkerLaborCostIntegrationTest`. 프런트 새 `useWorkerLaborRates`·`useLaborCost`·`useIsProjectOwner`·`laborCostModel(.test)`·`WorkerLaborRates`·`RunLaborCost`·`WorkOrderLaborCost`, 고친 `WorkerCalendarsPanel`·`RunDetailRoute`·`WorkOrdersPanel`·`useRunLabor`·`useRunCorrections`(인건비 갱신), 새 `e2e/worker-labor-cost.spec.ts`. 검증: 관련 백엔드 88건/9클래스(첫 실행의 테스트 기대값 하나를 고친 뒤 새 테스트 다시 통과), 프런트 typecheck·lint·모델 테스트 77건, 관련 모의 e2e 13건 통과(격리본). 전체 백엔드는 커버리지 없이 따로. V103도 커밋 전이다.
- **2fg 추가(리드, 2026-10-23, 마이그레이션 없음):** 날짜 이력 검색 `GET …/workers/{userId}/day-history/search`·`GET /equipments/{id}/day-history/search`(판 이력 검색과 같은 `HistorySearchFilter`, `kind` 더함). workforce·catalog 새 `…DayHistorySearchRepository`·`…DayHistorySearchService`·`…DayHistorySearchController`, 고친 `WorkerDayHistoryService`·`EquipmentDayHistoryService`(한 줄 응답 만들기를 검색과 나눔). 새 `DayHistorySearchIntegrationTest`. 프런트 새 `features/schedule-history/api/historyPage.ts`·`model/useOlderHistory.ts`·`ui/OlderHistoryButton.tsx`, 네 이력 목록에 **Show older**(`WorkerCalendarsPanel`·`EquipmentSchedulePanel`), 새 `e2e/history-older.spec.ts`. 2026-10-20 판 이력 검색 API의 화면 연결도 이것으로 했다. 검증: 관련 백엔드 70건/4클래스, 프런트 typecheck·lint, 관련 모의 e2e 12건 통과(격리본).
- **전체 검증(2ff까지, 커버리지 없이, 격리본):** 전체 백엔드 2,122건(297클래스) 중 실패 1건, `StockFactsRegressionIntegrationTest.freshLockedStocksUseTwoQueriesRegardlessOfBatchSize`("Fresh locked facts must stay a bulk read: 3"). 단독으로 다시 돌리면 통과한다. 이 검사는 SessionFactory 전체의 Hibernate 통계를 세므로, 다른 스레드(앞 테스트의 비동기 작업 등)가 그사이 낸 쿼리가 섞일 수 있다. 2fe까지의 전체 2,041건에서는 통과했고, 그 뒤 바뀐 코드는 인건비·flowrun 정리라 재고 조회와 관계가 없다.
- **리드 → 오류 담당(2026-10-23): 부하 중 모의 e2e 시간 초과.** 백엔드 전체와 함께 돌린 모의 e2e(2fe 격리본)에서 `auth.spec.ts:317`(동시 탭의 refresh 쿠키, `E2E Workflow` 5초 안에 안 보임)과 `bom-effectivity.spec.ts:91`(미리보기의 날짜 입력이 안 보임)이 실패했다. 부하 없이 `auth` 5회·`bom-effectivity` 3회는 모두 통과했다. 10-19의 스캐너 경합(요청 1)과 같은 종류인지 재현해 넘겨 달라. 재시도·시간 연장으로 가리지 않는다.
- **리드 → 오류 담당(2026-10-23): 쿼리 수 검사 흔들림.** 위 실패를 재시도로 가리지 말고, 같은 트랜잭션·스레드의 쿼리만 세도록(예: 테스트 전용 `StatementInspector`나 datasource proxy) 고치는 후보를 넘겨 달라. 재현 조건(어떤 앞 테스트와 겹칠 때인지)도 함께 적어 달라.
- **2fh 추가(리드, 2026-10-23, 사용자 "ㄱ"):** ADR-025 결정 10·11. `WorkerLaborRateRequest`에 `fromStart`, `WorkerLaborRateService`(첫 단가만 처음부터), `WorkerLaborCostIntegrationTest`(데이터로 넣던 처음부터 단가를 API로), 프런트 `useWorkerLaborRates`·`WorkerLaborRates`(**From the start**)·`e2e/worker-labor-cost.spec.ts`. 검증: 관련 백엔드 2건(WorkerLaborCostIntegrationTest), 프런트 typecheck·lint, 모의 e2e 3건 통과(격리본). 출퇴근 대조는 [ADR-026](../architecture/adr/ADR-026-attendance.md) 초안(Proposed)만 쓰고 사용자 결정을 기다린다.
- **최종 검증(2026-10-23, 2fe–2fh 반영본, 격리본, 커버리지 없이):** 전체 백엔드 2,124건(298클래스) 실패·오류·제외 0(앞서 흔들린 `StockFactsRegressionIntegrationTest`도 통과). 이어서 따로 돌린 프런트 Vitest·빌드 통과, 모의 e2e 215 통과·40 건너뜀(실제 API 전용)·실패 0.
- **2fi 추가(리드, 2026-10-23, 화면만):** 새 `LoadOrderMove.tsx`·`useProjectRole.ts`, 고친 `EquipmentLoadBoard.tsx`·`equipmentLoadModel(.test).ts`, 새 `e2e/equipment-load-move.spec.ts`. 검증: 프런트 typecheck·lint, 모델 테스트 10건, 모의 e2e 5건(`equipment-load-move.spec.ts` 2, 부하표 갱신 3) 통과(격리본). 백엔드 변경 없음. 오류 담당 확인 요청: 실제 API에서 초안 이동 뒤 다른 칸(BOM·지침·인력)이 그대로인지, 승인 작업지시 이동의 409(그사이 바뀐 날짜)와 editor 403.
- **리드 → 오류 담당(2026-10-23): 2ff 회귀 요청(보안).** ① 소유자 외(editor·viewer·작업자 본인·구성원 제거 뒤 토큰·다른 프로젝트 소유자)의 `labor-rates`·`labor-cost` 403, 다른 프로젝트 작업자 id 404. ② 소유자가 아닌 사람의 어떤 응답에도 단가·금액이 없는지(`/labor`, `/labor-comparison`, 보정 응답, 작업자 달력). ③ 동시 저장·같은 시작일 재저장·지우기 경합, 저장 불가 사유, 잘못된 금액(지수 표기, 큰 정수) 거절과 DB 불변. ④ 시간대 경계에서 기록한 날·마감 날의 단가 선택. ⑤ V102·V103 리허설은 사용자 커밋 뒤.
- **리드 → 오류 담당(2026-10-23): 2fe 회귀 요청.** ① 권한: 다른 프로젝트·삭제된 설비·구성원 제거 뒤의 `day-history` 404/403. ② 거절된 날짜 저장(잘못된 시각·범위 62일 초과·저장 불가 사유)과 없는 날짜 지우기(404)가 줄을 남기지 않는지, 트랜잭션 롤백 시 이력도 롤백되는지. ③ 범위 지우기가 날짜마다 다른 교대·사유를 그대로 남기는지, 복사가 받는 쪽에만 한 줄인지. ④ 정지의 시작·끝 시각이 DB에 저장된 정지와 같은 정밀도인지(앞의 정밀도 회귀와 같은 방식). ⑤ V102 리허설은 사용자가 커밋한 뒤. 리드 기능 코드의 결함은 리드가 고친다.

## 2. 다음 작업 (순서대로)

이번 주(2026-10-19 ~ 10-23) 계획은 [주간 개발 계획](WEEK-PLAN-2026-10-19.md)에 있다.

2026-11-01: 끝난 줄 228개는 [보관 문서](../archive/2026-10-workboard/WORKBOARD-done-2026-10.md) §2로 옮겼다. 번호(`2hb` 등)는 그대로이니 번호로 찾는다. 아래는 아직 남은 줄이다.

| # | 일 | 조건 | 상세 |
|---|---|---|---|
| 2bj | ~~D+ 원가 조회·기준 표시~~ | **조회·화면 단계 완료(2026-10-05, 커밋 전).** V1 종료 시각 열 mapping, 완료 시각 단가/추정 응답·줄별 기준·화면. 기존 저장소 2개 제거(자동 동결 84→82). 신규 통합 6, 전체 백엔드 1,070·커버리지·빌드, 프런트 469·모의 API 브라우저 3·타입·기존 lint·빌드 통과. **새 마감 시각 기록은 미완료** | [재료비](../domain/material-cost.md) D+ 2단계 |
| 2bn | ~~BOM 유효기간 명령·명시적 날짜 조회 기반~~ | **기반 구현(2026-10-06).** V1 날짜 mapping·V55 기간 version/이력·권한·재송신·기간 겹침 방어·프로젝트 날짜 조회 API·편집/미리보기 화면. 통합 13·기간 모의 API 8 통과. 전체 유효일 BOM 전환은 미완료: 프로젝트 시간대·계획 시작일 선택·다중 승인·팬텀 남음 | [BOM](../domain/multi-level-bom.md#유효기간-명령날짜-조회와-여러-초안-2026-10-06) |
| 2cf | ~~Data Flow 실제 API 브라우저 전체 흐름(§9-10)~~ | **스펙 완료·CI에서 실행(2026-11-01 확인):** `e2e/data-flow-browser.spec.ts`가 browser-e2e의 "Run real Data Flow browser acceptance" 단계로 돈다. `bc85104` 뒤로 DF12의 출력 단정이 낡아 고칠 것이 §1 "`bc85104` CI 빨강 4건" ③에 있다. **설계 완료(2026-10-10, 리드), 스펙은 오류·테스트 담당 Agent.** DF1–DF14: 새 프로젝트를 화면으로 만들고 노드 3·품목 없는 포트·연결·Publish·그래프 실행·Step 출력(잘못된 출력의 계약 거절 포함)·Finish·출력/이벤트 확인·제조 행 0, API는 정리에만. 화면이 없어 API로 우회한 단계가 있으면 VALIDATED로 올리지 않음 | [시나리오](../domain/data-flow-browser-scenario.md) |
| 2ch | ~~ADR-001 조직 Phase 1–3 설계~~ | **설계 완료(2026-10-10), OR6 사용자 확인 뒤 2cj로 구현.** OR1–OR10: 조직·멤버 테이블과 NULL 허용 `project.organization_id`, 조직 API, owner·admin 전용 관리 metadata 목록, 탈퇴·제거 시 그 조직 프로젝트 멤버십만 비활성화(프로젝트 owner·마지막 owner 409), 개인 조직 backfill(멱등), NOT NULL 전환 없음. 호환성 회귀 표(전후 접근 판정 동일)·cross-project deny·override 없음 검증 계획 | [조직 설계](../domain/organization.md) |
| 4 | 리본 마이그레이션 Step 3 리뷰 → Step 6(`workspace-topbar` 버튼 영역 제거) | Step 3 리뷰 | [editor/current-state](../editor/current-state.md) §8, [리본 계획](../editor/toolbar_ribbon_migration_plan.md) §7 |

2026-10-02에 끝난 아키텍처 작업(결정 인계 §6-1~4): ADR 3개, 공개 API 기준 구현(`CatalogQuery`·`FlowRunCommand`), ArchUnit 기준선, `resourceType` registry 경고.

## 3. 확정된 결정과 남은 확인

2026-10-05 사용자 첨부의 최종 결정은 [DECISIONS-2026-10-05](DECISIONS-2026-10-05.md)에 기록했다. 이전 Proposed 권장안과 다른 부분은 최종 결정이 우선한다. **승인 ≠ 구현 완료.**

| 항목 | 결정 | 구현·확인 상태 |
|---|---|---|
| 실행 원가 | D+ Accepted | actualEndAt 기록·historical price 조회·추정 표시 구현 순서 1 |
| 시간대 | 프로젝트별 IANA Zone, 기존 Asia/Seoul | 구현 순서 2 |
| 작업지시 일정 / 피킹 / 실사 | C / B / A + 계획별 blind, owner 기준 수량 열람 | 구현 순서 3~5 |
| 실사 금액 기준 / 작업지시 순서 제안 / 실사 위치 잠금 | 2026-10-12 사용자: 오늘·실사 당시 단가 둘 다 / 전환 최소 / 잠금 넣지 않음 | 2dp·2dq 로컬 구현. 잠금은 하지 않는다 |
| 승인 알림 | 2026-10-13 사용자: 요청은 소유자에게, 결정은 요청한 사람에게 | 2dr 로컬 구현(V78 2026-10-16 개발 DB 적용) |
| 고르는 알림 | 2026-10-13 사용자: 재고 경보·NCR 효과 확인 지남·작업지시 마감을 각자 프로젝트마다 고름 | 2dt 로컬 구현(V79 2026-10-16 개발 DB 적용) |
| 작업자 달력 | 2026-10-13 사용자: 작업자 = 프로젝트 구성원, 주간 교대·쉬는 날, 작업지시가 필요한 사람을 적고 준비 점검·계획 제안이 확인 | 2du 달력·2dv 작업지시 인력 로컬 구현(V80·V81 2026-10-16 개발 DB 적용) |
| 작업자 부하 | 2026-10-14 사용자: 작업자마다 승인·진행 중 작업지시에 잡힌 시간 대 교대 시간, 같은 때 다른 작업지시에 잡힌 인력은 준비 점검이 경고 | 2dw 로컬 구현(마이그레이션 없음) |
| 인력을 따르는 계획 제안 | 2026-10-14 사용자: 계획 제안(한 건·여러 건)이 인력의 쉬는 시간과 다른 승인 작업지시를 비키고, 누가 늦췄는지 말한다 | 2dx 로컬 구현(마이그레이션 없음) |
| 작업자 날짜별 교대 | 2026-10-14 사용자: 사람마다 날짜별 교대(특근·단축·하루 쉼), 설비 날짜별 교대(D1–D7)처럼 | 2dy 로컬 구현(V82 2026-10-16 개발 DB 적용), 다른 작업자로 복사 2ed(마이그레이션 없음). 순환 근무표는 2026-10-17 사용자 "Rotating worker rota": 2ee 로컬 구현(V87 2026-10-17 개발 DB 적용), 팀 근무표 2eg(사용자 "Team rota", 마이그레이션 없음) |
| 인력 몫 | 2026-10-14 사용자: 인력마다 필요한 몫(%), 부하·겹침·계획 제안이 그만큼만 셈 | 2dz 로컬 구현(V83 2026-10-16 개발 DB 적용) |
| 설비 효율 | 2026-10-14 사용자 "Equipment efficiency", 2026-10-16 정리: 기본 100%, 산출 × 효율, 전환은 그대로, 지난 실적·확정 원가는 그대로, 동시 작업은 따로 | 2ea 로컬 구현(V84 2026-10-17 개발 DB 적용). 동시 작업은 2026-10-17 사용자 "Concurrent equipment flag": 설비마다 켜고 끄는 스위치, 2ec 로컬 구현(V86 2026-10-17 개발 DB 적용) |
| 유효일 BOM / 팬텀 | 기간 비중첩 다중 승인·자동 retire 금지 / BomLine.phantom | 구현 순서 6~7 |
| Setup / 부산물 / 첨부 | 다차원 속성·별도 비용 / 별도 가치 / Local-S3 추상화 | 구현 순서 8~9 |
| 조직 | ADR-001 Accepted, 업무 접근 상속 없음, metadata·탈퇴 정책 확정 | Phase 1~3 로컬 구현(2cj, V64·V65 2026-10-16 개발 DB 적용)·권한 회귀 테스트(오류 담당)·관리 화면(2co) |
| 모듈·포트 | ADR-002/003 기존 원칙 유지, ADR-005 Accepted | 비제조 quantity/unit 선택 로컬 구현(2cq, V68 2026-10-16 개발 DB 적용) |
| 노드 종류·분기·합류 | ADR-006 **Accepted**(2026-10-17 사용자 "Accept as recommended": 게이트웨이 노드, 1단계 Exclusive만) | 2eb Exclusive 로컬 구현(2026-10-17, V85 2026-10-17 개발 DB 적용, [연결 계약](../domain/process-port-connection-contract.md) GR-13–GR-20). Join·Subflow·수량 분할은 후속이고, Join은 [ADR-007](../architecture/adr/ADR-007-join-gateway.md) **Accepted**(2026-10-17 사용자 "Accept as recommended": 도달 가능성 기준 한 번 합류). 2ef 로컬 구현(2026-10-17, V88 개발 DB 미적용, [연결 계약](../domain/process-port-connection-contract.md) GR-21–GR-28). 수량 분할은 [ADR-008](../architecture/adr/ADR-008-quantity-split.md) **Accepted**(2026-10-17 사용자 "Accept as recommended": Split 게이트웨이, 비율·고정·나머지). 2eh 로컬 구현(2026-10-17, V89 개발 DB 미적용, [연결 계약](../domain/process-port-connection-contract.md) GR-29–GR-36) |
| 연결 손실률 | [ADR-009](../architecture/adr/ADR-009-connection-loss-rate.md) **Accepted**(2026-10-17 사용자 "Accept as recommended") | 연결을 지날 때 보낸 수량 × (1 − 손실률), 비율 0 ≤ r < 1, 이벤트 `transfer_loss`, 새 발행본부터(`schemaVersion` 2). 2ei 로컬 구현(2026-10-17, 마이그레이션 없음, [연결 계약](../domain/process-port-connection-contract.md) GR-37–GR-44) |
| 연결 지연 | [ADR-010](../architecture/adr/ADR-010-connection-delay.md) **Accepted**(2026-10-17 사용자 "Accept as recommended") | 다음 단계 `scheduledAt` = 출발 완료 + 지연, 게이트웨이는 받은 시각을 넘김, Join은 가장 늦은 도착, 앞당기기 409, 새 발행본부터(`schemaVersion` 3). 2ej 로컬 구현(2026-10-17, 마이그레이션 없음, [연결 계약](../domain/process-port-connection-contract.md) GR-45–GR-52) |
| 생산 실행의 공정 단계 | [ADR-011](../architecture/adr/ADR-011-production-steps.md) **Accepted**(2026-10-17 사용자 "Accept as recommended") | 새로 시작하는 생산 실행의 연결 Flow Run을 그래프로 돌려 노드 단계를 두고, 지침 단계에 노드를 적는다. 2el 로컬 구현(2026-10-17, V91 개발 DB 미적용) |
| 실행 정책 | ADR-004 Accepted, workflowRevision + node | timeout·delay·concurrency 로컬 구현(2cp, V67 2026-10-16 개발 DB 적용), 편집 화면 2di(2026-10-11 사용자 결정, NodeInspector) |
| 협업 / 역할 | 요소별 optimistic patch / 초기 viewer-editor-owner 확정 | 적용 범위 확인, CRDT Deferred |
| 범용 실행 | backend integration VALIDATED / 제품 PARTIALLY VALIDATED | 전체 실제 API 브라우저 흐름 구현 순서 10 |
| D2 집계 | 기존 15건 중 확인 14(조건부 포함), 품목 삭제 방어 1건 확인 중 | 이전 보고가 품목 삭제를 완료 실행 보정으로 잘못 집계. 완료 실행 보정은 별도로 승인됨. 회신 전 15/15로 표시하지 않음 |
| 운영 | 기존 DB validate·STOMP 보안·부하·접근성 실사용 검증 | 오래된 D4~D6 기록은 현재 상태 재확인 필요 |
| 기존 테스트 BOM | 유지·정리 선택 | 참조 관계 확인·사용자 결정 전 삭제 금지 |
| 첨부 저장소 운영 정책 | 미정(2026-10-10 첨부 보안 검토에서 이어짐, 2026-11-01 옮김) | 코드 검수는 끝났다. 운영 S3 비공개 버킷·IAM·TLS, 바이러스 검사, 미참조 파일 회수, 보존 기간, 전체 저장 용량은 배포·정책 검토 대상이다([보관 문서](../archive/2026-10-workboard/WORKBOARD-done-2026-10.md) §1 "2026-10-10 첨부 보안 정적 검토") |

### 2026-10-18 사전 위임 결정: 10월 19–25일 개발

근거는 사용자 요청 "일주일동안의 개발동안 필요한 의사결정을 모두 결정해. 권한같은것도"다. 아래는 그 위임으로 정한 정책이며, 사용자에게 개별 선택을 다시 받은 것으로 기록하지 않는다. 10월 18일 준비 작업부터 즉시 적용하고 25일까지의 개발 기준으로 쓴다. 기존 확정 계약을 유지하면서 이번 주의 빈칸을 닫는다. 승인은 구현·운영 적용·검증 완료를 뜻하지 않는다. 리드의 [주간 계획](WEEK-PLAN-2026-10-19.md)과 함께 적용하며, 이후 사용자의 추가 선택과 새로 수용된 ADR의 구체적 계약을 이 표의 일반 문구로 뒤집지 않는다.

#### 작업 순서와 담당

| 항목 | 결정 |
|---|---|
| 기간 | 10월 19–23일 기능·통합, 24–25일 회귀·문제 수정. 일정이 밀리면 새 기능보다 실패 해결을 먼저 한다. 상주 자동 실행이나 7일 뒤 자동 배포를 약속하는 결정은 아니다 |
| 리드 | ADR-014 설비 달력 이력 → ADR-015 설비 근무시간 지연 → ADR-016 인력 근무시간 지연. 10월 18일 이미 로컬 구현된 2es–2eu는 완료 상태를 유지하고 검증 결과를 받는다. 기능·API·새 마이그레이션과 도메인 설계를 맡는다 |
| 오류 담당 | 재현 테스트, 오류 분석, 테스트·CI·보안 회귀를 맡는다. 이미 개별 허가한 기능 수정은 그대로 진행하고, 새 기능 코드의 실패는 재현·최소 수정안을 리드에게 넘긴다 |
| 테스트·CI 파일 수정 | 담당이 비어 있거나 작업이 끝난 파일은 오류 담당이 status·mtime 확인과 WORKBOARD 예약 뒤 수정할 수 있다. 대기하던 Split·손실 편집기 테스트 두 파일도 포함한다. 기능 정책·검사를 삭제하거나 기준을 낮추는 변경은 허용하지 않는다 |
| 작업 중인 파일 | 다른 담당이 예약한 파일은 수정하지 않는다. 실패·수정안·대상 줄을 넘기고 다른 일을 진행한다. 남의 변경 원복·일괄 정리는 하지 않는다 |
| 작은 설계 선택 | 기존 데이터·API·권한 계약을 유지하는 권장안으로 진행하고 근거를 남긴다. 파괴적인 호환성 변경은 이번 주 범위에서 제외한다 |
| 의존성 | API 호환성을 유지하는 패치 보안 업데이트는 격리 검증 뒤 리드가 로컬에 반영한다. AWS SDK 2.55.14 대기도 이 절차로 처리한다. major 업데이트·PR 머지·원격 변경은 이번 주에 하지 않는다 |

#### 앱 권한 기준

프로젝트 소유자 또는 활성 프로젝트 구성원인지 매 요청마다 검사한다. 역할은 프로젝트별이며 다른 프로젝트·조직에서 가져오지 않는다. 로그인 여부와 프로젝트 권한은 별개다.

| 작업 | viewer | editor | owner |
|---|---|---|---|
| 프로젝트 업무 데이터·실행·달력·첨부 조회, 협업 구독 | 읽기 | 읽기 | 읽기 |
| 워크플로·포트·연결·실행 정책 편집, 정상 생산·재고·품질 명령, 실사, 초안 작성 | 불가 | 허용 | 허용 |
| 설비 달력·정지 시간·미래 달력 판 작성·삭제 | 불가 | 허용 | 허용 |
| 작업자 본인의 교대·쉬는 날·미래 판 작성·삭제 | 불가 | 본인만 | 허용 |
| 다른 작업자 달력 변경·팀 근무표 복사 | 불가 | 불가 | 허용 |
| BOM·작업지시 승인, 승인 작업지시 일정 변경, 완료 실행 보정 승인, LOT 종료·재개 | 불가 | 보정 요청 등 기존 요청 API만 | 승인·명령 허용 |
| 프로젝트 시간대·멤버·역할 관리, 프로젝트 삭제, 품목 삭제 | 불가 | 불가 | 기존 계약·참조 방어를 만족할 때 허용 |
| 자기 알림 읽음·구독 설정 | 본인 것만 | 본인 것만 | 본인 것만 |
| 블라인드 실사의 기준 수량 열람 | 불가 | 불가 | 허용 |

- 조직 OWNER/ADMIN과 시스템 관리자는 그 지위만으로 프로젝트 업무 내용에 접근하지 않는다. 기존 관리 API의 관리 메타데이터 권한만 유지한다. 프로젝트 읽기·쓰기는 위 표를 따른다.
- 멤버 제거·탈퇴는 기존 토큰·협업 구독을 통해 권한을 유지하는 수단이 될 수 없다. REST·STOMP와 ID 혼용 요청을 함께 검사한다.
- viewer의 알림 설정은 개인 설정 예외이며 업무 데이터 쓰기 권한을 주지 않는다. editor는 남의 근무표를 바꿀 수 없다.
- owner의 기존 승인 경로는 유지한다. 이 주에 2인 승인·새 APPROVER/QUALITY_MANAGER 역할·조직 관리자 override는 추가하지 않는다.
- 비로그인·권한 없음·다른 부모의 ID·삭제된 부모·같은 요소의 버전 충돌을 회귀로 확인한다. 기존 401/403/404/409 계약을 임의로 통일하지 않는다.

#### 기능·데이터 정책

| 항목 | 결정 |
|---|---|
| 설비 달력 이력 | ADR-014 그대로. 프로젝트 날짜의 오늘 이후 적용, 여러 미래 판, 같은 날짜 저장은 해당 판 수정, 과거 판 변경 불가. 지우기는 그날부터 항상 가동 판이며 과거 교대는 보존한다 |
| 날짜별 교대 | 판이 아닌 설비·날짜에 묶는다. 그날 유효한 기본 판에 교대가 있을 때만 기존 D2 규칙대로 덮어쓴다. 판별 복제·정리는 하지 않는다 |
| 변경 사유 | 달력 판의 새 필수 입력으로 만들지 않는다. 변경자·변경 시각은 보존하고, 기존 사유 필드의 필수 조건은 유지한다 |
| 연결 지연 | 우선 완료 시각 + 기존 지연 초로 도착 시각을 계산한다. 그 시각이 비가동이면 다음 가동 시작으로 미룬다. 지연 초를 근무시간만 세어 누적하는 방식은 채택하지 않는다 |
| 지연 달력 | 확정·구현된 ADR-015·016을 유지한다. 생산 실행과 연결된 작업지시의 설비와, 몫 100%이고 교대가 있는 인력 모두의 가용 시간을 따른다. 설비 없이 인력만 있어도 적용한다. 몫 100% 미만·교대 없는 인력은 시각을 정하지 않는다. 부하 최적화는 추가하지 않는다 |
| 지연 실행 호환성 | ADR-010의 지연 대상 일 단계에 ADR-015·016을 적용한다. 게이트웨이는 받은 시각을 넘기고 다음 일 단계에서 조정한다. 기존 scheduledAt은 달력이 바뀌어도 재계산하지 않는다. 지연 자체가 끝났고 현재 설비·인력이 모두 가용하면 기존 판정에 따라 바로 시작할 수 있다. 1년 안에 공동 가용 시간이 없거나 설비가 삭제됐으면 기존 계약대로 readyAt을 유지한다. 자동 실행·새 opt-in 정책·새 실패 처리는 도입하지 않는다 |
| 실행 시간 제한 | 일반 Flow Run의 ADR-004 정책과 생산 단계의 "늦음 표시만"을 구분한다. 생산 단계에 자동 실패를 도입하지 않는다 |
| 품목 삭제 방어 | 재고 행(수량 0 포함)이나 활성 BOM 참조가 남으면 409인 기존 방어를 확정·유지한다. 다른 참조 방어도 약화하지 않는다. 과거 D2 14/15 집계는 원문 대응 검수 전 숫자만 15/15로 바꾸지 않는다 |
| 기존 테스트 BOM | 이번 주는 보존한다. 실행·작업지시 참조를 끊거나 강제로 삭제하지 않는다. 개발 DB 신규 BOM 작성·가져오기·생성 E2E는 계속 금지한다 |
| Stage C | 이번 주는 의존 현황 조사와 설계까지. 전역 Entity import 금지를 즉시 켜거나 대규모 치환하지 않는다. 저장소 경계 0건은 유지하고 새 도메인 간 연결은 공개 API를 쓴다. **(2026-10-19 사용자 지시로 바뀜: 기준선 39줄을 동결하고 새 의존을 막았다. 대규모 치환은 여전히 하지 않는다. ADR-018 Accepted)** |
| 웹훅·Outbox | 이번 주 외부 전송 구현은 하지 않는다. 이후 선택 시 주간 계획의 HMAC-SHA256·소유자 등록·최대 5회 재시도·Outbox 기준을 출발점으로 보안 검토한다 |
| 기타 확장 | Subflow·자동 노드 실행기·Tenant/Site/거래처·배출량·AI·조직 이동/삭제·권한 상속은 이번 주 범위에서 제외한다 |

#### 실행 권한·검증 기준

- 브랜치 생성·전환, commit·push·merge, 스테이징, `.gitignore` 변경, UTF-8 BOM 삽입은 계속 금지한다. 사용자 대신 배포·외부 발송·자격증명 변경도 하지 않는다.
- 리드는 이미 허용된 `flowmat-session1-db` 기동과 커밋된 새 마이그레이션의 개발 DB 적용을 맡는다. 적용 전 백업 → 임시 복제본 → 트랜잭션 리허설 → 기존 데이터 비교 → 주요 API 확인 순서다. V94처럼 작업 중인 번호는 리드 예약이며 작성 직전 최고 버전을 재확인한다. 기존·적용된 SQL과 Flyway 이력·체크섬을 수정하지 않는다.
- 실패하면 개발 DB 적용을 중단하고 복제본에서 원인을 해결한다. 자동 초기화·백업 덮어쓰기·강제 데이터 정리는 하지 않는다. 커밋 전 기능은 새 일회용 DB에서 검증하므로 사용자 커밋을 기다리며 테스트까지 멈추지 않는다.
- 오류 담당의 전체 Gradle·실제 API E2E는 격리 소스·별도 포트·일회용 DB에서만 실행한다. 테스트용 프로젝트는 고유 이름을 쓰고 자기 데이터만 정리한다. 개발 DB에는 BOM 없는 모의 화면 테스트만 사용한다. 타인 서버·컨테이너는 종료하지 않고 자기 것만 PID·시작 시각·명령 또는 ID·레이블 대조 후 정리한다.
- 버그마다 재현 실패와 수정 후 통과를 남긴다. 변경 영역의 테스트·타입·린트·빌드를 실행하고, 통합 시 전체 백엔드와 기존 커버리지 gate를 통과시킨다. 브라우저 실패를 retry·timeout 확대나 assertion 삭제로 숨기지 않는다.
- 기존 BOM 생성 실제 스펙은 로컬 개발 DB 실행에서 제외한다. 실제 API는 전용 환경 opt-in, workers 1, trace off로 실행하고 토큰·쿠키를 기록하지 않는다.
- 완료한 기능을 다시 결정 대기로 돌리지 않는다. 구현되지 않은 결정을 완료라고 쓰지 않는다. 위 범위의 선택은 개별 재확인 없이 진행하고, 실패나 담당 충돌은 WORKBOARD에 남겨 다른 일을 진행한다.

Tenant·Site·거래처·조직 이동/삭제·노드 실행기·세분화 역할·배출량 모델은 별도 후속 범위다. Outbox·Microservices는 Deferred. 승인된 범위를 결정 대기로 다시 막지 않는다.

### 2026-11-01 사용자 최종 결정: D1–D4

사용자가 전수조사의 결정 대기 네 가지를 정했다. 다시 묻지 않는다. 결정을 적용하면 기존 필수 기능이 깨진다는 명백한 증거가 나올 때만, 그 증거와 영향을 보고하고 가장 안전한 호환 구현을 고른다.

| 결정 | 내용 | 적용 |
|---|---|---|
| D1 빌드 산출물 | git에서 추적하지 않는다. Flyway 마이그레이션·픽스처·소스 역할의 생성 파일은 제외한다. CI가 필요한 것은 CI artifact로 남긴다 | 2026-11-01 색인 제거·`.gitignore`(§1) |
| D2 개발 DB 5434 | 활성화 전에 복제본 리허설(전·후 버전, 적용 목록, 실패 여부, 데이터 보존, 기동, 핵심 점검을 남김). 적용된 마이그레이션은 고치거나 지우거나 번호를 다시 쓰지 않고 앞으로만 간다 | 2026-11-01 V132(§1) |
| D3 Prometheus | 관리 포트 분리. 기본 방어선은 망·포트 격리이고 토큰은 나중의 겹방어 | [ADR-029](../architecture/adr/ADR-029-management-port.md) |
| D4 얼굴 로그인 | 운영 기본 OFF, 기능 격리(코드는 지우지 않음). 다시 켜기 전 생체 자료·동의·위조 대응 등을 검토 | [ADR-030](../architecture/adr/ADR-030-face-login-off.md) |

## 4. 기능 쪽 남은 큰 덩어리

> 아래는 2026-10-03 조사 이력이다. 원가·피킹·실사·첨부·시간대·setup·부산물·유효일·팬텀의 정책 선택은 §3에서 확정됐으며, 남은 일은 구현·도메인 공개 API·회귀 검증이다. 예전 "결정 대기" 표현으로 재승인을 요구하지 않는다.

세부 남은 범위는 [CURRENT_CAPABILITIES](CURRENT_CAPABILITIES.md)의 각 행과 "원래 백로그 대비 현황"에 있다.

- 미착수: FM-AI-001~003. (2026-11-01 정정: FM-PLAN-001은 Exclusive·Join·Split 게이트웨이까지 구현됐다([ADR-006](../architecture/adr/ADR-006-operation-types.md)–[008](../architecture/adr/ADR-008-quantity-split.md)). FM-INT-001·003은 다른 세션(커넥터 구역)이 HTTP 프로필·판, 공정 실행 바인딩, Flow Run 단계의 HTTP 실행, JSON 매핑 미리보기로 일부 구현했다(V107, 2026-10-25 `398de46`). FM-INT-002 예약·반복 연동 작업은 없다.)
- 일부: FM-RUN-005 실행 정책(시간 제한·재시도 간격·동시 실행 제한), FM-PLAN-002 용량 자원, FM-PLAN-003 달력(작업자 달력. 하루 여러 교대는 V43, 날짜별 교대·설비별 휴일은 V45로 끝)
- 실행 원가 저장(`production_run.total_material_cost`·`cost_per_unit`, 열은 있음): 마감 때 채우려면 `ProductionRunServiceImpl`을 고쳐야 하고, ADR-002 Stage B로 그 파일의 다른 도메인 저장소 6줄(workflow 4, catalog 1, inventory 1)을 공개 API로 옮겨야 한다. workflow·inventory 공개 API를 새로 만드는 일이라 따로 잡는다 2026-10-03 확인: (1) 그 파일은 규칙 엔진에 `workflow`·`item`·`process`·`processIo`·`inventory` 엔티티를 사실(fact)로 넘기고, 엔진은 `objectMapper.convertValue`로 모든 필드를 읽는다. 공개 API의 좁은 뷰로 바꾸면 기존 규칙식이 보던 필드가 사라질 수 있어, Stage B는 사실의 모양을 그대로 지키는 방법(소유 도메인이 사실 맵을 만들어 주는 연산 등)을 먼저 정해야 한다. (2) 마감 때 저장하는 값이 무엇인지(마감 시점 단가로 고정한 금액인가, 마감 뒤 보정 전표가 기록을 바꾸면 다시 계산하는가)는 원가 정책이라 §3 결정 대기에 올렸다
- 에디터 결정 7개 중 명령 스택·모델링 서비스·규칙 레지스트리·도구 레지스트리

### 재고·생산·품질 쪽 남은 후보와 막힌 까닭 (2026-10-03 정리)

도메인 문서의 "이후" 항목 중 결정 없이 할 수 있는 것은 2026-10-03에 대부분 끝냈다(§2 2a–2al). 남은 것은 아래처럼 결정이나 큰 구조 작업이 먼저다.

| 항목 | 막힌 까닭 | 근거 문서 |
|---|---|---|
| 실행 원가 저장, 단가 이력으로 과거 금액 계산(끝난 실행·실사 차이) | 실행 D+는 2bx 완료(금액 저장은 미채택). 실사 금액은 2026-10-12 사용자 결정으로 오늘·실사 당시 둘 다(2dp) | [재료비](../domain/material-cost.md), §2 2bx |
| ~~출퇴근 기록과 작업자 시간 대조~~ | **풀림(2026-10-24, 2fj).** 사용자 위임("의사결정도 recommend 로 계속 알아서 밀고나가")으로 권장안 A. 보존 기간·내보내기는 남았다 | [ADR-026](../architecture/adr/ADR-026-attendance.md) Accepted |
| ~~할당된 재고를 먼저 집는 피킹, 투입 취소 때 재할당~~ | **풀림.** 피킹은 2026-10-05 2bl·2bm(예약 유지 이동, 자기 할당 우선 피킹), 투입 취소 때 다시 할당은 2026-10-24 2fk(R4). 아래는 2026-10-03 기록: 할당은 production, 피킹·재고 행 잠금은 inventory라 어느 쪽이 다른 쪽을 부를지(의존 방향). `StockAllocationService`는 inventory 저장소로 행을 잠그고 고르므로 Stage B에 inventory 공개 API(행 잠금·후보) 설계가 먼저 | [재고 할당](../domain/stock-allocation.md), [ADR-002](../architecture/adr/ADR-002-module-dependency.md). 선택지·권장안: [결정 메모](../domain/stock-allocation.md#결정-메모-할당된-재고를-먼저-집는-피킹-proposed-2026-10-03)(Proposed) |
| ~~재고 있는 위치의 코드 바꾸기(L7 풀기)~~ | **풀림(2026-10-03, §2 2al).** 재고 행을 새로 만드는 곳이 모두 inventory 안(Add Stock·가져오기·이동 도착 행)이라 `ProductionRunServiceImpl`을 고칠 필요가 없었다. 위치 코드별 잠금으로 해결 | [보관 위치](../domain/storage-location.md) L7 |
| ~~작업 지침 이미지·파일 첨부~~ | Accepted 정책·수정 허가 후 2bz 완료. 운영 저장소 이관·미참조 회수/보존기간·바이러스 검사는 별도 | [첨부 계약](../domain/work-instruction.md#2026-10-09-구현-파일-첨부-2bz), §2 2bz |
| 공정(노드)별 작업 지침·검사 기준 | 공정 단계 실행(`flow_run_step`)과 잇는 일이라 flow-run 구역과 함께 정해야 한다 | [작업 지침](../domain/work-instruction.md), [검사 기준](../domain/inspection-standard.md) |
| ~~블라인드 실사·실사 계획~~ | **풀림(2026-10-05 결정, 2bk V53, [서버 실사 계획](../domain/stock-count.md) "서버 실사 계획" P1–P8).** 담당자 배정은 2ge(P9–P10, V114). 아래는 2026-10-03 기록: 지금 실사는 센 사람이 본 수량(C3)으로 동시 이동을 막는다. 수량을 숨기면 기준 시점을 계획에 저장하는 새 모델이 필요 | [재고 실사](../domain/stock-count.md). 선택지·권장안: [결정 메모](../domain/stock-count.md#결정-메모-블라인드-실사와-실사-계획-proposed-2026-10-03)(Proposed) |
| ~~setup 묶음·전환 비용~~(2bt V57·2bs V56·2cy로 끝남), ~~부산물 가치~~(2026-10-06 따로 표시, 폐기 처리비 2ce·2cw)·배출 종류(보류), ~~유효일 BOM·팬텀 반제품~~(2cc·2ci·2cn) — 2026-10-24 정정 | 제품 규칙(무엇으로 묶을지, 금액을 어디에 더할지, revision을 날짜로 고를지)이 먼저. 일부는 D2 대기 항목과 겹침 | 각 도메인 문서 "이후". 선택지·권장안: [전환](../domain/equipment-changeover.md#결정-메모-setup-묶음과-전환-비용-proposed-2026-10-04), [부산물](../domain/bom-by-products.md#결정-메모-부산물-가치와-배출-proposed-2026-10-04), [BOM](../domain/multi-level-bom.md#결정-메모-유효일-bom과-팬텀-반제품-proposed-2026-10-04) 결정 메모(Proposed) |
| ~~프로젝트별 시간대(유효기한·설비 날짜)~~ | Accepted B 정책·수정 허가 후 2by 완료 | [구현 계약](../domain/project-time-zone.md), §2 2by |

## 5. 사용자가 하는 일

- 브랜치 생성·커밋·푸시. Agent는 하지 않는다.
- docs 커밋·푸시. (2026-11-01 정정: 2026-10-05 `d22d37f`부터 `*.md` 무시 규칙이 없어 새 문서도 보통 `git add`로 추적된다. 2026-10-02의 `git add -f` 스테이징 안내는 끝난 일이다.)
