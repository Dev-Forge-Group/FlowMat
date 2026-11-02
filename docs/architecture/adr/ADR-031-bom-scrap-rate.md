# ADR-031: BOM 줄 스크랩률

작성일: 2026-11-02

## 상태

**Accepted (2026-11-02). 로컬 구현 2hv(2026-11-02, 마이그레이션 없음, 커밋 전).**
- 결정 1–9는 리드가 권장안으로 정했다(결정 9는 같은 날 추가). 근거는 2026-10-24 사용자 위임("의사결정도 recommend 로 계속 알아서 밀고나가")이다.
- 대체 자재·선택 자재는 여기서 정하지 않는다("결정하지 않은 것").

근거 문서:
- [재고·BOM·LOT 계약](../../domain/inventory-bom-lot-contract.md) 승인 검증 6("1차에서 거절")
- [다단계 BOM](../../domain/multi-level-bom.md) "이후"
- [ADR-009](ADR-009-connection-loss-rate.md) 결정 11(BOM 스크랩률은 그 결정 밖)
- [현재 기능](../../status/CURRENT_CAPABILITIES.md) FM-MFG-001

## 배경 (2026-11-02 코드·개발 DB 기준)

- **열은 V1부터 있다.** `bom_line.scrap_rate numeric(5,4) DEFAULT 0`이다.
  - 줄 추가 API가 이 값을 저장하지만, 승인이 "scrap rates are not supported yet"로 거절했다. 그래서 승인 revision은 모두 0이다.
  - 개발 DB 5434의 줄 48개(approved 32, draft 10, retired 6)도 모두 0이다.
- **소요량을 계산하는 곳은 둘뿐이다.**
  - [`BomServiceImpl.requirements`](../../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/bom/application/BomServiceImpl.java): 간이 MRP·준비 점검·재고 할당·창고 피킹·실행 계획 투입·만들 수 있는 양·다단계 전개·폐기 처리가 모두 이것을 부른다.
  - [`BomTree.perProductUnit`](../../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/bom/application/BomTree.java): 원가 누적과 역전개가 쓴다.
- **연결 손실률(ADR-009)과 겹치지 않는다.** 연결 손실률은 Flow Run 그래프 수량에만 적용되고 재고·생산 기록을 움직이지 않는다(L7). 그래서 BOM 스크랩률과 두 번 세는 일이 없다.
- **두 사용자 모두에게 필요하다.**
  - 생산관리자에게 "넣는 양 = 제품에 남는 양 + 잃는 양"(공정 손실·자투리·시험분)은 흔한 계획 방식이다.
  - 데이터 흐름을 다루는 개발자에게는 계획 단계의 소비량이 손실까지 담아야, 부족 경고와 원가가 실제와 맞는다.
  - 지금은 손실을 줄 수량에 섞어 넣어야 해서, 순량과 손실을 따로 볼 수 없다.

## 선택지

- A: 손실을 위에 더한다. 필요량 = 순량 × (1 + s).
- B: 수율로 나눈다. 필요량 = 순량 ÷ (1 − s).
- C: 지금처럼 거절하고, 사용자가 줄 수량에 손실을 직접 더한다.

**권장안: A.**
- 입력한 퍼센트가 "순량의 몇 %를 더 넣는가"로 그대로 읽힌다.
- s = 1(100%)에서도 값이 정해진다.
- B는 s가 1에 가까울수록 값이 급히 커지고, 50%가 "두 배"로 읽혀 헷갈린다.
- C는 원가·차이에서 손실만 따로 볼 수 없다.

## 결정

1. **필요량 계산.**
   - 필요량 = 생산 수량 ÷ 기준 수량 × 줄 수량 × (1 + 스크랩률)이다.
   - 단위 환산과 반올림은 그 뒤에 지금과 같은 순서로 한다.
2. **허용 범위.**
   - 스크랩률은 0 이상 1 이하이고, 소수 넷째 자리까지 받는다(열 `numeric(5,4)`). 화면은 0–100%, 소수 둘째 자리까지 받는다.
   - 범위를 벗어나면 줄 추가 때 400으로 거절한다.
   - 승인 검증도 같은 문장으로 다시 거절한다. 검사 전에 저장된 줄에 대비한 것이다.
3. **둘 수 있는 줄.**
   - 스크랩률은 자재 줄에만 둔다. 부산물·폐기물 줄은 0이어야 한다. 배치가 내놓는 양이지 쓰는 양이 아니기 때문이다.
   - 팬텀 줄에도 둘 수 있고, 팬텀 BOM 안의 스크랩과 곱해진다.
   - 예: 반제품 2 kg × 1.1 → 2.2 kg. 그 안의 자재 1 kg/kg × 1.05 → 2.31 kg.
