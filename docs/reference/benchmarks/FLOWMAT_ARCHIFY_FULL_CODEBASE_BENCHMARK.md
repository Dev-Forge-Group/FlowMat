# FlowMat × Archify 코드베이스 벤치마킹 및 구현 청사진

> **참고 문서(동결)** · 갱신하지 않는다. 쓴 날 기준의 근거·조사이며, 현행 문서와 다르면 현행 문서가 우선한다. 지금 기준은 [docs/README.md](../../README.md), 이 폴더 안내는 [reference/README.md](../README.md).

> 대상: `SeolJhin/FlowMat`, `tt-a1i/archify`\
> 조사 기준일: 2026-09-27\
> 목적: Archify를 FlowMat에 복제하는 것이 아니라, 실제 코드 구조를
> 비교해 FlowMat의 2D/Graph Engine·Validation·Simulation-ready
> architecture에 가져올 요소와 가져오지 않을 요소를 결정한다.

------------------------------------------------------------------------

## 0. 조사 범위와 판정 기준

이번 조사는 README 비교가 아니라 두 저장소의 recursive tree, 현행
source/test/config 구조, 코드 검색 결과와 핵심 실행 경로를 기준으로
수행했다.

특히 FlowMat은 현행 `flowmat_backend/`, `flowmat_frontend/` 외에
`legacy/`, build/report 산출물이 함께 존재한다. 따라서 벤치마킹 판단은
**현행 소스**를 기준으로 하고, `legacy/`는 역사적 구조 확인용으로만
취급한다. `build-*`, coverage HTML, 생성 artifact, 대형 정적
HTML/asset은 구현의 원본(source of truth)이 아니므로 동일 가중치로
평가하지 않는다.

Archify는 `archify/` 아래의 schema, renderer, geometry, CLI, validation,
delta, tests를 중심으로 분석한다. 예제 HTML처럼 생성된 결과물은 renderer
동작 확인용으로 취급한다.

### 조사에서 구분한 네 종류

1.  **현행 source** --- 실제 제품 동작의 기준.
2.  **test/fixture** --- 의도와 회귀 조건 확인.
3.  **docs/examples** --- 설계 의도와 사용법 확인.
4.  **generated/legacy/assets** --- 참고 자료. 구현 판단의 1차 근거로
    사용하지 않음.

따라서 이 문서의 "전수조사"는 저장소 전체 구조를 inventory한 뒤 구현
파일을 역할별로 분류하고 핵심 실행 경로를 코드 수준에서 추적하는 의미다.
동일한 generated HTML 수백 개를 한 줄씩 독해했다는 의미가 아니다.

------------------------------------------------------------------------

# 1. Executive Conclusion

두 프로젝트는 겉으로는 "노드와 선을 그리는 시스템"처럼 보이지만 핵심
목적이 다르다.

**Archify**는 typed JSON IR을 입력으로 받아 schema/semantic/geometry
품질을 검증하고 deterministic한 기술 다이어그램을 렌더링·검사·전달하는
**Diagram-as-Code compiler/quality pipeline**에 가깝다.

**FlowMat**은 사용자가 직접 공정을 편집하고
Process/ProcessIo/ProcessConnection에 물질·자원·item·unit·조건·capacity
등의 의미를 부여한 뒤 이를 저장하고 향후 실행/시뮬레이션/ERP·MES
영역까지 연결하는 **stateful process modeling application**이다.

따라서 결론은 다음과 같다.

``` text
절대 하면 안 되는 방향
────────────────────────────────────
FlowMat React editor
        ↓ 제거
Archify renderer로 교체

권장 방향
────────────────────────────────────
FlowMat Interactive Editor
        │
        ▼
FlowMat Graph IR
        │
 ┌──────┼───────────────┐
 ▼      ▼               ▼
Schema  Domain           Geometry
Validator Validator      Validator
        │
        ▼
Structured Diagnostics
        │
        ▼
Save / Revision Gate
        │
        ▼
Simulation-ready Graph
```

Archify에서 가장 가치 있는 것은 "렌더러" 그 자체보다 다음 다섯 가지다.

1.  **renderer-independent typed IR**
2.  **schema version + strict validation**
3.  **pure geometry validation library**
4.  **stable structured diagnostics**
5.  **render/save 결과를 검증하는 quality gate**

반대로 FlowMat에서 절대로 잃으면 안 되는 것은 다음이다.

-   interactive editing
-   Process / ProcessIo / ProcessConnection의 도메인 의미
-   item/resourceType/unit/schema compatibility
-   condition/capacity/delay/loss/failurePolicy
-   workflow/project 권한 및 persistence
-   ERP/MES 및 simulation으로 확장 가능한 domain model

------------------------------------------------------------------------

# 2. 프로젝트 성격 비교

  ----------------------------------------------------------------------------------------
  영역              Archify                 FlowMat                      판단
  ----------------- ----------------------- ---------------------------- -----------------
  주 목적           검증 가능한 기술        범용 공정 설계·관리·실행     목적 자체가 다름
                    다이어그램 생성         기반                         

  입력 방식         JSON IR 중심            Interactive editor +         FlowMat 유지
                                            API/domain                   

  편집기            WYSIWYG 핵심 제품이     React 기반 interactive       FlowMat 우위
                    아님                    canvas                       

  그래프 의미       architecture/workflow   process/resource/item/unit   FlowMat이 훨씬
                    등 diagram semantics    semantics                    깊음

  Schema            강한 JSON Schema 계약   API/DTO/domain validation    Archify 벤치마킹
                                            중심                         

  Geometry          매우 강함               상대적으로 약함              최우선 벤치마킹
  validation                                                             

  Structured        안정된                  BusinessException 중심       벤치마킹 가치 큼
  diagnostics       code/evidence/fix                                    

  Rendering         deterministic SVG/HTML  interactive React canvas     서로 역할이 다름

  Persistence       artifact-oriented       DB/stateful                  FlowMat 고유

  ERP/MES           없음                    핵심 확장 영역               FlowMat 고유

  Simulation        실행 엔진 목적 아님     장기 핵심 목표               FlowMat 고유

  Delta/compare     stable ID 기반 비교     revision과 결합 여지         참고 가치 큼
                    구조 존재                                            

  Verified delivery 강함                    DB transaction/revision 관점 개념 변형 도입
                                            필요                         
  ----------------------------------------------------------------------------------------

