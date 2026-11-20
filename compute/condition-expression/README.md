# flowmat-condition

FlowMat 조건식 언어의 Rust 구현이다. 백엔드의 Java `ConditionExpression`(`flowmat_backend/modules/workflow/.../domain/expression/ConditionExpression.java`)과 같은 결과, 같은 오류 문구, 같은 오류 위치를 낸다. [월간 계획](../../docs/status/MONTH-PLAN-2026-11-16.md)의 "계산 하나를 Rust로 구현해 같은 입력으로 비교"다. 서버는 여전히 Java를 쓴다. 이 크레이트를 어디에 쓸지는 [성능 기준선 §5](../../docs/operations/performance-baseline.md)의 판단을 따른다.

## 언어

공정 연결의 조건과 포트의 검증 규칙에 쓴다. 산출물 스냅숏의 `quantity`, `unit`, `item`, `attrs.<이름>`과 숫자·문자열·`true`/`false`를 `= != < <= > >=`로 비교하고 `and`, `or`, `not`, 괄호로 묶는다. 함수 호출과 산술은 없다.

- 숫자는 값으로 비교한다. `2`, `2.0`, `2.00`은 같고 반올림하지 않는다. 스냅숏의 숫자도 적힌 그대로 읽는다(`12345678901234567890.123`).
- 문자열은 UTF-16 코드 단위 순서로 비교한다(Java `String.compareTo`). U+FFFF 위의 문자는 코드 포인트 순서와 다르다.
- 참·거짓은 `=`, `!=`만 된다. 형이 다르거나 값이 없거나 `null`이면 비교는 거짓이다.
- 공백과 숫자는 JDK 21이 `Character.isWhitespace`, `Character.isDigit`로 판정하는 것과 같다. U+001C–U+001F는 공백이고 U+00A0, U+0085는 아니다. `١٢.٥` 같은 아랍·데바나가리·전각 숫자도 숫자다.
- 원문은 UTF-16 500단위까지다. 오류 위치는 1부터 세는 UTF-16 단위다. JavaScript 편집기가 세는 방식과 같다.

## 같은 답인지 확인하는 법

`fixtures/`를 Java와 Rust가 함께 읽는다.

| 파일 | 내용 | 누가 쓰나 |
|---|---|---|
| `cases.json` | 손으로 고른 원문 141개와 스냅숏 15개 | 사람 |
| `cases.expected.json` | 각 원문에 Java가 답한 것: 오류(위치·사유), 또는 읽는 속성과 스냅숏마다의 결과(`1`/`0`), 선언된 속성 검사. JDK의 공백·숫자 표 | `ConditionExpressionFixtureTest` |
| `generated.json` | 고정 시드로 만든 원문 1,500개(1,129개가 컴파일된다)와 스냅숏 24개, Java의 답 | `ConditionExpressionFixtureTest` |

- 백엔드 테스트 `ConditionExpressionFixtureTest`가 Java의 지금 답이 파일과 같은지 본다.
- `cargo test`가 Rust의 답이 파일과 같은지 본다(`tests/fixtures.rs`).
- `node wasm/check-fixtures.mjs`가 WebAssembly 빌드를 거쳐 같은 것을 본다.

언어를 일부러 바꿨으면 Java의 답을 다시 쓰고, 바뀐 줄을 읽은 뒤 Rust를 맞춘다.

```bash
cd flowmat_backend
```

```bash
./gradlew test --tests '*ConditionExpressionFixtureTest' -Dcondition.fixtures.update=true
```

## 실행

```bash
cargo test
```

```bash
cargo build --release --lib --target wasm32-unknown-unknown
```

```bash
node wasm/check-fixtures.mjs
```

툴체인은 `rust-toolchain.toml`이 정한다(1.97.0, `wasm32-unknown-unknown` 포함). CI는 `.github/workflows/compute.yml`이다.

시간 비교는 저장소 루트에서 `scripts/perf/condition-bench.sh`로 한다.

## 편집기에서

편집기는 이 크레이트의 WebAssembly 빌드로 연결 조건과 포트 검증 규칙을 입력하는 중에 검사한다(`flowmat_frontend/src/shared/lib/conditionLanguage.ts`). 빌드된 모듈은 `flowmat_frontend/src/shared/wasm/flowmat_condition.wasm`에 커밋돼 있어 프런트 빌드와 CI에 Rust가 필요 없다. 소스를 바꾸면 다시 만들어 함께 커밋한다.

```bash
wasm/build.sh
```

`wasm/build.sh`는 경로를 바꿔 적어(`--remap-path-prefix`) 어느 기계에서 빌드해도 같은 바이트가 나온다. compute CI가 `wasm/build.sh --check`로 커밋된 모듈이 이 소스의 빌드와 같은지 본다. 프런트의 `conditionLanguage.test.ts`가 그 모듈과 TypeScript 감싸개로 고정 입력 1,641개를 돌린다.

## WebAssembly 경계

바인딩 생성기 없이 C ABI로 내보낸다. 호스트는 `alloc(len)`으로 받은 메모리에 UTF-8을 쓰고, 결과는 JSON 텍스트의 포인터(상위 32비트)와 길이(하위 32비트)로 받아 `dealloc`으로 돌려준다. 쓰는 예는 `wasm/check-fixtures.mjs`다.

| 함수 | 답 |
|---|---|
| `check(source, declared)` | `{"ok":true,"attributes":[…],"declaredError":…}` 또는 `{"ok":false,"position":N,"reason":"…","message":"Invalid condition at position N: …"}`. `declared`는 스키마가 선언한 이름의 JSON 배열이고, 길이 0이면 선언 검사를 하지 않는다 |
| `evaluate(source, snapshot)` | 스냅숏(JSON 텍스트)에서 참이면 1, 거짓이면 0, 조건이 틀리면 -1, 스냅숏이 JSON이 아니면 -2 |

릴리스 빌드는 205KB, gzip 68KB다(대부분 `serde_json`).
