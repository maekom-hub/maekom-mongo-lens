# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.9-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.2%2B-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English](#english) | [한국어](#한국어)**

---

## English

### Overview

**Maekom Mongo Lens** is an IntelliJ IDEA (Community & Ultimate) plugin built for safe querying, type-safe data editing, real-time debugging, index diagnosis, and multi-collection join analysis on **MongoDB** and **AWS DocumentDB**. It automatically discovers Spring Boot `application*.yml` profiles (Zero-Config), provides a MongoDB Compass-style 4-tab connection manager with IntelliJ `PasswordSafe` credential protection and AWS DocumentDB TLS/CA bundle support, locks production (`prd`/`prod`) databases as Read-Only by default, and delivers fast, freeze-free performance even on massive collections.

---

### Requirements & License

| Item | Specification |
| :--- | :--- |
| **Version** | `0.2.9` |
| **IntelliJ IDEA** | `2024.2+` (Community / Ultimate — matches `sinceBuild = "242"`) |
| **Java Runtime (JBR)** | Minimum `JDK 17` / Recommended `JDK 21` |
| **Database Support** | `MongoDB 4.4 ~ 8.0` (Standalone, Replica Set, Atlas `mongodb+srv`) & `AWS DocumentDB 3.6 / 4.0 / 5.0` |
| **License** | `Apache-2.0` ([Apache License 2.0](LICENSE)) |

---

### Key Features

- **Type-Safe BSON Data Editing & Real-Time Validation (`FieldEditDialog` & `DocumentEditDialog`)**
  - Detects and preserves the exact original BSON type (`Int32`, `Int64`, `Double`, `Decimal128`, `String`, `Boolean`, `ObjectId`, `Date`, `Null`, `JSON (Object/Array)`), preventing accidental string overwrites on numeric/date/ObjectId fields.
  - Real-time `DocumentListener` validation highlights invalid values (Int32 overflow hints, decimal/NaN/Infinity guards, 24-hex ObjectId regex, strict Boolean, flexible `Date` parser supporting ISO-8601/`+09:00`/`yyyy-MM-dd HH:mm:ss` UTC/13-digit Epoch ms with **`[Now]`** button + live UTC ISO preview) and disables **`OK`** until valid.
  - Protects `_id`, blocks edits on Read-Only / Join / Aggregation / `_id`-less results, displays a **`Red (Prod)`** warning banner on production connections, and executes typed `$set` `updateOne` matching the original `_id` type (`matchedCount == 0` error reporting, array dot-path preservation such as `items.0.price`, and zero `maxTimeMS` on writes for AWS DocumentDB compatibility).
- **Collection JSON Data Import & 4-Stage Atomic Pre-Flight Validation (`ImportJsonDialog`)**
  - Import via Connections tree right-click → **`Import JSON Data...`** or the result toolbar **`Import JSON`** button (blocked immediately on Read-Only profiles).
  - Supports **single JSON object (`{...}`)**, **JSON array (`[...]`)**, and **NDJSON (`mongoexport` line-delimited)** with UTF-8 BOM stripping, Extended JSON (`$oid`, `$date`, `$numberLong`, `$numberDecimal`), **`Load .json File`** (`10MB` safety cap), **`Format JSON`**, **`Strip existing _id`**, and **`Validate Only (Dry-Run)`**.
  - Runs 4-stage atomic Pre-Flight checks off-EDT before touching the DB: (1) JSON syntax & DB-specific key rules (blocks `.` keys on AWS DocumentDB while allowing them on MongoDB), (2) in-batch + 500-item chunked DB `_id` duplicate detection (with Int/Long cross-checks), (3) recursive nested schema type mismatch checks against 50 sampled documents with safe numeric widening promotion, and (4) detailed copyable error alert aborting all writes on any error, or 500-doc chunked `insertMany` + automatic tab refresh when clean.