------------------------------------------------------------------------

# 3. Archify 코드 구조

Archify에서 확인되는 핵심 축은 다음과 같다.

``` text
archify/
├─ bin/
│  ├─ archify.mjs
│  ├─ preview.mjs
│  ├─ visual-check.mjs
│  └─ open-artifact.mjs
├─ schemas/
│  ├─ common...
│  ├─ architecture...
│  ├─ workflow...
│  ├─ sequence...
│  ├─ dataflow...
│  └─ lifecycle...
├─ renderers/
│  ├─ shared/
│  │  ├─ geometry.mjs
│  │  └─ diagnostics...
│  ├─ workflow/
│  ├─ architecture/
│  ├─ sequence/
│  ├─ dataflow/
│  └─ lifecycle/
├─ delta/
│  └─ architecture-delta.mjs
├─ examples/
├─ test/
└─ assets/
```

이 구조의 중요한 점은 **IR/schema와 renderer와 geometry 검증을 한
덩어리로 만들지 않았다는 것**이다.

FlowMat도 이 분리를 받아들이는 것이 좋다.

------------------------------------------------------------------------

# 4. Archify IR / Schema에서 가져올 핵심

Archify의 typed renderer는 임의 JavaScript object를 그대로 신뢰하지
않는다. JSON Schema를 계약으로 사용하고, 공통 정의와 diagram별 schema를
분리한다.

주요 특징:

-   `schema_version`
-   `diagram_type`
-   `meta`
-   diagram별 collection
-   공통 ID/point/component/legend/view 구조
-   `additionalProperties: false`
-   enum 기반 값 제한
-   관계의 `from`, `to`
-   routing 관련 `fromSide`, `toSide`, `route`, `via`, label 위치
-   schema 검증 이후 추가 cross-reference 검증

`additionalProperties: false`는 FlowMat 관점에서 특히 중요하다.

현재 프론트 canvas payload가 계속 확장될 때 알 수 없는 field를 조용히
저장하면 다음 문제가 발생한다.

``` text
Frontend A
   ↓ unknown property
Backend
   ↓ 그대로 저장
Frontend B
   ↓ 의미를 모름
Revision / Simulation
   ↓
재현 불가능 상태
```

Graph IR의 외부 경계에서는 unknown field를 거부하거나 migration 대상으로
분류해야 한다.

------------------------------------------------------------------------

# 5. Archify Geometry Engine 분석

핵심 파일:

`archify/renderers/shared/geometry.mjs`

이 파일은 renderer에 강하게 종속된 imperative UI 코드가 아니라 **pure
geometry helper** 중심으로 설계되어 있다.

확인된 대표 함수/책임:

### `isFinitePoint`

계산된 좌표에 `NaN`, `undefined`, Infinity 등이 들어가는 것을 막는다.

SVG의 다음과 같은 조용한 오염을 방지한다.

``` xml
<rect x="NaN" ... />
```

FlowMat에서도 좌표/크기 validation의 가장 낮은 계층에 동일한 방어가
필요하다.

### `rectsOverlap`

두 사각형과 optional gap을 사용해 overlap을 계산한다.

중요한 구현 철학은 non-finite geometry를 무조건 overlap으로 판정하지
않는다는 것이다. 좌표 자체가 잘못된 오류와 overlap 오류를 분리한다.

이 방식은 diagnostics 품질에 중요하다.

나쁜 결과:

``` text
NODE_POSITION_INVALID
NODE_OVERLAP
NODE_OVERLAP
NODE_OVERLAP
...
```

좋은 결과:

``` text
NODE_POSITION_INVALID
```

원인 하나에 파생 오류 수십 개를 붙이지 않는 것이다.

### `segmentIntersectsRect`

connection segment가 node/obstacle 사각형을 관통하는지 검사한다.

FlowMat에서 가장 직접적으로 가져올 수 있는 기능이다.

### `segmentRectClearance`

line과 rectangle 사이 최소 clearance를 계산한다.

단순 collision뿐 아니라 "너무 가까워서 읽기 어려운 관계선"을 판정할 수
있다.

### `segmentRectIntersectionLength`

선이 rectangle 내부를 얼마나 지나가는지 계산한다.

단순 boolean보다 evidence가 강하다.

### `collectLabelRouteClearance`

다른 relationship의 route와 label rect 사이 clearance를 측정한다.

FlowMat에서 edge label이 다른 edge를 가리거나 지나가는 문제에 그대로
응용 가능하다.

### `routeHonorsEndpointSides`

`fromSide`/`toSide`를 단순 anchor 위치가 아니라 **방향 계약(direction
contract)** 으로 취급한다.

예를 들어 오른쪽 port에서 나가는 선이 첫 segment부터 왼쪽으로 역주행하면
시각적으로 부자연스러울 뿐 아니라 authored intent를 위반한 것으로
판단한다.

