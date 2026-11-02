# 품목 일괄 등록·수정 (CSV)

상태: **구현(2026-09-25).** 새 테이블·마이그레이션 없음.

## 목적

처음 도입할 때나 단가·안전재고를 한꺼번에 고칠 때, 품목을 스프레드시트로 내보내고 고친 뒤 다시 올립니다.

## 파일 형식

첫 줄은 열 이름, 한 줄에 품목 하나입니다. 내보내기는 아래 순서로 쓰고, 가져오기는 이름으로 찾으므로 순서는 상관없습니다(대소문자·공백·`-` 무시).

| 열 | 다른 이름 | 뜻 |
|---|---|---|
| `item_code` | `code` | **필수.** 이 값으로 기존 품목을 찾음 |
| `item_name` | `name` | 새 품목이면 필수 |
| `item_type` | `type` | |
| `category` | `resource_category` | |
| `unit` | `unit_code` | 단위 **코드**(`kg`, `ea` …, 대소문자 무시). 활성 단위여야 함 |
| `status` | `item_status` | active·inactive·discontinued 중 하나([품목 상태](item-status.md)), 그 밖은 줄 문제 |
| `lot_tracked` | `lot`, `lot_manage_yn` | Y/N, yes/no, true/false, 1/0 |
| `safety_stock` | `safety_stock_qty` | 0 이상 |
| `lead_time_days` | `lead_time` | 0 이상 정수 |
| `unit_cost` | `cost` | 0 이상 |
| `group` | `item_group` | 품목 그룹(50자) — 이하 여섯 열은 [품목 상세](item-details.md) |
| `spec` | `specification` | 규격(200자) |
| `barcode` | `ean`, `upc` | 바코드(100자). I10 |
| `sku` | | SKU(100자) |
| `storage` | `storage_condition` | 보관 조건(100자) |
| `description` | `item_desc`, `desc` | 설명(2000자, 줄바꿈 가능) |
| `purchase_unit` | `pack_unit` | 구매 단위(20자, 예 bag) — [품목 상세 "구매 단위"](item-details.md) |
| `purchase_unit_qty` | `units_per_pack`, `pack_size` | 구매 단위 하나에 든 재고 단위 수(0 초과). 구매 단위가 파일이나 품목에 있어야 함 |

- 모르는 열은 무시하고, 화면에 "ignored columns"로 알려 줍니다.
- 따옴표 규칙은 RFC 4180(쉼표·줄바꿈·`""`)이고, 앞의 BOM은 버립니다.
- 숫자는 소수점에 `.`을 쓰고, 천 단위 쉼표는 세 자리마다 쓸 수 있습니다(예: `1,234.5`). CSV에서 쉼표가 든 수치는 셀을 따옴표로 감쌉니다. `1,2` 같은 잘못된 그룹 표기는 값이 조용히 바뀌지 않도록 줄 오류로 처리합니다.
- 내보낸 파일(`items-YYYY-MM-DD.csv`)을 그대로 다시 올리면 모두 "unchanged"입니다.

## API

`POST /items/import` — 쓰기 권한(editor 이상)

```json
{ "projectId": "...", "dryRun": true, "rows": [{ "itemCode": "FLR-1", "itemName": "Flour", "unitCode": "kg", "unitCost": "1.2" }] }
```

- 값은 모두 **문자열**입니다. 숫자가 틀려도 요청 전체가 400이 되지 않고 그 줄의 문제로 보고됩니다.
- 응답: `created`, `updated`, `unchanged`, `errors`, `applied`, 줄별 `rows[]`(`row`는 데이터 줄 번호 1부터, `action`은 create·update·unchanged·error, `message`는 바뀌는 항목이나 문제).
- 한 번에 1000줄까지. 빈 파일은 400.

## 규칙

