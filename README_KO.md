# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.4-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.1%20~%202026.x-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English (README.md)](./README.md) | [한국어 (README_KO.md)](./README_KO.md)**

> **IntelliJ IDEA (Community & Ultimate) 내부에서 Zero-Config & Zero-Overhead로 바로 사용하는 MongoDB · AWS DocumentDB Compass 스타일 GUI, 다중 조인 뷰, 100% 읽기 전용 인덱스 인스펙터, 양방향 Aggregation 파이프라인 빌더.**

---

## 소개 (Overview)

**Maekom Mongo Lens**는 외부 클라이언트를 열거나 접속 정보를 수동 관리할 필요 없이 IntelliJ IDEA 안에서 실무에 최적화된 MongoDB Compass 워크플로우를 제공하는 오픈소스 플러그인입니다. Spring Boot의 `application*.yml`을 자동 감지하고 운영(`prd`/`prod`) DB를 기본 읽기 전용으로 보호하며, Zero-Overhead 초기 오픈과 Lazy 뷰 렌더링으로 대용량 컬렉션도 지연 없이 즉시 조회합니다. **MongoDB** 및 **AWS DocumentDB** 모두 100% 호환됩니다.

---

## 주요 기능 (Key Features)

### 1. Zero-Overhead 초기 오픈 & UI-First 초고속 렌더링
- **초기 오픈 Zero-Overhead**: `Filter`/`Sort` 조건 없이 컬렉션을 처음 열 때는 `explain()`과 `listIndexes()`를 전면 생략하고 중립 **`조건 없음 (Preview)`** 배지를 표시하며, `.limit(N).batchSize(N)` 단 1회 왕복으로 본 데이터를 즉시 가져옵니다.
- **클릭 즉시 탭 오픈 & Single-Flight 요청 제어**: 컬렉션 클릭 즉시 EDT에서 탭 생성·포커스 및 로딩 스피너를 표시하며, 빠른 연속 클릭 시 `AtomicLong` 세대 번호와 `Future.cancel(true)`로 구세대 요청을 자동 취소해 중복 쿼리 폭주와 응답 꼬임을 원천 차단합니다.
- **Lazy 뷰 렌더링 & 대용량 프리뷰 안전 절삭**: 현재 활성화된 뷰(`Tree` / `Table` / `JSON` 중 1개)만 백그라운드 스레드에서 즉시 생성하고, 나머지 2개 비활성 뷰는 전환 시 지연 생성합니다. 수천 자 초장문 문자열·거대 배열·대용량 `Binary`는 화면 표시 텍스트만 안전 절삭(`... (truncated)`)하며, 복사·수정·Diff·내보내기에는 100% 원본 BSON 데이터를 그대로 유지합니다.
- **60초 메타 캐시 & 예외 완벽 격리**: `estimatedDocumentCount()`와 `listIndexes()` 결과를 컬렉션별 60초 TTL 캐싱(수동 새로고침/데이터 수정 시 무효화)하고, 전체 스캔을 유발하던 `EXECUTION_STATS` 폴백을 완전히 제거(`QUERY_PLANNER` + 1,500ms `maxTimeMS` 전용)했으며, View나 권한 제한 컬렉션의 메타 쿼리 오류를 안전하게 격리합니다.

### 2. 실행 계획(`IXSCAN`/`COLLSCAN`) 감지 & 100% 읽기 전용 인덱스 뷰어 / Filter 가이드
- **정확한 MongoDB & AWS DocumentDB `explain()` 분석**: 사용자가 `Filter` 또는 `Sort`를 지정해 조회할 때만 본 데이터 렌더링 이후 비동기로 `QUERY_PLANNER` 실행 계획을 분석해 **`IXSCAN`**, **`COLLSCAN`**, **`UNKNOWN`**(실패 사유 툴팁 포함) 배지를 표시합니다.
- **100% 읽기 전용(Read-Only) 인덱스 인스펙터**: 실행 계획 배지(`IXSCAN` / `COLLSCAN` / `조건 없음 (Preview)` / `UNKNOWN`)를 클릭하거나 좌측 Connections 트리에서 컬렉션 우클릭 → **`인덱스 조회 및 쿼리 가이드...`** 로 엽니다.
  - 인덱스명, 키 구성, 속성(`UNIQUE`, `TTL`, `SPARSE`), 크기를 한눈에 조회합니다.
  - **`이 인덱스 키로 Filter 템플릿 적용`** 버튼으로 선택한 인덱스 키를 상단 `Filter` 입력창에 바로 채울 수 있습니다.
  - 현재 `Filter`/`Sort`와 기존 인덱스를 대조해 `COLLSCAN` 원인 및 활용 가능한 선두(Prefix) 키 예시를 안내하며, 운영/DBA 협의용 **ESR(`Equality → Sort → Range`) 권장 `createIndex` 구문을 복사 전용**으로 제공합니다 (DB에 쓰기 명령 절대 미실행).

