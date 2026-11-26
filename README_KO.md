# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.3-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.1%20~%202026.x-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English (README.md)](./README.md) | [한국어 (README_KO.md)](./README_KO.md)**

> **IntelliJ IDEA (Community & Ultimate) 내부에서 바로 사용하는 Zero-Config MongoDB & AWS DocumentDB Compass 스타일 GUI, 다중 조인 뷰, 읽기 전용 인덱스 인스펙터, 양방향 Aggregation 파이프라인 빌더.**

---

## 소개 (Overview)

**Maekom Mongo Lens**는 외부 툴을 켜거나 접속 정보를 매번 수동 입력할 필요 없이, IntelliJ IDEA 안에서 MongoDB Compass 수준의 실무 워크플로우를 제공하는 오픈소스 플러그인입니다. Spring Boot 프로젝트의 `application*.yml`을 자동 감지하고 운영(PRD) DB를 읽기 전용으로 안전하게 보호하며, 쿼리 실행·다중 조인·인덱스 진단·문서 Diff 비교·AES 암호화 필드 임시 복호화 기능을 **MongoDB** 및 **AWS DocumentDB** 100% 호환 방식으로 제공합니다.

---

## 주요 기능 (Key Features)

### 1. Zero-Config Spring Boot YAML 자동 탐색 & 운영(PRD) 보호
- 단일/멀티 모듈 프로젝트의 `**/src/main/resources/application*.yml(yaml)` 및 `---` 멀티 도큐먼트 YAML(`spring.config.activate.on-profile`, `spring.profiles`)을 자동 파싱·병합합니다.
- `spring.data.mongodb.uri`와 개별 속성(`host`, `port`, `database`, `username`, `password`, `authentication-database`)을 모두 지원합니다.
- `${VAR:default}` 플레이스홀더(기본값 → 프로젝트 `.env` → 시스템 환경변수 순)를 자동 해석하며, YAML 저장 시 연결 트리가 실시간으로 갱신됩니다.
- **지연 연결(Lazy Connection)**: IDE 시작 시 불필요한 소켓을 열지 않고 프로필을 펼칠 때만 연결합니다.
- **운영 자동 읽기 전용 잠금(`READ-ONLY 🔒`)**: 프로필/파일명에 `prod` 또는 `prd`가 포함되면 쓰기·삭제를 자동 차단하며, 필요 시 현재 세션에 한해 일시 해제할 수 있습니다.

### 2. Compass 스타일 멀티 탭 워크스페이스 & 시각적 위계 개선
- **직관적인 모드 전환**: 상단 메인 모드 탭(`Documents (Find)` / `Aggregations` / `Mongo Shell`)에 하단 강조 바(Accent Indicator)와 굵은 글씨를 적용해 현재 모드를 즉시 구분할 수 있습니다.
- **아이콘 세그먼트 뷰 전환**: 결과 뷰어 우측의 **`Tree` | `Table` | `JSON`** 버튼을 아이콘이 포함된 둥근 세그먼트 컨트롤로 제공하여 현재 보기 모드가 한눈에 들어옵니다.
- **통합 Filter / Sort / Project 에디터**: 자동 높이 조절 JSON 에디터, 문법 하이라이팅, `{}` 자동 감싸기, 필드/연산자 자동완성, Mongo Shell 전처리기(`ObjectId(...)`, `ISODate(...)`, `NumberLong(...)`, `NumberDecimal(...)`, 따옴표 없는 키 지원).
- **빠른 컬럼 선택 (`SELECT` 모드)**: `Table` 컬럼 헤더 우클릭 → **`이 컬럼 숨기기`** 또는 **`표시할 컬럼 선택 (Project)...`** 클릭만으로 `$project` 쿼리를 자동 생성합니다.
- **`Smart $in` 빌더**: 줄바꿈·쉼표·공백으로 구분된 ID 목록을 붙여넣으면 `ObjectId` / 숫자 / 문자열을 자동 판별해 `$in` 쿼리로 변환합니다.

### 3. 5개 논리 그룹 우클릭 메뉴 & 다중 선택 스마트 빠른 필터·정렬
`Tree` 또는 `Table` 뷰에서 `Ctrl`/`Shift` 다중 선택 상태를 유지한 채 우클릭하면 5개 논리 그룹으로 정돈된 메뉴가 열립니다:
1. **동적 빠른 필터 & 정렬** *(기존 `Filter`의 다른 조건은 보존하면서 해당 필드만 안전하게 병합/갱신 후 즉시 조회)*:
   - **단일 스칼라 값**: `이 값으로 필터 (=)` / `이 값 제외하고 필터 (!=)`
   - **동일 필드 + 2개 이상 값**: `선택한 N개 값으로 필터 ($in)` / `선택한 N개 값 제외 ($nin)`
   - **동일 필드 + 2개 날짜/타임스탬프** (`BsonDateTime` 또는 13자리 epoch ms): `선택한 날짜 범위로 필터 ($gte ~ $lte)` / `선택한 2개 날짜 일치 ($in)`
   - **서로 다른 2개 이상 필드**: `선택한 N개 조건 모두 일치 (AND)` / `선택한 N개 조건 중 하나 일치 ($or)` *(기존 Filter에 `$or`가 이미 있으면 `$and`로 안전하게 감싸 기존 조건을 덮어쓰지 않음)*
   - **중첩 객체(`{...}`) / 배열(`[...]`)**: `이 필드 존재 ($exists: true)` / `이 필드 비어있음·없음`
   - **즉시 정렬**: `이 필드로 정렬 ▶ 오름차순 (1) / 내림차순 (-1)`
   - **ObjectId / Date 시각 표시 & UTC 범위 필터**: KST/UTC 병기 표시 및 클릭 두 번으로 정확한 UTC 기준 `$gte` / `$lte` 범위 필터 생성.