FlowMat의 Handle도 장기적으로 같은 의미를 가질 수 있다.

------------------------------------------------------------------------

# 6. Archify Clean Flow Validation

대표적인 rule:

`clean-flow/endpoint-side-direction`

diagnostic에 포함되는 evidence:

-   endpoint
-   authoredField
-   sideOrigin
-   side
-   segmentIndex
-   from
-   to
-   expectedAxis
-   expectedDirection

그리고 `supportedFixes`가 존재한다.

즉 오류 문자열 하나가 아니다.

``` text
code
severity
subject
evidence
supportedFixes
```

구조다.

또 다른 핵심:

`clean-flow/edge-through-node`

관계선의 각 segment와 unrelated obstacle을 검사한다.

source/target node는 의도적으로 예외 처리한다.

이 설계는 매우 중요하다. "모든 node와 edge 충돌"을 검사하면 endpoint가
node border에 닿는 정상 관계까지 오류가 되기 때문이다.

FlowMat GeometryValidator에서도:

``` text
source node
target node
```

는 해당 connection의 obstacle 검사에서 제외해야 한다.

------------------------------------------------------------------------

# 7. Archify의 Geometry 철학

Archify가 좋은 이유는 geometry algorithm 하나 때문이 아니다.

``` text
측정
 ↓
판정
 ↓
stable code
 ↓
subject
 ↓
numeric evidence
 ↓
supported fix
```

가 하나의 pipeline으로 연결되어 있기 때문이다.

FlowMat은 이 구조를 벤치마킹해야 한다.

------------------------------------------------------------------------

# 8. Archify Diagnostics를 FlowMat에 적용

현재 FlowMat backend는 의미 있는 domain validation을 이미 많이
수행하지만 실패 결과가 주로 `BusinessException`이다.

예:

-   `"A connection cannot link a process to itself."`
-   `"Ports are already connected."`
-   `"Connected ports itemId must match."`
-   `"Connection itemId must match the ports."`
-   `"Connected ports resourceType must match."`
-   `"Connection unit type must match port units."`

사람에게는 읽기 쉽지만 editor가 오류를 programmatically 처리하기에는
부족하다.

FlowMat은 다음 구조로 발전시키는 것이 좋다.

``` json
{
  "code": "flowmat/domain/resource-type-mismatch",
  "severity": "error",
  "subject": {
    "workflowId": "...",
    "connectionId": "...",
    "sourcePortId": "...",
    "targetPortId": "..."
  },
  "evidence": {
    "sourceResourceType": "electricity",
    "targetResourceType": "material"
  },
  "supportedFixes": [
    "change target port resource type",
    "connect to a compatible input port"
  ]
}
```

그러면 frontend가 diagnostic을 클릭했을 때 해당 edge와 port를 직접
focus할 수 있다.

------------------------------------------------------------------------

# 9. FlowMat Backend 핵심 분석

핵심 파일:

`flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/application/ProcessConnectionServiceImpl.java`

이 파일은 FlowMat이 단순 diagram editor가 아님을 가장 명확하게 보여준다.

## 9.1 createConnection 흐름

코드 흐름을 요약하면:

``` text
requireWorkflowWriteAccess
 ↓
PESSIMISTIC_WRITE workflow lock
 ↓
requireProcessWriteAccess(from)
 ↓
requireProcessWriteAccess(to)
 ↓
validateProcessMembership
 ↓
ProcessConnection 생성
 ↓
validateProcessIo(output)
 ↓
validateProcessIo(input)
 ↓
validateItem
 ↓
handle 결정
 ↓
connection properties
 ↓
validateConnectionContract
 ↓
save
 ↓
GraphSyncService.broadcast
```

이미 persistence consistency와 collaboration/sync를 고려한 구조다.

Archify renderer로 이 영역을 대체할 이유가 전혀 없다.

------------------------------------------------------------------------

# 10. ProcessIo 방향 계약

`validateProcessIo`는 port가 실제 process에 속하는지 검사하고
direction을 강제한다.

source:

``` text
output
```

target:

``` text
input
```

즉 FlowMat connection은 시각적인 `A → B` 이상의 의미를 갖는다.

권장 diagnostic:

``` text
flowmat/domain/source-port-direction
flowmat/domain/target-port-direction
```

------------------------------------------------------------------------

# 11. Connection Contract

`validateConnectionContract`는 현재 FlowMat의 가장 중요한 validation
nucleus 중 하나다.

확인된 검증은 다음과 같다.

### Condition Expression

`ConditionExpression.compile(...)`

조건식 자체를 compile하고 source schema에 선언된 attribute인지 검사한다.

### Self Connection

``` text
fromProcessId == toProcessId
```

금지.

### Duplicate Port Pair

동일 `fromIoId → toIoId` 연결이 이미 존재하는지 검사한다.

### Deleted Port Reference

connection이 삭제된 port를 가리키는 것을 막는다.

### Port Ownership

source port가 `fromProcessId` 소속인지, target port가 `toProcessId`
소속인지 확인한다.

### Direction

source = output, target = input.

### PortSchema

`PortSchema.parseStored`

로 저장 schema를 읽는다.

### Condition Attribute Contract

condition expression이 source schema에서 선언되지 않은 attribute를
참조하지 못하게 한다.

### Item Compatibility

source와 target port의 itemId가 둘 다 있으면 동일해야 한다.

connection 자체의 itemId도 port item과 일치해야 한다.

특정 조건에서는 connection item을 port에서 infer한다.