### 3. IntelliJ New UI 네이티브 컨텍스트 메뉴 (`AllIcons`) & 5그룹 다중 선택 스마트 빠른 필터
**Tree** 또는 **Table** 뷰에서 `Ctrl`/`Shift` 다중 선택을 유지한 채 우클릭하면, IntelliJ New UI 네이티브 팝업(`ActionPopupMenu`)과 전 항목 16×16 `AllIcons`가 적용된 **5개 논리 그룹 메뉴**가 0ms로 열립니다:
1. **동적 빠른 필터 & 정렬** *(기존 `Filter`의 다른 조건은 유지하면서 해당 필드만 병합/갱신 후 즉시 조회)*:
   - **단일 스칼라**: `이 값으로 필터 (=)` / `이 값 제외하고 필터 (!=)`
   - **동일 필드 2개 이상**: `선택한 N개 값으로 필터 ($in)` / `선택한 N개 값 제외 ($nin)`
   - **동일 필드 날짜 2개**: `선택한 날짜 범위로 필터 ($gte ~ $lte)` / `선택한 2개 날짜 일치 ($in)`
   - **서로 다른 다중 필드**: `선택한 N개 조건 모두 일치 (AND)` / `선택한 N개 조건 중 하나 일치 ($or)` *(기존 `$or` 존재 시 `$and`로 안전 래핑)*
   - **중첩 객체 / 배열**: `이 필드 존재 ($exists: true)` / `이 필드 비어있음·없음`
   - **즉시 정렬 & UTC 시간 범위**: `이 필드로 정렬 ▶ 오름차순 (1) / 내림차순 (-1)` 및 `ObjectId`/`Date` KST+UTC `$gte` / `$lte` 범위 필터.
2. **편집**: **`데이터 수정`** (Key-Value 구조 편집 + Raw JSON 편집 + 미저장 변경사항 Diff 확인).
3. **키/값 복사 서브메뉴**: `값(Value)만 복사` | `키:값(JSON) 복사` | `필드 경로(Path) 복사`.
4. **연관 조회 & 조인**: `참조 컬렉션에서 조회 (새 탭)...` 및 `다른 컬렉션과 조인해서 보기...`.
5. **문서 Diff 비교**: 선택한 2개 문서 비교 및 다른 탭/환경 간 **Baseline 고정 비교**.

### 4. AWS DocumentDB 100% 호환 컬렉션 조인 뷰 (`A ⋈ B ⋈ C`)
- **Star Join & Chain Join**: 기준 컬렉션에 여러 대상 컬렉션을 연결(`A → B`, `A → C`, `A → B → C`)하며, 표준 `localField`/`foreignField` `$lookup`과 `.allowDiskUse(true)`만 사용해 AWS DocumentDB에서 오류 없이 동작합니다 (`let`/`pipeline`, `$facet` 미사용).
- **`Array<Object>` 내부 연결 키 지원**: 스키마 샘플링 시 중첩 객체 및 객체 배열 내부 필드까지 dot-notation 경로(예: `items.product_id`)로 자동 추출해 조인 키와 `WHERE AND` 조건 드롭다운에 제공합니다.
- **단일화 페이징 & 명시적 Limit 모드**: `Base $match → 결정론적 $sort(_id 타이브레이커) → $lookup/제외 $match → 조인 후 단일 $skip / $limit(N+1) → $project` 순서를 보장해 페이지 건수 불일치를 완전히 제거했습니다. **`20` / `50` / `100` / `500` / `전체 조회 (Unlimited, 최대 5,000건 안전 상한)`** 모드를 지원합니다.
- **라이브 편집 & 파이프라인 복사**: **`조건/컬럼 수정`** 으로 조인 설정을 상태 유실 없이 즉시 수정하고, **`파이프라인 복사`** 로 실행된 Aggregation JSON 배열을 복사할 수 있습니다.

### 5. 직관적인 모드/뷰 전환 UI, Zero-Config YAML, Aggregation 빌더 & AES 복호화
- **시각적 위계 강화**: 하단 강조 바(Accent Bar)가 적용된 상단 모드 탭(`Documents (Find)` / `Aggregations` / `Mongo Shell`)과 아이콘 세그먼트 컨트롤(**`Tree` | `Table` | `JSON`**).
- **Zero-Config Spring Boot YAML & 운영 보호**: 단일/멀티 도큐먼트 `application*.yml` 자동 스캔, `${VAR:default}` 및 `.env` 자동 해석, `prd`/`prod` 자동 **`READ-ONLY 🔒`** 잠금.
- **양방향 Aggregation 빌더 & Mongo Shell & AES 복호화**: UI 스테이지 카드 ↔ Raw JSON 동기화, `{{변수}}`(`{{$now}}`, `{{$today}}`, `{{$objectId}}`) 지원 Mongo Shell, 자동 마스킹 카운트다운이 포함된 AES 필드 임시 복호화(Tree 개별 / Table 페이지 일괄).

---

## 설치 방법 (Installation)