4. **적용 범위.**
   - `requirements`를 부르는 모든 곳에 적용된다: 간이 MRP, 준비 점검, 재고 할당, 창고 피킹, 실행 계획 투입(`production_run_item.planned_qty`), 만들 수 있는 양, 다단계 전개.
   - 정정(2026-11-02, 같은 날 2hw 범위 조사에서 찾음): "만들 수 있는 양"은 팬텀 줄이 있는 BOM만 `requirements`를 불렀다. 팬텀이 없는 BOM은 줄 수량을 그대로 써서 스크랩이 빠져 있었다. [`BuildableQuantityService`](../../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/production/application/BuildableQuantityService.java)가 이제 줄 스크랩률을 곱한다. `BomScrapRateIntegrationTest` 첫 테스트가 밀가루 4.2 kg으로 20개(스크랩이 빠지면 21개)를 확인한다.
   - 원가 누적·역전개(`perProductUnit`)에도 적용된다.
   - 재료비는 스크랩을 포함한 양으로 계산한다.
5. **기존 revision은 그대로다.**
   - 승인된 revision은 바뀌지 않는다. 스크랩률을 바꾸려면 새 revision을 만든다.
   - 지금 승인 revision은 모두 0이어서, 기존 계산 결과는 바뀌지 않는다.
6. **실제 투입은 바뀌지 않는다.**
   - 실제 투입은 작업자가 기록한 양 그대로다. 스크랩률은 계획(필요량·예약·계획 투입)에만 쓰이고, 실제 투입을 자동으로 늘리지 않는다.
   - 계획 대 실제 차이는 스크랩을 포함한 계획과 비교한다.
7. **응답과 화면.**
   - 소요량 응답의 줄에 `scrapRate`를 함께 준다. `requiredQuantity`·`requiredItemQuantity`에는 이미 들어 있다.
   - 화면은 BOM 줄에 "5% scrap", 소요량에 "incl. 5% scrap"을 보인다.
   - revision 비교는 스크랩률이 다르면 changed로 표시한다.
8. **다른 손실률과는 별개다.**
   - 연결 손실률(ADR-009)·포트 손실률(`process_io.loss_rate`)과 상관없다.
   - Flow Run 그래프 수량은 BOM 스크랩률을 보지 않는다.
9. **CSV 줄 가져오기(2026-11-02 추가).**
   - 선택 열 `scrap_percent`(별칭 `scrap`, `scrap_pct`, `scrap %`)는 화면처럼 퍼센트다. `5`나 `5%`는 0.05로 저장된다.
   - 범위와 자리는 결정 2와 같다(0–100%, 소수 둘째 자리까지). 자재 줄에만 둘 수 있고, 비우거나 0이면 스크랩이 없다.
   - 어긋나면 줄 오류다: `Scrap is not a number: …`, `Scrap must be from 0% to 100%`, `Scrap takes at most 2 decimals (0.01%)`, `Only a material line can have scrap`.
   - `scrap_rate`·`scrap_factor` 열은 0–1 비율로 읽힐 수 있어서 파일째 거절한다. 0.05를 0.05%로 잘못 읽는 일을 막으려는 것이다.

## 수용 기준