### Resource Type

source/target의 `resourceType` 또는 fallback `ioType`이 일치해야 한다.

### Schema Compatibility

`PortSchema.requireCompatible(sourceSchema, targetSchema)`

를 호출한다.

### Unit Type

source/target/connection unit을 UnitMaster에서 찾아 unit type 집합을
만들고 서로 다른 type이 섞이면 거부한다.

이 정도면 FlowMat domain validator는 Archify보다 훨씬 복잡한 의미 검증을
이미 가지고 있다.

------------------------------------------------------------------------

# 12. Connection Runtime/Simulation 관련 속성

현재 ProcessConnection에서 확인되는 주요 속성:

-   connectionType
-   connectionLabel
-   flowRate
-   conditionExpr
-   capacity
-   failurePolicy
-   unit
-   delayTimeSec
-   lossRate
-   priority
-   version
-   versionNonce

이것은 Graph IR 설계 시 절대로 React Flow Edge의 임의 `data` 객체에만
종속시키면 안 된다.

권장:

``` text
ConnectionIR
├─ id
├─ source
├─ target
├─ sourcePort
├─ targetPort
├─ semantics
│  ├─ itemId
│  ├─ resourceType
│  ├─ unit
│  ├─ flowRate
│  ├─ capacity
│  ├─ delay
│  ├─ lossRate
│  ├─ condition
│  └─ failurePolicy
└─ presentation
   ├─ sourceHandle
   ├─ targetHandle
   ├─ route
   ├─ label
   └─ style
```

**semantics와 presentation을 분리해야 한다.**

------------------------------------------------------------------------

# 13. FlowMat Frontend 핵심

핵심 파일:

`flowmat_frontend/src/pages/workspace/ui/CanvasViewport.tsx`

그리고 package dependency를 기준으로 현재 interactive canvas는 React
Flow/@xyflow 계열을 중심으로 한다.

FlowMat의 editor는 다음 책임을 갖는다.

``` text
사용자 pointer/keyboard
 ↓
CanvasViewport
 ↓
node/edge state
 ↓
nodeTypes / edgeTypes
 ↓
handle / connection interaction
 ↓
domain/API state
```

이 interactive loop는 Archify의 static/deterministic renderer와 목적이
완전히 다르다.

따라서 React Flow를 Archify로 교체하는 것은 벤치마킹이 아니라 기능
제거다.

------------------------------------------------------------------------

# 14. 가장 중요한 구조적 문제: ReactFlow Model ≠ Domain Model

장기적으로 반드시 다음 관계를 만든다.

``` text
FlowMatNodeIR
      ↓ adapter
ReactFlow Node

FlowMatConnectionIR
      ↓ adapter
ReactFlow Edge
```

반대 방향도 필요하다.

``` text
ReactFlow interaction
      ↓
Editor Command
      ↓
Graph IR mutation
      ↓
ReactFlow projection
```

React Flow의 Node/Edge shape가 FlowMat 영구 저장 포맷의 SSOT가 되면 안
된다.

이유:

1.  renderer 교체가 어려워진다.
2.  simulation engine이 UI library type에 의존한다.
3.  backend가 frontend library 개념을 알아야 한다.
4.  export renderer가 React Flow를 우회하기 어렵다.
5.  schema migration이 UI library upgrade와 결합된다.

------------------------------------------------------------------------

# 15. 추천 FlowMat Graph IR

초기 v1 예시:

``` text
FlowMatGraphIR
├─ schemaVersion
├─ graphId
├─ projectId
├─ workflowId
├─ metadata
├─ nodes[]
├─ ports[]
├─ connections[]
├─ graphics[]
├─ groups[]
├─ annotations[]
├─ viewport
└─ simulation
```

Node:

``` text
NodeIR
├─ id
├─ kind
├─ processRef
├─ geometry
│  ├─ x
│  ├─ y
│  ├─ width
│  ├─ height
│  └─ rotation
├─ presentation
└─ metadata
```

Port:

``` text
PortIR
├─ id
├─ nodeId
├─ direction
├─ resourceType
├─ itemId
├─ unit
├─ schema
└─ presentation
```

Connection:

``` text
ConnectionIR
├─ id
├─ sourceNodeId
├─ targetNodeId
├─ sourcePortId
├─ targetPortId
├─ semantics
└─ presentation
```

------------------------------------------------------------------------

# 16. IR Versioning

Archify의 `schema_version` 개념은 FlowMat에서 P0급으로 도입할 가치가
있다.

``` text
GraphIR v1
   ↓ migrate
GraphIR v2
   ↓ migrate
GraphIR v3
```

중요한 원칙:

DB migration과 Graph IR migration은 같은 것이 아니다.

``` text
DB Schema Version
≠
Graph IR Schema Version
≠
API Version
```

각각 독립적으로 발전할 수 있어야 한다.

------------------------------------------------------------------------

# 17. Validator 계층 분리

추천 구조:

``` text
GraphValidationPipeline

1. SchemaValidator
2. ReferenceValidator
3. DomainValidator
4. GeometryValidator
5. SimulationValidator
```

## SchemaValidator

-   required
-   enum
-   primitive type
-   unknown field
-   schema version

## ReferenceValidator

-   node reference
-   port reference
-   group reference
-   connection endpoint
-   dangling ID
-   duplicate ID

## DomainValidator

현재 `ProcessConnectionServiceImpl`, `WorkflowValidationService`,
`PortSchema` 계열이 가진 의미 규칙.

## GeometryValidator

Archify에서 가장 직접적으로 벤치마킹할 부분.

