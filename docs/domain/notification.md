# 알림 (Notification)

> **현행 문서** · 2026-10-11 · 상태: **구현(2026-10-11, 세션 1 리드, WORKBOARD §2 2dg) — 로컬 격리 검증만, 개발 DB에 V74 미적용.**

## 왜 필요한가

NCR 조치의 담당자나 창고 작업의 담당자를 정해도, 그 사람은 화면을 열어 거르기 전에는 알 수 없었다([부적합](nonconformity.md) "이후", [창고 작업](warehouse-task.md) W7 "알림은 없음"). V1부터 비어 있던 `notification` 표를 처음으로 쓴다.

## 범위

- **직접 맡긴 일**: 다른 사람이 나에게 준 일. 받는 사람이 정해져 있으므로 구독 설정이 필요 없다.
- **재고 경보 → 프로젝트 소유자**(2026-10-11 사용자 결정, NT7): 받는 사람을 소유자로 고정해 구독 설정 없이 보낸다. 다른 사람에게 퍼지는 알림(구성원별 선택, 기한 지남 등)은 구독 설정을 정한 뒤에 한다.
- **승인 요청 → 소유자, 결정 → 요청한 사람**(2026-10-13 사용자 결정 "Both ways", NT8): 받는 사람이 규칙으로 정해져(프로젝트 소유자, 제출·요청한 사람) 구독 설정 없이 보낸다.
- **고르는 알림**(2026-10-13 사용자 결정, NS1–NS9): 그 밖의 재고 경보, 지난 NCR 효과 확인, 마감이 다가온 작업지시는 각자 프로젝트마다 골라 받는다.
- 앱 안(Home)에서만 보인다. 메일·Slack은 보내지 않는다(전역 `SlackNotificationService`는 지금처럼 프로젝트 초대만).

## 규칙

| # | 규칙 |
|---|---|
| NT1 | **저장:** V1 `notification`(받는 사람, 종류, 제목 200자, 본문, 무엇을 열지 `ref_type`·`ref_id`, 읽음·읽은 시각, 만든 시각)에 V74 `project_id`(그 일의 프로젝트)와 (받는 사람, 만든 시각) 색인. 지우지 않는다 |
| NT2 | **만드는 곳**(일과 같은 트랜잭션이라 일이 거절되면 알림도 없다): NCR 조치 추가 때 담당자(`ncr_action`, 제목 `Action 1 of NCR-0003 is yours`, 본문 `내용 · due 2030-01-31`, ref `nonconformity`), 창고 작업 배정 때 새 담당자(`warehouse_task`, `Putaway task WT-0007 is yours`, 본문 `From DOCK-1 to SHELF-2.`, ref `warehouse_task`), 작업지시를 만들거나 초안을 고칠 때 새로 적힌 담당자(`work_order`, `Work order WO-0012 is yours`, 본문 작업지시 제목, ref `work_order`, 2dh). 작업지시 담당 칸은 자유 글이라(조 이름 등) 그 프로젝트를 읽을 수 있는 사람의 ID일 때만 알린다. 스스로에게 준 일과 같은 사람으로 다시 배정한 것은 알리지 않는다. 다른 컨텍스트는 공개 API `NotificationCommand.notifyAssignee`만 부른다(ADR-002) |
| NT3 | **API**(로그인한 본인 것만): `GET /notifications?unreadOnly=`(최신 100건, 새것부터), `GET /notifications/unread-count` → `{unread}`, `POST /notifications/{id}/read`(남의 것·없는 것 404, 다시 읽어도 그대로), `POST /notifications/read-all` → `{marked}` |
| NT4 | **프로젝트를 더 읽을 수 없으면**(소유자도 활성 구성원도 아니거나 프로젝트가 삭제됨) 그 프로젝트의 알림은 목록·개수에서 빠지고 읽음 표시도 404. 판단은 project 공개 API `ProjectMemberQuery.canRead`(새로 더함, 프로젝트당 한 번만 물음) |
| NT5 | **화면:** Home 인사말 아래 **Notifications** — `2 unread`/`All read`, 최신 20건, 안 읽은 것은 보라 테두리와 `New`, 본문, **Open**(NCR → 재고 Quality 탭, 창고 작업 → Tasks 탭, 작업지시 → Runs의 Work orders, 열면서 읽음), **Mark read**, **Mark all read**. 1분마다 다시 읽음. 없으면 `Nothing new for you.` 목록 키는 사람마다 따로라 다른 사람으로 로그인해도 앞 사람 목록이 보이지 않는다 |
| NT7 | **재고 경보가 열리면 소유자에게**(2026-10-11, 마이그레이션 없음): [재고 경보](stock-alert.md)가 새로 열릴 때(이동 때든 주기 점검 때든) 그 프로젝트 소유자에게 `stock_alert`(제목 `Low stock: FLOUR at WH-A`·`Over the maximum: …`·`Expiring stock: …`, 본문은 경보 문장, ref 경보 ID, Open → 재고 Stock 탭). 소유자가 직접 재고를 움직여 열린 경보도 알린다(경보는 기록이라서, 공개 API `NotificationCommand.tell`). 열린 채 심각도만 바뀌면 다시 알리지 않고, 닫혔다가 다시 열리면 새로 알린다. 소유자는 project 공개 API `ProjectMemberQuery.ownerOf` |
| NT8 | **승인 알림**(2026-10-13, V78, 2dr): BOM revision 제출 → 소유자 `bom_approval`(`BOM Bread v3 waits for your approval`, 본문 `Submitted by <사용자>.`, ref `bom`), 승인·반려 → 제출한 사람 `bom_decision`(`… was approved`·`… was rejected`, 본문은 승인 메모나 `Approved.`, 반려 사유). 완료 실행 보정 요청 → 소유자 `correction_approval`(`Correction #2 of RUN-… waits for your approval`, 본문 사유, ref `production_run`), 승인·반려 → 요청한 사람 `correction_decision`(본문 `Its changes are applied.`나 반려 메모). 자기가 한 요청·결정은 알리지 않는다(NT2와 같음). 제출한 사람은 V78 `bom_header.submitted_by`·`submitted_at`에 남는다(V78 전 제출의 결정은 알림 없음). Home **Open**: 보정은 그 실행 상세, BOM은 재고 BOM 탭 |
| NT6 | 개발 DB(5434)에는 V74를 적용하지 않는다. Testcontainers와 격리 복사본으로만 검증한다 |

