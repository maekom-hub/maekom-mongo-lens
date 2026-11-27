# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.5-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.1%20~%202026.x-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English](#english) | [한국어](#한국어)**

---

## English

### Overview

**Maekom Mongo Lens** is an IntelliJ IDEA (Community & Ultimate) plugin built for safe querying, real-time debugging, index diagnosis, and multi-collection join analysis on **MongoDB** and **AWS DocumentDB**. It automatically discovers Spring Boot `application*.yml` profiles (Zero-Config), locks production (`prd`/`prod`) databases as Read-Only by default, and delivers fast, freeze-free performance even on massive collections.

---

### Key Features

- **Zero-Overhead Initial Preview & Read-Only Index Inspector (`IndexInspectorDialog`)**
  - Clicking a collection opens the workspace tab immediately with a loading spinner and fetches documents in a single roundtrip (`.limit(N).batchSize(N)`), skipping `explain()` / `listIndexes()` when no `Filter`/`Sort` is active (`No Filter (Preview)` badge).
  - When a `Filter` or `Sort` is specified, asynchronously inspects `QUERY_PLANNER` plans (`IXSCAN` / `COLLSCAN` / `UNKNOWN` with 1,500ms `maxTimeMS` and zero full-scan `EXECUTION_STATS`).
  - Click the execution plan badge or right-click any collection → **`Inspect Indexes & Query Guide...`** to inspect indexes (60s TTL cache), apply one-click Filter templates, and copy ESR (`Equality → Sort → Range`) `createIndex` recommendations — strictly **100% read-only**.
- **Smart Keyboard Autocomplete for `Filter` / `Sort` / `Project`**
  - Navigate suggestions seamlessly with **`↑` / `↓`** (wrap-around + auto-scroll), confirm with **`Enter` / `Tab`** (inserts `"field": `, `"$in": []`, or `"field": 1` cleanly without triggering query execution or newlines), and dismiss with **`Esc`**.
  - Automatically suppresses unwanted immediate re-opening after confirmation until the next character is typed.
- **IntelliJ New UI Native Context Menu (5 Groups + `AllIcons`)**
  - Preserves `Ctrl`/`Shift` multi-selection across **Tree** and **Table** views with instant native `ActionPopupMenu` styling and 16×16 `AllIcons`:
    - **Dynamic Quick Filter & Sort**: single `=` / `!=`, same-field `$in` / `$nin`, 2-date range `$gte ~ $lte`, cross-field `AND` / `$or` (safely wraps existing `$or` in `$and`), nested `$exists` / empty, UTC time range filters, and quick Sort (`1` / `-1`).
    - **Edit**, **Copy Submenu** (Value / Key:Value JSON / Field Path), **Reference Jump & Join**, and **Document Diff** (2-doc compare + cross-tab Baseline pin).
- **Collection Join View (`A ⋈ B ⋈ C`) — Composite Join Keys & Auto-Match**
  - **Composite Join Keys (`AND` Matching) & Auto-Match Common Keys**: Add 1..N join key pairs via **`[+ Add Join Condition]`** or append shared field names (excluding `_id`) without overwriting existing rows via **`[Auto-Match Common Keys]`**.
  - **Hybrid `$lookup` Pipeline**: Uses index-friendly `localField`/`foreignField` for single-key joins and automatically switches to MongoDB & AWS DocumentDB (4.0/5.0) compatible `let` + `pipeline` (`local_k0..kN`, `null`/missing guard, dynamic `$cond($isArray)`, and `LATEST_ONE` `$sort`/`$limit: 1` pushdown) for 2+ keys — protected by `15,000ms maxTimeMS` and Single-Flight cancellation.
  - **Nested / `Array<Object>` Keys, Consistent Paging & Unlimited Mode**: Supports dot-notation paths (e.g. `items.product_id`), `LATEST_ONE` / `UNWIND_ALL` (`1:1` / `1:N`), deterministic post-join `$skip`/`$limit(N+1)`, **`20 / 50 / 100 / 500 / Unlimited (5,000 cap)`**, live **`Edit Join`**, **`Copy Pipeline`**, and join-key summary badges.