## SimulationValidator

실행 전에만 필요한 규칙.

------------------------------------------------------------------------

# 18. Geometry Validator 상세 설계

## `flowmat/geometry/non-finite`

대상:

-   x/y
-   width/height
-   route point

ERROR.

다른 geometry rule보다 먼저 실행한다.

## `flowmat/geometry/node-overlap`

rectangle intersection.

편집 중에는 WARNING 또는 editor assist로 사용 가능.

공정 의미상 겹침을 허용하는 group/container가 있다면 예외 정책 필요.

## `flowmat/geometry/edge-through-node`

Archify `clean-flow/edge-through-node`의 직접적인 벤치마킹 대상.

알고리즘:

``` text
for connection
  route = polyline(connection)
  endpoints = {sourceNode, targetNode}

  for obstacle node
    if obstacle in endpoints:
       continue

    for segment in route
       if segmentIntersectsRect(segment, obstacle, clearance):
          diagnostic
```

## `flowmat/geometry/endpoint-side-direction`

Handle이 node의 어느 side에 있는지 알고 있을 경우 첫/마지막 segment
방향을 검사한다.

## `flowmat/geometry/edge-crossing`

서로 다른 polyline segment의 proper crossing 검사.

단, 공통 endpoint와 authored junction은 별도 취급.

## `flowmat/geometry/ambiguous-corridor`

여러 connection이 거의 같은 corridor를 지나가 의미를 구분하기 어려운
경우.

## `flowmat/geometry/label-node-collision`

edge/node label rectangle과 node rectangle 비교.

## `flowmat/geometry/label-edge-clearance`

Archify `collectLabelRouteClearance` 방식 벤치마킹.

## `flowmat/geometry/micro-segment`

라우팅 결과에 매우 짧은 segment가 생기는 경우.

자동 routing bug를 찾는 데 유용하다.

## `flowmat/geometry/off-canvas`

export 또는 bounded canvas가 필요한 경우 사용.

------------------------------------------------------------------------

# 19. Structured Diagnostic 모델

Backend 권장 DTO:

``` text
GraphDiagnostic
├─ code
├─ severity
├─ message
├─ subject
├─ evidence
└─ supportedFixes
```

Severity:

``` text
INFO
WARNING
ERROR
BLOCKING
```

다만 `BLOCKING`을 severity로 둘지 `blocksSave`, `blocksSimulation`
정책으로 분리할지는 구현 시 결정하는 것이 낫다.

추천은 severity와 gate를 분리하는 것이다.

``` text
severity = ERROR
blocksSave = false
blocksSimulation = true
```

같은 경우가 존재할 수 있기 때문이다.

------------------------------------------------------------------------

# 20. 기존 BusinessException과 공존시키는 방법

BusinessException을 한 번에 제거하지 않는다.

권장:

``` text
Domain rule
  ↓
GraphDiagnostic 생성
  ↓
API validation endpoint에서는 diagnostics[] 반환
  ↓
command/save endpoint에서 blocking diagnostic 존재
  ↓
BusinessException/ValidationException 변환
```

즉 structured diagnostics가 domain rule의 결과가 되고, exception은 HTTP
transaction을 중단시키는 adapter가 된다.

------------------------------------------------------------------------

# 21. Save Gate

권장 pipeline:

``` text
EDIT
 ↓
Client Fast Validation
 ↓
SAVE
 ↓
Server Schema Validation
 ↓
Reference Validation
 ↓
Domain Validation
 ↓
Revision 생성
 ↓
GraphSync broadcast
```

Geometry error가 무조건 save를 막아야 하는 것은 아니다.

예:

-   edge-through-node: save 허용, publish/export 경고 가능
-   invalid port reference: save 차단
-   resourceType mismatch: save 또는 executable 상태 차단
-   schema corruption: save 차단

정책을 validator 자체와 분리한다.

------------------------------------------------------------------------

# 22. Simulation Gate

FlowMat에는 다음 개념이 필요하다.

``` text
Editable
≠
Persistable
≠
Publishable
≠
Executable
```

예:

``` text
node overlap
```

은 실행 가능성에는 영향을 주지 않는다.

반면:

``` text
resourceType mismatch
```

는 실행 의미를 깨뜨린다.

따라서:

``` text
Visual Valid
Domain Valid
Simulation Valid
```

을 분리한다.

------------------------------------------------------------------------

# 23. Verified Delivery를 FlowMat식으로 재해석

Archify의 candidate → validate → receipt → atomic commit 사고방식은
유용하지만 파일 artifact 방식을 그대로 가져오면 안 된다.

FlowMat식:

``` text
Draft Graph
 ↓
Validate
 ↓
Canonical Serialize
 ↓
Hash
 ↓
Revision
 ↓
Commit Transaction
```

Revision에 저장할 수 있는 정보:

``` text
revisionId
workflowId
schemaVersion
graphHash
createdAt
createdBy
validationSummary
parentRevisionId
```

향후 simulation result도 특정 graph hash/revision에 묶을 수 있다.

``` text
SimulationRun
 └─ graphRevisionId
```

그러면 "어떤 그래프로 실행한 결과인지" 재현 가능하다.

------------------------------------------------------------------------

# 24. Archify Delta에서 참고할 점

`archify/delta/architecture-delta.mjs`처럼 stable ID를 기준으로 두
architecture 상태의 차이를 계산하는 사고방식은 FlowMat revision
compare에 가치가 있다.

FlowMat에서는:

``` text
Node added
Node removed
Node moved
Port changed
Connection added
Connection removed
Resource contract changed
Simulation parameter changed
```

