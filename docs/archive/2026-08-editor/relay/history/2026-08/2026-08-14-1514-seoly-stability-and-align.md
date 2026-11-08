---
updated_at: 2026-08-14T15:47:33+09:00
author: Seoly
branch: main
base_commit: e536290
head_commit: e536290
status: ready
topic: stability-and-align
---

# Relay Leg — 2026-08-14 / Seoly / stability-and-align

> **보관 문서(레거시)** · 현재 기준이 아니다. 구현·리팩토링으로 사실과 달라진 내용이 있을 수 있으니 작업 근거로 쓰지 않는다. 지금 기준은 [docs/README.md](../../../../../README.md), 이 폴더 안내는 [archive/README.md](../../../../README.md).

## 목표

- 직전 바통([`2026-08-14-0054-seoly-connectors.md`](./2026-08-14-0054-seoly-connectors.md))이 요청한 "다음 사람이 먼저 할 일" 세 가지(회전 리사이즈 시각 검증, 커넥터 화살표 방향 검증, Ribbon Arrange·Align·View 버튼 전수 검증)를 완료한다.
- 검증 과정에서 발견되는 결함이 있으면 근본 원인까지 수정한다.
- 백엔드 JSONB 컬럼 매핑 누락 패턴이 이번에 고친 필드 외에 더 있는지 전수 점검한다.

## 완료한 작업

- STS(Eclipse) 실행 환경에서 `bin/main`(STS 자체 컴파일 캐시)이 `editor` 패키지 전체를 누락한 채로 stale 상태였던 것을 발견했다. Gradle의 실제 빌드 결과물(`build/classes/java/main`)에는 문제가 없었다. STS의 **Project → Clean**으로 재빌드해 해결했다.
- 회전된 도형을 90도 회전 후 리사이즈해 반대쪽 모서리가 화면 좌표 기준으로 고정되는지 확인했다.
- 커넥터 화살표 marker(`#flowmat-editor-arrow`)가 선 3개 전부에 정상 참조되고 실제로 렌더링되는 것을 확인했다.
- Ribbon Arrange 그룹(Duplicate, Delete, Bring to Front, Send to Back)을 하나씩 클릭해 `order` 필드 변화와 저장을 확인했다.
- View 탭 Fit View가 React Flow viewport transform을 실제로 변경하는 것을 확인했다.
- **신규 발견**: Ribbon의 Align·Distribute 버튼이 legacy annotation에만 동작하고 새 backend editor element에는 전혀 동작하지 않는 것을 발견했다. `WorkspaceEditorCommandApi`에 `alignSelected`/`distributeSelected`를 새로 구현하고(`entities/canvas-annotation/model/annotationLayout.ts`의 기존 순수 함수 재사용), `WorkflowCanvasPage.tsx`의 `handleAlign`/`handleDistribute`가 선택된 엘리먼트 종류에 따라 두 경로 중 하나로 라우팅하도록 수정했다. 라이브로 Align Top과 Distribute Horizontally를 모두 확인했다.
- **신규 발견**: "Select All" 리본 버튼과 Ctrl/Cmd+A가 워크플로 노드가 많을 때(21개) 앱 전체를 크래시시키는 것을 발견했다. 진단 로그(`console.count`)로 근본 원인을 확정했다 — `CanvasViewport.tsx`, `WorkflowCanvasPage.tsx`, `CanvasNode.tsx` 세 곳이 `useWorkspaceStore()`를 셀렉터 없이 호출해 스토어 전체를 구독하고 있었고, `setMultiSelect()` 호출마다(값이 같아도) 전체 재구독 컴포넌트가 리렌더되면서 인라인 `onSelectionChange` prop이 매번 새 참조를 받아 React Flow의 내부 재구독·재호출 루프를 일으켰다. 세 파일 모두 필드별 셀렉터로 전환해 완전히 해결했다. `CanvasNode`는 워크플로 노드 하나당 렌더되는 컴포넌트라(이 워크플로에서 21개) 증폭 효과도 있었다.
- Group/Ungroup을 도형 3개로 실제 검증했다 — 그룹 생성 시 `group` 타입 엘리먼트와 `parentId` 연결을 확인했고, 해제 시 원상 복구를 확인했다.
- 라이브 테스트로 만든 도형 9개를 전부 삭제하고 빈 문서 상태로 저장되는 것을 확인했다.
- **백엔드 JSONB 매핑 전수 감사**: 마이그레이션에 선언된 jsonb 컬럼 33개 전부를 대응하는 Java 엔티티 필드와 대조했다. 이번에 고친 3개(`camera_json`/`geometry_json`/`style_json`) 외에 같은 버그를 가진 필드 4개를 추가로 발견해 수정했다 — `CanvasAnnotation.pointsJson`/`styleJson`(레거시 주석), `FlowRule.actionConfig`, `RunStateSnapshot.snapshotData`. 나머지 jsonb 컬럼은 대응하는 Java 필드가 아예 없거나(Hibernate가 건드리지 않으므로 안전) 대응 엔티티 클래스 자체가 아직 없었다(`project_export`, `cad_import_job`, `simulation_run`, `simulation_step`, `project_activity_log`).
- STOMP 실시간 동기화 코드 경로를 리뷰했다 — `WorkflowCanvasPage.tsx`의 `applyGraphChanges`가 그래프 변경 종류와 무관하게 매번 `invalidateEditorDocument()`를 무조건 호출하는 것을 확인했다. 이는 다른 세션에서의 커넥터 바인딩 변경도 결국 전체 재조회로 반영됨을 의미하지만, **실제 두 브라우저 세션 간 라이브 테스트는 실행하지 않았다.**
- `FlowRule.actionConfig` jsonb 수정을 `POST /flow-rules`에 중첩 JSON(`{threshold, nested:{ok}, tags:[...]}`)을 직접 보내 **라이브로 검증했다**(200 OK, 저장된 값이 그대로 왕복됨). 프론트엔드에 Rules 생성 화면이 없어(`/rules` 경로는 "scheduled for a future sprint") 브라우저에서 `access_token`을 읽어 API를 직접 호출하는 방식으로 확인했다.
- `RunStateSnapshot.snapshotData` jsonb 수정을 검증하려고 선행 리소스인 production run을 만들려다 **별개의 버그를 발견했다**: `production_run` 테이블에 `deleted_yn` 컬럼 자체가 없는데 `ProductionRun.java`는 `CreatedUpdatedAuditEntity`(→`SoftDeleteEntity`)를 상속해 이 컬럼을 요구한다. `GET /production-runs`와 `POST /production-runs/start` 둘 다 "column deleted_yn does not exist"로 500 에러가 난다 — production run 기능 자체가 조회·생성 모두 불가능한 상태였다. `CreatedUpdatedAuditEntity`/`SoftDeleteEntity`를 상속하는 엔티티 15개를 전부 찾아 각 테이블의 마이그레이션과 대조했고, `production_run`이 유일하게 이 컬럼이 빠진 테이블임을 확인했다. `V13__production_run_deleted_yn.sql` 마이그레이션을 추가해 다른 14개 테이블과 동일한 `deleted_yn char(1) DEFAULT 'N'` 패턴으로 맞췄다.

