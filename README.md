# Maekom Mongo Lens

**Zero-Config MongoDB Compass-style GUI & Bidirectional Aggregation Pipeline Builder for IntelliJ IDEA (Community & Ultimate, 2024.1 ~ 2026.x).**

IntelliJ IDEA 내부에서 **MongoDB Compass 수준의 GUI 환경**, **Spring Boot YAML 기반 Zero-Config 자동 서버 연결**, **Find & 양방향 Aggregation 파이프라인 빌더**, **GitHub Releases 기반 인앱 자동 업데이트**를 제공하는 오픈소스 플러그인입니다.

---

## 주요 기능 / Key Features

- **Zero-Config Spring Boot YAML 자동 탐색 (Auto-Discovery)**
  - 단일/멀티 모듈 프로젝트의 `**/src/main/resources/application*.yml(yaml)` 및 `---` 멀티 도큐먼트 YAML(`spring.config.activate.on-profile`, `spring.profiles`)을 자동 파싱·병합합니다.
  - `spring.data.mongodb.uri` 및 개별 속성(`host`, `port`, `database`, `username`, `password`, `authentication-database`)을 모두 지원합니다.
  - `${MONGO_URI:mongodb://localhost:27017/mydb}` 플레이스홀더(기본값 우선 → `.env` → 시스템 환경변수)를 자동 해석하며, YAML 저장 시 연결 트리가 즉시 갱신됩니다.
  - **지연 연결(Lazy Connection)** 및 **운영(PRD) 자동 읽기 전용 보호(`READ-ONLY 🔒`)**를 제공합니다.
- **Compass 스타일 멀티 탭 Find 워크스페이스**
  - `Filter`, 접기/펼치기 가능한 `Project`, `Sort` JSON 에디터 (문법 하이라이팅, 괄호 자동완성, 최근 20건 샘플링 기반 필드명 자동완성).
  - 프리셋(`20 / 50 / 100 / 500 / 1000`) + `[-]`/`[+]` 원클릭 즉시 재조회 + `Skip` 페이지네이션.
  - **`Smart $in` 빌더**: 줄바꿈·쉼표·공백 구분 ID 목록을 `ObjectId` / 숫자 / 문자열로 자동 판별해 `{ "_id": { "$in": [...] } }` 쿼리로 변환합니다.
  - **Mongo Shell 전처리기**: `ObjectId(...)`, `ISODate(...)`, `NumberLong(...)`, `NumberDecimal(...)`, 따옴표 없는 키를 자동 정규화합니다.
  - **3종 결과 뷰어 & 인덱스 배지**: `Tree` (BSON 타입 색상 태그 + `Date` 마우스 오버 시 KST `UTC+9` 툴팁) | `JSON` (`Ctrl+F` 검색 지원) | `Table` (컬럼 평탄화·정렬) 및 `IXSCAN` / `COLLSCAN` 배지.
- **양방향 Aggregation 파이프라인 빌더**
  - `🧩 스테이지 빌더 (UI)` ↔ `{ } Raw 스크립트` 실시간 양방향 동기화.
  - 스테이지 순서 이동(`↑`/`↓`), 개별 `ON`/`OFF` 토글, **`[미리보기 (5건)]`** 단계별 검증.
- **마켓플레이스형 인앱 자동 업데이트 & 실시간 한/영 전환 (`KO / EN`)**
  - GitHub Releases의 최신 버전을 확인하고 클릭 한 번으로 다운로드·무결성 검증·설치 예약 및 IDE 재시작을 수행합니다.

---

## 설치 방법 / Installation

### GitHub Releases ZIP 수동 설치 (Install from Disk)
1. [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases)에서 최신 `maekom-mongo-lens-x.y.z.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → ⚙️(기어 아이콘) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하고 IDE를 재시작합니다.
4. 하단 ToolWindow 영역에서 **`Maekom Mongo Lens`** 탭을 엽니다.

> 설치 이후에는 플러그인 상단 우측의 **`[↻ 업데이트 확인]`** / **`[⬆ vX.Y.Z 업데이트]`** 버튼으로 인앱 업데이트를 진행할 수 있습니다.

---

## Spring Boot YAML Zero-Config 예시

### 1) 멀티 도큐먼트 `application.yml` 예시
```yaml
spring:
  data:
    mongodb:
      authentication-database: admin
      port: 27017