- **Intuitive Mode/View Navigation & Real-Time KO/EN UI**
  - Bottom-accent mode tabs (`Documents (Find)` / `Aggregations` / `Mongo Shell`), icon-backed segmented view switcher (`Tree` | `Table` | `JSON`) with Lazy off-EDT rendering & display truncation, AES temporary field decryption, and instant Korean/English switching without restarting the IDE.

---

### Installation

1. Download **`maekom-mongo-lens-0.2.5.zip`** from [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases).
2. In IntelliJ IDEA, go to **`Settings / Preferences` → `Plugins` → ⚙️ (Gear Icon) → `Install Plugin from Disk...`**.
3. Select the downloaded `.zip` archive and restart the IDE.
4. Open the **`Maekom Mongo Lens`** tool window at the bottom of the IDE.

---

### Quick Start & Usage

1. **Register Connections (Zero-Config YAML or Custom URI)**
   - Spring Boot `application*.yml` profiles appear automatically in the left **Connections** tree (or choose **`+ Add Custom URI...`** from the profile dropdown). Production (`prd`/`prod`) profiles are automatically locked as **`READ-ONLY 🔒`**.
2. **Instant Preview & Conditional Execution Plan**
   - Click any collection to open its tab immediately in **`No Filter (Preview)`** mode.
   - Type in `Filter` / `Sort` / `Project` using **`↑`/`↓`** and **`Enter`/`Tab`** autocomplete, then press `Ctrl + Enter` (`Cmd + Enter`) to run and inspect **`IXSCAN`** / **`COLLSCAN`** plans.
3. **Multi-Selection Quick Filter**
   - Select one or multiple cells/fields with `Ctrl` / `Shift`, then right-click to filter (`=`, `!=`, `$in`, `$nin`, Date `$gte ~ $lte`, `AND`, `$or`) or sort immediately.
4. **Single & Composite-Key Collection Join View**
   - Right-click any key field → **`Join with Another Collection...`** → pick target collection(s), add extra keys with **`[+ Add Join Condition]`** or **`[Auto-Match Common Keys]`**, select columns & limit mode, and click **`Run in Join Tab ▶`**.

---

### Changelog (`v0.2.5`)

- **Composite Join Keys (`AND`) & Auto-Match Common Keys**: Dynamic multi-key rows in `JoinViewDialog` with scrollable key list, duplicate/empty validation, BSON type/performance notice banner, hybrid standard vs `let`+`pipeline` `$lookup` (`null` guard, `$cond($isArray)`, `LATEST_ONE` pushdown), `15,000ms maxTimeMS`, join-key header summary badge, and synced `Copy Pipeline`.
- **Keyboard Navigation for `Filter` / `Sort` / `Project` Autocomplete**: High-priority `↑`/`↓` wrap-around navigation, `Enter`/`Tab` context-aware completion without accidental execution/newline, `Esc` dismiss, and post-confirm reopen suppression across all 3 query inputs.

---

### Build from Source

```bash
./gradlew buildPlugin --no-daemon
```

Generated archive: `build/distributions/maekom-mongo-lens-0.2.5.zip`

---

## 한국어