## 주요 변경 파일

- `flowmat_backend/.../production/domain/entity/RunStateSnapshot.java`: `snapshotData`에 `@JdbcTypeCode(SqlTypes.JSON)` 추가
- `flowmat_backend/.../rule/domain/entity/FlowRule.java`: `actionConfig`에 동일 어노테이션 추가
- `flowmat_backend/.../workflow/annotation/domain/CanvasAnnotation.java`: `pointsJson`/`styleJson`에 동일 어노테이션 추가
- `flowmat_frontend/.../CanvasViewport.tsx`: `useWorkspaceStore()` 셀렉터 전환, `onSelectAllReady`의 `setLocalNodes` 안정화
- `flowmat_frontend/.../WorkflowCanvasPage.tsx`: `useWorkspaceStore()` 셀렉터 전환, `handleAlign`/`handleDistribute` 라우팅 추가, Align/Distribute 버튼 `disabled` 조건 수정
- `flowmat_frontend/.../CanvasNode.tsx`: `useWorkspaceStore()` 셀렉터 전환
- `flowmat_frontend/.../WorkspaceEditorLayer.tsx`: `alignSelected`/`distributeSelected` 구현 및 `WorkspaceEditorCommandApi`에 추가
- `flowmat_backend/src/main/resources/db/migration/V13__production_run_deleted_yn.sql`: `production_run`에 누락된 `deleted_yn` 컬럼 추가 (신규 마이그레이션)
- `docs/archive/2026-08-editor/connector-line-progress-2026-08-14.md`: 이번 교대의 세부 로그 반영 (relay 체계 발견 전 사용하던 임시 진행 기록)

## 커밋

- 없음. 이 교대의 모든 변경은 아직 미커밋 상태다. 커밋과 push는 저장소 소유자가 직접 수행한다.

## 결정 사항과 이유

