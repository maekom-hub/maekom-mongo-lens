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

## 빌드 방법 / Build from Source

```bash
./gradlew buildPlugin
```

빌드가 완료되면 `build/distributions/maekom-mongo-lens-0.1.3.zip` 파일이 생성됩니다.

---

## 라이선스 / License

이 프로젝트는 **Apache License 2.0** 라이선스로 배포됩니다. 자세한 내용은 [LICENSE](./LICENSE) 파일을 참고하세요.

This project is licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