---
spring:
  config:
    activate:
      on-profile: local
  data:
    mongodb:
      uri: ${MONGO_LOCAL_URI:mongodb://localhost:27017/my_service}

---
spring:
  config:
    activate:
      on-profile: prd
  data:
    mongodb:
      host: ${PRD_MONGO_HOST}
      database: my_service_prod
      username: ${PRD_MONGO_USER}
      password: ${PRD_MONGO_PASS}
```

- 프로필명 또는 파일명에 `prod` / `prd`가 포함되면 자동으로 **`READ-ONLY 🔒`** 모드가 활성화되어 실수로 운영 문서를 수정·삭제하는 것을 방지합니다.
- 기본값이 없는 `${PRD_MONGO_HOST}` 변수는 프로젝트 루트의 `.env` 파일 또는 OS 환경변수에서 자동으로 값을 읽어옵니다.

---

## 사용법 및 단축키 / Usage & Shortcuts

| 기능 / Action | 단축키 / 방법 |
|---|---|
| 쿼리 / 파이프라인 실행 | `Ctrl + Enter` (macOS: `Cmd + Enter`) |
| 필드명 / MongoDB 연산자 자동완성 | `Ctrl + Space` (또는 `"`, `$` 입력 시 자동 팝업) |
| Smart `$in` 생성 | Find 바의 **`[📋 Smart $in]`** 클릭 (클립보드 ID 자동 불러오기) |
| 동일 컬렉션 다중 비교 탭 열기 | Connections 트리에서 컬렉션 **더블클릭** 또는 **우클릭 → 새 Find/Aggregation 탭 열기** |
| Raw JSON 결과 내 검색 | `JSON` 뷰 선택 후 `Ctrl + F` (macOS: `Cmd + F`) |
| 언어 즉시 전환 | 상단 우측 **`[KO / EN]`** 버튼 클릭 |

---

## 실무 핵심 기능 가이드 (v0.1.8+) / Key Feature Guides (v0.1.8+)

### 1. 빠른 컬럼 선택 및 숨기기 (`Quick Column Chooser` / `SELECT` Mode)
- **KO**: 복잡한 `$project` JSON을 직접 타이핑하지 않고 마우스 클릭만으로 원하는 컬럼만 골라 조회합니다.
  1. `Table` 뷰에서 컬럼 헤더를 우클릭합니다.
  2. **`[이 컬럼 숨기기]`** 또는 **`[표시할 컬럼 선택 (Project)...]`** 을 클릭합니다.
  3. 체크박스에서 필요한 컬럼만 선택 후 **`[적용 및 재조회]`** 를 누르면 `Project` 입력창이 자동으로 펼쳐지며 쿼리가 반영됩니다.
- **EN**: Select or hide columns with clicks instead of typing `$project` JSON manually.
  1. Right-click any column header in `Table` view.
  2. Click **`Hide This Column`** or **`Select Visible Columns (Project)...`**.
  3. Check the desired columns and click **`Apply & Run`** — the `Project` bar expands automatically with the generated projection.
- **예제 / Example** (`userId`, `status`, `createdAt`만 선택 시 / Selecting only `userId`, `status`, `createdAt`):
  ```json
  { "_id": 0, "userId": 1, "status": 1, "createdAt": 1 }
  ```

### 2. 참조 컬렉션 1-클릭 점프 & RDB 스타일 조인 뷰 (`Reference Jump & Collection Join View`)
- **KO**: 외래키/식별자 필드를 따라 연관 컬렉션 문서를 새 탭에서 즉시 열거나, 두 이력 컬렉션(`A ⋈ B`)의 원하는 컬럼만 골라 RDB `LEFT JOIN`처럼 한 테이블로 합쳐 조회합니다.
  - **단순 점프**: `userId` 등 참조 필드 우클릭 → **`[참조 컬렉션에서 조회 (새 탭)...]`** → 대상 컬렉션 선택 시 새 탭에서 자동 필터 조회 (`ObjectId` ↔ 24자리 hex 문자열 자동 변환 지원).
  - **조인 뷰 (`auth_hst ⋈ temp_hst`)**: 기준 필드 우클릭 → **`[다른 컬렉션과 조인해서 보기...]`** → 대상 컬렉션 및 매칭 필드 선택(예: `user_id` ↔ `userId` 자동 매칭) → 양쪽 컬렉션에서 표시할 컬럼 체크 → **`[조인 탭으로 실행 ▶]`**.
- **EN**: Jump to a referenced document in a new tab or join two collections (`A ⋈ B`) in an RDB `LEFT JOIN` table view.
  - **Reference Jump**: Right-click a reference field (`userId`, etc.) → **`Find in Referenced Collection (New Tab)...`** → pick target collection (auto-coerces `ObjectId` ↔ 24-char hex string).
  - **Join View (`auth_hst ⋈ temp_hst`)**: Right-click a source field → **`Join with Another Collection...`** → pick target collection & matching field (auto-matches `user_id` ↔ `userId`) → check columns from both sides → **`Run in Join Tab ▶`**.
- **예제 / Example (실무 안전 파이프라인 / Safe Pre-Filtered Pipeline)**:
  대용량 DB 보호를 위해 기존 `Filter`와 `Limit`을 먼저 적용해 압축한 뒤 `$lookup`을 수행합니다:
  ```javascript
  // auth_hst ⋈ temp_hst (최신 1건 매칭 및 원하는 컬럼 평탄화 조회 / Match latest 1 & flatten columns)
  [
    { "$match": { "status": "FAIL" } },
    { "$limit": 50 },
    { "$lookup": { ... } },
    { "$project": { "user_id": 1, "status": 1, "temp_hst_status": "$_joined.status", "item1": "$_joined.item1" } }
  ]
  ```

### 3. `ObjectId` 및 `Date` 시각 표시(KST/UTC 병기) & UTC 기간 범위 필터 (`Time Display & Range Filter`)
- **KO**: 원본 `ObjectId`와 `ISODate` 값은 그대로 보존하면서 사람이 읽기 쉬운 KST/UTC 시각을 함께 보여주고, 클릭 두 번으로 해당 시각 기준 기간 필터를 생성합니다.
  - **시각 확인**: `Tree` 뷰에서는 `ObjectId("...")` 우측 회색 라벨로, `Table` 뷰에서는 셀 마우스 호버 툴팁으로 `2026-11-26 10:30:15 KST (UTC 01:30:15)` 확인.
  - **범위 필터 생성**: `ObjectId` 셀 우클릭 → **`[생성 시각 기준 필터]`** → **`이 시각 이후 생성 문서만 조회 ($gte)`** 또는 **`이 시각 이전 생성 문서만 조회 ($lte)`** 선택.
- **EN**: Display human-readable KST/UTC timestamps alongside raw `ObjectId` and `ISODate` values, and generate UTC-based range filters in two clicks.
  - **View Time**: Inline gray label next to `ObjectId("...")` in `Tree` view, or cell hover tooltip (`2026-11-26 10:30:15 KST (UTC 01:30:15)`) in `Table` view.
  - **Range Filter**: Right-click an `ObjectId` cell → **`Filter by Creation Time`** → **`Find docs created at/after this time ($gte)`** or **`Find docs created at/before this time ($lte)`**.
- **예제 / Example** (시차 오차 없는 원본 Unix Epoch(UTC) 경계값 자동 생성 / Exact UTC epoch boundary):
  ```json
  { "_id": { "$gte": ObjectId("67452a1b0000000000000000") } }
  ```

### 4. 문서 간 Diff 비교 (`Document Diff` & Baseline 핀 고정)
- **KO**: 같은 목록 내 두 문서나, API/배치 실행 전후·다른 환경(`dev` vs `prd`) 문서를 키 순서 차이 없이 깔끔하게 비교합니다.
  - **2개 문서 즉시 비교**: `Table` 또는 `Tree`에서 `Ctrl`/`Shift` 클릭으로 2개 문서 선택 후 우클릭 → **`[선택한 2개 문서 Diff 비교]`**.
  - **Baseline 고정 비교**: 기준 문서 우클릭 → **`[비교 기준(Baseline)으로 고정]`** (상단 바에 `[📌 기준: ... ✕]` 배지 표시) → 재조회 또는 다른 탭에서 대상 문서 우클릭 → **`[고정된 기준과 Diff 비교]`**.
  - **특징**: 필드 키를 알파벳순으로 자동 정렬하여 실제 값이 바뀐 부분만 정확히 하이라이트하며, 화면에 복호화가 활성화된 경우 복호화된 평문 JSON 기준으로 비교합니다.
- **EN**: Compare two documents in the same list or across environments (`dev` vs `prd`) using IntelliJ's built-in Diff viewer without field-order noise.
  - **Compare 2 Selected Docs**: `Ctrl`/`Shift`-click 2 documents in `Table` or `Tree` → right-click → **`Compare 2 Selected Documents (Diff)`**.
  - **Compare with Pinned Baseline**: Right-click a reference document → **`Pin as Diff Baseline`** (`[📌 Baseline: ... ✕]` badge appears) → right-click any target document (even after re-querying or in another tab) → **`Compare with Pinned Baseline`**.
  - **Highlights**: Keys are canonically sorted alphabetically, and if decryption is active on screen, Diff compares the decrypted plaintext JSON.

---

## 빌드 방법 / Build from Source

```bash
./gradlew buildPlugin
```

빌드가 완료되면 `build/distributions/maekom-mongo-lens-0.2.1.zip` 파일이 생성됩니다.

---

## 라이선스 / License

이 프로젝트는 **Apache License 2.0** 라이선스로 배포됩니다. 자세한 내용은 [LICENSE](./LICENSE) 파일을 참고하세요.

This project is licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
