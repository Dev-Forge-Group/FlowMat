# 에디터 현재 상태

> **현행 문서** · 최종 확인 2026-10-02(코드 `45f5df0` + 작업 트리) · 에디터 구조·저장·동기화가 바뀌면 같은 변경에서 고친다.
> 2026-08 진행 기록(완료 항목 전체 목록, 버그 수정 경위): [archive/2026-08-editor/editor-current-state-2026-08.md](../archive/2026-08-editor/editor-current-state-2026-08.md)

워크플로 화면(`pages/workspace`)의 캔버스와 도형 편집 엔진의 현재 구조다. 경계 결정은 [ADR-0001](adr-0001-flowmat-editor-core-boundary.md)이다. 에디터는 Core 정의(공정·포트·연결)를 보여 주고 고치는 계층이며, 전면 교체하지 않는다([결정 인계](../architecture/decision-handoff.md) P2).

## 1. 두 층

| 층 | 무엇을 그리나 | 코드 | 저장 |
|---|---|---|---|
| 공정 그래프 | 공정 노드, 연결 | React Flow(`@xyflow/react`): `CanvasViewport.tsx`, `CanvasNode.tsx`, `CanvasEdge.tsx` | 워크플로 API(공정·포트·연결). 정의 계층이며 발행 revision의 대상 |
| 도형 편집 | 자유 도형, 선·커넥터, 글, 기존 주석 | `WorkspaceEditorLayer.tsx`가 React Flow `ViewportPortal` 안에 SVG 층을 그린다 | 아래 두 저장 모델 |

React Flow에는 공정 노드만 등록돼 있다(`flowmatNode`). 공정 노드를 다른 엔진으로 옮기는 것은 React Flow가 실제 요구를 막는다는 근거가 생길 때만 검토한다.

## 2. 도형 편집 엔진 (`flowmat_frontend/src/lib/flowmat-editor/`)

순수 TypeScript 코어다. React, React Flow, Zustand, React Query, API, STOMP를 import하지 않는다. `core/importBoundary.test.ts`가 이를 검사한다.

| 폴더 | 내용 |
|---|---|
| `model/` | `EditorDocument`, `EditorElement`, `Camera`, 직렬화, 병합, 복제, 그룹 연산, 커넥터 재계산(`ConnectorSync`) |
| `elements/` | rectangle, ellipse, polygon(triangle), line, freehand, text, group |
| `geometry/` | `Vec2`, `Box2`, `Matrix2D`, 경계, 적중 판정, 변환, 앵커 점 |
| `selection/`, `snapping/`, `history/` | 선택, 격자 스냅, 스냅샷 기반 실행 취소 |
| `renderer/svg/`, `react/` | SVG 렌더링, `SvgEditorSurface`·`SvgEditorReadOnlyLayer` |
| `adapters/` | 기존 주석 ↔ 편집 요소, 편집 문서 ↔ 백엔드 DTO |
| `core/`, `tools/` | `Editor`·`EditorStore`, 도구 |

`/editor-demo`는 이 엔진만 따로 돌려 보는 화면이다. `?workflowId=`를 주면 백엔드 편집 문서를 읽고 쓴다.

## 3. 저장 모델 두 개

| 모델 | API | 담는 것 | 비고 |
|---|---|---|---|
| 편집 문서(새 엔진) | `GET`·`PUT /workflows/{id}/editor-document` (V12 `workflow_editor_document`·`workflow_editor_element`) | rectangle, ellipse, polygon/triangle, line(커넥터 바인딩 포함), freehand, text, group | 문서 전체를 저장한다. `expectedVersion`이 다르면 409. 워크플로 행을 잠가 첫 저장이 겹치지 않게 한다 |
| 기존 주석(legacy) | `/workflows/{id}/annotations` `GET`·`POST`·`PATCH /{id}`·`DELETE /{id}`·`POST /batch` | `shape`(rectangle·ellipse·diamond), `freehand`, `text` | 새 도형은 여기에 저장하지 않는다. SVG 층에서 편집 문서 요소와 함께 보이고 고칠 수 있다 |

- 같은 id가 양쪽에 있으면 편집 문서 요소를 보여 준다.
- triangle, line, group은 기존 주석 API에 억지로 넣지 않는다.
- 커넥터는 편집 문서 요소끼리만 잇는다. 기존 주석 도형에는 잇지 않는다(의도된 제약).

### 저장 실패 처리 (2026-10-02)

편집 문서 저장은 보내기 전에 편집 결과를 화면 캐시에 먼저 넣는다. 그래서 드래그를 끝낸 순간 옛 위치로 튀지 않는다.

- 응답 없음·429·5xx로 실패하면 편집을 화면에 남기고 "Your change is kept and will be saved with your next edit."를 알린다. 다음 저장이 문서 전체를 보내므로 그 편집도 함께 저장된다(`keepsUnsavedEdit`).
- 409(다른 사람이 먼저 저장)와 그 밖의 4xx는 서버 문서를 다시 받아 보여 준다.
- 한계: 남겨 둔 편집은 다음 편집 전까지 저장되지 않는다. 그 사이 문서를 다시 받으면(창 포커스·실시간 동기화) 사라진다.
- 기존 주석 경로(`PATCH …/annotations/{id}`)는 바꾸지 않았다.

## 4. 실행 취소

| 대상 | 기록 |
|---|---|
| 편집 문서 요소 | 엔진의 스냅샷 기록(`HistoryManager`). 편집 중에는 Ctrl/Cmd+Z, Shift+Z, Y와 리본 Undo/Redo가 이쪽으로 간다 |
| 공정 노드·연결 | 기존 워크플로 명령 기록(`pages/workspace/model/commandHistory.ts`) |
| 기존 주석 | 실행 취소 없음. 지운 주석을 같은 id로 되살리는 API가 없다 |