## 검증

- `NotificationIntegrationTest`: 새 활성 구성원에게 NCR 조치 → 그 사람에게 1건(종류·제목·본문·프로젝트·ref·안 읽음), 소유자가 자기에게 준 조치는 알림 없음 → 창고 작업을 같은 사람에게 두 번 배정 → 1건만 더해 안 읽음 2, 최신이 창고 작업(`… to SHELF-….`) → 소유자가 그 사람 알림 읽기 404 → 본인 읽기(읽음·시각) → 안 읽은 것 1 → 모두 읽기 1건 → 0 → 구성원을 `removed`로 바꾸면 목록 0·읽기 404. 둘째(2dh): 담당자가 구성원인 작업지시 → `work_order` 1건(제목·본문·ref), 같은 사람으로 초안 저장·자유 글 `Night crew`로 바꾸기는 더 알리지 않음. NCR·창고 작업·프로젝트 접근·아키텍처·마이그레이션과 함께 96건 실패 0, 전체 백엔드 1,272건 실패 0(2dg까지), 2dh 뒤 알림·작업지시·아키텍처 136건 실패 0.
- 재고 경보(2026-10-11, NT7): `NotificationIntegrationTest` 셋째 — 최소 10인 재고 20에서 15·5 출고 → 소유자 알림 1건(`Low stock: ALR-… at WH-…`, 본문 `Available 5 is below the minimum 10.`, ref `stock_alert`) → 30 입고로 닫히고 25 출고로 다시 열리면 2건. 재고·경보·알림·아키텍처 60건 실패 0.
- 승인(2026-10-13, NT8): `ApprovalNotificationIntegrationTest` 2건 — editor 보정 요청 → 소유자 1건(제목·사유·ref·프로젝트), editor 자신은 0 → 반려 → editor `was rejected`·메모 → 다시 요청·승인 → editor 2건(최신 `was approved`·`Its changes are applied.`), 소유자 2건 → 소유자가 스스로 요청·승인하면 아무에게도 없음. BOM: editor 제출 → 소유자 `bom_approval`·`Submitted by …` → 반려 → editor `was rejected`·사유 → 재제출·승인 → editor 최신 `was approved`·`Approved.`, 소유자 2건. 전체 백엔드 1,303건 실패·건너뜀 0(격리 복사본, 2dr·V78 포함). `notificationModel.test.ts`(보정 → 실행 상세, refId 없으면 링크 없음, BOM → BOM 탭).
- `notificationModel.test.ts` 2건(이동 주소 네 가지, 안 읽은 수 문구). 모의 E2E `e2e/notifications.spec.ts`(2 unread, New·본문·Open 주소 두 가지, 읽은 것엔 Mark read 없음, Mark read → 1 unread, Mark all read → All read, 요청 순서).

## 고르는 알림 (2026-10-13, 리드, §2 2dt)

사용자 결정(2026-10-13, "Subscribed notifications"): 맡긴 일·승인·소유자 재고 경보(NT2–NT8)는 늘 오고, **그 밖의 알림은 각자 프로젝트마다 고른다.** 새 마이그레이션 **V79**(2026-10-16 개발 DB 적용).

