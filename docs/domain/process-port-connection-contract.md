# 공정 포트·연결 계약 (2026-09-26)

이 문서는 V24·V25의 공정 포트·연결 열을 정의한다. 설계 검증과 revision 발행 관문, 발행된 revision을 따르는 Flow Run 그래프 라우팅을 구현했다. 기존 마이그레이션은 변경하지 않았고 실행 모드와 단계 출처 기록을 V39에 추가했다.

## 필드 의미

| 필드 | 뜻 |
|---|---|
| `process_io.direction` | `output`은 자원을 내보내고 `input`은 받는다. 명시적 연결은 출력에서 입력으로 향한다. |
| `process_io.role` | 선택적 업무 라벨이다. `feed`(원료 투입), `product`(주생산물), `byproduct`(부산물), `waste`(폐기물), `control`(제어 정보)을 권장한다. 사용자 정의 라벨도 허용하며 연결·실행 판정에는 사용하지 않는다. 최대 50자다. |
| `process_io.item_id` | 포트가 Catalog Item과 연결될 때만 쓰는 선택적 binding이다(V42부터 NULL 허용, [ADR-003](../architecture/adr/ADR-003-resource-port-contract.md) 결정 4). 데이터·파일·API 포트는 비우고 `schema_json`으로 계약한다. 생성 API에서 `itemId`는 선택이다. 수정 API에서 비어 있는 `itemId`는 기존 값을 유지하고, `clearItem: true`가 연결을 지운다(`itemId`와 함께 보내면 400 `itemId cannot be supplied with clearItem.`). 없는 품목은 404, 다른 프로젝트 품목은 400이다. |
| `process_io.resource_type` | 연결 호환성을 판정하는 자원 분류다. 비어 있으면 `io_type`을 따른다. 두 명시적 포트에서 정확히 같아야 한다. 현재 기본값은 `material`이다. |
| `process_io.schema_json` | 포트가 실어 나르는 속성의 최소 객체 스키마다. 그래프 실행에서 명시적 연결을 통과할 때 `outputSnapshot.attrs`의 필수 속성과 타입을 검사한다. 없으면 형식 검증을 건너뛴다. |
| `process_io.validation_rule` | 포트 값에 대한 조건식이다. 저장 시 문법과 속성 참조를 검증한다. 그래프 실행에서 명시적으로 연결된 출력·입력 포트의 규칙을 단계의 `outputSnapshot`에 적용한다. |
| `process_connection.condition_expr` | 연결을 통과할 조건식이다. 발행된 revision의 식을 출발 단계의 `outputSnapshot`에 대해 평가한다. 거짓이면 후속 단계를 만들지 않는다. |
| `process_connection.capacity` | 한 번의 연결 흐름에서 허용할 최대 자원 수량이다. 단위는 연결의 `unit`이 있으면 그것을, 없으면 출발 포트의 `unit`을 사용한다. `null`은 제한 없음이다. 저장 시 0 이상 및 `numeric(19,4)` 범위를 검증하고 그래프 실행에서 `outputSnapshot.quantity`와 비교한다. |
| `process_connection.failure_policy` | 해당 연결로 생성된 단계 실패 시 `stop`은 실행 전체 실패, `skip`은 단계를 건너뜀, `retry`는 즉시 최대 3회 재시도를 뜻한다. 기본값은 `stop`이다. |
| `process_connection.default_route_yn` | V85. `Y`면 Exclusive 게이트웨이의 기본 연결이다: 조건을 만족하는 다른 연결이 없을 때 간다(GR-15). 게이트웨이가 아닌 노드의 연결에서는 검사 오류다(GR-19). API는 `defaultRoute`(boolean). 기본값 `N`. |

## 저장 계약

| 규칙 | 이유 | 위반 응답 |
|---|---|---|
| PC-01. 같은 공정을 출발·도착으로 연결할 수 없다. | 자기 참조와 무의미한 흐름 방지 | 400 `A connection cannot link a process to itself.` |
| PC-02. 같은 명시적 `fromIoId`→`toIoId` 살아 있는 연결은 하나다. | 중복 자원 흐름 방지 | 409 `Ports are already connected.` |
| PC-03. 출발 포트는 출발 공정의 `output`, 도착 포트는 도착 공정의 `input`이어야 한다. | 방향과 소유 관계 보장 | 400 `Connection fromIoId must be an output port of fromProcessId.` 또는 `Connection toIoId must be an input port of toProcessId.` |
| PC-04. 양쪽 포트의 `resourceType`은 같아야 한다. | 다른 자원 종류의 암묵적 변환 방지 | 400 `Connected ports resourceType must match.` |
| PC-05. 포트의 `itemId`는 서로 같아야 하며 연결의 `itemId`도 지정된 포트와 같아야 한다. 양쪽 포트 품목이 같고 연결 품목이 비어 있으면 저장 시 채운다. | 품목 계보 보존 | 400 `Connected ports itemId must match.` 또는 `Connection itemId must match the ports.` |
| PC-06. 등록된 단위의 유형은 포트·연결 간 같아야 한다. 모르는 단위는 저장을 허용하고 검증 보고서에 경고한다. | kg↔ea 같은 잘못된 변환 방지 | 400 `Connection unit type must match port units.` |
| PC-07. 포트의 `schemaJson`은 `{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}` 모양이다. 속성 타입은 `string`, `number`, `boolean`만 허용하고 필수 이름은 `properties`에 있어야 한다. 최상위의 알 수 없는 키는 무시한다. | 서로 비교 가능한 최소 스키마 확보 | 400 `schemaJson.type must be object.`, `schemaJson.properties...`, `schemaJson.required...` 등 필드 포함 메시지 |
| PC-08. 입력 포트의 필수 속성은 출력 포트의 `properties`에 같은 타입으로 있어야 한다. 한쪽 스키마가 없으면 연결은 허용한다. | 필수 입력의 공급 보장 | 400 `Target port needs <name> (<type>).` |
| PC-09. `conditionExpr`와 `validationRule`은 아래 문법에 맞고 최대 500자다. `attrs.name`은 해당 포트 스키마에 선언돼야 한다. 스키마가 없으면 참조를 허용한다. | 임의 코드 실행을 막고 조건을 재현 가능하게 함 | 400 `Invalid condition at position N: ...` |
| PC-10. `capacity`는 `null` 또는 0 이상이고 `failurePolicy`는 `stop`, `skip`, `retry` 중 하나다. | 정의 값의 의미 보존 | 400 `Connection capacity cannot be negative.` 또는 `Unknown connection failure policy.` |
| PC-11. 연결된 포트의 방향·자원 종류·품목·단위·스키마 수정은 기존 연결을 모두 재검증한다. 포트 삭제 시 연결도 같은 트랜잭션에서 소프트 삭제하고 각 연결 삭제 이벤트를 보낸다. | 고아 연결과 사후 호환성 파괴 방지 | 409 `Port change would break connection(s): <ids>` |

