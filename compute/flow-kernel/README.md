# flowmat-flow-kernel

FlowMat 흐름의 의미를 한 곳에 모을 Rust 크레이트다([ADR-040](../../docs/architecture/adr/ADR-040-flow-kernel.md)). 포트 호환, 조건식, 검증 31규칙, 경로 계산을 맡는다. 편집기는 WebAssembly로 그리는 즉시 검증하고 돌려 본다. 서버가 쓸지는 4주차에 잰다(결정 6). 그때까지 Java가 정본이다.

## 지금 하는 것

검증과 경로 계산 둘 다 Java와 같은 답을 낸다. `cargo test`가 고정 입력의 모든 그래프를 맞춘다.

| 모듈 | Java 원본 | 하는 일 |
|---|---|---|
| `validation` | `WorkflowValidationService.check`, `ConnectionContract` | 초안 검증 31규칙. 이슈의 코드·대상·메시지·근거·고치는 법·숨기는 경고·순서가 같다 |
| `routing` | `PublishedFlowRunGraphReader`, `FlowRunGraph`, `FlowRunPortContracts` | 발행된 판 읽기, 출력 하나가 나가는 연결마다 하는 일(이유·양·손실), 합류에 아직 올 수 있는 연결 수 |
| `schema` | `PortSchema` | 포트 스키마 읽기와 호환 |
| `ports` | `ProcessIoServiceImpl.validatePortValues`, `ResourceTypes` | 포트 값과 표준 자원 종류 |
| `decimal` | `BigDecimal` | 자리수(scale)를 지키는 십진수. 반올림은 HALF_UP, 출력은 `toPlainString`이다 |
| `graph` | Jackson 바인딩, Java 문자열 규칙 | 서버 응답 모양의 그래프 읽기. `trim`·`isBlank`·`equalsIgnoreCase`가 Java와 같다 |

- 조건식은 아직 `compute/condition-expression` 크레이트를 경로 의존성으로 쓴다. 이 크레이트로 흡수하는 것은 [월간 계획](../../docs/status/MONTH-PLAN-2026-11-23.md) 2주차 3번이다.
- 노드 실행 정책(`nodePolicies`)과 실행 바인딩은 경로와 상관없으므로 읽지 않는다.
- 아직 없는 것: 시작 노드부터 끝까지 따라가는 "돌려 보기"의 반복(3주차 2번).

## 편집기에서

편집기는 이 크레이트의 WebAssembly 모듈 `flowmat_frontend/src/shared/wasm/flowmat_flow_kernel.wasm` 하나를 싣는다. 크기는 561KB, gzip으로 168KB다. 작업 화면을 처음 열 때 늦게 싣는다.

- 이 모듈은 조건식 크레이트를 링크하므로 `check`·`evaluate`도 내보낸다. 조건식 입력칸의 검사(`conditionLanguage.ts`)도 같은 인스턴스를 쓴다.
- **그리는 대로 검사**(`useLiveValidation`, `LiveValidation.tsx`): 캔버스가 150ms 멈추면 검사한다.
  - 캔버스 보기 모델을 커널의 그래프로 바꿔(`toKernelGraph`) 단위 목록과 함께 넣는다.
  - "As drawn: N errors, M warnings" 한 줄을 늘 보이고, 누르면 이슈 목록이 열린다.
  - 서버의 "Check workflow"와 발행 전 검사는 그대로다.

| 함수 | 넣는 것 | 답 |
|---|---|---|
| `validate` | `{graph, knownUnits}` | `{errors, warnings, issues}`(서버 응답과 같음) |
| `route` | `{graph, probes: [{nodeId, output}], schemaVersion}` | `{error}` 또는 `{roots, delays, joins, probes: [{decisions} 또는 {error}]}` |
| `check`, `evaluate` | 조건식 크레이트와 같음 | 조건식 크레이트와 같음 |

호스트가 `alloc`으로 받은 메모리에 UTF-8 JSON을 쓰고, 답은 JSON 텍스트의 포인터(상위 32비트)와 길이(하위 32비트)로 받아 `dealloc`으로 돌려준다.

소스를 바꾸면 모듈을 다시 만들어 함께 커밋한다. `wasm/build.sh`는 경로를 바꿔 적어 어느 기계에서 빌드해도 같은 바이트가 나오고, CI가 `--check`로 커밋된 모듈과 소스의 빌드를 비교한다.

```bash
wasm/build.sh
```

```bash
node wasm/check-fixtures.mjs
```

```bash
cd compute/flow-kernel
```

```bash
cargo test
```

## 고정 입력

| 파일 | 내용 | 누가 쓰나 |
|---|---|---|
| `fixtures/cases.json` | 손으로 고른 그래프 10개: 문제없는 줄·게이트웨이·분할과 합류, 기본 경로 둘, 분할 몫 초과, 스키마 불일치(하나, 둘), 순환, 반올림으로 다 잃는 손실, 빈 흐름. 몇 개는 보낼 출력(`probes`)도 있다 | 사람 |
| `fixtures/validation.json` | 위의 그래프와 고정 시드(`20261123`)로 만든 그래프 160개, 각각에 `WorkflowValidationService`가 낸 답(`errors`, `warnings`, `issues`의 순서·근거·고치는 법·숨기는 경고), 알려진 단위(`context.knownUnits`) | `FlowKernelFixtureTest` |
| `fixtures/routing.json` | 위의 그래프와 고정 시드(`20261124`)로 만든 그래프 120개, 노드마다 보낼 출력, 실행 그래프(`FlowRunGraph`)의 답 | `FlowKernelRouteFixtureTest` |

- 그래프 하나는 `{processes, ports, connections}`이고, 필드 이름은 서버의 공정·포트·연결과 같다. 알려진 단위는 파일의 `context.knownUnits` 하나다. 표준 자원 종류는 커널에 고정한다(`ResourceTypes`와 같은 목록).
- 백엔드 테스트 `FlowKernelFixtureTest`가 Java의 지금 답이 파일과 같은지, 31규칙이 각각 한 번 이상 걸리는지 본다. 백엔드 CI는 이 폴더가 바뀌어도 돈다.

경로 계산의 답(`routing.json`의 `expected`):

- 그래프는 발행된 판(`schemaVersion` 3)으로 읽는다. 포트는 판의 이름 `processIos`로 들어간다. 읽지 못하면 `error`(`code`, `message`)다. 순환, 없는 공정, 범위 밖 손실률, 깨진 조건, 나머지가 둘인 분할 같은 것이다.
- 읽으면 다음을 낸다.
  - `roots`: 들어오는 연결이 없는 노드.
  - `delays`: 연결마다 지연 초와 근무 시간 기준 여부.
  - `joins`: 합류마다 시작 노드에서 아직 올 수 있는 연결 수.
  - `probes`: 출력마다 나가는 연결의 결정(`willRoute`, `reason`, `quantity`, `loss`) 또는 단계가 나아가지 못하는 이유(`error`).
- 숫자는 적힌 자리수 그대로다(`0.0000`, `2.50`). 서버가 판과 출력을 읽는 방식과 같다.
- 결정의 이유는 모두 한 번 이상 나온다: `first_match`, `default`, `condition_false`, `exclusive_not_chosen`, `default_not_needed`, `split`, `split_empty`, `loss_empty`. 이유 없는 보통 연결도 나온다. 테스트가 이것을 센다.

검사를 일부러 바꿨으면 Java의 답을 다시 쓰고, 바뀐 줄을 읽은 뒤 커널을 맞춘다.

```bash
cd flowmat_backend
```

```bash
./gradlew test --tests '*FlowKernel*' -Dflow.fixtures.update=true
```