를 구분할 수 있다.

특히 다음 두 변경은 동일하게 취급하면 안 된다.

``` text
node.x: 100 → 120
```

vs

``` text
port.resourceType: water → electricity
```

전자는 presentation change, 후자는 semantic change다.

Revision diff에서도 둘을 분리한다.

------------------------------------------------------------------------

# 25. Canvas Object 확장 전략

FlowMat이 PPT/Figma 수준의 도형·선·텍스트 기능까지 가려면 Process
entity와 일반 graphics를 동일 개념으로 만들지 않는 것이 좋다.

추천:

``` text
CanvasObject
├─ DomainObject
│  ├─ ProcessNode
│  └─ ResourceNode
│
├─ GraphicObject
│  ├─ Rectangle
│  ├─ Ellipse
│  ├─ Triangle
│  ├─ Polygon
│  ├─ Line
│  ├─ Text
│  └─ Image
│
└─ Group
```

그러나 persistence에서는 지나친 Java inheritance보다 IR의 discriminated
union 구조가 더 적합할 수 있다.

예:

``` json
{
  "id": "shape-1",
  "kind": "rectangle",
  "geometry": {},
  "style": {}
}
```

------------------------------------------------------------------------

# 26. 렌더러 독립 구조

최종 목표:

``` text
                  FlowMat Graph IR
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
ReactFlow Adapter    SVG Exporter     Simulation Adapter
        │
Interactive UI
```

향후:

``` text
Canvas/WebGL Renderer
PPTX Exporter
PDF/SVG Exporter
Thumbnail Renderer
```

를 추가해도 domain model은 변하지 않아야 한다.

------------------------------------------------------------------------

# 27. Archify에서 그대로 가져오지 말아야 할 것

## 27.1 Renderer 교체

비채택.

Archify는 FlowMat의 interactive editor 대체물이 아니다.

## 27.2 Diagram Type를 FlowMat Domain으로 그대로 사용

비채택.

Archify의 architecture/workflow/sequence/dataflow/lifecycle는 기술
다이어그램 taxonomy다.

FlowMat의 process/resource/simulation domain과 동일하지 않다.

## 27.3 모든 Geometry Error로 저장 차단

비채택.

FlowMat은 editor다. 작성 중 불완전 상태가 정상이다.

## 27.4 Artifact Delivery를 DB Transaction 대신 사용

비채택.

개념만 revision/hash에 응용한다.

## 27.5 SVG를 SSOT로 사용

비채택.

SVG는 projection/output이어야 한다.

------------------------------------------------------------------------

# 28. FlowMat에서 절대로 퇴행시키면 안 되는 부분

Archify 벤치마킹 과정에서 다음을 단순화하면 안 된다.

### ProcessIo

단순 anchor point로 축소 금지.

### ProcessConnection

단순 `{source,target}` edge로 축소 금지.

### Item

시각 label로 축소 금지.

### resourceType

edge style/color 정도로 취급 금지.

### Unit

표시 문자열만으로 취급 금지.

### PortSchema

JSON metadata 정도로 약화 금지.

### ConditionExpression

renderer label로만 취급 금지.

이들은 simulation-ready semantics다.

------------------------------------------------------------------------

# 29. 코드 위치별 추천 변경

## Frontend 신규 영역

현재 구조와 충돌을 최소화하려면 editor 내부에서 시작한다.

``` text
flowmat_frontend/src/
  graph/
    ir/
      types.ts
      schema.ts
      migrations/
    adapter/
      reactFlowAdapter.ts
    geometry/
      primitives.ts
      intersections.ts
      routing.ts
    validation/
      validateGeometry.ts
      diagnostics.ts
      rules/
    commands/
      graphCommands.ts
```

실제 package naming은 현재 frontend conventions에 맞춰 조정한다.

## Backend 신규 영역

``` text
flowmat_backend/src/main/java/org/myweb/flowmat/
  domain/workflow/
    graph/
      validation/
      diagnostic/
      revision/
```

또는 현재 domain layering이 application/domain으로 엄격히 나뉜다면 그
convention을 따른다.

------------------------------------------------------------------------

# 30. 기존 코드에서 우선 refactor할 후보

### `ProcessConnectionServiceImpl`

현재 validation이 서비스 내부에 상당히 집중되어 있다.

당장 쪼개기보다 characterization test를 먼저 만든다.

그 다음:

``` text
ConnectionContractValidator
PortCompatibilityValidator
ConnectionResourceValidator
ConnectionUnitValidator
```

등으로 점진 추출한다.

주의: 지나치게 작은 validator class 수십 개로 쪼개는 것도 피한다.

초기에는 다음 정도가 적당하다.

``` text
ConnectionContractValidator
 ├─ reference checks
 ├─ port contract
 ├─ item/resource/unit
 └─ condition
```

------------------------------------------------------------------------

# 31. Validation Endpoint 제안

예:

``` text
POST /api/workflows/{workflowId}/validate
```

응답:

``` json
{
  "schemaVersion": 1,
  "valid": false,
  "diagnostics": []
}
```

추후:

``` text
?profile=edit
?profile=save
?profile=simulate
?profile=export
```

처럼 gate profile을 둘 수 있다.

------------------------------------------------------------------------

# 32. Client Fast Validator와 Server Authoritative Validator

Geometry는 frontend에서 즉시 계산하는 것이 UX상 좋다.

Domain은 server가 authoritative해야 한다.