기존 캔버스의 기본 핸들 연결은 명시적 포트 ID 없이 저장할 수 있다. PC-02·PC-04·PC-08은 두 명시적 포트가 있을 때 적용한다. PC-05·PC-06은 한쪽만 지정돼도 지정된 값에 적용한다.

조건 문법: `or`, `and`, `not`, 괄호, `= != < <= > >=`, 숫자, 작은따옴표 문자열, `true`, `false`, `quantity`, `unit`, `item`, `attrs.<이름>`을 지원한다. 함수 호출은 허용하지 않는다. 평가 함수는 `boolean evaluate(Map<String,Object>)`이며 없는 값이나 서로 다른 타입의 비교는 `false`다.

## 워크플로 검증과 발행

`GET /workflows/{workflowId}/validation`은 읽기 권한을 요구한다. 결과는 `errors`, `warnings`, `issues[]`이며 각 문제에 `severity`, `code`, `processId`, `ioId`, `connectionId`, `message`가 있다. 오류 코드: `CONNECTION_ORPHAN`, `CONNECTION_INCOMPATIBLE`, `EXPRESSION_INVALID`, `PORT_SCHEMA_INVALID`, `REQUIRED_INPUT_UNCONNECTED`. 경고 코드: `PROCESS_ISOLATED`, `CYCLE`, `SCHEMA_UNVERIFIED`, `UNIT_UNKNOWN`, `RESOURCE_TYPE_UNKNOWN`. Exclusive 게이트웨이의 오류·경고는 GR-19, Join은 GR-27. 오류를 먼저 표시한다.

`RESOURCE_TYPE_UNKNOWN`(2026-10-02)은 포트의 `resourceType`이 registry에 없을 때 나온다. Registry(Experimental)는 `material, product, energy, water, waste, file, data, api, parameter, signal, generic`이다([ADR-003](../architecture/adr/ADR-003-resource-port-contract.md) "Resource Type Registry"). `labor`는 실행 요건이지 포트로 흐르는 대상이 아니어서 넣지 않았다. 포트 편집 화면의 I/O Type에 `labor`가 있어 그 포트는 이 경고를 받는다. 저장과 발행은 막지 않는다. 목록 밖 값도 그대로 저장되며, 연결의 PC-04(양쪽 `resourceType` 일치)는 그대로 적용된다.

Revision 발행은 같은 검증을 수행한다. 오류가 있으면 409 `Workflow has N error(s): <앞 3개 요약>`으로 거절하고, 경고만 있으면 발행한다. 기존 revision 조회·폐기는 영향받지 않는다.

기존 데이터는 저장 시 일괄 수정하지 않는다. 과거의 위반 연결은 검증 보고서에 남기고 발행을 막는다. 운영 전 보고서를 검토하여 개별 포트·연결을 정상 API로 수정한다. 모르는 단위와 스키마 부재는 경고이므로 기존 그래프를 강제로 깨지 않는다. 자동 실행에 이 계약을 적용하려면 flow-run 담당자가 revision snapshot의 조건식·용량·실패 정책을 읽고 실행 시점의 수량·품목·속성으로 평가해야 한다.

## 2026-09-27 보강 규칙

| 규칙 | 이유 | 위반 응답 |
|---|---|---|
| PC-12. 포트 `direction`은 `input` 또는 `output`, `requiredYn`과 `allowShortageYn`은 `Y` 또는 `N`, `quantity`는 0 이상이어야 한다(2026-10-10부터 수량·단위는 material·product·품목 포트만 필수, [포트 수량·단위](port-measurement.md) PM2–PM3). 대소문자와 앞뒤 공백은 저장 전에 정규화한다. | 잘못된 값이 연결 및 필수 입력 검증을 우회하지 않게 한다. | 400, 해당 필드 이름을 포함한 메시지 |
| PC-13. 포트 수정·삭제와 연결 생성·수정은 동일한 workflow 행 잠금을 사용한다. 포트 수정은 잠금 뒤 최신 포트 상태를 다시 읽고 연결 호환성을 검증한다. | 동시에 요청해도 호환되지 않는 연결을 남기지 않는다. | 충돌 시 409 `Port change would break connection(s): <ids>`; 이미 삭제된 포트는 404 |

기존 DB의 잘못된 포트 값은 자동 변경하지 않는다. 워크플로 검증은 `PORT_INVALID` 오류로 표시하고 revision 발행을 막는다. 정상 API로 개별 값을 수정한다.

포트 수정 API는 `clearSchema: true`로 기존 `schemaJson`을 명시적으로 제거한다. `schemaJson`과 `clearSchema: true`를 함께 보내면 400 `schemaJson cannot be supplied with clearSchema.`로 거절한다. 두 필드를 모두 생략하면 기존 스키마를 유지한다.

편집 화면에서 `role`, `formula`, `validationRule`을 비우면 빈 문자열을 보내고 서버가 `null`로 저장한다. 필드를 요청에서 생략하면 기존 값을 유지한다.

## 2026-09-29 저장 정밀도·문자 보강 규칙

| 규칙 | 이유 | 위반 응답 |
|---|---|---|
| PC-14. 연결 수정의 `flowRate`, `delayTimeSec`, `lossRate`는 원래 JSON 소수를 보존하여 각각 `numeric(14,4)`, `numeric(10,2)`, `numeric(5,4)` 범위를 검증한다. 유효한 지수 표기와 소수 끝의 0은 허용한다. | 부동소수점 반올림으로 초과 자릿수가 사라지거나 극소 값이 0이 되는 것을 막는다. | 400 `<field> must fit numeric(...).`; 문자열·객체·배열 등은 400 `<field> must be a number or null.` |
| PC-15. 포트·연결 문자열과 `schemaJson`의 모든 문자열·객체 키에 NUL 및 짝 없는 UTF-16 surrogate를 허용하지 않는다. 원래 입력을 공백 정규화 전에 검사한다. 정상 한글·이모지 및 다른 저장 가능한 제어 문자는 허용한다. | DB 오류와 문자 대체, 끝의 NUL이 trim으로 사라지는 데이터 손상을 막는다. | 400 `<field> contains a character PostgreSQL cannot store.` |
| PC-16. 연결 생성·수정 요청의 잘못된 JSON과 파서가 표현할 수 없는 숫자는 저장 전에 거절한다. | 입력 오류가 500으로 표시되지 않도록 한다. | 400 `Request body must contain valid JSON.` 또는 식별된 숫자 필드의 `<field> could not be read from the request body.` |
| PC-17. 포트의 `ioType`, `resourceType`, `colorScheme`과 연결의 `connectionType`은 서버 언어 설정에 독립적인 소문자로 정규화한다. 연결의 자원 종류 비교도 같은 정규화를 사용한다. | 터키어 설정에서 `MATERIAL`이 `materıal`로 저장되거나 대소문자만 바꾼 포트가 기존 연결을 깨는 것을 막는다. | 유효한 코드의 대소문자 변경은 200. 실제 자원 종류 불일치는 기존 PC-04의 400 또는 연결된 포트 변경의 409. |
| PC-18. 포트 `quantity`와 연결 `capacity`의 수학적 0은 지수·소수 자릿수와 관계없이 일반적인 0으로 저장한다. | `0e-1000000`처럼 값은 유효한 0이지만 원래 소수 자릿수로 인해 PostgreSQL 입력 범위를 넘는 표현을 안전하게 처리한다. | 유효한 0은 200. 0이 아닌 값의 기존 숫자 범위·정밀도 위반은 계속 400. |
| PC-19. 앞뒤 공백 정리 후 남은 내용이 없는 선택 문자열은 빈 값으로 처리한다. 생성의 `ioType`, `resourceType`, `colorScheme`, `connectionType`, `failurePolicy`, 핸들은 기존 기본값을 사용한다. 수정의 `ioType`, `resourceType`, `connectionType`, 포트 `unit`은 빈 값이면 기존 값을 유지하며, 선택 이름·역할·수식·검증식·연결 레이블·연결 단위·조건식은 `null`로 지운다. | 공백 정리로 사라지는 제어 문자만 보낸 값이 자원 종류를 빈 문자열로 바꾸거나 연결 무결성 검사에 실패하는 문제를 막는다. | 유효한 빈 선택 값은 200. 포트 생성의 필수 `unit`이 비어 있으면 400 `unit is required.` |