- **MongoDB Compass-Style 4-Tab Connection Manager & Bidirectional URI Sync**
  - Configure connections cleanly across **`General`**, **`Authentication`**, **`TLS/SSL`**, and **`Advanced`** tabs with live two-way MongoDB URI sync (`Edit Connection String` toggle, RFC-3986 password encoding, `mongodb+srv` rules, lossless Extra Query Params table), IntelliJ **`PasswordSafe`** credential storage, AWS DocumentDB **`global-bundle.pem`** multi-CA & mTLS client certificates, Environment Color Tags (`Dev`/`Stg`/`Prod`), and root-node context actions (`Connection Settings / View URI...`, Full/Masked URI & `mongosh` CLI copy, YAML read-only inspect + clone-to-Custom).
- **Zero-Overhead Initial Preview & Read-Only Index Inspector (`IndexInspectorDialog`)**
  - Opens tabs immediately with `.limit(N).batchSize(N)` single-roundtrip preview (`No Filter (Preview)` badge, skipping `explain()`/`listIndexes()` when unfiltered), runs bounded `QUERY_PLANNER` plans (`IXSCAN`/`COLLSCAN`/`UNKNOWN`) on filtered queries, and offers 100% read-only index inspection with Filter templates and ESR `createIndex` recommendations.
- **Smart Keyboard Autocomplete, 5-Group Native Context Menu & Composite-Key Collection Join View (`A ⋈ B ⋈ C`)**
  - `↑`/`↓` + `Enter`/`Tab` single-line autocomplete for `Filter`/`Sort`/`Project`; multi-selection Quick Filter & Sort; Document Diff; Star & Chain multi-collection joins with 1..N composite `AND` keys, **`Auto-Match Common Keys`**, hybrid `$lookup` compatible with MongoDB & AWS DocumentDB, AES temporary field decryption, and instant `KO ↔ EN` UI switching.

---

### Installation

1. Download **`maekom-mongo-lens-0.2.9.zip`** from [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases).
2. In IntelliJ IDEA (`2024.2+`), go to **`Settings / Preferences` → `Plugins` → ⚙️ (Gear Icon) → `Install Plugin from Disk...`**.
3. Select the downloaded `.zip` archive and restart the IDE.
4. Open the **`Maekom Mongo Lens`** tool window at the bottom of the IDE.

---

### Quick Start & Usage

1. **Manage Connections (Zero-Config YAML or 4-Tab Custom Dialog)**
   - Spring Boot `application*.yml` profiles appear automatically in the left **Connections** tree (`prd`/`prod` profiles are auto-locked as **`READ-ONLY 🔒`**).
   - Right-click any `[YAML]`/`[Custom]` connection → **`Connection Settings / View URI...`** to configure Hosts, Auth, AWS DocumentDB TLS/CA (`global-bundle.pem`), `retryWrites=false`, Color Tags, or copy a **`mongosh` CLI command**.
2. **Type-Safe Data Editing**
   - Right-click any field/cell in **Tree** or **Table** → **`Edit Data...`** to modify the field with its exact original BSON type preserved and real-time validation (or **`Edit Full Document...`** for structural/raw JSON edits).
3. **Pre-Flight Validated JSON Import**
   - Right-click any collection in the Connections tree → **`Import JSON Data...`** (or click **`Import JSON`** on the result toolbar), paste single/array/NDJSON or load a `.json` file, click **`Validate Only (Dry-Run)`** to verify syntax, `_id` uniqueness, and nested schema types, then click **`Import`**.
4. **Query, Quick Filter & Composite-Key Join View**
   - Use **`↑`/`↓`** and **`Enter`/`Tab`** autocomplete in `Filter`/`Sort`/`Project`, `Ctrl`/`Shift` multi-select right-click for dynamic Quick Filters, or right-click a key → **`Join with Another Collection...`** for single/composite-key joins.

---

### Changelog (`v0.2.9`)

