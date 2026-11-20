# 워크플로 발행 revision

상태: **구현(V20 2026-09-24, V21 생산 실행 고정).** 2026-10-02에 코드에서 다시 정리한 현행 문서다. 설계 근거는 [2026-09-24 위임 지시서 D7](../archive/2026-09-handoff/2026-09-24-agent-brief.md)(보관).

## 왜 필요한가

워크플로 초안은 계속 고쳐진다. 실행이 초안을 직접 읽으면, 실행 중이나 실행 뒤에 공정·포트·연결이 바뀌어 "그 실행이 어떤 정의로 돌았는가"를 재현할 수 없다. 발행 revision은 그 순간의 정의를 통째로 고정한 읽기 전용 사본이다.

## 모델

`workflow_revision`(V20):

- `revision_no`: 워크플로마다 1부터 증가한다.
- `status`: published 또는 retired.
- `schema_version`
- `snapshot_json`
- 발행한 사람·시각, 폐기한 사람·시각

`snapshot_json`(`WorkflowRevisionSnapshot`)에는 발행 순간의 다음이 들어 있다.

- 워크플로
- 캔버스 스냅샷과 시뮬레이션 설정
- 공정
- 포트(ProcessIo)
- 연결
- 주석
- 도형 편집 문서
- 안내 보기의 장(`guidedViews`, 2026-11-20, [ADR-037](../architecture/adr/ADR-037-guided-views.md)). 장이 없으면 필드가 없다. 그 전에 발행한 판에도 없다

세션 상태와 Redis 커서는 넣지 않는다.

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| V1 | 발행은 쓰기 권한. 같은 워크플로의 발행·폐기·실행 시작은 워크플로 행 잠금으로 줄을 세운다 | 403 |
| V2 | 발행 전에 [워크플로 검증](process-port-connection-contract.md#워크플로-검증과-발행)을 돌린다. 오류가 하나라도 있으면 발행하지 않는다(앞의 오류 3개를 메시지에 담는다). 경고만 있으면 발행한다 | 409 `Workflow has N error(s): …` |
| V3 | 발행 뒤에도 초안은 계속 고칠 수 있다. 이미 발행된 revision은 바뀌지 않는다 | |
| V4 | published만 retired로 바꿀 수 있다 | 409 `Only a published revision can be retired.` |
| V5 | 폐기된 revision으로는 새 실행(생산 실행·Flow Run)을 시작하지 않는다. 이미 시작된 실행은 그대로 그 revision을 따른다 | 409 `Retired workflow revisions cannot start new runs.` |
| V6 | 생산 실행은 시작 때 revision을 고정한다(`production_run.workflow_revision_id`, V21). 요청에 revision이 없으면 그 워크플로의 최신 published를 쓰고, 그것도 없으면 revision 없이 시작한다 | 없는 revision 404 |
| V7 | Flow Run은 시작 때 revision을 반드시 지정한다 | 없는 revision 404 |
| V8 | **에디터에서 발행**(2026-10-11, 프런트만): 같은 `POST …/revisions`(V1·V2)를 쓴다. 저장된 것을 발행하므로 아직 저장하지 않은 편집 요소는 들어가지 않는다. 거절(V2)되면 메시지를 보이고 워크플로 검사 패널을 열어 문제를 고를 수 있게 한다 | 서버 응답 그대로 |

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `POST /workflows/{workflowId}/revisions` | 쓰기 | 발행(V1·V2) |
| `GET /workflows/{workflowId}/revisions` | 읽기 | 최신 번호 먼저, 요약 |
| `GET /workflows/{workflowId}/revisions/{revisionId}` | 읽기 | 스냅샷 포함 |
| `POST /workflows/{workflowId}/revisions/{revisionId}/retire` | 쓰기 | 폐기(V4) |

## 화면

- Runs 화면에 발행 버튼과 revision 선택이 있다.
- 발행 버튼 아래 **Revisions (N published, M retired)**(접힘, 2026-10-03): 최신 번호 먼저 `v3 published by demo-owner 2026-10-03 07:05`, 폐기된 것은 흐리게 `· retired by … 시각`. published 줄에 **Retire**(확인창: 새 실행은 그 revision으로 시작할 수 없고, 이미 시작된 실행은 그대로) → V4. 폐기하면 시작 폼의 revision 선택에서 빠지고, 남은 published가 없으면 `No published revision`
- 에디터(2026-10-11, V8): **Workflow validation** 줄의 **Check workflow** 옆에 **Publish revision**(editor·owner만, viewer는 비활성)과 `Latest: v2 published by demo-owner 2026-10-11 09:00`(없으면 `Not published yet`). 발행하면 `Published v3.`와 **Start a run**(`/projects/{projectId}/runs?workflowId=…`, Runs의 워크플로 선택이 그 워크플로로 열림). 거절되면 빨간 서버 메시지와 함께 검사 패널이 열림(`EditorPublish`)
- ~~남은 것: 에디터에서 바로 발행~~ → V8(2026-10-11).

## 검증

- 에디터 발행(2026-10-11, V8): `revisionModel.test.ts` `latestPublished`(폐기 건너뜀, 가장 큰 번호). 모의 E2E `e2e/editor-publish.spec.ts`: 캔버스·편집 문서·revision·검사 모의 → `Latest: v1 …` → 첫 발행 409 → 경고 `Workflow has 1 error(s): …`·검사 패널 `1 errors, 0 warnings`·문제 버튼 → 다시 발행 → `Published v2.`·**Start a run** 주소·`Latest: v2`, 경고 사라짐. 서버는 바뀌지 않음
- 폐기 화면(2026-10-03): `revisionModel.test.ts`(발행·폐기 줄, 개수). 실 화면 `e2e/revision-retire.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): 새 프로젝트·워크플로·공정 하나를 API로 만들고 Runs에서 **Publish current workflow** → 선택에 `v1 · …` → Revisions 펼쳐 v1 **Retire** → `v1 retired`·`retired by demo-owner`, 선택 비활성·`No published revision`. 끝나면 프로젝트 삭제

- `WorkflowRevisionIntegrationTest`
- `RevisionPublishGateIntegrationTest`(오류가 있으면 발행 409)
- `ProductionRunRevisionIntegrationTest`(생산 실행의 revision 고정)
- `WorkflowValidationIntegrationTest`(경고만 있으면 발행)

## 관련

- 실행이 revision을 어떻게 쓰는지: [실행 모델](../architecture/execution-model.md) §2
- revision으로 도는 실행 기록: [Flow Run](flow-run.md)