| # | 규칙 | 이유 |
|---|---|---|
| I1 | **모든 줄을 먼저 검사**하고, 한 줄이라도 문제가 있으면 `dryRun=false`여도 아무것도 저장하지 않음 | 반쯤 들어간 파일을 고치는 일이 없게 |
| I2 | 저장은 기존 품목 등록·수정 서비스를 한 트랜잭션에서 차례로 부름 | 음수 금지, 단위 확인 같은 규칙이 한 곳에만 있게 |
| I3 | 기존 품목은 **빈 칸이면 그대로 둠.** 새 품목은 평소 기본값(유형 generic, 분류 material, 상태 active, LOT N) | 필요한 열만 담은 파일로도 고칠 수 있게 |
| I4 | 같은 코드가 파일에 두 번 나오면 두 번째 줄이 오류 | 어느 값이 맞는지 알 수 없음 |
| I5 | 같은 코드의 품목이 프로젝트에 이미 여럿이면 오류 | DB에 코드 UNIQUE가 없어(V1) 어느 품목인지 정할 수 없음. 화면에서 먼저 정리 |
| I9 | 품목 등록(`POST /items`, 가져오기의 새 품목 포함)은 프로젝트의 활성 품목이 이미 쓰는 코드면 409(대소문자 그대로 비교). 삭제된 품목의 코드는 다시 쓸 수 있음. 코드는 품목 수정(`PUT /items/{id}`의 `itemCode`, 편집 양식의 Code)으로 바꿀 수 있고 같은 검사를 받음. 품목은 어디서나 ID로 참조되므로 코드를 바꿔도 연결은 그대로 | DB UNIQUE는 새 마이그레이션이 필요해 서비스에서 막음. 이미 있던 중복은 Items 탭 위 경고("Some codes are used by more than one item")와 I5로 드러나며, 그중 하나의 코드를 바꿔 풀면 됨. `ItemCodeIntegrationTest` 2건, `listFilterModel.test.ts`의 `duplicateCodes` |
| I6 | 재고 행이 있는 품목의 LOT 관리 변경은 오류 | 품목 화면과 같은 규칙(계약서 §6) |
| I7 | 글자 수는 DB 열 길이(코드 50, 이름 100, 유형 50, 분류 30, 상태 20)로 미리 검사, 숫자는 `numeric(14,4)` 범위 | 저장 중 DB 오류(500) 대신 줄 오류로 |
| I8 | 품목 삭제는 가져오기로 하지 않음 | 삭제는 사용 중 검사가 필요한 별도 동작 |
| I10 | 상세 열도 빈 칸이면 그대로(I3). 상세는 통째로 저장되므로 빈 칸은 저장된 값으로 채워 보냄. 바코드가 파일에 두 번 나오거나 다른 품목이 이미 쓰면 그 줄이 오류. 바코드를 한 품목에서 다른 품목으로 옮기려면 두 번에 나눠(먼저 화면에서 지우고) 가져옴 | 품목 화면과 같은 바코드 규칙을 저장 전에 줄 오류로 보이려고. 옮기기는 드물어서 보수적으로 막음 |
| I11 | 품목 CSV는 같은 필드의 열을 하나만 허용. unit_cost·cost, barcode·ean, item_code·code처럼 별칭과 대소문자 중복도 파일 오류 | 단가·바코드·매칭 품목이 마지막 값으로 조용히 바뀌는 것을 방지 |
| I12 | 품목 CSV의 파일 선택·취소는 이전 읽기·검사 결과와 오류를 무효화. 검사 중 Cancel 가능, 읽기 실패 안내, 실제 저장 중 파일·취소 잠금 | 다른 파일의 검사 결과와 표시 이름이 섞이거나 저장 중 새 선택이 지워지지 않음 |

## 화면

재고 → Items 탭 목록 위
- **Export CSV:** 지금 품목 전체를 위 형식으로 받습니다.
- **Import CSV:** 파일을 고르면 바로 검사(dry run)하고 "N to add, N to update, N unchanged, N with problems"와 줄별 표(파일 줄 번호, 코드, 동작, 바뀌는 항목 또는 문제)를 보여 줍니다.
- 문제가 없을 때만 **Save N changes**가 켜집니다. 저장하면 "Saved: …"를 보여 주고 목록을 새로 고칩니다.
- 문제가 있으면 파일을 고쳐 다시 고릅니다.

## BOM 자재 (초안 BOM)

`POST /boms/{bomId}/lines/import` — 쓰기 권한. **초안(draft)** BOM만(그 밖은 409).

```json
{ "dryRun": true, "replace": false, "rows": [{ "itemCode": "FLR-1", "quantity": "5000", "unit": "g" }] }
```