2. **편집**: **`데이터 수정`** (구조 편집 + Raw JSON 편집 + 미저장 변경사항 Diff 확인).
3. **복사 서브메뉴**: `값(Value)만 복사` | `키:값(JSON) 복사` | `필드 경로(Path) 복사`.
4. **연관 조회 & 조인**: `참조 컬렉션에서 조회 (새 탭)...` (`ObjectId` ↔ 24자리 hex 자동 변환) 및 `다른 컬렉션과 조인해서 보기...`.
5. **문서 Diff 비교**: 2개 문서 선택 비교 및 다른 탭/환경(`dev` vs `prd`) 간 **Baseline 고정 비교** (키 알파벳 정렬 + 복호화 평문 비교 지원).

### 4. 읽기 전용 인덱스 조회기 & ESR 쿼리 필터 가이드 (`IndexInspectorDialog`)
- **정확한 MongoDB & AWS DocumentDB `explain()` 분석**: `winningPlan`, `executionStages`, `$cursor`, `SUBSCAN`, `IXONLY` 등을 재귀 순회(`rejectedPlans`/`allPlansExecution` 제외)하며 다단계 폴백을 수행합니다. 모든 `explain` 시도가 권한/미지원으로 실패하면 `COLLSCAN`으로 오판하지 않고 **`UNKNOWN`** 배지와 실패 사유 툴팁을 명시적으로 표시합니다.
- **100% 읽기 전용(Read-Only) 인스펙터**: 결과 바의 `IXSCAN` / `COLLSCAN` / `UNKNOWN` 배지를 클릭하거나 좌측 Connections 트리에서 컬렉션 우클릭 → **`인덱스 조회 및 쿼리 가이드...`** 로 엽니다.
  - 현재 컬렉션의 인덱스명, 키 구성, 속성(`UNIQUE`, `TTL`, `SPARSE`), 크기를 60초 통합 캐시 기반으로 중복 DB 호출 없이 조회합니다.
  - **`이 인덱스 키로 Filter 템플릿 적용`** 버튼으로 선택한 인덱스 키 조합을 상단 `Filter` 입력창에 바로 채워 넣을 수 있습니다.
  - 현재 `Filter`/`Sort` 필드와 기존 인덱스를 대조해 `COLLSCAN` 원인과 활용 가능한 인덱스 선두(Prefix) 예시를 안내하며, `_id_` 인덱스만 있거나 미인덱싱 쿼리인 경우 운영/DBA 협의용 **ESR(`Equality → Sort → Range`) 권장 `createIndex` 구문을 복사 전용 텍스트**로 제공합니다 (DB에 인덱스 생성·수정·삭제를 절대 실행하지 않음).

### 5. AWS DocumentDB 100% 호환 다중 컬렉션 조인 뷰 (`A ⋈ B ⋈ C`)
- **Star Join & Chain Join 지원**: 기준 컬렉션에 여러 대상 컬렉션을 연결(`A → B`, `A → C`)하거나 이전 조인 결과를 체이닝(`A → B → C`)합니다. 표준 `localField`/`foreignField` `$lookup`과 `.allowDiskUse(true)`만 사용하여 AWS DocumentDB에서도 오류 없이 동작합니다 (`let`/`pipeline`, `$facet` 미사용).
- **객체 배열(`Array<Object>`) 조인 키 지원**: 스키마 샘플링 시 중첩 객체 및 객체 배열 내부 필드까지 dot-notation 경로(예: `items.product_id`)로 자동 추출해 기준/대상 키 드롭다운에 제공합니다.
- **대상별 `WHERE AND` 조건 & 1:N 전략**: 최신 1건 매칭(`LATEST_ONE`) 또는 전체 펼치기(`UNWIND_ALL`) 선택, `=`, `!=`, `>`, `>=`, `<`, `<=`, `IN`, `값 있음(NOT NULL)`, `값 없음(NULL/빈값)` 조건 및 미매칭 행 제외(INNER JOIN 효과)를 지원합니다.
- **일관된 페이지 건수 & 명시적 Limit 모드**: 파이프라인을 `Base $match → 결정론적 $sort(_id 타이브레이커) → $lookup/제외 $match → 조인 후 단일 $skip / $limit(N+1) → $project` 순서로 고정해 페이지별 건수 들쭉날쭉 현상(17건→13건)과 `LIMIT`/`SKIP` 충돌을 해결했습니다. **`20` / `50` / `100` / `500` / `전체 조회 (Unlimited, 최대 5,000건 안전 상한)`** 옵션을 지원합니다.
- **라이브 편집 & 파이프라인 복사**: **`조건/컬럼 수정`** 버튼으로 현재 조인 설정을 유지한 채 즉시 수정하고, **`파이프라인 복사`** 로 실행된 Aggregation JSON 배열을 복사할 수 있으며, `Table`/`Tree`에 `컬렉션명.원본필드명` 출처가 명확히 표기됩니다.