PC-14에서 필드 생략은 기존 값을 유지한다. 명시적 `null`은 `flowRate`를 지우고 `delayTimeSec`·`lossRate`를 0으로 돌린다. 이 규칙은 연결 요청 필드에만 적용하며 전역 JSON 파싱 설정을 변경하지 않는다.

PC-15는 새 생성·수정 입력에 적용한다. 실패한 요청은 포트·연결의 기존 값과 연결 버전을 바꾸지 않는다. 과거 대체·정규화된 문자의 원래 입력은 복구할 수 없어 기존 데이터와 발행된 리비전을 자동 수정하지 않는다.

PC-15의 문자열 검사는 요청 본문의 참조 ID에도 적용한다. 포트 생성의 `processId`·`itemId`, 포트 수정의 `itemId`, 연결 생성의 `workflowId`·`fromProcessId`·`toProcessId`·`fromIoId`·`toIoId`·`itemId`, 연결 수정의 `fromIoId`·`toIoId`·`itemId`를 각각 조회 전에 검사한다. 잘못된 문자는 필드명이 든 400으로 거절하며, 유효한 ID의 기존 권한·소속·연결 호환성 검증은 그대로 적용한다.

PC-17·PC-18도 새 생성·수정 입력에 적용한다. 과거 언어 설정으로 저장된 자원 종류는 사용자 정의 코드일 수 있어 자동 치환하지 않는다. 잘못 저장된 코드가 확인되면 정상 API로 개별 수정한다. 발행된 리비전과 기존 마이그레이션은 변경하지 않는다. 숫자는 값이 정확히 0인 경우에만 정규화하며, 극소인 0이 아닌 값을 0으로 바꾸지 않는다.

PC-19의 빈 값 판단은 기존 `trim` 정규화 후 수행한다. NUL·짝 없는 surrogate는 PC-15에 따라 정규화 전에 거절하고, 내용 중간의 저장 가능한 제어 문자는 기존대로 보존한다. 기존 데이터와 발행된 리비전은 일괄 수정하지 않는다.

## 2026-09-30 조건 연산자·부모 상태 보강 규칙

| 규칙 | 이유 | 위반 응답 |
|---|---|---|
| PC-20. 비교 연산자는 `=`, `!=`, `<`, `<=`, `>`, `>=`만 허용한다. `==`는 `=`의 별칭이 아니다. 포트 `validationRule`과 연결 `conditionExpr` 모두 저장 전에 검사한다. | `==`가 저장은 되지만 평가할 때 항상 `false`가 되던 불일치를 막는다. | 400 `Invalid condition at position N: unsupported comparison operator '=='` |
| PC-21. 포트의 생성·수정·삭제와 연결의 생성·수정·삭제는 워크플로 행 잠금 뒤 워크플로의 최신 삭제 상태를 확인한다. 포트 작업은 부모 공정의 최신 삭제 상태도 확인한다. 포트 생성은 revision 발행과 같은 워크플로 잠금을 사용한다. | 잠금 대기 중 부모가 삭제된 요청을 거절하고, 발행 검증과 스냅샷 사이에 새 필수 포트가 들어오지 않게 한다. | 삭제된 워크플로 또는 공정에 대한 포트 변경과 삭제된 워크플로의 연결 변경은 404. 잠금이 해제된 뒤 정상 부모의 작업은 처리한다. |
| PC-22. 워크플로가 삭제되면 포트 목록·단건 조회와 연결 목록·단건 조회는 404다. | 삭제된 설계의 하위 객체가 개별 URL에서 계속 노출되지 않게 한다. | 404. 기존 포트·연결 행과 과거 revision의 스냅샷은 자동 삭제하지 않는다. |

과거에 저장된 `==` 조건은 자동으로 바꾸지 않는다. 현재 초안은 워크플로 검증에서 `EXPRESSION_INVALID`로 표시하고 새 revision 발행을 409로 막는다. 수정할 때는 포트 또는 연결 API로 `=`을 명시해야 한다. 이미 발행된 revision도 자동 변경하지 않으며, 그래프 실행은 시작할 때 표현식을 검증해 잘못된 발행본을 409로 거절한다.

PC-21은 포트·연결 서비스와 revision 발행이 함께 쓰는 워크플로 잠금에 관한 규칙이다. 공정 자체를 수정·삭제하는 서비스까지 같은 잠금으로 직렬화하는 계약은 아직 확정되지 않았다.

## 2026-09-30 그래프 실행 계약 (V39)