- Align/Distribute 로직은 새로 작성하지 않고 `entities/canvas-annotation/model/annotationLayout.ts`의 기존 순수 함수(`computeSelectionBounds`/`computeAlignedPosition`/`computeDistributedPositions`)를 그대로 재사용했다. `LayoutBox { id, x, y, width, height }` 형태만 맞추면 되고, 로직 중복을 피할 수 있기 때문이다.
- Align/Distribute 라우팅은 선택된 엘리먼트 개수(`editorSelection.elements.length >= 2` 또는 `>= 3`)로 판단해 새 엔진과 레거시 annotation 경로 중 하나를 고른다. 기존 Undo/Redo가 쓰던 `isEditorCommandContext` 라우팅과 같은 발상이며, 하나의 리본 버튼이 두 저장 모델을 모두 지원해야 하는 현재 구조에서 가장 적은 변경으로 두 경로를 살리는 방법이다.
- `useWorkspaceStore()`를 필드별 셀렉터로 바꾸는 수정은 세 파일(`CanvasViewport`, `WorkflowCanvasPage`, `CanvasNode`) 모두에 적용했다. `CanvasViewport`만 고쳤을 때는 크래시가 재발했는데, `WorkflowCanvasPage`도 같은 패턴으로 전체 구독 중이어서 부모 리렌더가 자식으로 계속 전파됐기 때문이다.
- 백엔드 jsonb 필드 수정은 이미 두 번(`WorkflowEditorDocument`/`WorkflowEditorElement`, `CanvasAnnotation`) 검증된 동일 패턴이라 라이브 브라우저 재현 없이 구조 분석만으로 적용했다. 세 테이블(`flow_rule`, `run_state_snapshot`, `canvas_annotation`) 모두 마이그레이션에 `jsonb`로 선언되어 있고 Hibernate 전역 JSON 타입 설정이 없어(같은 코드베이스의 이미 고쳐진 필드들과 동일 조건) 사실상 확실한 버그로 판단했다.

## 실행한 검증

| 검증 | 결과 |
|---|---|
| frontend `tsc --noEmit` | PASS (여러 차례, 매 변경 후) |
| frontend lint | PASS |
| frontend unit test | PASS, 87 tests |
| frontend build | PASS |
| backend `compileJava` | PASS |
| backend `test --tests *FlowRule* *RunStateSnapshot* *CanvasAnnotation*` | PASS (관련 테스트 실패 없음) |
| 회전 도형 리사이즈 (반대 모서리 고정) | PASS, 브라우저에서 픽셀 좌표로 확인 |
| 커넥터 화살표 marker 렌더링 | PASS, DOM 및 스크린샷 확인 |
| Duplicate / Delete / Front / Back / Fit View | PASS |
| Align Top (도형 2개, 새 엔진) | PASS, y좌표 일치 및 저장 확인 |
| Distribute Horizontally (도형 3개, 새 엔진) | PASS, 간격 균등화 및 저장 확인 |
| Group → Ungroup (도형 3개) | PASS, `group` 엘리먼트 생성·해제 확인 |
| Select All (21개 노드) 반복 클릭 | PASS, 크래시 없음, 콘솔 에러 0건 |
| 테스트 데이터 삭제 후 재조회 | PASS, 빈 문서 확인 |
| `POST /flow-rules` (`actionConfig`에 중첩 JSON) | PASS, 200 OK, 저장값 왕복 확인 |
| `GET/POST /production-runs` (V13 이전) | FAIL(예상됨), "column deleted_yn does not exist" — 버그 재현 확인 |

## 실행하지 못한 검증

- 두 브라우저 세션 간 STOMP 실시간 동기화의 실제 라이브 테스트. 코드 경로(`invalidateEditorDocument()`가 모든 그래프 변경에 무조건 호출됨)만 확인했다. 다음 사람은 두 개의 로그인 세션을 열고 한쪽에서 도형을 옮겨 다른 쪽 커넥터가 따라오는지 확인해야 한다.
- `RunStateSnapshot.snapshotData`의 라이브 검증은 아직 못 했다. 선행 리소스인 production run을 만들 수 없어서(위 `production_run.deleted_yn` 버그) 막혔었고, `V13` 마이그레이션을 추가한 뒤 **재시작 전에 이 교대를 마감**했다. 다음 사람은 STS Clean 후 재시작 → `POST /production-runs/start` → `POST /run-state-snapshots` 순서로 확인해야 한다.
- `CanvasAnnotation.pointsJson`/`styleJson`의 라이브 재현은 하지 않았다. 사이드바 "Shape" 주석 도구로 두 차례 배치를 시도했으나 클릭이 반영되지 않았고, 시간 관계상 재시도하지 않았다. `compileJava`와 관련 단위 테스트 통과로만 검증됐다.
- Group/Ungroup을 제외한 나머지 조작(리사이즈, 회전)에서 그룹 내부 도형이 함께 움직이는지는 확인하지 않았다.

## 알려진 사항