### 6. 양방향 Aggregation 빌더, Mongo Shell & AES 필드 복호화
- **양방향 Aggregation 파이프라인 빌더**: `🧩 스테이지 빌더 (UI)`(스테이지 ON/OFF 토글, 순서 이동, 5건 단계별 미리보기) ↔ `{ } Raw 스크립트` 실시간 양방향 동기화.
- **Mongo Shell 스마트 헬퍼**: `{{변수명}}` 및 동적 변수(`{{$now}}`, `{{$today}}`, `{{$objectId}}`), 템플릿 삽입, 검색 가능한 문법 치트시트 제공.
- **AES 필드 임시 복호화**: 프로필별 AES/CBC(IV prefix / 고정 IV) 및 AES/ECB 키 설정(YAML 키 자동 탐색 지원), Tree 개별 복호화 및 Table 전용 **`[🔒 일괄 복호화]`** (카운트다운 후 자동 마스킹).
- **인앱 자동 업데이트 & 실시간 한/영 전환 (`KO / EN`)**: 우측 상단 **`설정`** 메뉴에서 GitHub Releases 원클릭 업데이트 및 IDE 재시작 없는 언어 전환을 지원합니다.

---

## 설치 방법 (Installation)

### GitHub Releases ZIP 수동 설치 (Install from Disk)
1. [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases)에서 최신 `maekom-mongo-lens-0.2.3.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **`Settings / Preferences` → `Plugins` → ⚙️(기어 아이콘) → `Install Plugin from Disk...`** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하고 IDE를 재시작합니다.
4. 하단 ToolWindow 영역에서 **`Maekom Mongo Lens`** 탭을 엽니다.

> 최초 설치 이후에는 플러그인 우측 상단 **`설정` → `업데이트 확인`** 메뉴를 통해 인앱에서 원클릭 업데이트를 진행할 수 있습니다.

---

## Spring Boot YAML Zero-Config 예시

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

- 프로필명 또는 파일명에 `prod` / `prd`가 포함되면 자동으로 **`READ-ONLY 🔒`** 보호 모드가 켜집니다.
- 기본값이 없는 `${PRD_MONGO_HOST}` 변수는 프로젝트 루트의 `.env` 파일 또는 OS 환경변수에서 자동으로 값을 읽어옵니다.

---

## 단축키 및 빠른 사용법 (Shortcuts)

| 기능 | 단축키 / 방법 |
|---|---|
| 쿼리 / 파이프라인 / Shell 실행 | `Ctrl + Enter` (macOS: `Cmd + Enter`) |
| 필드명 / MongoDB 연산자 자동완성 | `Ctrl + Space` (또는 `"`, `$` 입력 시 자동 팝업) |
| 다중 선택 스마트 빠른 필터 | `Table` 셀 또는 `Tree` 노드 `Ctrl`/`Shift` 클릭 → 우클릭 |
| 인덱스 조회 및 쿼리 가이드 | `IXSCAN` / `COLLSCAN` / `UNKNOWN` 배지 클릭, 또는 Connections 트리 컬렉션 우클릭 |
| 빠른 컬럼 선택 (`SELECT` 모드) | `Table` 컬럼 헤더 우클릭 → **`표시할 컬럼 선택 (Project)...`** |
| 다중 컬렉션 조인 뷰 (`A ⋈ B`) | 필드 우클릭 → **`다른 컬렉션과 조인해서 보기...`** |
| 2개 문서 Diff 비교 | `Ctrl`/`Shift` 클릭으로 2개 문서 선택 → 우클릭 → **`선택한 2개 문서 Diff 비교`** |
| Raw JSON 결과 내 검색 | `JSON` 세그먼트 선택 후 `Ctrl + F` (macOS: `Cmd + F`) |
| UI 언어 즉시 전환 (`KO ↔ EN`) | 우측 상단 **`설정` → `언어 변경`** |

---

## 소스 빌드 방법 (Build from Source)

```bash
./gradlew buildPlugin --no-daemon
```

빌드가 완료되면 `build/distributions/maekom-mongo-lens-0.2.3.zip` 파일이 생성됩니다.

---

## 라이선스 (License)

이 프로젝트는 **Apache License 2.0** 라이선스로 배포됩니다. 자세한 내용은 [LICENSE](./LICENSE) 파일을 참고하세요.