`POST /flow-runs/graph`는 발행 상태의 revision snapshot에서 시작 노드(진입 연결 없음)를 `planned`로 만든다. 기존 `POST /flow-runs`는 수동 모드이며 기존 기록은 `execution_mode=manual` 기본값을 가진다. 그래프 모드에서 단계 수동 추가와 수동 retry는 409다. 실제 공정 작업은 각 단계를 사용자가 시작하고 완료 또는 실패로 기록한다. 이는 라우팅 정책이며 작업자·설비를 자동 가동하는 엔진은 아니다.

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-01 | 순환 연결이 있거나 노드·연결 참조가 잘못된 revision은 그래프 실행을 시작하지 않는다. | 409 `Graph revisions with cycles cannot execute.` 등 |
| GR-02 | 완료된 출발 단계의 `outputSnapshot` 객체에서 `quantity`, `unit`, `item`, `attrs`를 읽고 발행된 `conditionExpr`을 평가한다. 거짓이면 해당 연결은 통과하지 않는다. | 객체가 아니면 400 `Graph step outputSnapshot must be an object.` |
| GR-03 | 조건을 통과한 연결만 `capacity`와 단위를 검사한다. `quantity`가 없거나 용량을 초과하면 출발 단계 완료 전체를 원자적으로 거절해 재입력할 수 있게 한다. | 409 `Connection <id> requires outputSnapshot.quantity for capacity.` 또는 `Connection <id> capacity exceeded.` / `unit does not match...` |
| GR-04 | 통과한 연결마다 대상 `planned` 단계를 만들고 `sourceConnectionId`, `sourceStepId`, `inputSnapshot`, `step_created` 이벤트를 기록한다. 여러 진입 연결은 각각 별도 단계이며 V40의 같은 run FK가 정확한 원본 단계를 보존한다. | 단계 수 상한 초과 시 409 |
| GR-05 | 대상 단계 실패는 진입 연결의 `failurePolicy`를 따른다. `skip`은 단계 `skipped`와 이벤트 기록, `retry`는 처음 시도 이후 최대 3회 즉시 새 attempt, `stop` 및 재시도 소진은 run 실패다. 시작 노드의 정책은 `stop`이다. | 종료된 run 수정은 409 |
| GR-06 | 모든 기록된 단계가 `completed` 또는 `skipped`일 때 run을 마칠 수 있다. | 미완료 단계가 있으면 409 |
| GR-07 | 통과할 연결에 명시적 출력·입력 포트가 있으면 두 포트의 `validationRule`을 전송 값에 적용한다. 규칙이 거짓이면 단계 완료를 원자적으로 거절한다. | 409 `Port <id> validationRule did not pass.` |
| GR-08 | 명시적 출력·입력 포트의 `schemaJson.required`는 `outputSnapshot.attrs`에 있어야 하고 선언된 속성 값은 `string`, `number`, `boolean` 타입을 따라야 한다. 추가 속성은 허용한다. | 409 `Port <id> requires outputSnapshot.attrs.<name>.` 또는 `... must be <type>.` |
| GR-09 | 발행 snapshot의 연결이 명시한 `fromIoId`와 `toIoId`는 각각 존재하는 출발 공정의 출력 포트와 도착 공정의 입력 포트여야 한다. 과거 snapshot의 누락·오방향 참조도 실행 시작 전에 거절한다. | 409 `Published revision connection <id> has an invalid fromIoId.` 또는 `... toIoId.` |
| GR-10 | `POST /flow-runs/{runId}/steps/{stepId}/preview`는 실행 중인 그래프 단계의 `outputSnapshot`을 완료와 같은 발행 revision 규칙으로 평가해 연결별 `willRoute`를 반환한다. 조건이 거짓인 연결도 결과에 포함한다. 단계·attempt·event는 바꾸지 않으며 완료 요청에서 다시 검증한다. | 쓰기 권한 없으면 403, 실행 중인 그래프 단계가 아니면 409, 출력 객체가 아니면 400, 용량·포트 검증 실패는 완료와 같은 409 |
| GR-11 | 그래프 단계 완료 시 조건이 거짓인 연결마다 출발 단계에 `connection_filtered` 이벤트를 남긴다. payload에는 `connectionId`, `targetNodeId`, `reason=condition_false`를 넣고 화면 이력에 연결 이름을 표시한다. 미리보기는 이 이벤트를 만들지 않는다. | 단계 완료의 단일 트랜잭션에 포함되어 완료 실패 시 이벤트도 저장되지 않음 |
| GR-12 | 과거 발행 snapshot의 `processIos` 형태, 포트 검증식·스키마, 연결 조건식이 잘못되면 그래프 실행 시작을 거절한다. 저장된 발행본의 오류이며 요청 본문 오류로 취급하지 않는다. | 409 `Published revision has invalid ...` |

조건·용량·정책·포트 검증식과 스키마는 시작할 때 고정된 발행 revision에서 읽는다. 이후 draft를 고쳐도 진행 중인 run에 반영되지 않는다. 연결 단위는 `outputSnapshot.unit`이 제공되면 비교하며 자동 단위 변환은 하지 않는다. 연결되지 않은 포트의 실행 값 검증, 여러 진입 연결의 합류, 수량 분할, 일정·지연 시간, 자동 작업 실행은 후속 범위다.

`GET /flow-runs/{flowRunId}/steps/{stepId}/lineage`는 프로젝트 읽기 권한을 확인한 뒤 선택 단계와 정확한 원본 단계 체인(`ancestors`, 시작 단계부터 순서대로), 모든 하위 실행 단계(`descendants`, 단계 순번 순서대로)를 반환한다. 수렴 그래프에서도 각 단계의 `sourceStepId`를 따라가므로 같은 공정 노드의 다른 실행 경로가 섞이지 않는다. 다른 run에 속한 `stepId`는 404다. 화면의 단계 상세에는 이 경로와 저장된 입력·출력 snapshot, 오류 메시지를 표시한다.

## 2026-10-17 Exclusive 게이트웨이 (ADR-006, V85)