- **Inline Green `+` Join Target Tab Bar (`JoinViewDialog`)**: Placed the compact textless green `+` icon button inline right beside the last join target chip (`6px` gap) inside a horizontally scrollable strip, with conditional per-tab `×` close buttons (shown only when 2+ targets exist) and active tab highlighting across Darcula and New UI Dark/Light themes.
- **Real-Time Searchable Field ComboBoxes (`SearchableFieldComboBox` / `FieldSearchFilter`)**: Enabled instant case-insensitive typing search with `startsWith` priority sorting, automatic popup opening, arrow/mouse selection conflict guards, caret preservation, and custom nested path input across all join-key mapping (source & target) and `WHERE AND` condition field selectors.
- **Bidirectional MongoDB & AWS DocumentDB Multi-Key Join Engine + JUnit 5 Tests**: Native `$lookup` on MongoDB + seamless hybrid 2-phase single-key `$lookup` / `$slice(500)` / `MultiKeyJoinFilterEngine` / signature-isolated cached `Next`/`Prev` paging on AWS DocumentDB (and automatic `Error 304` runtime fallback), verified with JUnit 5 tests covering 35-doc composite matching, mixed `INNER`/`LEFT` multi-joins, `Next`/`Prev` slicing, and `FieldSearchFilter` priority ranking.

### Changelog (`v0.2.8`)

- **Join Target Tab Bar UI Refactoring (`JoinViewDialog`)**: Removed the standalone right-side Remove button; each join target chip (`① collection ×`) displays a conditional `×` close button only when 2+ targets exist, highlights the active tab, scrolls horizontally when many targets are added, and pairs with a compact textless green `+` icon button rendered natively for Darcula and New UI Dark/Light themes.
- **Bidirectional MongoDB & AWS DocumentDB Multi-Key Join Engine + Exact Cached Pagination**: Uses native multi-condition `$lookup` + server paging on standard MongoDB and automatically runs a 2-phase hybrid join on AWS DocumentDB (including seamless runtime fallback on `Error 304`): prioritizes unique identifier keys (`_id`, `id`, `*_id`, `*Id`) in 1st-stage single-key `$lookup` + 500-item `$slice` 16MB guard, filters remaining keys in memory via `MultiKeyJoinFilterEngine` (`Int32`/`Int64` cross-type equality, nested dot-paths, array flattening, `INNER`/`LEFT` semantics), and serves zero-gap, zero-duplicate `Next`/`Prev` pages from a signature-isolated session cache.
- **Automated JUnit 5 Verification**: Added `MultiKeyJoinFilterEngineTest` verifying 35-document composite-key matching, trap record elimination, cross-type numeric/nested/array joins, `INNER`/`LEFT` behavior, and exact `Next`/`Prev` page slicing.

### Changelog (`v0.2.7`)

- **Strict BSON Type Preservation & Real-Time Validation (`FieldEditDialog` / `BsonValueValidator`)**: Preserves original BSON types (`Int32`, `Int64`, `Double`, `Decimal128`, `String`, `Boolean`, `ObjectId`, `Date`, `Null`, `JSON`), adds live `DocumentListener` validation blocking `OK` on invalid inputs, `Date [Now]` button + live UTC ISO preview, `Red (Prod)` warning banner, Read-Only / Join / Aggregation / `_id` entry guards, and typed `$set` `updateOne` matching exact original `_id` type (`matchedCount == 0` check, AWS DocumentDB compatible).
- **Collection JSON Data Import & 4-Stage Atomic Pre-Flight Validation (`JsonImportEngine` / `ImportJsonDialog`)**: Supports single object (`{...}`), JSON array (`[...]`), and NDJSON with UTF-8 BOM stripping, `.json` file loader (`10MB` cap), `Format JSON`, `Strip existing _id`, `Validate Only (Dry-Run)`, DB-specific key checks (AWS DocumentDB vs MongoDB), in-batch & 500-chunk DB `_id` duplicate checks, recursive nested schema type mismatch detection with safe numeric promotion, detailed copyable error alert aborting writes atomically, and 500-doc chunked `insertMany`.
- **Compatibility & Licensing Alignment**: Aligned `sinceBuild = "242"` (`IntelliJ IDEA 2024.2+`), verified Apache-2.0 `LICENSE` & dependencies, and added Requirements & License tables to `README.md`.