추천:

  Rule                  Client      Server
  --------------------- ----------- -------------
  non-finite geometry   O           O
  node overlap          O           선택
  edge-through-node     O           export 시 O
  dangling port         O           O
  direction             O           O
  resourceType          O           O
  item                  가능        O
  unit master           캐시 의존   O
  PortSchema            일부        O
  permission            X           O
  DB membership         X           O

------------------------------------------------------------------------

# 33. Test Strategy

Archify에서 가장 강하게 벤치마킹해야 할 부분 중 하나가 geometry
regression test다.

FlowMat에 필요한 test fixture:

``` text
geometry/
├─ overlap.json
├─ edge-through-node.json
├─ crossing.json
├─ label-collision.json
├─ micro-segment.json
└─ endpoint-direction.json
```

각 fixture는:

``` text
input GraphIR
expected diagnostic codes
expected subjects
expected evidence
```

를 가진다.

UI screenshot만으로 검증하지 않는다.

------------------------------------------------------------------------

# 34. Characterization Test 우선

리팩터링 전에 현재 ProcessConnection 동작을 고정한다.

최소 테스트:

1.  self connection 거부
2.  duplicate port pair 거부
3.  deleted source port 거부
4.  deleted target port 거부
5.  wrong source process 거부
6.  wrong target process 거부
7.  source direction 거부
8.  target direction 거부
9.  item mismatch 거부
10. selected item mismatch 거부
11. resourceType mismatch 거부
12. PortSchema incompatibility 거부
13. unit type mismatch 거부
14. condition undeclared attribute 거부
15. item inference 성공
16. default handle 생성
17. version 증가
18. GraphSync broadcast

------------------------------------------------------------------------

# 35. Migration Plan

## Phase 0 --- Characterization

목표:

현재 동작을 보존한다.

작업:

-   backend connection validation test 강화
-   frontend canvas serialization test
-   legacy/generated source 제외 규칙 명확화

완료 조건:

핵심 domain behavior가 테스트로 고정됨.

------------------------------------------------------------------------

## Phase 1 --- Graph IR v1

목표:

React Flow와 영구 domain 사이 canonical graph contract 정의.

작업:

-   `schemaVersion`
-   node
-   port
-   connection
-   presentation/semantics 분리

완료 조건:

React Flow node/edge 없이도 workflow graph를 JSON으로 표현 가능.

------------------------------------------------------------------------

## Phase 2 --- ReactFlow Adapter

목표:

UI library dependency 격리.

``` text
GraphIR → ReactFlow
ReactFlow interaction → Graph command
```

완료 조건:

domain/simulation code에서 `@xyflow/react` type import 없음.

------------------------------------------------------------------------

## Phase 3 --- Structured Diagnostics

목표:

문자열 exception을 machine-readable validation 결과로 확장.

완료 조건:

editor가 diagnostic을 클릭하여 node/edge/port focus 가능.

------------------------------------------------------------------------

## Phase 4 --- Geometry Library

우선 구현:

1.  finite geometry
2.  rectangle overlap
3.  segment/rect intersection
4.  edge-through-node
5.  label clearance
6.  endpoint direction
7.  crossing
8.  micro segment

완료 조건:

fixture 기반 deterministic test 통과.

------------------------------------------------------------------------

## Phase 5 --- Validation Profiles

``` text
EDIT
SAVE
EXPORT
SIMULATE
```

프로파일별 blocking 정책 정의.

------------------------------------------------------------------------

## Phase 6 --- Revision / Hash

canonical Graph IR serialization + hash.

완료 조건:

simulation result가 정확한 graph revision을 참조.

------------------------------------------------------------------------

## Phase 7 --- Graphic Objects

rectangle/ellipse/text/line/group 등 일반 2D object 확장.

Process Node와 일반 도형 의미를 분리.

------------------------------------------------------------------------

# 36. Top 20 Benchmarking Targets

    \# 대상                              Archify → FlowMat 판단        우선순위
  ---- --------------------------------- ----------------------------- ----------
     1 Typed Graph IR                    적극 도입                     P0
     2 schemaVersion                     적극 도입                     P0
     3 Strict unknown-field policy       변형 도입                     P0
     4 Renderer/domain 분리              적극 도입                     P0
     5 Stable diagnostic codes           적극 도입                     P0
     6 Diagnostic subject                적극 도입                     P0
     7 Numeric evidence                  적극 도입                     P1
     8 supportedFixes                    적극 도입                     P1
     9 Pure geometry helpers             적극 도입                     P1
    10 edge-through-node                 적극 도입                     P1
    11 endpoint-side-direction           적극 도입                     P1
    12 label-route clearance             적극 도입                     P1
    13 crossing detection                적극 도입                     P1
    14 micro-segment checks              도입                          P2
    15 quality profiles                  FlowMat gate profile로 변형   P1
    16 stable-ID delta                   revision compare에 변형       P2
    17 verified artifact concept         revision/hash로 변형          P2
    18 deterministic fixtures            적극 도입                     P0
    19 Architecture renderer 자체        미채택                        \-
    20 Static SVG를 editor core로 사용   미채택                        \-

------------------------------------------------------------------------