[ADR-006](../architecture/adr/ADR-006-operation-types.md)(Accepted 2026-10-17, 사용자 결정 "Accept as recommended")의 1단계. 일을 하지 않는 게이트웨이 노드 중 Exclusive만 구현했다(2eb, 로컬 검증, V85 2026-10-17 개발 DB 적용). Join·Parallel 노드·Subflow·수량 분할은 없다. 병렬 분기는 지금처럼 조건 없는 여러 연결로 한다.

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-13 | 노드 종류 `exclusive`(편집기 팔레트 **Exclusive**, `processType` `gateway`)는 게이트웨이다. 발행 snapshot의 `processes[].nodeType`으로 알아보고 실행 중에는 바뀌지 않는다. 게이트웨이가 없는 이전 revision은 GR-02~GR-04대로 돈다 | |
| GR-14 | 게이트웨이에서 나가는 연결은 `priority`가 작은 것부터, 같으면 발행 snapshot에 그려진 순서로 시도한다. 기본 연결이 아니면서 조건이 없거나 참인 **첫** 연결 하나만 단계를 만들고 게이트웨이 단계에 `gateway_routed`(reason `first_match`)를 남긴다 | |
| GR-15 | 그런 연결이 없으면 기본 연결(`defaultRoute`, 게이트웨이마다 최대 하나)로 간다(`gateway_routed` reason `default`). 기본 연결의 조건식은 쓰지 않는다. 기본 연결도 없으면 게이트웨이 단계가 `failed`(errorCode `NO_ROUTE`)가 되고 실행이 실패한다(`run_failed` errorCode `NO_ROUTE`, 남은 planned 단계는 cancelled). 연결 실패 정책(GR-05)은 쓰지 않는다 | 앞 단계 완료는 200, 실행은 failed |
| GR-16 | 고르지 않은 연결마다 게이트웨이 단계에 `connection_filtered`를 남긴다. reason은 `condition_false`, `exclusive_not_chosen`(조건은 참이지만 앞 순서 연결이 골라짐), `default_not_needed`. 일반 노드의 연결은 지금처럼 `condition_false`만 | |
| GR-17 | 게이트웨이 단계는 서버가 바로 지나간다(ADR-006 결정 4, ADR-004 결정 3의 좁은 예외). 앞 단계 완료와 같은 트랜잭션에서 시도 1을 만들고 `step_started`·`step_completed`를 행위자 system으로 기록하며, 출력 = 입력이다. 사람이 시작·완료·실패·재시도할 단계가 아니다. 재고·생산 기록은 움직이지 않는다 | 끝난 게이트웨이 단계를 시작하면 409 |
| GR-18 | 고른 연결에도 용량·단위·포트 검증식·스키마(GR-03·GR-07·GR-08)를 그대로 적용한다. 통과하지 못하면 앞 단계 완료 전체를 거절하고 아무것도 남기지 않는다. 게이트웨이가 이어지면 같은 트랜잭션에서 차례로 지나간다 | GR-03·GR-07·GR-08과 같은 409 |
| GR-19 | 워크플로 검사·발행: 오류 `DEFAULT_ROUTE_OUTSIDE_GATEWAY`(게이트웨이가 아닌 노드의 연결이 기본 연결), `GATEWAY_DEFAULTS`(기본 연결 둘 이상), `GATEWAY_WITHOUT_INPUT`(들어오는 연결이 없는 게이트웨이: 넘겨줄 값이 없다). 경고 `GATEWAY_SINGLE_ROUTE`(나가는 연결 둘 미만), `GATEWAY_PRIORITY_TIE`(기본이 아닌 연결끼리 priority가 같음), `DEFAULT_ROUTE_CONDITION_IGNORED`(기본 연결의 조건식) | 오류가 있으면 발행 409 |
| GR-20 | API: 연결 생성·수정의 `defaultRoute`는 JSON `true`·`false`만 받는다. 생성에서 빠지면 false, 수정에서 빠지면 그대로다. 응답과 캔버스에 `defaultRoute`가 있다. 편집기: 게이트웨이에서 나가는 연결의 인스펙터에만 **Default route** 체크와 설명이 있고, 그 밖의 연결은 이 값을 보내지 않는다. 실행 취소가 기본 연결 여부도 되돌린다 | 다른 값이면 400 |

검증(2026-10-17, 격리 복사본): `ExclusiveGatewayIntegrationTest` — Measure → Choose(Exclusive) → Big(priority 1, `quantity > 10`)·Medium(2, `quantity > 5`)·Rest(9, 기본). 12는 Big만(Medium은 `exclusive_not_chosen`, Rest는 `default_not_needed`, 게이트웨이 완료 행위자 system, 끝난 게이트웨이 단계 시작 409), 7은 Medium, 3은 Rest(`default`). 기본 연결을 끄고 다시 발행하면 3에서 게이트웨이 단계 `NO_ROUTE` 실패·실행 실패이고, 앞 revision으로 시작한 실행은 여전히 Rest로 간다. 기본 연결 둘·게이트웨이 밖 기본 연결·입력 없는 게이트웨이 발행 409, 나가는 연결 하나면 경고, `"defaultRoute":"yes"` 400. Exclusive·그래프 실행·실행 정책·데이터 흐름·포트 측정·연결 계약·워크플로 검사·모듈 경계·마이그레이션 91건, 워크플로·캔버스·협업·revision·공정·그래프·편집기 249건 실패 0. 프런트 `nodeCatalog.test.ts`(팔레트), `connectionUndo.test.ts`(기본 연결 되돌리기) 각 1건, 모의 API e2e `exclusive-gateway-editor.spec.ts`(게이트웨이 연결만 Default route를 보이고 보냄) 4회 반복 통과.

## 2026-10-17 Join 게이트웨이 (ADR-007, V88)

[ADR-007](../architecture/adr/ADR-007-join-gateway.md)(Accepted 2026-10-17, 사용자 결정 "Accept as recommended")을 구현했다(2ef, 로컬 검증, V88 개발 DB 미적용). 노드 종류 `join`(편집기 팔레트 **Join**, `processType` `gateway`).

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-21 | Join으로 가는 연결이 통과하면 단계를 따로 만들지 않고, 그 실행의 Join 단계(노드마다 하나)에 **도착**으로 더한다. 첫 도착 때 planned로 만들고 도착마다 `join_arrived`(연결·출처 단계·도착 번호)를 남긴다. 도착은 V88 `flow_run_step_source`에 순서대로 남는다 | |
| GR-22 | **합류 시점(도달 가능성):** Join으로 들어오는 연결 가운데, 그 실행의 planned·running 단계(재시도 대기·예정 시각 대기, 기다리는 다른 Join 포함)에서 발행 그래프를 따라 닿을 수 있는 것이 하나도 없으면 합류한다. 단계 완료와 건너뜀(시간 초과로 인한 것 포함) 뒤마다 실행 행 잠금 안에서 다시 보므로, 동시에 끝난 두 갈래도 한 번만 합류한다 | |
| GR-23 | 끊긴 갈래(조건 거짓, Exclusive 미선택, 건너뜀)는 기다리지 않는다. 하나도 도착하지 않으면 Join 단계와 그 뒤 단계를 만들지 않고, 실행은 마칠 수 있다. 위쪽에서 다시 모인 갈래가 같은 연결로 여러 번 오면 모두 모아 한 번 합류한다. 재시도는 도착을 늘리지 않는다 | |
| GR-24 | **합친 입력·출력:** `arrivals[{connectionId, sourceStepId, quantity, unit, item, attrs}]`(도착 순서, 출처 단계 출력에 있는 것만). 모든 도착에 수량이 있고 단위가 같으면(모두 없음 포함) 위쪽에 `quantity`(합)와 `unit`, 품목이 모두 같으면 `item`을 둔다. `attrs`는 합치지 않는다. 출력은 입력과 같고, 나가는 연결의 조건·용량·단위·포트 규칙은 이 출력으로 본다 | |
| GR-25 | 합류하면 서버가 system으로 지나간다(시도 1, `step_started`·`step_completed`). 사람이 시작·완료·일정 변경할 단계가 아니다. 재고·생산 기록은 움직이지 않는다 | 시작·일정 변경 409 `A Join step waits for its branches; the server passes it on (ADR-007).`, 완료 409 |
| GR-26 | 합친 출력이 나가는 연결 규칙(GR-03·GR-07·GR-08)을 통과하지 못할 때: 계기가 완료 보고면 그 보고 전체를 409로 거절해 아무것도 남기지 않는다. 계기가 건너뜀·시간 초과면 Join 단계가 `JOIN_TRANSFER_REJECTED`로 실패하고 실행도 실패한다(건너뜀은 그대로 남는다). Join 뒤 Exclusive가 고른 연결도 같다 | 409 / 실행 failed |
| GR-27 | 발행 검사: 오류 `JOIN_WITHOUT_INPUT`(진입 연결 없음), 경고 `JOIN_SINGLE_INPUT`(진입 연결 하나). 진입 연결 없는 Join이 든 발행본으로는 그래프 실행을 시작하지 않는다 | 발행 409 / 실행 시작 409 `Published revision Join <id> has no incoming connection.` |
| GR-28 | 응답·계보: 단계 목록의 Join 단계에 `joinWaiting`(아직 닿을 수 있는 진입 연결 수, 지나간 뒤 0. 다른 단계와 다른 응답은 null). 계보 API의 조상은 Join의 모든 도착 출처를 따라 단계 순번 순, 자손은 다시 모인 곳을 한 번만 센다. 실행 화면은 Join 단계에 `joins steps #2, #3 · waiting for 1 branch`를 보이고 Start·Edit schedule을 숨긴다. Join 단계의 `sourceStepId`·`sourceConnectionId`는 첫 도착이다 | |