- [x] **입력 검사:** 줄 추가와 승인이 0–1, 소수 넷째 자리까지, 자재 줄만이라는 조건을 지킨다. 그 밖의 값은 400 또는 승인 거절이다([`BomScrapRateIntegrationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/BomScrapRateIntegrationTest.java) 세 번째 테스트). 승인 검증 기존 테스트(`BomIntegrationTest.approvalReportsEveryProblem`)는 예시를 선택 자재 거절로 바꿨다(같은 날 ADR-033으로 선택 자재를 받게 되어, 예시를 "부산물 줄은 선택 자재일 수 없다"로 다시 바꿨다).
- [x] **계산 일치:** 소요량·재료비·원가 누적·준비 점검·재고 할당·실행 계획 투입이 같은 4.2 kg(10개당 2 kg, 20개, 5%)을 쓴다(첫 번째 테스트).
- [x] **팬텀:** 팬텀 줄의 스크랩이 팬텀 BOM 안의 스크랩과 곱해져 2.31 kg이 된다(두 번째 테스트).
- [x] **화면:** 확인한 것은 다음과 같다.
  - 줄 입력은 퍼센트로 받고, 자재 줄에서만 켜지며, 소수 셋째 자리 이상이면 보내지 않는다.
  - 줄과 소요량에 스크랩이 보인다([`e2e/bom-scrap-rate.spec.ts`](../../../flowmat_frontend/e2e/bom-scrap-rate.spec.ts), 모의 API).
  - revision 비교가 스크랩 차이를 보인다(`bomModel.test.ts`).
- [x] **기존 회귀:** 격리 사본에서 BOM·할당·MRP·준비 점검·창고·전개·원가 테스트 19개 클래스 82건이 통과했다. 모의 BOM 브라우저 27건, 프런트 단위 961건, 타입·lint·빌드도 통과했다.
- [x] **백엔드 전체:** 같은 사본에서 438개 클래스 3,236건을 돌려 실패 1건이 나왔다.
  - 실패한 테스트는 `FlowRunGraphIntegrationTest.publishedGraphRoutesOnConditionAndKeepsPinnedCapacity`다.
  - BOM과 관계없는 간헐 실패다. 따로 세 번 돌려 세 번 모두 통과했다.
  - 원인은 같은 시각 이벤트의 순서가 흔들리는 것이다(WORKBOARD §1 "Flow Run 이벤트 같은 시각 순서"). 같은 날 따로 고쳤다([Flow Run](../../domain/flow-run.md) F19).
- [x] **CSV 열(결정 9):** `BomLineImportIntegrationTest.aScrapColumnTakesPercentsOnMaterialLines`가 범위·자리·자재 줄만·숫자 아님 오류와 퍼센트 저장(5% → 0.05, 2.25 → 0.0225)을 본다. `bomModel.test.ts`와 모의 e2e `bom-scrap-rate.spec.ts`가 `scrap_rate` 열 거절을 본다. 사본에서 BOM 관련 41개 클래스 202건, 모의 BOM 브라우저 26건이 통과했다. 이벤트 순서 수정까지 넣은 백엔드 전체는 439개 클래스 3,239건, 실패 0이다.
- [ ] 원격 CI 네 가지 초록(커밋·push 뒤)

## 결정하지 않은 것

- ~~**대체 자재(`substitute_group`):** 같은 묶음의 어느 자재를 언제 고르는지(계획·할당·피킹·투입), 우선순위·비율, 원가, LOT 추적을 정하지 않았다. 승인은 계속 "not supported yet"로 거절한다.~~ 정정(2026-11-02): [ADR-032](ADR-032-bom-substitute-materials.md)로 정하고 구현했다(2hw).
- ~~**선택 자재(`optional_yn`):** 작업지시마다 넣을지를 어디에 저장하는지와 기본값을 정하지 않았다. 계속 거절한다.~~ 정정(2026-11-02): [ADR-033](ADR-033-bom-optional-materials.md)로 정하고 구현했다(2hx).
- ~~**CSV 줄 가져오기의 스크랩률 열:** 아직 없어서, 가져온 줄은 0이다.~~ 정정(2026-11-02): 결정 9로 정하고 구현했다.
- ~~**실제 손실 집계:** 계획 스크랩 대 실제 투입 차이를 줄별로 모아 보는 기능은 없다.~~ 정정(2026-11-02): [ADR-034](ADR-034-bom-scrap-performance.md)로 정하고 구현했다(2hy).

## 결과

- **백엔드**(마이그레이션 없음):
  - `BomTree`: `scrapFactor`, `scrapRateProblem`, `MAX_SCRAP_RATE`를 더했고 `perProductUnit`에 스크랩을 반영했다.
  - `BomServiceImpl`: `addLine`에 검사를 넣었고 `requirements`가 자재 줄에 (1 + s)를 곱한다.
  - `BomApprovalServiceImpl`: 거절 대신 범위·줄 종류 검사를 한다.
  - `BomRequirementResponse.Line.scrapRate`를 더했다. 기존 생성자는 그대로 두었다.
  - 이 밖에 `BomLine`·`BomLineCreateRequest`의 주석을 고쳤다.
- **프런트:**
  - `useBoms.BomLineInput.scrapRate`, `BomRequirementLineDto.scrapRate`를 더했다.
  - `bomModel`: `scrapRateFromPercent`, `scrapLabel`을 더했고, `compareBoms`가 스크랩을 비교한다.
  - `BomPanel`: "Scrap percent" 입력과 표시를 더했다.
  - `BomRevisionCompare`: 비교 칸에 스크랩을 보인다.
- **테스트:** `BomScrapRateIntegrationTest`(새 파일), `BomIntegrationTest`(예시 교체), `bomModel.test.ts`, `e2e/bom-scrap-rate.spec.ts`(새 파일).
- **CSV(결정 9):** `BomLineImportRequest.Row.scrapPercent`(기존 생성자 유지), `BomLineImportService`의 퍼센트 검사, 프런트 `bomLinesFromCsv`의 열과 `scrap_rate` 거절. 테스트는 `BomLineImportIntegrationTest`에 한 건, `bomModel.test.ts`에 한 건, 모의 e2e에 파일 단계를 더했다.