| # | 규칙 | 오류 |
|---|---|---|
| NS1 | 저장: V79 `notification_subscription`(프로젝트 × 사람 × 주제, 유일). 주제는 `stock_alert`·`ncr_check_overdue`·`work_order_due`, `days_ahead`는 `work_order_due`에만(0–30). 행이 있으면 고른 것, 끄면 행을 지운다 | |
| NS2 | API(프로젝트 읽기, 자기 것만): `GET /notification-subscriptions?projectId=` → 세 주제 `{projectId, topic, subscribed, daysAhead}`; `PUT /notification-subscriptions` `{projectId, topic, subscribed, daysAhead?}` → 같은 목록. 작업지시 마감은 `daysAhead`를 빼면 3 | 400 `projectId is required.`·`Unknown topic '…'. Use stock_alert, ncr_check_overdue or work_order_due.`·`subscribed is required.`·`daysAhead is only for work_order_due.`·`daysAhead must be whole days from 0 to 30.`·형식 `Send projectId, topic, subscribed as true or false and daysAhead as whole days.` / 403 / 409 동시 변경 |
| NS3 | 보낼 때 그 프로젝트를 읽을 수 있는 사람(소유자, 활성 구성원)에게만. 떠난 사람의 선택은 남아도 조용하다 | |
| NS4 | `stock_alert`: 재고 경보가 열릴 때(NT7과 같은 순간) 고른 사람에게도 같은 알림. 소유자는 이미 받으므로 한 번만 | |
| NS5 | 주기 알림은 키를 단다(V79 `notification.dedupe_key`, 사람별 유일): 같은 키는 한 사람에게 한 번 | |
| NS6 | `work_order_due`: 매시간 점검(`app.notification.reminder-interval`, 기본 PT1H)이 승인·진행 중 작업지시 중 계획 끝 ≤ 지금 + 고른 날 수를 찾아 `WO-0005 is due 2030-01-10`(프로젝트 시간대 날짜), 이미 지났으면 `WO-0005 is past its planned end (2030-01-10)`, 본문 작업지시 제목, ref `work_order`. 키 = 작업지시 + 계획 끝이라 일정을 바꾸면 다시 한 번 | |
| NS7 | `ncr_check_overdue`: 같은 점검이 닫힌 NCR 중 효과 확인이 없고 확인 예정일이 프로젝트 오늘보다 앞선 것(NCR 목록의 지남 표시와 같은 기준)을 `Effectiveness check of NCR-0003 is overdue`, 본문 `Due 2030-01-31 · 제목`, ref `nonconformity`. 키 = NCR + 예정일 | |
| NS8 | 화면: **Project Settings → Notifications**: 세 칸(`Stock alerts`·`Overdue NCR checks`·`Work orders due`)과 설명, 작업지시 마감은 **Days before the planned end**(기본 3, 고를 때 함께 보내고 고른 뒤 바꾸면 칸을 떠날 때 저장). 누르면 바로 새 선택을 보이고 거절되면 서버 값으로 돌아간다. 오류 표시 | |
| NS9 | 읽기는 공개 API로: production `WorkOrderQuery.findDueBy`, quality 새 `NonconformityQuery.findPendingChecks`, project `ProjectCalendarQuery`·`ProjectMemberQuery`. 점검은 `batch` 패키지. 범위 밖: 메일·Slack, 남의 선택 바꾸기, 품목·작업지시별 거르기, 묶음 요약 | |

검증(2026-10-13, 격리 복사본): `NotificationSubscriptionIntegrationTest` 3건 — (1) 새 구성원의 세 주제 기본 꺼짐 → 작업지시 마감 켜면 3일, 7일로 바꿈, 재고 경보 켬·끔, 소유자의 선택은 따로, 모르는 주제·31일·재고 경보에 날 수·`subscribed` 없음·형식 틀린 날 수 400, `projectId` 없음 400, 외부인 403. (2) 고른 구성원·고른 소유자·고르지 않은 구성원 → 경보가 열리면 고른 구성원 1건(제목·ref), 소유자 1건(두 번 아님), 고르지 않은 사람 0. (3) 2일을 고른 구성원 → 하루 뒤 끝나는 승인 작업지시는 `… is due <날짜>` 1건, 5일 뒤 것은 0, 이틀 전 예정일이 지난 닫힌 NCR은 `Effectiveness check of … is overdue` 1건, 고르지 않은 사람 0 → 다시 점검해도 그대로 1건 → 프로젝트를 떠나면 새로 마감되는 작업지시에도 0. 알림·구독·재고 경보·NCR·승인·모듈 경계·마이그레이션 관련 35건 실패 0. 모의 E2E `e2e/notification-subscriptions.spec.ts`(기본 3일, 칸마다 보낸 본문, 날 수 바꿔 떠나면 저장, 31일 넘으면 거절 표시, 끄기).

## 이후

- ~~구독 설정을 정한 뒤 퍼지는 알림(구성원별 재고 경보, NCR 효과 확인 예정일 지남, 작업지시 마감 임박)~~ → 위 "고르는 알림" NS1–NS9(2dt, V79)
- 메일·Slack 보내기, 오래된 알림 정리