검증(2026-10-17, 격리 복사본): `JoinGatewayIntegrationTest` 4건 — 두 갈래(B 2 kg 도착 → Join planned·`joinWaiting` 1·시작 409·일정 409 → C 3 kg → 같은 Join 단계가 system으로 완료, 수량 5 kg·`attrs` 없음·도착 태그 B·C, 입력 = 출력, `join_arrived` 2, End 입력 = 합친 출력, End 계보 조상 A·B·C·J, A 자손 B·C·J·End, 실행 마감), 끊긴 갈래(skip 뒤 도착 1로 합류, Exclusive 미선택 갈래 기다리지 않음, 도착 없으면 Join 없음·마감), 전송 규칙(용량 4에 2+3 완료 보고 409·그대로 running·도착 1 → 1로 다시 보내 3, skip이 계기면 `JOIN_TRANSFER_REJECTED`·실행 failed·skip 유지), 발행 검사(`JOIN_WITHOUT_INPUT` 발행 409, `JOIN_SINGLE_INPUT`). 오류 담당이 미리 쓴 수용 테스트 `JoinGatewayAcceptanceIntegrationTest`(격리본에만 복사): 14건 중 13건 통과, 1건 실패(retry 갈래의 상태 단정, 아래). Join·Exclusive·그래프 실행·실행 정책·데이터 흐름·포트·연결·워크플로 검사·모듈 경계·마이그레이션 95건 실패 0, 전체 백엔드 1,425건 실패 0. 프런트 `nodeCatalog.test.ts` 1건, 모의 E2E `flow-run-join.spec.ts`(기다리는 Join `joins step #2 · waiting for 1 branch`·Start·Edit schedule 없음 → 지나간 뒤 `joins steps #2, #3`).

## 2026-10-17 Split 게이트웨이 (ADR-008, V89)

[ADR-008](../architecture/adr/ADR-008-quantity-split.md)(Accepted 2026-10-17, 사용자 결정 "Accept as recommended")을 구현했다(2eh, 로컬 검증, V89 개발 DB 미적용). 노드 종류 `split`(편집기 팔레트 **Split**, `processType` `gateway`). 일반 노드가 통과한 연결마다 같은 출력을 보내는 동작(GR-04)은 그대로다.

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-29 | 저장: 연결의 `splitKind`(`share`·`fixed`·`rest`)와 `splitValue`(V89 `split_kind`·`split_value numeric(19,4)`, DB 검사 제약 `ck_process_connection_split`). `share`는 입력 수량의 %(0 초과 100 이하, 소수 2자리까지), `fixed`는 수량(0 초과, 소수 4자리까지), `rest`는 값 없음. 생성에서 빠지면 몫 없음, 수정에서 빠지면 그대로, 수정의 `clearSplit: true`가 지운다. 응답·캔버스·발행 snapshot에 `splitKind`·`splitValue`(다른 연결은 null) | 400 `splitKind is share, fixed or rest.`·`A share split takes a percent above 0 and at most 100, with up to 2 decimals.`·`A fixed split takes a quantity above 0 with up to 4 decimals.`·`A rest split takes no splitValue.`·`splitValue needs splitKind.`·`splitKind and splitValue cannot be supplied with clearSplit.` |
| GR-30 | 나누기: Split 단계 입력의 `quantity`를 그린 순서로 나눈다. 고정량은 그대로, 비율 몫은 입력 전체 × % ÷ 100을 소수 4자리 HALF_UP으로, 나머지 연결은 입력 − 고정량 합 − 비율 몫 합. 나머지 연결이 없으면 반올림 차이는 마지막 비율 연결이 가진다. 그래서 몫의 합은 언제나 입력과 같다 | |
| GR-31 | 각 갈래의 입력: Split 입력을 복사하고 `quantity`만 그 몫으로 바꾼다(`unit`·`item`·`attrs` 그대로). 연결 용량·단위·포트 규칙(GR-03·GR-07·GR-08)은 각 몫으로 본다. `gateway_routed`는 reason `split`과 그 몫 `quantity` | |
| GR-32 | 0인 몫: 그 연결로는 단계를 만들지 않고 `connection_filtered` reason `split_empty`를 남긴다. 몫이 모두 0이어도 Split은 실패하지 않는다(Exclusive의 `NO_ROUTE` 없음). 나가는 연결이 없는 Split은 거기서 끝난다 | |
| GR-33 | Split은 서버가 system으로 바로 지나간다(시도 1, `step_started`·`step_completed`, 출력 = 입력). 사람이 시작·완료할 단계가 아니다. 재고·생산 기록은 움직이지 않는다 | |
| GR-34 | 나눌 수 없을 때(입력에 수인 `quantity`가 없거나 음수, 고정량·비율 몫의 합이 입력을 넘음, 몫이 연결 규칙을 어김): 계기가 완료 보고면 그 보고 전체를 409로 거절해 아무것도 남기지 않는다. Join 뒤처럼 다시 보낼 사람이 없으면(건너뜀·시간 초과가 계기) Split 단계가 `SPLIT_REJECTED`로 실패하고 실행도 실패한다(GR-26과 같은 규칙) | 409 `Split <id> needs outputSnapshot.quantity to divide.`·`Split <id> gives out 3 but has 2.` |
| GR-35 | Join과: Split에서 Join으로 바로 가는 연결은 그 몫이 도착 수량이다(`arrivals[].quantity`, GR-24). 나눈 갈래를 Join으로 모으면 같은 단위의 합이 다시 입력과 같다. 연결 손실률은 GR-37–GR-44(새 발행본부터), 흐름률은 반영하지 않는다(FM-FLOW-002) | |
| GR-36 | 발행 검사: 오류 `SPLIT_WITHOUT_INPUT`(진입 연결 없음), `SPLIT_SHARE_MISSING`(몫이 없는 나가는 연결), `SPLIT_CONDITION`(조건식), `SPLIT_REST_DUPLICATE`(나머지 둘 이상), `SPLIT_SHARES_OVER`(비율 합 100 초과), `SPLIT_NO_REST`(나머지 없이 고정량이 있거나 비율 합이 100이 아님), `SPLIT_OUTSIDE_GATEWAY`(Split이 아닌 노드의 연결에 몫). 경고 `SPLIT_SINGLE_ROUTE`(나가는 연결 둘 미만). 화면: 팔레트 **Split**, Split에서 나가는 연결 인스펙터에 **Share**(Percent·Fixed quantity·The rest)와 값(보내기 전 0 초과·100 이하 검사), 실행 화면의 갈래 단계에 `received 70 kg` | 발행 409 |