## 5. 실시간 동기화

`entities/workflow/api/useWorkflowSync.ts`(STOMP)

- 그래프 변경 수신: 공정·연결·주석. 편집 문서가 바뀌면 백엔드가 `EDITOR_DOCUMENT_UPDATED`(payload 없음)를 보내고, 받은 쪽은 편집 문서를 다시 읽는다.
- 재접속 때도 편집 문서를 다시 읽는다.
- presence 메시지: `JOIN`, `LEAVE`, `HEARTBEAT`, `CURSOR_MOVED`, `NODE_EDITING`, `ANNOTATION_DRAWING`(손그림 미리보기)
- 편집 요소 단위의 동시 편집 병합은 없다. 충돌은 문서 단위 409로 처리한다. 충돌 모델(문서 단위 / 요소 patch / CRDT)은 사용자 결정 대기다([WORKBOARD](../status/WORKBOARD.md) D1, [C5 비교](../archive/2026-09-handoff/reports/C-collab-model-comparison.md)).

## 6. 화면 구성

- **리본**(`widgets/canvas-toolbar`, 탭 정의 `config/ribbonConfig.ts`)
  - Home: 도구(Pointer, Add Node), Undo/Redo, 자동 배치(TB·LR), 내보내기(JSON·PNG)
  - Annotate: 그리기 도구, 편집 문서 Save·Reload, 정렬·분배, Group·Ungroup, Duplicate·Delete·Front·Back, 격자
  - View: Fit View, Select All
  - Collaborate: presence
- 기존 상단 바 `workspace-topbar`는 아직 [`WorkflowCanvasPage.tsx`](../../flowmat_frontend/src/pages/workspace/ui/WorkflowCanvasPage.tsx)에 남아 있다. 리본 마이그레이션 Step 6에서 버튼 영역을 지운다([리본 계획](toolbar_ribbon_migration_plan.md) §7).
- 오른쪽 검사 패널: 공정(`NodeInspector`, 포트 편집 포함), 연결(`ConnectionInspector`), 편집 요소(채우기·선·두께·투명도·글·글자 크기, 복제·삭제·그룹·순서)
  - 포트 편집의 Item은 선택이다(2026-10-03, V42). 첫 선택지 "No item (data, file, API…)"를 고르면 생성 때 `itemId`를 보내지 않고, 수정 때 `clearItem`으로 연결을 지운다. 품목이 없는 프로젝트에서도 포트 폼이 보인다. 검증: `entities/workflow/model/portPolicy.test.ts`, `e2e/data-ports.spec.ts`(실 API)
- 워크플로 검증 결과 패널: 오류·경고 목록. 오류가 있으면 발행이 막힌다([워크플로 발행 revision](../domain/workflow-revision.md))
- PNG 내보내기는 `.react-flow__viewport`를 찍으므로 편집 요소도 함께 들어간다.
- 단축키: V(포인터), R/O/L/T(사각형·타원·선·글), Esc, Ctrl/Cmd+A·D·G·Shift+G·[·], Alt(스냅 끄기)

## 7. 테스트

- 엔진 단위 테스트 12개 파일(`lib/flowmat-editor/**/*.test.ts`), import 경계 검사 포함
- `pages/workspace/ui/CanvasViewport.retention.test.ts`: 공정 노드·연결이 계속 React Flow로 그려지는지, 편집 층이 덮어쓰지 않는지
- `pages/workspace/model/workspaceEditorDocument.test.ts`: 저장 실패 시 편집 유지 판단, 끌기(이동·크기)·복제·연결선·앞뒤 순서·주석 z-index·실행 취소 이력. 그리기 층(`WorkspaceEditorLayer`)은 상태를 들고 그리기만 하고, 문서 계산은 `model/workspaceEditorDocument.ts`가 한다
- 렌더러 성능 확인: `SvgRenderer.performance.test.ts`
- 브라우저 E2E는 워크플로 검증(`e2e/workflow-validation.spec.ts`)만 있다. 도형 편집 자체의 E2E는 없다.

## 8. 남은 일

| 일 | 상태 |
|---|---|
| 리본 마이그레이션 Step 3(Group/Ungroup 통합) 리뷰 | 2026-08-18부터 대기 |
| 리본 마이그레이션 Step 6(`workspace-topbar` 버튼 영역 제거) | Step 3 리뷰 뒤 |
| 다중 세션 실시간 동기화 실사용 확인(커넥터 바인딩 포함) | 코드 경로만 확인됨 |
| 저장 실패로 남겨 둔 편집의 재시도·보존 | 위 §3 한계 |
| 기존 주석을 편집 문서로 옮기기 | 정하지 않음. 지금은 두 모델이 함께 있다 |
| 편집 충돌 모델 결정(D1) | 사용자 결정 대기 |
| 엔진 지시서의 남은 계획(명령 스택·모델링 서비스·규칙/도구 레지스트리) | [CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md) "원래 백로그 대비 현황" 끝 문단 |

## 근거 문서

- 엔진 구현 지시서(2026-08, 동결): [reference/editor/](../reference/editor/FlowMat_Editor_Engine_Implementation_Directive.md)
- 2026-08 계획·교대 기록·진행 기록(레거시): [archive/2026-08-editor/](../archive/README.md)
- 캔버스 라이브러리 조사(2026-07, 레거시): [archive/2026-07-editor-research/](../archive/README.md)