- adapter 수정 전에 저장된 커넥터는 `startBinding`/`endBinding`이 `null`로 굳어 있다. 이번 교대에서 테스트 데이터를 전부 삭제해 `wf_demo_main`에는 더 이상 남아 있지 않다.
- 커넥터는 backend editor element끼리만 연결된다. legacy annotation 도형에는 연결할 수 없다 (의도된 범위, 직전 교대에서 이미 결정).
- Playwright 확장 브리지 모드에서 "기존 도형을 클릭해 선택"하는 동작이 이번 교대에서도 간헐적으로 반응하지 않았다. 재시도하면 대부분 성공했다. 마퀴 선택(shift-drag)이 개별 클릭보다 안정적이었다.
- `production_run.deleted_yn` 마이그레이션(`V13`)은 작성·컴파일만 됐고 **아직 적용 확인 전이다** — 이 교대는 STS 재시작 전에 마감했다. 다음 사람이 재시작 후 가장 먼저 이 마이그레이션이 정상 적용됐는지(`GET /production-runs`가 더 이상 500을 내지 않는지) 확인해야 한다.

## 다음 작업

1. STS에서 Project → Clean 후 재시작해 `V13__production_run_deleted_yn.sql`을 적용한다.
2. `POST /production-runs/start`로 production run을 하나 만들고, `POST /run-state-snapshots`로 `snapshotData`에 중첩 JSON을 넣어 저장되는지 확인한다 (`FlowRule`과 동일한 방식 — 프론트 화면이 없으므로 `access_token`으로 API 직접 호출).
3. 두 브라우저 세션을 열어 커넥터 바인딩의 실시간 동기화를 직접 확인한다.
4. `CanvasAnnotation.pointsJson`/`styleJson`도 legacy Shape 주석 도구로 라이브 재현을 시도한다.
5. 코드 변경을 기능 단위로 커밋한다. 커밋 후보 그룹은 [`connector-line-progress-2026-08-14.md`](../../../connector-line-progress-2026-08-14.md)에 정리되어 있던 구성을 참고하되, 이번 교대분(align/distribute, select-all 크래시, 신규 jsonb 4건, production_run 마이그레이션)을 추가로 나눠 커밋한다.

## 검토받고 싶은 부분

- Align/Distribute의 라우팅 기준(`editorSelection.elements.length >= 2/3`)이 legacy annotation과 backend editor element가 섞여 선택된 경우 우선순위가 맞는지 — 현재는 새 엔진 쪽 개수만 보고 판단하며, annotation이 함께 선택되어 있어도 무시한다.
- `useWorkspaceStore()`의 셀렉터 미사용 패턴이 이 세 파일 외에 더 있을 가능성 — 이번 조사는 `useWorkspaceStore()`(인자 없는 호출) 리터럴 검색으로만 확인했다.

## 작업 트리 상태

- 상태: dirty
- 미커밋 파일 (전부 이 세션에서 작성/수정, 소유자: 세션 작업자):
  - `docs/archive/2026-08-editor/connector-line-progress-2026-08-14.md`
  - `docs/archive/2026-08-editor/relay/BATON.md`
  - `docs/archive/2026-08-editor/relay/history/2026-08/2026-08-14-1514-seoly-stability-and-align.md` (본 문서)
  - `docs/editor/current-state.md`
  - `flowmat_backend/modules/production/src/main/java/org/myweb/flowmat/domain/production/domain/entity/RunStateSnapshot.java`
  - `flowmat_backend/modules/rule/src/main/java/org/myweb/flowmat/domain/rule/domain/entity/FlowRule.java`
  - `flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/annotation/domain/CanvasAnnotation.java`
  - `flowmat_backend/src/main/resources/db/migration/V13__production_run_deleted_yn.sql`
  - `flowmat_frontend/src/pages/workspace/ui/CanvasNode.tsx`
  - `flowmat_frontend/src/pages/workspace/ui/CanvasViewport.tsx`
  - `flowmat_frontend/src/pages/workspace/ui/WorkflowCanvasPage.tsx`
  - `flowmat_frontend/src/pages/workspace/ui/WorkspaceEditorLayer.tsx`
- 원격 반영: 없음. base/head 모두 `e536290`이며, 위 변경은 그 위에 아직 커밋되지 않은 상태다.

## 참고 자료

- [`../../BATON.md`](../../BATON.md)
- [`../../../current-state.md`](../../../../../editor/current-state.md)
- [`../../../connector-line-progress-2026-08-14.md`](../../../connector-line-progress-2026-08-14.md)
- [`../../../adr-0001-flowmat-editor-core-boundary.md`](../../../../../editor/adr-0001-flowmat-editor-core-boundary.md)
- [`2026-08-14-0054-seoly-connectors.md`](./2026-08-14-0054-seoly-connectors.md) (직전 교대)