검증(2026-10-17, 격리 복사본): `SplitGatewayIntegrationTest` 5건 — 비율(100 kg → 70·30, Split은 system 완료, `gateway_routed` `split` 수량 70, `unit` 그대로, 두 갈래를 Join으로 모으면 100), 고정과 나머지(0.5·99.5), 반올림(0.001을 33.33·33.33·33.34% → 0.0003·0.0003·0.0004), 0인 몫(fixed 5 + rest에 5 → rest 단계 없음·`split_empty`), 나눌 수 없음(2에 fixed 3 보고 409 `gives out 3 but has 2.`, 수량 없는 보고 409, A는 running 그대로 → 3으로 다시 보내 통과, Join 뒤 skip이 계기면 `SPLIT_REJECTED`·실행 failed·뒤 단계 없음), Split→Join으로 바로 가는 몫 30이 도착 → B 70과 합쳐 100, 저장·발행 검사(오류 코드 5종 한 번에·발행 409, `SPLIT_SHARES_OVER`·`SPLIT_NO_REST`·`SPLIT_SINGLE_ROUTE`, 400 메시지 7종, `clearSplit`). Split·Join·Exclusive·그래프 실행·워크플로 검사·연결·모듈 경계·마이그레이션 96건 실패 0, 전체 백엔드 1,431건 실패 0(V89 제약을 CASE로 고친 뒤 Split·마이그레이션·연결·워크플로 검사·모듈 경계 52건 다시 실패 0). 프런트 Vitest 659건(`splitShare.test.ts` 2건, `connectionUndo.test.ts`·`nodeCatalog.test.ts` 각 1건), 모의 E2E `split-gateway-editor.spec.ts`(일반 노드 연결은 몫을 묻지도 보내지도 않음, 120 거절·70 보냄, rest는 값 없이)·`flow-run-split.spec.ts`(`received 70 kg`·`received 30 kg`, Split 단계 자신은 없음) 2회 반복 통과. V89 리허설(개발 DB 5434, `BEGIN; … ROLLBACK;`, `lock_timeout` 5s): 기존 연결 16건 그대로 통과, 잘못된 조합 7가지(`share` 150·`rest` 1·`fixed` 0·종류 없이 값 2·모르는 종류·값 없는 `share`·`fixed`) 거절, 맞는 조합 5가지 통과, 롤백 뒤 열 없음·V87 그대로. 처음 쓴 OR 제약은 종류 없이 값만 있는 행을 통과시켜(CHECK가 NULL이면 통과) CASE로 고쳤다.

## 2026-10-17 연결 손실률 (ADR-009, 마이그레이션 없음)

[ADR-009](../architecture/adr/ADR-009-connection-loss-rate.md)(Accepted 2026-10-17, 사용자 결정 "Accept as recommended")을 구현했다(2ei, 로컬 검증, 새 마이그레이션 없음). 연결의 `lossRate`(`numeric(5,4)`, PC-14)는 이제 그 연결이 나르는 수량의 비율이고, 발행 snapshot `schemaVersion` 2부터 실행에 쓴다.

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-37 | 뜻과 저장: 비율 0 이상 1 미만(0.05 = 5%). PC-14의 `numeric(5,4)` 검사 뒤에 범위를 본다. 생성에서 빠지거나 수정의 명시적 `null`은 0이다(PC-14 그대로). 편집기 **Loss %**는 퍼센트(0 이상 100 미만, 소수 2자리까지)로 보이고 받으며 비율로 보낸다 | 400 `lossRate is a fraction of at least 0 and below 1 (0.05 is 5%).`, `numeric(5,4)`를 넘으면 PC-14 메시지 |
| GR-38 | 계산: 연결을 지날 때 도착 = 보낸 수량 × (1 − r)을 소수 4자리 HALF_UP으로 반올림, 손실 = 보낸 − 도착. 보낸 수량이 소수 4자리를 넘어 반올림이 보낸 것보다 커지면 보낸 만큼만 도착한다 | |
| GR-39 | 보낸 수량: 그 연결로 가는 수량(일반 노드·Exclusive·Join은 출력, Split은 그 몫, GR-30). 출력에 양수 `quantity`가 없으면 빼지 않고 그대로 보낸다 | |
| GR-40 | 검사 순서: 연결 용량(GR-03)·단위·출발 포트 스키마·검증식은 보낸 수량으로, 도착 포트 스키마·검증식(GR-07·GR-08)은 도착 수량으로 본다. 손실이 없으면 지금과 같다 | 409 그대로 |
| GR-41 | 기록: 다음 단계 입력 스냅샷의 `quantity`는 도착 수량, 다른 값은 그대로. 출발 단계에 이벤트 `transfer_loss`(`connectionId`·`targetNodeId`·`sent`·`lossRate`·`lost`·`arrived`·`unit`). Split의 `gateway_routed` 수량은 몫(보낸 수량)이다. Join 도착(`arrivals[].quantity`)은 도착 수량이고 합친 수량은 그 합이다. 새 표 없음 | |
| GR-42 | 도착 0: 양수가 반올림으로 모두 사라지면 단계를 만들지 않고 `connection_filtered` reason `loss_empty`(`transfer_loss`는 남김). Exclusive가 고른 연결이 그렇게 되어도 `NO_ROUTE`가 아니고 게이트웨이는 완료된다 | |
| GR-43 | 이미 발행한 revision: 발행 snapshot·`workflow_revision.schema_version`이 2부터 손실을 적용한다. 1(2ei 전 발행본)은 `lossRate`를 갖고 있어도 지금처럼 전량을 보낸다. 2 이상 발행본에 범위 밖 값이 있으면 실행 시작 409 | 409 `Published revision has an invalid lossRate: <id>` |
| GR-44 | 발행 검사: 오류 `LOSS_RATE_RANGE`(0 미만 또는 1 이상, 규칙 전에 저장된 값). 실행 화면: 손실이 있었던 단계에 `received 98 kg · lost 2 kg`(Split 몫 줄과 같은 자리, Join 단계는 제외) | 발행 409 |