# 37. 최종 의사결정표

  -----------------------------------------------------------------------------
  기술/개념         결정              이유                   적용 위치
  ----------------- ----------------- ---------------------- ------------------
  React Flow        유지              interactive editing    frontend
                                      핵심                   

  Archify renderer  교체용으로 미채택 제품 목적이 다름       export 참고

  FlowMat Graph IR  신규 도입         UI/domain/simulation   shared contract
                                      분리                   

  JSON Schema       IR boundary에     strict contract        frontend/backend
                    도입                                     

  schemaVersion     도입              장기 migration 필수    IR

  PortSchema        유지·강화         FlowMat 핵심 semantic  backend

  Domain validation 유지·구조화       Archify보다 깊음       backend

  Geometry          신규 강화         현재 주요 결손         frontend/shared
  validation                                                 

  Structured        도입              editor UX/API 자동화   both
  diagnostics                                                

  Validation        도입              edit/save/simulate     both
  profiles                            차이                   

  Revision hash     도입              실행 재현성            backend

  Stable-ID diff    후속 도입         revision compare       backend/frontend

  CanvasObject      단계적 도입       일반 2D 확장           frontend/IR

  SVG export        renderer          출력 기능              exporter
                    adapter로 고려                           

  DB Entity = IR    금지              coupling 과다          architecture

  ReactFlow Node =  금지              vendor lock-in         architecture
  IR                                                         
  -----------------------------------------------------------------------------

------------------------------------------------------------------------

# 38. Recommended Implementation Order

실제 개발 순서는 다음이 가장 안전하다.

``` text
1. 현재 connection/domain validation 테스트 고정
2. Graph IR v1 정의
3. schemaVersion 정의
4. ReactFlow Adapter 도입
5. Diagnostic DTO/code 체계 도입
6. 기존 ProcessConnection validation을 diagnostic 구조에 연결
7. pure geometry library 작성
8. edge-through-node
9. overlap / label clearance / crossing
10. editor Problems panel
11. validate API
12. save/simulate validation profile
13. revision + canonical hash
14. stable-ID graph diff
15. 일반 CanvasObject
16. export renderer
```

이 순서의 핵심은 **새 엔진을 만들겠다고 현재 editor를 먼저 뜯지 않는
것**이다.

------------------------------------------------------------------------

# 39. 권장 Problems Panel

향후 FlowMat UI:

``` text
Problems (7)

ERROR 2
  CONNECTION_RESOURCE_TYPE_MISMATCH
  PORT_SCHEMA_INCOMPATIBLE

WARNING 5
  EDGE_THROUGH_NODE
  EDGE_CROSSING
  LABEL_CLEARANCE
  NODE_OVERLAP
  MICRO_SEGMENT
```

항목 클릭:

``` text
diagnostic.subject
 ↓
canvas locate
 ↓
select node/edge
 ↓
zoomTo
 ↓
property panel에서 관련 필드 강조
```

Archify의 structured diagnostics가 interactive FlowMat에서 훨씬 더 큰 UX
가치를 만들 수 있는 지점이다.

------------------------------------------------------------------------

# 40. 최종 Target Architecture

``` text
                         ┌──────────────────────┐
                         │   FlowMat Graph IR   │
                         │      SSOT / vN       │
                         └──────────┬───────────┘
                                    │
             ┌──────────────────────┼───────────────────────┐
             │                      │                       │
             ▼                      ▼                       ▼
   ReactFlow Adapter        Validation Engine       Simulation Adapter
             │                      │                       │
             ▼            ┌────────┼────────┐              ▼
     Interactive Editor   │        │        │       Execution Engine
                          ▼        ▼        ▼
                       Schema    Domain   Geometry
                          │        │        │
                          └────────┼────────┘
                                   ▼
                           Structured Diagnostics
                                   │
                 ┌─────────────────┼─────────────────┐
                 ▼                 ▼                 ▼
              EDIT Gate         SAVE Gate       SIMULATE Gate
                                   │
                                   ▼
                           Revision / Graph Hash
                                   │
                                   ▼
                              Spring Backend
                                   │
                                   ▼
                               PostgreSQL
```

------------------------------------------------------------------------

# 41. 최종 결론

Archify를 조사했을 때 FlowMat이 따라가야 할 핵심은 "예쁜 SVG"가 아니다.

가장 중요한 벤치마킹 대상은:

> **그래프를 명시적인 계약으로 만들고, 그 계약을 기계적으로 검증하며,
> geometry 문제도 문자열 경고가 아니라 구조화된 진단 결과로 만드는
> 방식**

이다.

FlowMat에는 이미 Archify가 가지지 않는 강력한 기반이 있다.

`ProcessConnectionServiceImpl`에서 확인되는 port 방향, process
membership, item, resourceType, PortSchema, unit, condition, capacity
등의 검증은 **실제 공정의 의미**를 다룬다.

따라서 최종 방향은:

``` text
Archify의 강점
────────────────
IR
Schema
Geometry
Diagnostics
Quality Gate
Deterministic Test

          +

FlowMat의 강점
────────────────
Interactive Editing
Process Domain
Port Contract
Item/Resource/Unit
Persistence
ERP/MES
Simulation

          =

FlowMat 자체 Graph/Process Engine
```

이다.

즉 Archify는 FlowMat의 대체 엔진이 아니라 **FlowMat 자체 엔진을 설계할
때 품질 계층을 어떻게 만들 것인지 보여주는 좋은 reference
implementation**이다.

FlowMat은 React Flow를 계속 UI engine으로 활용하되, React Flow 위에
FlowMat의 독립적인 Graph IR과 validation architecture를 세우는 것이 가장
중요하다.

그 구조가 만들어지면 이후 React Flow를 유지하든, 일부를 자체
Canvas/SVG/WebGL renderer로 교체하든, PPT/Figma 수준의 GraphicObject를
추가하든, simulation engine을 강화하든 핵심 domain contract를 다시 뜯을
필요가 없다.

그것이 이번 벤치마킹에서 얻을 수 있는 가장 큰 설계적 이득이다.