- 파일 열: `item_code`(또는 `code`, `material`), `quantity`(`qty`), `unit`(`unit_code`), 선택 `note`, 선택 `type`(`line_type`, `kind`: 비우면 material, by_product, waste — [부산물](bom-by-products.md), 2026-10-03), 선택 `phantom`(`is_phantom`: Y/N, [다단계 BOM](multi-level-bom.md) P1, 2026-10-10 2cr), 선택 `scrap_percent`(`scrap`, `scrap_pct`, `scrap %`: 계획 스크랩 퍼센트, `5`나 `5%`, 0–100·소수 둘째 자리까지·자재 줄만, [ADR-031](../architecture/adr/ADR-031-bom-scrap-rate.md) 결정 9, 2026-11-02). 세 필수 열이 없으면 화면에서 바로 알려 줍니다. `scrap_rate`·`scrap_factor` 열은 0–1 비율로 읽힐 수 있어 파일째 거절합니다. 대체 자재는 화면에서만 넣습니다. `substitute…`·`alternate…`·`alternative…` 열이 있으면 조용히 버리지 않고 파일째 거절합니다. 선택 자재도 화면에서만 표시하며 `optional`·`is_optional`·`optional_yn` 열은 같은 식으로 거절합니다([ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md) 결정 10, 2026-11-02)([ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md) 결정 3, 2026-11-02).
- 수량은 소수점에 `.`을 쓰며, 천 단위 쉼표는 세 자리마다 쓸 수 있습니다(예: `1,234.5`). 쉼표가 든 CSV 셀은 따옴표로 감싸야 합니다. `1,2` 같은 잘못된 쉼표 표기는 줄 오류입니다.
- `replace=false`면 지금 자재에 더하고, `true`면 지금 자재를 모두 지우고 파일 내용으로 바꿉니다.
- 줄마다 **승인 검증 중 한 줄에 관한 규칙**을 미리 봅니다(계약서 §5): 품목 코드가 있고 하나뿐, 수량은 소수점 네 자리 반올림 후 0보다 크고 `numeric(14,4)` 범위 안에 있음, 단위가 자재 단위로 바뀜, 이 BOM이 만드는 품목이 아님, 파일 안이나 남는 자재와 겹치지 않음, 그 자재에 승인된 BOM이 없음(1차는 단일 단계).
- 한 줄이라도 문제가 있거나 dry run이면 저장하지 않습니다. 저장은 평소 자재 추가·삭제를 한 트랜잭션에서 부릅니다. 한 번에 500줄까지.
- 화면: BOMs 탭 → 초안 BOM 상세 → **Materials from CSV**. 파일을 고르면 검사 결과("N to add, N to remove, N with problems")와 문제 줄을 보여 주고, 문제가 없으면 **Add N materials**(또는 **Replace materials**)로 저장합니다. "Replace current materials"를 바꾸면 다시 검사합니다.
- 검증: `BomLineImportIntegrationTest` 1건 — 6줄(좋은 줄, 이미 있는 자재, 제품 자신, 승인 BOM이 있는 반제품, 없는 코드, 수량 0·단위 안 맞음) → 오류 5, 저장 안 됨. 좋은 줄만 → 추가(`g` 그대로). replace dry run → removed 2, 저장 안 됨. replace 저장 → 2줄. 가져온 초안이 승인까지 통과. 승인된 BOM·다른 BOM 409. `bomModel.test.ts`의 `bomLinesFromCsv` 2건.
- 스크랩 열(2026-11-02, ADR-031 결정 9): `BomLineImportIntegrationTest.aScrapColumnTakesPercentsOnMaterialLines` — 150·-1(범위), 2.555(자리), 폐기물 줄 5(자재 줄만), `five%`(숫자 아님) → 오류 5. ` 5 % `·`2.25`·폐기물 0·빈 칸 저장 → 0.05·0.0225·0. 100%는 통과하고 초안이 제출된다. `bomModel.test.ts`가 `Scrap %` 열 읽기, 별칭 중복, `Scrap Rate` 열 거절을 보고, 모의 e2e `bom-scrap-rate.spec.ts`가 `scrap_rate` 파일 거절(요청 없음)과 `scrapPercent: '5%'` 전송을 본다.

## 검증

- `ItemImportIntegrationTest` 1건(실제 Postgres)
  - 6줄 파일(새 품목, 단가 수정, 같은 코드 두 번, 없는 단위, 음수 단가·소수 리드타임, 이름 없는 새 품목) → 오류 4, `applied=false`, 아무것도 저장 안 됨
  - 좋은 두 줄만 → 저장. 새 품목의 단위 `unit_kg`(코드 `KG`), LOT Y, 단가 1.5, 안전재고 5, 리드타임 3. 기존 품목 단가 2, 이름 그대로
  - 같은 파일 다시 → unchanged 2
  - 재고 있는 품목의 LOT 변경 → 오류. 이름 변경 dry run → updated 1이지만 저장 안 됨
  - 빈 파일 400, 외부인 403