검증(2026-10-17, 격리 복사본): `ConnectionLossIntegrationTest` 6건 — 손실 2%(발행 `schemaVersion` 2, 100 kg → B 입력 98 kg·`unit` 그대로, `transfer_loss` 100·0.02·2·98·kg), Split 몫과 Join(70% 몫에 1% → 69.3, `gateway_routed` 수량 70, Split→Join 20% 몫에 10% → 도착 18, B→J 손실 50%로 69.3 → 34.65, C 10 → Join 62.65, 뒤 단계 입력 62.65), 검사 순서(용량 99에 100을 2% 손실로 보내도 409, 출발 포트 `quantity >= 100`·도착 포트 `quantity < 99`는 통과해 98 도착), 도착 0(0.0001에 60% → B 단계 없음·`loss_empty`·`transfer_loss` 손실 0.0001·도착 0, Exclusive가 고른 연결도 단계 없이 게이트웨이 완료, 실행 마감), 예전 발행본(`schemaVersion` 1로 되돌린 발행본은 100 그대로·`transfer_loss` 없음), 저장·발행 검사(생성 1·−0.1 400, 수정 1 400, 0.9999 저장, 예전 값 1.5는 `LOSS_RATE_RANGE`·발행 409). `ProcessConnectionDecimalValidationIntegrationTest`의 `lossRate` 경계를 9.9999에서 0.9999로 바꿨다(계약 변경). 관련 백엔드 113건(손실·Split·Join·Exclusive·그래프 실행·워크플로 검사·연결·포트·revision·데이터 흐름·모듈 경계·마이그레이션) 중 테스트 자체의 단정 1건만 실패 → 고쳐 다시 통과, 전체 백엔드 1,437건 실패 0. 프런트 Vitest 662건(`lossPercent.test.ts` 3건), 모의 E2E `connection-loss-editor.spec.ts`(저장된 0.02가 `2`로 보임, 100 거절·보내지 않음, 2.5 → 0.025)·`flow-run-split.spec.ts`(`received 69.3 kg · lost 0.7 kg`) 반복 통과.

## 2026-10-17 연결 지연 (ADR-010, 마이그레이션 없음)

[ADR-010](../architecture/adr/ADR-010-connection-delay.md)(Accepted 2026-10-17, 사용자 결정 "Accept as recommended")을 구현했다(2ej, 로컬 검증, 새 마이그레이션 없음). 연결의 `delayTimeSec`(`numeric(10,2)`, PC-14)는 이제 다음 단계를 시작할 수 있는 시각을 정하고, 발행 snapshot `schemaVersion` 3부터 실행에 쓴다.

| 규칙 | 동작 | 위반 응답 |
|---|---|---|
| GR-45 | 저장: 0 이상(초). PC-14의 `numeric(10,2)` 검사 뒤에 범위를 본다. 생성에서 빠지거나 수정의 명시적 `null`은 0이다(PC-14 그대로). 편집기 **Delay (s)**는 음수를 보내기 전에 거절한다 | 400 `delayTimeSec is 0 or more seconds.` |
| GR-46 | 계산: 연결을 지나 만든 단계의 `scheduledAt` = 기준 시각 + 지연(마이크로초로 자름). 기준은 출발 단계의 완료 시각이고, 출발이 게이트웨이면 그 게이트웨이가 받은 시각이다. 지연이 없고 넘겨받은 시각도 없으면 지금처럼 비어 있다 | |
| GR-47 | 게이트웨이: Exclusive·Split은 지금처럼 바로 지나가되 받은 시각을 자기 `scheduledAt`에 남기고 나가는 연결로 넘긴다(지연이 더해진다). Join은 지나갈 때 도착들의 준비 시각(출처 기준 + 그 연결의 지연, 지연이 없는 도착은 출처 완료 시각) 가운데 가장 늦은 것을 받는다. 도착 중 하나라도 지연이 있을 때만 남긴다. 게이트웨이 단계의 `scheduledAt`은 "받은 시각"이다 | |
| GR-48 | 시작: `scheduledAt` 전 `start`는 지금처럼 409. 서버는 단계를 스스로 시작하지 않는다 | 409 `Step cannot start before its scheduledAt.` |
| GR-49 | 바닥: 지연으로 정한 시각보다 앞당기거나 지우는 `schedule`은 409. 늦추는 것은 된다. 재시도 대기(EP5)는 그대로다 | 409 `Step's input is ready at <시각>; schedule it at or after then.` |
| GR-50 | 기록: 지연으로 예정 시각을 정한 단계에 `step_scheduled`(`scheduledAt`, `reason` `connection_delay`, `connectionId`, `delaySeconds`). Join은 `scheduledAt`·`reason`만 | |
| GR-51 | 이미 발행한 revision: 발행 snapshot·`workflow_revision.schema_version`이 3부터 지연을 적용한다. 1·2(2ej 전 발행본)는 `delayTimeSec`가 있어도 지금처럼 바로 시작할 수 있다. 3 이상 발행본에 음수가 있으면 실행 시작 409 | 409 `Published revision has an invalid delayTimeSec: <id>` |
| GR-52 | 발행 검사: 오류 `DELAY_RANGE`(규칙 전에 저장된 음수). 실행 화면: 지연이 정한 예정 시각 옆에 `· after a 2 h delay`(그 뒤 누가 일정을 바꾸면 보이지 않음) | 발행 409 |

검증(2026-10-17, 격리 복사본): `ConnectionDelayIntegrationTest` 5건 — 지연 7 200초(발행 `schemaVersion` 3, B `scheduledAt` = A 완료 + 2시간, 그 전 `start` 409, `step_scheduled` `connection_delay`·연결·7200·같은 시각), 게이트웨이(A →1 800→ Exclusive X →3 600→ C: X는 바로 완료·`scheduledAt` A + 30분, C는 A + 90분; Join: B는 지연 없음·`scheduledAt` 없음, D→J 600초 → J는 D 완료 + 10분, 뒤 단계 E도 같은 시각·그 전 `start` 409), 바닥(1시간 당기기 409, 지우기 409, 1시간 늦추기 200), 예전 발행본(`schemaVersion` 2로 되돌린 발행본은 `scheduledAt` 없음·`step_scheduled` 없음·바로 `start`), 저장·발행 검사(생성 −1 400, 수정 −1 400, 0.5 저장, 예전 값 −5는 `DELAY_RANGE`·발행 409). 지연·손실·Split·Join·Exclusive·그래프 실행·워크플로 검사·연결·revision·모듈 경계 104건 실패 0, 전체 백엔드 1,442건 실패 0. 프런트 Vitest(`delayText.test.ts` 1건), 모의 E2E `flow-run-delay.spec.ts`(`after a 2 h delay`, 다시 잡은 단계엔 없음)·`connection-loss-editor.spec.ts`(지연 −5 거절·보내지 않음) 2회 반복 통과.