### GitHub Releases ZIP 디스크 설치 (Install from Disk)
1. [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases)에서 **`maekom-mongo-lens-0.2.4.zip`** 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → ⚙️(기어 아이콘) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하고 IDE를 재시작합니다.
4. 하단 ToolWindow 영역에서 **`Maekom Mongo Lens`** 탭을 엽니다.

> 최초 설치 이후에는 우측 상단 **`설정` → `업데이트 확인`** 메뉴에서 원클릭 인앱 업데이트를 진행할 수 있습니다.

---

## 사용 방법 (Quick Start & Usage)

1. **커넥션 등록 (Zero-Config 또는 Custom URI)**
   - 프로젝트에 Spring Boot `application*.yml`이 있으면 좌측 **Connections** 트리에 자동 표시됩니다. (직접 연결 시 프로필 드롭다운에서 **`+ Custom URI 추가...`** 선택)
   - 프로필/파일명에 `prd`/`prod`가 포함되면 자동으로 **`READ-ONLY 🔒`** 모드로 보호됩니다.
2. **즉시 미리보기 & 조건부 실행 계획 확인**
   - 컬렉션을 한 번 클릭하면 로딩 스피너와 함께 즉시 탭이 열리고 **`조건 없음 (Preview)`** 상태로 `explain()` 부하 없이 본 데이터만 빠르게 표시됩니다.
   - `Filter` 또는 `Sort` 조건을 입력하고 `Ctrl + Enter` (`Cmd + Enter`)로 조회하면 **`IXSCAN`** / **`COLLSCAN`** 배지가 표시되며, 배지를 클릭해 읽기 전용 인덱스 뷰어와 Filter 가이드를 확인할 수 있습니다.
3. **다중 선택 우클릭 빠른 필터**
   - **Table** 뷰에서 `Ctrl`/`Shift` 클릭으로 여러 셀을 선택(같은 컬럼 → `$in`/`$nin`/날짜 범위, 다른 컬럼 → `AND`/`$or`)하거나 **Tree** 뷰에서 여러 필드를 선택한 뒤 우클릭해 조건을 즉시 적용합니다.
4. **컬렉션 조인 뷰 (`A ⋈ B`) 활용**
   - 연결 기준 필드(객체 배열 내부 `items.product_id` 포함) 우클릭 → **`다른 컬렉션과 조인해서 보기...`** → 대상 컬렉션, `WHERE AND` 조건, 표시 컬럼, 조회 건수(`20`~`500` 또는 `전체 조회`)를 선택 후 **`조인 탭으로 실행 ▶`** 을 클릭합니다.

---

## 릴리즈 노트 (`v0.2.4` Changelog)

- **초기 오픈 Zero-Overhead 성능 최적화**: 조건 없는 최초 컬렉션 오픈 시 `explain()` 및 `listIndexes()`를 전면 생략(`조건 없음 (Preview)` 배지 표시), `find()`에 `.limit(N).batchSize(N)` 적용, 전체 스캔을 유발하던 `EXECUTION_STATS` 폴백 완전 제거(`QUERY_PLANNER` + 1,500ms `maxTimeMS`), `estimatedDocumentCount()`/`listIndexes()` 60초 TTL 캐싱 및 View·권한 제한 컬렉션 예외 완벽 격리.
- **Lazy 뷰 렌더링 & 대용량 프리뷰 안전 절삭**: 활성화된 뷰(`Tree`/`Table`/`JSON`) 1개만 백그라운드 스레드에서 즉시 생성하고 비활성 뷰는 전환 시 지연 빌드하며, 거대 문자열·배열·Binary는 화면 표시 텍스트만 안전 절삭(`... (truncated)`)하고 원본 BSON 데이터는 그대로 보존.
- **UI-First 즉시 탭 오픈 & Single-Flight 제어**: 좌측 트리 클릭 이벤트 정합성 개선(단일 클릭 즉시 탭 생성·포커스 + 로딩 스피너), `AppExecutorUtil` + `AtomicLong` 세대 번호 + `Future.cancel(true)` + `Disposable` 정리로 다중 클릭 지연 및 중복 쿼리 폭주 해소.
- **컨텍스트 메뉴 IntelliJ New UI 스타일 & `AllIcons` 업그레이드**: 결과 Tree/Table 및 좌측 컬렉션 트리 우클릭 메뉴 전체를 `ActionPopupMenu` (`setTargetComponent`) 기반 네이티브 New UI 팝업으로 전환하고 모든 메뉴·서브메뉴 항목에 16×16 `AllIcons` 배치.

---

## 소스 빌드 방법 (Build from Source)

```bash
./gradlew buildPlugin --no-daemon
```

빌드 산출물: `build/distributions/maekom-mongo-lens-0.2.4.zip`

---

## 라이선스 (License)

이 프로젝트는 **Apache License 2.0** 라이선스로 배포됩니다. 자세한 내용은 [LICENSE](./LICENSE) 파일을 참고하세요.