**[▲ Top / English](#english)**

### 개요

**Maekom Mongo Lens**는 **MongoDB** 및 **AWS DocumentDB** 환경에서 안전한 데이터 조회, 실시간 디버깅, 인덱스 진단, 다중 컬렉션 조인 분석을 수행할 수 있는 IntelliJ IDEA (Community & Ultimate) 플러그인입니다. Spring Boot의 `application*.yml`을 자동으로 탐색(Zero-Config)하고 운영(`prd`/`prod`) DB를 기본 읽기 전용으로 보호하며, 대용량 컬렉션에서도 UI 프리징 없이 쾌적하게 동작합니다.

---

### 주요 기능

- **컬렉션 클릭 즉시 미리보기(Zero-Overhead) & 100% 읽기 전용 인덱스 뷰어 / Filter 가이드 (`IndexInspectorDialog`)**
  - `Filter`/`Sort` 조건 없이 컬렉션을 클릭하면 즉시 탭과 로딩 스피너를 열고 `.limit(N).batchSize(N)` 단 1회 왕복으로 본 데이터만 빠르게 표시합니다(`조건 없음 (Preview)` 배지, `explain()`/`listIndexes()` 생략).
  - 조건을 입력해 조회할 때만 비동기로 `QUERY_PLANNER` 실행 계획(`IXSCAN` / `COLLSCAN` / `UNKNOWN`, 1,500ms `maxTimeMS`, 풀스캔 유발 `EXECUTION_STATS` 미사용)을 분석합니다.
  - 실행 계획 배지를 클릭하거나 좌측 트리 컬렉션 우클릭 → **`인덱스 조회 및 쿼리 가이드...`** 에서 60초 캐시된 인덱스 목록, 원클릭 Filter 템플릿 적용, 운영/DBA 협의용 ESR(`Equality → Sort → Range`) `createIndex` 복사 전용 구문을 확인할 수 있습니다(100% 읽기 전용).
- **`Filter` / `Sort` / `Project` 스마트 자동완성 (키보드 `↑`/`↓` 이동 및 `Enter`/`Tab` 적용)**
  - 자동완성 팝업 표시 중 **`↑` / `↓`** 로 순환 이동, **`Enter` / `Tab`** 으로 현재 토큰 위치에 문맥 맞춤 삽입(`"field": `, `"$in": []`, `"field": 1`) 후 쿼리 오실행·줄바꿈 없이 팝업 닫기, **`Esc`** 로 닫기를 지원합니다.
  - 항목 확정 직후에는 다음 문자 타이핑 전까지 팝업 재오픈을 1회 억제하여 이어지는 `Enter` 실행을 방해하지 않습니다.
- **New UI 네이티브 컨텍스트 메뉴 (5개 논리 그룹 + 전 항목 `AllIcons`)**
  - **Tree** / **Table** 뷰에서 `Ctrl`/`Shift` 다중 선택을 유지한 채 우클릭하면 IntelliJ New UI 네이티브 `ActionPopupMenu`와 16×16 아이콘 메뉴가 0ms로 열립니다:
    - **동적 빠른 필터 & 정렬**: 단일 `=`/`!=`, 동일 필드 `$in`/`$nin`, 2개 날짜 범위 `$gte ~ $lte`, 이종 필드 `AND`/`$or`(기존 `$or` 보존을 위해 `$and` 안전 래핑), 중첩 `$exists`/비어있음, `ObjectId`/`Date` UTC 범위 필터, 즉시 정렬(`1`/`-1`).
    - **데이터 수정**, **키/값 복사 서브메뉴**(값만/JSON/필드 경로), **참조 컬렉션 점프 & 조인**, **문서 Diff 비교**(2건 비교 + 크로스탭 Baseline 고정).
- **컬렉션 조인 뷰 (`A ⋈ B ⋈ C`) — 다중 연결키 `AND` 복합 매칭 & 공통 키 자동 매칭**
  - **다중 연결키 & 공통 키 자동 매칭**: **`[+ 연결 조건 추가]`** 로 1~N개 연결키 행을 동적으로 추가하고, **`[공통 키 자동 매칭]`** 으로 기존 입력 행을 덮어쓰지 않은 채 이름이 동일한 공통 필드(`_id` 제외)만 하단에 자동 추가합니다.
  - **하이브리드 `$lookup` 파이프라인**: 단일 키는 인덱스 효율이 높은 표준 `localField`/`foreignField`를 유지하고, 2개 이상 복합 키에서는 MongoDB 및 AWS DocumentDB(4.0/5.0) 호환 `let` + `pipeline`(`local_k0..kN`, `null == null` 매칭 폭발 방지 가드, `$cond($isArray)` 동적 분기, 최신 1건 모드 `$sort`/`$limit: 1` 푸시다운)으로 자동 전환되며 `15,000ms maxTimeMS` 및 Single-Flight 취소 안전장치가 적용됩니다.
  - **`Array<Object>` 내부 키, 일관된 페이징 & 전체 조회**: `items.product_id` 등 중첩/배열 경로 지원, `1:1`(최신 1건)/`1:N`(전체 펼치기), 조인 후 단일 `$skip`/`$limit(N+1)` 페이징, **`20 / 50 / 100 / 500 / 전체 조회 (5,000건 안전 상한)`**, **`조건/컬럼 수정`**, **`파이프라인 복사`**, 상단 연결키 요약 배지를 제공합니다.
- **직관적인 모드/뷰 전환 UI & 실시간 한/영 전환**
  - 하단 강조 바가 적용된 모드 탭(`Documents (Find)` / `Aggregations` / `Mongo Shell`), 아이콘 세그먼트 뷰 스위처(`Tree` | `Table` | `JSON`, Lazy 백그라운드 렌더링 및 대용량 표시 절삭), AES 임시 복호화, IDE 재시작 없는 `KO ↔ EN` 즉시 전환을 지원합니다.

---

### 설치 방법

1. [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases)에서 **`maekom-mongo-lens-0.2.5.zip`** 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → ⚙️(기어 아이콘) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하고 IDE를 재시작합니다.
4. 하단 ToolWindow에서 **`Maekom Mongo Lens`** 탭을 엽니다.

---

### 사용 방법

1. **커넥션 등록 (Zero-Config YAML 또는 Custom URI)**
   - 프로젝트의 Spring Boot `application*.yml` 프로필이 좌측 **Connections** 트리에 자동 표시됩니다. (직접 연결은 드롭다운의 **`+ Custom URI 추가...`** 선택, `prd`/`prod`는 자동 **`READ-ONLY 🔒`** 보호)
2. **컬렉션 클릭 조회 & 조건부 실행 계획 확인**
   - 컬렉션을 클릭하면 **`조건 없음 (Preview)`** 상태로 즉시 조회됩니다.
   - `Filter` / `Sort` / `Project` 입력창에서 **`↑`/`↓`** 이동 및 **`Enter`/`Tab`** 자동완성으로 조건을 작성한 뒤 `Ctrl + Enter` (`Cmd + Enter`)로 조회하면 **`IXSCAN`** / **`COLLSCAN`** 분석 배지가 표시됩니다.
3. **다중 선택 우클릭 빠른 필터**
   - `Table` 셀 또는 `Tree` 노드를 `Ctrl`/`Shift`로 여러 개 선택한 뒤 우클릭하면 선택 조합에 맞는 필터(`=`, `!=`, `$in`, `$nin`, 날짜 `$gte ~ $lte`, `AND`, `$or`)나 정렬이 즉시 적용됩니다.
4. **단일 / 다중 연결키 컬렉션 조인 뷰 실행**
   - 기준 필드 우클릭 → **`다른 컬렉션과 조인해서 보기...`** → 대상 컬렉션 선택 후 **`[+ 연결 조건 추가]`** 또는 **`[공통 키 자동 매칭]`** 으로 복합 연결키를 설정하고, 컬럼·조회 건수를 선택해 **`조인 탭으로 실행 ▶`** 을 클릭합니다.

---

### 릴리즈 노트 (`v0.2.5` Changelog)

- **다중 연결키(Composite Join Keys) 조인 & 공통 키 자동 매칭**: 동적 1~N 연결키 행 UI, 기존 행 보존형 `[공통 키 자동 매칭]`, 중복/빈 값 검증 및 BSON 타입·성능 안내 배너, 단일/복합 하이브리드 `$lookup` (`let` + `pipeline`, `null` 가드, `$cond($isArray)`, 최신 1건 `$sort`/`$limit: 1` 푸시다운), `15,000ms maxTimeMS` 안전장치, 조인 탭 상단 연결키 요약 배지 및 `파이프라인 복사` 동기화.
- **`Filter` / `Sort` / `Project` 자동완성 키보드 조작 지원**: 3개 쿼리 에디터 공통 키 이벤트 우선 가로채기(`↑`/`↓` 순환 이동, `Enter`/`Tab` 오실행 없는 문맥 삽입, `Esc` 닫기, 확정 직후 재오픈 1회 억제).

---

### 소스 빌드 방법

```bash
./gradlew buildPlugin --no-daemon
```

빌드 산출물: `build/distributions/maekom-mongo-lens-0.2.5.zip`

---

### License

Licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