---

### Build from Source

```bash
./gradlew buildPlugin --no-daemon
```

Generated archive: `build/distributions/maekom-mongo-lens-0.2.9.zip`

---

## 한국어

**[▲ Top / English](#english)**

### 개요

**Maekom Mongo Lens**는 **MongoDB** 및 **AWS DocumentDB** 환경에서 안전한 데이터 조회, BSON 타입 보존 데이터 편집, 실시간 디버깅, 인덱스 진단, 다중 컬렉션 조인 분석을 수행할 수 있는 IntelliJ IDEA (Community & Ultimate) 플러그인입니다. Spring Boot의 `application*.yml`을 자동으로 탐색(Zero-Config)하고, MongoDB Compass 스타일 4탭 연결 설정(IntelliJ `PasswordSafe` 비밀번호 보호 · AWS DocumentDB `global-bundle.pem` TLS 지원)과 운영(`prd`/`prod`) 기본 읽기 전용 보호를 제공하며, 대용량 컬렉션에서도 UI 프리징 없이 쾌적하게 동작합니다.

---

### 최소 / 권장 사양 및 라이선스

| 항목 | 사양 |
| :--- | :--- |
| **Version** | `0.2.9` |
| **IntelliJ IDEA** | `2024.2+` (Community / Ultimate — `build.gradle.kts` `sinceBuild = "242"` 기준) |
| **Java Runtime (JBR)** | 최소 `JDK 17` / 권장 `JDK 21` |
| **Database Support** | `MongoDB 4.4 ~ 8.0` (Standalone, Replica Set, Atlas `mongodb+srv`) & `AWS DocumentDB 3.6 / 4.0 / 5.0` |
| **License** | `Apache-2.0` ([Apache License 2.0](LICENSE)) |

---

### 주요 기능

- **BSON 타입 보존 "데이터 수정" 팝업 & 실시간 입력 검증 (`FieldEditDialog` & `DocumentEditDialog`)**
  - 원본 필드의 실제 BSON 타입(`Int32`, `Int64`, `Double`, `Decimal128`, `String`, `Boolean`, `ObjectId`, `Date`, `Null`, `JSON (Object/Array)`)을 자동 감지·유지하여 숫자/날짜/ObjectId가 문자열로 덮어써지는 사고를 원천 차단합니다.
  - 입력 중 `DocumentListener` 실시간 검증으로 오입력(Int32 범위 초과 안내·소수점 차단, Double/Decimal128 `NaN`/`Infinity` 차단, 24자리 16진수 ObjectId, 엄격 Boolean, ISO-8601/`+09:00`/`yyyy-MM-dd HH:mm:ss` UTC/13자리 Epoch ms `Date` 파싱 + **`[현재 시각 (Now)]`** 버튼 및 실시간 UTC ISO 미리보기) 시 즉시 빨간색 경고·테두리 하이라이트와 함께 **`OK` 버튼을 비활성화**합니다.
  - `Read-Only` 연결·조인/집계 결과 뷰·`_id` 누락 문서·`_id` 필드를 보호하고, `Red (Prod)` 연결에는 상단 운영 주의 배너를 표시하며, 원본 `_id` 타입 그대로 매칭(`matchedCount == 0` 오류 알림) + 배열 경로(`items.0.price`) 보존 + AWS DocumentDB 호환(`updateOne`에 `maxTimeMS` 미사용) `$set` 업데이트를 수행합니다.
- **컬렉션 JSON 데이터 임포트(단건·다건·NDJSON) & 4단계 사전 무결성 검증 알럿 중단 (`ImportJsonDialog`)**
  - 좌측 트리 컬렉션 우클릭 → **`JSON 데이터 임포트...`** 또는 결과 탭 상단 **`JSON 임포트`** 버튼으로 진입합니다(`Read-Only` 연결은 진입 즉시 차단 알럿 표시).
  - **단건 JSON 객체(`{...}`)**, **다건 JSON 배열(`[...]`)**, **NDJSON(`mongoexport` 줄바꿈 포맷)**과 UTF-8 BOM 제거, Extended JSON(`$oid`, `$date`, `$numberLong`, `$numberDecimal`), **`[.json 파일 불러오기]`**(`10MB` 제한), **`[JSON 정렬]`**, **`[기존 _id 필드 제거 후 신규 자동 생성]`**, **`[검증만 실행 (Dry-Run)]`** 을 지원합니다.
  - DB 쓰기 전 백그라운드에서 4단계 사전 무결성 검증을 원자적으로 수행합니다: (1) JSON 문법 및 DB별 키 제약(AWS DocumentDB에서는 필드명 `.` 차단, 일반 MongoDB는 허용), (2) 배치 내부 및 500건 청크 분할 DB `_id` 중복 검증(Int/Long 숫자형 교차 체크), (3) 기존 샘플 최대 50건 기반 중첩 스키마 타입 불일치 검증 및 안전한 숫자형 승격, (4) 단 1건이라도 오류 발견 시 DB 쓰기 0건 전면 중단 + 상세 원인 테이블/클립보드 복사 알럿 노출, 검증 통과 시 500건 청크 `insertMany` 후 결과 탭 자동 새로고침.
- **MongoDB Compass 스타일 4탭 연결 설정 & URI 양방향 실시간 동기화**
  - `General` / `Authentication` / `TLS/SSL` / `Advanced` 4탭 양방향 URI 동기화, IntelliJ **`PasswordSafe`** 비밀번호 보안 저장, AWS DocumentDB **`global-bundle.pem`** 다중 CA/클라이언트 인증서, 환경 컬러 태그(`Dev`/`Stg`/`Prod`), 연결 노드 우클릭 메뉴(`연결 설정 / URI 보기...`, 전체/마스킹 URI 및 `mongosh` CLI 복사, YAML 읽기 전용 보기 + Custom 복제).
- **Zero-Overhead 초기 미리보기 & 100% 읽기 전용 인덱스 뷰어 (`IndexInspectorDialog`)**
  - 조건 없는 컬렉션 클릭 시 즉시 미리보기(`조건 없음 (Preview)`), 조건 조회 시 비동기 `QUERY_PLANNER`(`IXSCAN`/`COLLSCAN`/`UNKNOWN`), 60초 캐시 인덱스 조회 및 ESR `createIndex` 권장 구문 복사.
- **스마트 키보드 자동완성, 5그룹 네이티브 컨텍스트 메뉴 & 다중 연결키 조인 뷰 (`A ⋈ B ⋈ C`)**
  - `Filter`/`Sort`/`Project` 키보드 자동완성, 다중 선택 빠른 필터/정렬, 문서 Diff 비교, 다중 연결키(`AND`) 및 공통 키 자동 매칭 조인 뷰, AES 임시 복호화, 실시간 `KO ↔ EN` 전환을 지원합니다.

---

### 설치 방법

1. [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases)에서 **`maekom-mongo-lens-0.2.9.zip`** 파일을 다운로드합니다.
2. IntelliJ IDEA (`2024.2+`)에서 **`Settings / Preferences` → `Plugins` → ⚙️(기어 아이콘) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하고 IDE를 재시작합니다.
4. 하단 ToolWindow에서 **`Maekom Mongo Lens`** 탭을 엽니다.

---

### 사용 방법

1. **커넥션 등록 및 관리 (Zero-Config YAML & Compass 스타일 4탭 설정)**
   - 프로젝트의 Spring Boot `application*.yml` 프로필이 좌측 **Connections** 트리에 자동 표시됩니다(`prd`/`prod`는 자동 **`READ-ONLY 🔒`** 보호).
   - 연결 노드 우클릭 → **`연결 설정 / URI 보기...`** 에서 호스트, 인증, AWS DocumentDB TLS/CA(`global-bundle.pem`), `retryWrites=false`, 환경 컬러 태그를 설정하거나 **`mongosh` CLI 명령어**를 원클릭 복사할 수 있습니다.
2. **BSON 타입 보존 데이터 수정**
   - **Tree** 또는 **Table** 뷰에서 필드/셀 우클릭 → **`데이터 수정`** 을 선택해 원본 BSON 타입을 유지한 채 실시간 검증과 함께 안전하게 값을 수정합니다(전체 문서 구조/Raw JSON 편집은 **`전체 문서 편집...`** 선택).
3. **사전 무결성 검증 JSON 임포트**
   - 좌측 트리 컬렉션 우클릭 → **`JSON 데이터 임포트...`** (또는 결과 탭 상단 **`JSON 임포트`** 버튼) → 단건/배열/NDJSON 붙여넣기 또는 `.json` 파일 불러오기 → **`[검증만 실행 (Dry-Run)]`** 으로 문법·`_id` 중복·중첩 스키마 타입을 사전 확인한 뒤 **`[임포트 실행]`** 을 클릭합니다.
4. **쿼리, 다중 선택 빠른 필터 & 다중 연결키 조인 뷰**
   - `Filter`/`Sort`/`Project`에서 **`↑`/`↓`**·**`Enter`/`Tab`** 자동완성, `Ctrl`/`Shift` 다중 선택 우클릭 빠른 필터, 기준 필드 우클릭 → **`다른 컬렉션과 조인해서 보기...`** 로 단일/복합키 조인을 실행합니다.

---

### 릴리즈 노트 (`v0.2.9` Changelog)

- **조인 설정 상단 탭 바 인라인 초록색 `+` 버튼 & 조건부 `×` 닫기 (`JoinViewDialog`)**: 우측 단독 삭제 버튼을 제거하고 2개 이상 조인 시 각 탭 우측에 `×` 닫기 버튼을 조건부 표시하며, 텍스트 없는 초록색 `+` 아이콘 버튼을 가로 스크롤 컨테이너 내 마지막 조인 탭 바로 오른쪽 옆(6px 간격)에 인라인 밀착 배치했습니다.
- **조인 설정 필드 콤보박스 실시간 타이핑 검색 (`SearchableFieldComboBox` / `FieldSearchFilter`)**: 연결 키 매핑(기준/대상 컬렉션 키) 및 `WHERE AND` 추가 필터의 모든 필드 콤보박스에서 타이핑 시 대소문자 무시 부분 일치 + 접두사(`startsWith`) 우선 정렬 필터링, 드롭다운 자동 오픈, 방향키/마우스 선택 충돌 방지 가드, 직접 입력 중첩 경로 지원을 적용했습니다.
- **일반 MongoDB / AWS DocumentDB 양방향 다중 조인 엔진 & JUnit 5 단위 테스트 검증**: MongoDB 네이티브 다중 `$lookup`과 AWS DocumentDB(`Error 304` 런타임 폴백 포함) 하이브리드 2단계 조인·시그니처 격리 세션 캐시 페이징을 구현하고, 35건 복합키·혼합 `INNER`/`LEFT` 다중 조인·`다음`/`이전` 슬라이싱·`FieldSearchFilter` 우선순위 정렬을 검증하는 JUnit 5 테스트를 통과시켰습니다.

### 릴리즈 노트 (`v0.2.8` Changelog)

- **조인 설정 상단 탭 바 UI 리팩토링 (`JoinViewDialog`)**: 우측 단독 `[— 이 조인 삭제]` 버튼을 제거하고, 2개 이상 조인 대상이 있을 때만 각 탭 항목(`① collection ×`) 우측에 `×` 닫기 버튼을 조건부 노출합니다. 활성 탭 하이라이트, 가로 스크롤 컨테이너, Darcula/New UI 테마 대응 초록색 `+` 아이콘 전용 버튼을 적용했습니다.
- **일반 MongoDB / AWS DocumentDB 양방향 다중 조인 엔진 & `다음`/`이전` 페이징 정합성 보장**: 일반 MongoDB에서는 네이티브 다중 조건 `$lookup` + 서버 페이징을 유지하고, AWS DocumentDB(및 런타임 `Error 304` 감지 시 자동 마킹·폴백)에서는 고유 키(`_id`/`id`/`*_id`/`*Id`) 우선 1차 단일 키 `$lookup` + `$slice(500)` 16MB 방어 후 순수 로직 엔진(`MultiKeyJoinFilterEngine`) 인메모리 정밀 필터링(`Int32`/`Int64` 교차 동등성, 중첩 경로/배열 평탄화, `INNER`/`LEFT JOIN`) 및 쿼리 시그니처 격리 세션 캐시 슬라이싱 페이징으로 누락·중복 0건을 보장합니다.
- **JUnit 5 자동화 단위 테스트 추가 (`MultiKeyJoinFilterEngineTest`)**: 35건 복합키 매칭, 함정 데이터 탈락, `Int32`/`Int64` 교차 비교, 중첩·배열 경로, `INNER`/`LEFT JOIN`, `다음`/`이전` 페이지 슬라이싱 정합성을 검증합니다.

### 릴리즈 노트 (`v0.2.7` Changelog)

- **"데이터 수정" 팝업 BSON 타입 보존 및 실시간 입력 검증 강화 (`FieldEditDialog` / `BsonValueValidator`)**: 원본 BSON 타입(`Int32`/`Int64`/`Double`/`Decimal128`/`String`/`Boolean`/`ObjectId`/`Date`/`Null`/`JSON`) 자동 감지·고정, `DocumentListener` 실시간 유효성 검증 및 오입력 시 `OK` 비활성화, `Date [현재 시각 (Now)]` 버튼 및 UTC ISO 미리보기, `Red (Prod)` 운영 경고 배너, `Read-Only`/조인/집계/`_id` 보호 가드, 원본 `_id` 타입 매칭(`matchedCount == 0` 알림) 및 AWS DocumentDB 호환 `$set` `updateOne` 구현.
- **컬렉션 JSON 데이터 임포트 및 4단계 사전 무결성 검증 알럿 중단 (`JsonImportEngine` / `ImportJsonDialog`)**: 단건(`{...}`)/다건(`[...]`)/NDJSON 3가지 포맷 및 UTF-8 BOM 제거, `.json` 파일 불러오기(`10MB` 제한), `[JSON 정렬]`, `[기존 _id 제거 후 신규 자동 생성]`, `[검증만 실행 (Dry-Run)]`, DB별 키 제약(DocumentDB `.` 차단 vs MongoDB 허용), 배치 및 500건 청크 DB `_id` 중복 검증, 중첩 스키마 타입 불일치 사전 감지 및 안전 숫자 승격, 오류 시 DB 쓰기 전면 중단 및 상세 복사 가능 알럿, 통과 시 500건 청크 `insertMany` + 자동 새로고침 구현.
- **호환성 및 라이선스 정비**: `build.gradle.kts` `sinceBuild = "242"`(`IntelliJ IDEA 2024.2+`) 일치화, Apache-2.0 `LICENSE` 및 외부 의존성 라이선스 검증, `README.md` 최소/권장 사양 및 라이선스 표 반영.

---

### 소스 빌드 방법

```bash
./gradlew buildPlugin --no-daemon
```

빌드 산출물: `build/distributions/maekom-mongo-lens-0.2.9.zip`

---

### License

Licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