- `itemCsvModel.test.ts` 4건: 따옴표·줄바꿈·BOM 파싱, 열 이름·별칭 매핑과 무시한 열, 필수 열 없음·빈 파일, 내보내기 → 다시 읽기(상세 열, 쉼표·줄바꿈 든 설명 포함)
- `ItemDetailsIntegrationTest.theImportMergesDetailsAndGuardsBarcodes`: 규격만 적은 줄은 규격만 바뀌고 그룹·바코드 유지, 다른 품목의 바코드와 파일 안 중복 바코드는 줄 오류, 다시 올리면 unchanged

### CSV 열 중복 (2026-10-05, §2 2be)

`itemCsvModel.test.ts` 7건(단가·바코드·품목 코드 별칭 중복 3건 추가). 수정 전 세 경로가 마지막 값으로 덮어써졌습니다. 기존 별칭·열 순서·모르는 열 무시는 유지합니다. 품목·입고·실사 관련 모델 합계 21건, 라인 100%·분기 80.80%·80% 기준 통과, 전체 프런트 457건·타입·린트·빌드 통과. 서버·마이그레이션 변경 없음.
### 품목 파일 선택·검사 회귀 (2026-10-05, §2 2bf)

- `e2e/item-import-input.spec.ts` 가짜 API 6건: 파일 읽기 역순·서버 검사 역순·검사 중 취소·파일 읽기 실패·저장 중 입력 잠금 5건을 수정 전 재현, 중복 단가 열 거절과 수정 파일 저장까지 검증. 최신 파일의 단가 9만 한 번 저장합니다. 취소는 dry run 결과를 화면에서 무시하며 이미 시작한 실제 저장의 롤백 기능이 아닙니다.
- 최초 테스트 설정에서 이름 변경 후 남은 변수 때문에 수집 후 실행 오류가 발생했습니다. 설정을 고친 뒤 업무 실패 5건만 재현해 제품 수정 근거로 썼습니다.
- 6건과 프런트 전체 457건·타입 검사·린트·빌드 통과. 서버·마이그레이션 변경 및 실제 DB 요청 없음.
### BOM 자재 CSV 파일·교체 옵션 방어 (2026-10-05, §2 2bg)

- 파일 선택·Cancel·Replace current materials 변경마다 이전 검사 응답을 무효화합니다. 새 검사 동안 이전 미리보기는 비우고 적용하지 못하게 합니다. 파일 읽기가 끝난 뒤에는 현재 교체 옵션으로 검사하므로, 읽기 중 바꾼 옵션도 반영합니다.
- 실제 저장 중에는 파일·교체 옵션·Cancel을 잠그고 중복 제출을 막습니다. 검사 중에는 파일 변경·옵션 변경·취소가 가능합니다. 파일 읽기 실패는 안내하고 다시 고를 수 있습니다. Cancel은 실제 저장을 롤백하지 않습니다.
- 자재 CSV의 동일 필드 열 중복도 별칭까지 거절합니다(예: quantity/qty, unit/unit_code, type/kind). BOM·수량·부산물 규칙이나 서버 API는 변경하지 않았습니다.
- `bomModel.test.ts` 14건(중복 열 3건 추가), 라인 100%·분기 93.47%·80% 기준 통과. `e2e/bom-import-input.spec.ts` 가짜 API 8건: 파일·검사 역순·취소·읽기 실패·저장 잠금·옵션 검사 역순의 업무 실패 6건과 중복 열 실패 1건을 수정 전 재현. 읽기 중 교체 옵션 변경은 서버로 보낸 replace 값까지 검증합니다. 초기 읽기 옵션 테스트의 지연 설정을 정리한 뒤 해당 1건도 다시 통과했습니다.
- 전체 프런트 460건·타입 검사·린트·빌드 통과. 브라우저의 BOM은 미리 준비한 메모리 fixture이며 생성 요청은 하지 않습니다. 모든 API 요청을 모킹해 실제 DB·BOM 삽입·실제 로그인 없이 검증했습니다. 백엔드·마이그레이션 변경 없음.