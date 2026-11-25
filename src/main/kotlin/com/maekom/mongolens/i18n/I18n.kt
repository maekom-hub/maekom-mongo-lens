package com.maekom.mongolens.i18n

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

object I18n {
    enum class Lang(val code: String) {
        KO("KO"),
        EN("EN")
    }

    private const val PREF_KEY = "com.maekom.mongolens.ui.language"
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var currentLang: Lang = loadSavedLang()

    private fun loadSavedLang(): Lang {
        return try {
            val saved = PropertiesComponent.getInstance().getValue(PREF_KEY, Lang.KO.code)
            if (saved.equals(Lang.EN.code, ignoreCase = true)) Lang.EN else Lang.KO
        } catch (_: Throwable) {
            Lang.KO
        }
    }

    fun getLang(): Lang = currentLang

    fun setLang(lang: Lang) {
        if (currentLang == lang) return
        currentLang = lang
        try {
            PropertiesComponent.getInstance().setValue(PREF_KEY, lang.code)
        } catch (_: Throwable) {
            // Ignore in headless/non-IDE contexts
        }
        listeners.forEach { listener ->
            try {
                listener()
            } catch (_: Throwable) {
                // Keep notifying remaining listeners
            }
        }
    }

    fun toggle() {
        setLang(if (currentLang == Lang.KO) Lang.EN else Lang.KO)
    }

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun t(key: String, vararg args: Any): String {
        val map = if (currentLang == Lang.KO) ko else en
        var template = map[key] ?: en[key] ?: key
        args.forEachIndexed { idx, arg ->
            template = template.replace("{$idx}", arg.toString())
        }
        return template
    }

    private val ko: Map<String, String> = mapOf(
        // Global Header
        "app.title" to "Maekom Mongo Lens",
        "header.allProfiles" to "전체 프로필 (All)",
        "header.addCustomUri" to "+ Custom URI 추가...",
        "header.syncYaml" to "↻ YAML 동기화",
        "header.syncYamlTooltip" to "프로젝트 내 Spring Boot YAML 파일의 MongoDB 설정을 즉시 다시 스캔합니다.",
        "header.status.idle" to "대기 중",
        "header.status.connecting" to "연결 중...",
        "header.status.connected" to "연결됨 ({0}ms)",
        "header.status.readOnly" to "운영 읽기전용 🔒",
        "header.status.querying" to "조회 중...",
        "header.status.error" to "오류: {0}",
        "header.update.check" to "↻ 업데이트 확인",
        "header.update.checking" to "확인 중...",
        "header.update.available" to "⬆ {0} 업데이트",
        "header.update.upToDate" to "최신 버전입니다 ({0}).",
        "header.update.failed" to "업데이트 확인 실패: {0}",
        "header.update.tooltip" to "GitHub Releases에서 최신 버전을 확인합니다.",
        "header.update.availableTooltip" to "클릭하여 {0} 버전을 다운로드하고 설치합니다.",
        "header.lang.toggleTooltip" to "UI 언어를 한국어/영어로 즉시 전환합니다.",

        // Update Dialog
        "update.dialog.title" to "Maekom Mongo Lens 업데이트",
        "update.dialog.confirmDownload" to "새 버전 {0}이(가) 출시되었습니다. (현재: v{1})\n지금 다운로드하여 설치를 예약하시겠습니까?",
        "update.dialog.progressTitle" to "Maekom Mongo Lens {0} 다운로드 중...",
        "update.dialog.verifying" to "다운로드 파일 무결성 검증 중...",
        "update.dialog.scheduling" to "플러그인 설치 예약 중...",
        "update.dialog.restartTitle" to "업데이트 설치 준비 완료",
        "update.dialog.restartMessage" to "Maekom Mongo Lens {0} 설치가 예약되었습니다.\n변경사항을 적용하려면 IDE를 지금 재시작해야 합니다. 지금 재시작하시겠습니까?",
        "update.dialog.restartNow" to "지금 재시작",
        "update.dialog.restartLater" to "나중에",
        "update.dialog.error" to "업데이트 설치 중 오류가 발생했습니다:\n{0}",

        // Tabs Context Menu
        "tab.close" to "현재 탭 닫기",
        "tab.closeOthers" to "다른 탭 모두 닫기",
        "tab.closeAll" to "모든 탭 닫기",

        // Connections Tree
        "tree.header" to "Connections",
        "tree.empty" to "탐색된 MongoDB 설정이 없습니다.\nYAML 파일을 추가하거나 '+ Custom URI'를 클릭하세요.",
        "tree.loading" to "불러오는 중...",
        "tree.errorNode" to "연결 실패: {0}",
        "tree.emptyCollections" to "(컬렉션 없음)",
        "tree.emptyDatabases" to "(데이터베이스 없음)",
        "tree.badge.readOnly" to "READ-ONLY 🔒",
        "tree.badge.unlocked" to "세션 잠금해제 🔓",
        "tree.ctx.openFind" to "새 Find 탭으로 열기",
        "tree.ctx.openAgg" to "새 Aggregation 탭으로 열기",
        "tree.ctx.openShell" to "새 Mongo Shell 탭으로 열기",
        "tree.ctx.copyName" to "컬렉션명 복사",
        "tree.ctx.refreshCount" to "예상 문서 수 새로고침",
        "tree.ctx.reconnect" to "연결 새로고침",
        "tree.ctx.unlockSession" to "읽기 전용 일시 해제 (현재 세션)",
        "tree.ctx.relockSession" to "읽기 전용 다시 잠금",
        "tree.ctx.editCustom" to "연결 편집",
        "tree.ctx.deleteCustom" to "연결 삭제",
        "tree.deleteCustom.confirm" to "Custom 연결 '{0}'을(를) 삭제하시겠습니까?",

        // Read-Only Protection
        "readonly.blocked.title" to "운영(PRD) 읽기 전용 보호",
        "readonly.blocked.msg" to "프로필 '{0}'은(는) 운영 보호 모드(READ-ONLY)로 잠겨 있습니다.\n문서 수정·복제·삭제가 차단되었습니다.\n현재 세션에 한해 잠금을 일시 해제하시겠습니까?",
        "readonly.unlock.title" to "운영 쓰기 잠금 일시 해제",
        "readonly.unlock.msg" to "경고: 프로필 '{0}'은(는) 운영(PRD) 환경으로 감지되었습니다.\n정말로 현재 세션 동안 문서 수정·복제·삭제를 허용하시겠습니까?",
        "readonly.unlock.action" to "현재 세션만 해제",

        // Custom URI Dialog
        "custom.dialog.title" to "Custom MongoDB 연결 추가",
        "custom.dialog.editTitle" to "Custom MongoDB 연결 편집",
        "custom.dialog.name" to "연결 이름",
        "custom.dialog.uri" to "MongoDB URI",
        "custom.dialog.database" to "기본 DB (선택)",
        "custom.dialog.readOnly" to "읽기 전용(READ-ONLY)으로 보호",
        "custom.dialog.test" to "연결 테스트",
        "custom.dialog.testing" to "연결 테스트 중...",
        "custom.dialog.testSuccess" to "연결 성공! ({0}ms)",
        "custom.dialog.testFail" to "연결 실패: {0}",
        "custom.dialog.validationName" to "연결 이름을 입력하세요.",
        "custom.dialog.validationUri" to "mongodb:// 또는 mongodb+srv:// URI를 입력하세요.",

        // Workspace Empty State
        "workspace.welcome.title" to "Maekom Mongo Lens에 오신 것을 환영합니다",
        "workspace.welcome.desc" to "좌측 Connections 트리에서 프로필을 확장하고 컬렉션을 클릭하여 Find, Aggregation 또는 Mongo Shell 탭을 여세요.\nSpring Boot의 application*.yml 설정은 자동으로 인식됩니다.",
        "workspace.mode.find" to "Documents (Find)",
        "workspace.mode.agg" to "Aggregations",
        "workspace.mode.shell" to "Mongo Shell",

        // Find Query Bar
        "find.filter" to "Filter",
        "find.project" to "Project",
        "find.projectAdd" to "+ Project",
        "find.projectHide" to "- Project",
        "find.sort" to "Sort",
        "find.limit" to "Limit",
        "find.skip" to "Skip",
        "find.run" to "▶ 조회",
        "find.runTooltip" to "쿼리를 실행합니다 (Ctrl+Enter / Cmd+Enter)",
        "find.reset" to "초기화",
        "find.smartIn" to "📋 Smart \$in",
        "find.smartInTooltip" to "줄바꿈/쉼표/공백으로 구분된 ID 목록을 \$in 쿼리로 자동 변환합니다.",
        "find.toShell" to "Shell",
        "find.toShellTooltip" to "현재 Find 조건을 Mongo Shell(mongosh) 코드로 변환하여 Shell 탭으로 이동합니다.",
        "find.copyShell" to "Shell 복사",
        "find.copyShellTooltip" to "mongosh find 명령어를 클립보드에 복사합니다.",
        "find.savedQueries" to "⭐ 저장/기록",
        "find.savedQueries.save" to "+ 현재 쿼리 저장",
        "find.savedQueries.savedTab" to "저장된 쿼리 (Saved)",
        "find.savedQueries.historyTab" to "최근 실행 기록 (History)",
        "find.savedQueries.clearHistory" to "기록 전체 비우기",
        "find.savedQueries.clearHistoryConfirm" to "최근 실행 기록을 모두 비우시겠습니까?",
        "find.savedQueries.deleteTooltip" to "이 항목 삭제",
        "find.savedQueries.empty" to "(저장된 항목 없음)",
        "find.savedQueries.promptName" to "저장할 쿼리 이름을 입력하세요:",
        "find.savedQueries.savedToast" to "쿼리 '{0}'이(가) 저장되었습니다.",
        "find.limitDecreaseTooltip" to "조회 건수를 줄이고 즉시 재조회합니다.",
        "find.limitIncreaseTooltip" to "조회 건수를 늘리고 즉시 재조회합니다.",

        // Smart $in Dialog
        "smartIn.dialog.title" to "Smart \$in 쿼리 빌더",
        "smartIn.dialog.fieldLabel" to "대상 필드명:",
        "smartIn.dialog.inputLabel" to "ID 목록 붙여넣기 (줄바꿈, 쉼표, 공백 구분 — ObjectId/숫자/문자열 자동 판별):",
        "smartIn.dialog.previewLabel" to "생성된 쿼리 미리보기:",
        "smartIn.dialog.apply" to "Filter에 적용",
        "smartIn.dialog.applyAndRun" to "적용 후 바로 조회",

        // Result Viewer
        "result.summary" to "{0} - {1} / 총 {2}건 ({3}ms)",
        "result.summaryApprox" to "{0} - {1} / 총 {2}+건 ({3}ms)",
        "result.summaryEmpty" to "0건 ({0}ms)",
        "result.view.tree" to "Tree",
        "result.view.json" to "JSON",
        "result.view.table" to "Table",
        "result.tree.expandAll" to "전체 펼치기",
        "result.tree.collapseAll" to "전체 접기",
        "result.badge.ixscan" to "IXSCAN",
        "result.badge.collscan" to "COLLSCAN",
        "result.badge.ixscanTooltip" to "인덱스 스캔 사용됨: {0}",
        "result.badge.collscanTooltip" to "주의: 인덱스 없이 전체 컬렉션 스캔(COLLSCAN)이 발생했습니다.",
        "result.prev" to "◀ 이전",
        "result.next" to "다음 ▶",
        "result.empty" to "조회된 문서가 없습니다.",
        "result.doc.copyJson" to "JSON 복사",
        "result.doc.copyId" to "_id 복사",
        "result.doc.edit" to "문서 수정",
        "result.doc.editData" to "데이터 수정",
        "result.doc.copyKeyValue" to "키/값 복사",
        "result.doc.clone" to "문서 복제",
        "result.doc.delete" to "문서 삭제",
        "result.doc.noSelection" to "먼저 문서를 선택하세요.",
        "result.doc.editTitle" to "데이터 수정",
        "result.doc.editSuccess" to "문서가 저장되었습니다.",
        "result.doc.cloneSuccess" to "새 ObjectId로 문서가 복제되었습니다.",
        "result.doc.deleteConfirm" to "_id: {0} 문서를 정말로 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
        "result.doc.deleteTitle" to "문서 전체 삭제 확인",
        "result.doc.deleteSuccess" to "문서가 삭제되었습니다.",
        "edit.dialog.readOnlyBanner" to "운영 읽기 전용 모드입니다 (수정/삭제 잠금)",
        "edit.dialog.mode.structured" to "구조 편집 (Key-Value)",
        "edit.dialog.mode.rawJson" to "Raw JSON 편집",
        "edit.dialog.copyId" to "_id 복사",
        "edit.dialog.copyFullJson" to "JSON 전체 복사",
        "edit.dialog.col.key" to "키 (Key)",
        "edit.dialog.col.type" to "타입 (BSON)",
        "edit.dialog.col.value" to "값 (Value)",
        "edit.dialog.col.action" to "",
        "edit.dialog.addField" to "+ 키/값 추가",
        "edit.dialog.addFieldTitle" to "새 키/값 추가",
        "edit.dialog.addFieldKey" to "키 이름",
        "edit.dialog.addFieldType" to "BSON 타입",
        "edit.dialog.addFieldValue" to "값",
        "edit.dialog.addFieldKeyRequired" to "키 이름을 입력하세요.",
        "edit.dialog.deleteKeyTooltip" to "이 키 라인 삭제",
        "edit.dialog.deleteDocBtn" to "🗑 이 문서(_id) 전체 삭제",
        "edit.dialog.save" to "저장",
        "edit.dialog.cancel" to "취소",
        "edit.dialog.syncError" to "JSON 구문 오류로 전환할 수 없습니다: {0}",
        "edit.dialog.validationError" to "필드 '{0}' ({1}) 값 변환 오류: {2}",
        "result.table.copyCell" to "셀 값 복사",
        "result.table.copyColumn" to "컬럼명 복사",
        "result.table.copyRowJson" to "행 JSON 복사",
        "result.date.kstTooltip" to "KST (UTC+9): {0}",
        "result.copied" to "클립보드에 복사되었습니다.",

        // Aggregation Builder
        "agg.mode.ui" to "🧩 스테이지 빌더 (UI)",
        "agg.mode.raw" to "{ } Raw 스크립트",
        "agg.addStage" to "+ 스테이지 추가",
        "agg.run" to "▶ 파이프라인 실행",
        "agg.copy" to "파이프라인 복사",
        "agg.toShell" to "Shell로 변환",
        "agg.toShellTooltip" to "현재 파이프라인을 db.<col>.aggregate([...]) 코드로 변환하여 Mongo Shell 탭으로 이동합니다.",
        "agg.stage.on" to "ON",
        "agg.stage.off" to "OFF",
        "agg.stage.preview" to "미리보기 (5건)",
        "agg.stage.previewTooltip" to "이 스테이지까지 실행하고 \$limit: 5로 결과를 미리 봅니다.",
        "agg.stage.delete" to "🗑 삭제",
        "agg.stage.moveUp" to "위로 이동",
        "agg.stage.moveDown" to "아래로 이동",
        "agg.emptyStages" to "'[+ 스테이지 추가]' 버튼을 클릭하여 파이프라인 스테이지를 구성하세요.",
        "agg.error.parseRaw" to "Raw JSON 파싱 오류 (입력 내용은 보존되었습니다): {0}",
        "agg.error.notArray" to "파이프라인은 JSON 배열([ ... ]) 형태여야 합니다.",
        "agg.error.invalidStage" to "{0}번째 스테이지는 단일 연산자 키(예: \"\$match\")를 가진 JSON 객체여야 합니다.",
        "agg.previewBanner" to "스테이지 #{0} ({1})까지 미리보기 결과 (최대 5건)",

        // Mongo Shell Smart Helper
        "shell.run" to "▶ 실행",
        "shell.runTooltip" to "Mongo Shell 스크립트를 실행합니다 (Ctrl+Enter / Cmd+Enter)",
        "shell.templates" to "⚡ 템플릿 삽입 ▾",
        "shell.cheatsheet" to "📖 문법 가이드",
        "shell.previewVars" to "🔍 치환 미리보기",
        "shell.variables" to "{{ }} 변수",
        "shell.variables.title" to "Mongo Shell 변수 관리",
        "shell.variables.desc" to "스크립트에서 {{변수명}} 형태로 참조할 전역/로컬 변수를 관리합니다. (내장 동적 변수: {{\$now}}, {{\$today}}, {{\$objectId}})",
        "shell.variables.key" to "변수명",
        "shell.variables.value" to "값",
        "shell.variables.add" to "+ 변수 추가",
        "shell.variables.remove" to "선택 삭제",
        "shell.preview.title" to "스마트 변수 치환 미리보기",
        "shell.preview.desc" to "실제 MongoDB로 전송될 최종 변환 스크립트입니다:",
        "shell.cheatsheet.title" to "mongosh 문법 & 예시 치트시트",
        "shell.cheatsheet.search" to "연산자, 문법, 키워드 검색...",
        "shell.cheatsheet.copy" to "복사",
        "shell.cheatsheet.insert" to "삽입",
        "shell.tpl.cat.basic" to "1. 기본 조회 & 정렬",
        "shell.tpl.cat.operators" to "2. 비교 / 날짜 / 검색 연산자",
        "shell.tpl.cat.agg" to "3. 집계 파이프라인",
        "shell.tpl.cat.write" to "4. 데이터 수정/삭제 (개발 전용 - PRD 차단)",
        "shell.tpl.basic.findSortLimit" to "조건 조회 + 정렬 + 건수 제한 (find/sort/skip/limit)",
        "shell.tpl.basic.projection" to "원하는 필드만 선택 조회 (Projection)",
        "shell.tpl.basic.count" to "건수 카운트 (countDocuments)",
        "shell.tpl.basic.distinct" to "중복 제거 고유값 (distinct)",
        "shell.tpl.op.in" to "다건 ID 목록 조회 (\$in)",
        "shell.tpl.op.dateRange" to "날짜/기간 범위 조회 (ISODate \$gte~\$lte)",
        "shell.tpl.op.regex" to "문자열 부분 검색 (\$regex + 대소문자 무시)",
        "shell.tpl.op.existsNull" to "필드 존재 여부 및 Null 체크 (\$exists / \$ne)",
        "shell.tpl.agg.groupSum" to "상태별 건수 및 합계 (\$match → \$group → \$sort)",
        "shell.tpl.agg.unwind" to "배열 필드 펼치기 (\$unwind)",
        "shell.tpl.agg.lookup" to "다른 컬렉션 조인 (\$lookup)",
        "shell.tpl.write.updateOne" to "단건 수정 (updateOne + \$set)",
        "shell.tpl.write.updateMany" to "다건 일괄 수정 (updateMany)",
        "shell.tpl.write.deleteOne" to "단건 삭제 (deleteOne)",
        "shell.cheat.1.title" to "1. 체이닝 기본 구조",
        "shell.cheat.1.desc" to "조건 필터, 출력 필드(Projection), 정렬, 건너뛰기, 최대 건수를 체이닝해 조회합니다.",
        "shell.cheat.2.title" to "2. 비교 연산자 (\$eq, \$ne, \$gt, \$gte, \$lt, \$lte, \$in, \$nin)",
        "shell.cheat.2.desc" to "숫자·날짜·문자열 범위 및 목록 포함 여부를 필터링합니다.",
        "shell.cheat.3.title" to "3. 논리 / 요소 / 문자열 연산자 (\$and, \$or, \$exists, \$type, \$regex, \$elemMatch)",
        "shell.cheat.3.desc" to "복합 조건, 필드 존재/타입 검사, 정규식 검색, 배열 내부 조건 매칭에 사용합니다.",
        "shell.cheat.4.title" to "4. BSON 타입 헬퍼 (ObjectId, ISODate, NumberLong, NumberDecimal)",
        "shell.cheat.4.desc" to "Mongo Shell 문법 그대로 작성해도 자동으로 BSON 타입으로 변환되어 실행됩니다.",
        "shell.cheat.5.title" to "5. 전역 / 동적 변수 치환 ({{변수명}}, {{\$now}}, {{\$today}}, {{\$objectId}})",
        "shell.cheat.5.desc" to "따옴표 안(\"{{var}}\")은 순수 문자열로, 따옴표 밖({{var}})은 타입(ObjectId/숫자/날짜)을 자동 판별해 치환합니다."
    )

    private val en: Map<String, String> = mapOf(
        // Global Header
        "app.title" to "Maekom Mongo Lens",
        "header.allProfiles" to "All Profiles",
        "header.addCustomUri" to "+ Add Custom URI...",
        "header.syncYaml" to "↻ Sync YAML",
        "header.syncYamlTooltip" to "Re-scan Spring Boot YAML files in the project immediately.",
        "header.status.idle" to "Idle",
        "header.status.connecting" to "Connecting...",
        "header.status.connected" to "Connected ({0}ms)",
        "header.status.readOnly" to "PRD Read-Only 🔒",
        "header.status.querying" to "Querying...",
        "header.status.error" to "Error: {0}",
        "header.update.check" to "↻ Check Updates",
        "header.update.checking" to "Checking...",
        "header.update.available" to "⬆ Update {0}",
        "header.update.upToDate" to "You are on the latest version ({0}).",
        "header.update.failed" to "Update check failed: {0}",
        "header.update.tooltip" to "Check GitHub Releases for a newer version.",
        "header.update.availableTooltip" to "Click to download and install version {0}.",
        "header.lang.toggleTooltip" to "Switch UI language between Korean and English immediately.",

        // Update Dialog
        "update.dialog.title" to "Maekom Mongo Lens Update",
        "update.dialog.confirmDownload" to "A new version {0} is available (Current: v{1}).\nDownload and schedule installation now?",
        "update.dialog.progressTitle" to "Downloading Maekom Mongo Lens {0}...",
        "update.dialog.verifying" to "Verifying downloaded archive integrity...",
        "update.dialog.scheduling" to "Scheduling plugin installation...",
        "update.dialog.restartTitle" to "Update Ready to Install",
        "update.dialog.restartMessage" to "Maekom Mongo Lens {0} has been staged for installation.\nRestart IDE now to apply the update?",
        "update.dialog.restartNow" to "Restart Now",
        "update.dialog.restartLater" to "Later",
        "update.dialog.error" to "Failed to install update:\n{0}",

        // Tabs Context Menu
        "tab.close" to "Close Tab",
        "tab.closeOthers" to "Close Other Tabs",
        "tab.closeAll" to "Close All Tabs",

        // Connections Tree
        "tree.header" to "Connections",
        "tree.empty" to "No MongoDB configurations found.\nAdd Spring YAML config or click '+ Custom URI'.",
        "tree.loading" to "Loading...",
        "tree.errorNode" to "Connection failed: {0}",
        "tree.emptyCollections" to "(No collections)",
        "tree.emptyDatabases" to "(No databases)",
        "tree.badge.readOnly" to "READ-ONLY 🔒",
        "tree.badge.unlocked" to "Session Unlocked 🔓",
        "tree.ctx.openFind" to "Open in New Find Tab",
        "tree.ctx.openAgg" to "Open in New Aggregation Tab",
        "tree.ctx.openShell" to "Open in New Mongo Shell Tab",
        "tree.ctx.copyName" to "Copy Collection Name",
        "tree.ctx.refreshCount" to "Refresh Estimated Count",
        "tree.ctx.reconnect" to "Reconnect & Refresh",
        "tree.ctx.unlockSession" to "Temporarily Unlock Write (This Session)",
        "tree.ctx.relockSession" to "Re-lock Read-Only",
        "tree.ctx.editCustom" to "Edit Connection",
        "tree.ctx.deleteCustom" to "Delete Connection",
        "tree.deleteCustom.confirm" to "Delete custom connection '{0}'?",

        // Read-Only Protection
        "readonly.blocked.title" to "Production Read-Only Protection",
        "readonly.blocked.msg" to "Profile '{0}' is protected in READ-ONLY mode.\nEdit, clone, and delete operations are blocked.\nTemporarily unlock write access for this session?",
        "readonly.unlock.title" to "Unlock Production Write Access",
        "readonly.unlock.msg" to "Warning: Profile '{0}' is detected as a production (PRD) environment.\nAllow document edit, clone, and delete for the current session only?",
        "readonly.unlock.action" to "Unlock for This Session",

        // Custom URI Dialog
        "custom.dialog.title" to "Add Custom MongoDB Connection",
        "custom.dialog.editTitle" to "Edit Custom MongoDB Connection",
        "custom.dialog.name" to "Connection Name",
        "custom.dialog.uri" to "MongoDB URI",
        "custom.dialog.database" to "Default DB (Optional)",
        "custom.dialog.readOnly" to "Protect as READ-ONLY",
        "custom.dialog.test" to "Test Connection",
        "custom.dialog.testing" to "Testing connection...",
        "custom.dialog.testSuccess" to "Connected successfully! ({0}ms)",
        "custom.dialog.testFail" to "Connection failed: {0}",
        "custom.dialog.validationName" to "Please enter a connection name.",
        "custom.dialog.validationUri" to "Please enter a valid mongodb:// or mongodb+srv:// URI.",

        // Workspace Empty State
        "workspace.welcome.title" to "Welcome to Maekom Mongo Lens",
        "workspace.welcome.desc" to "Expand a profile in the Connections tree and click a collection to open a Find, Aggregation, or Mongo Shell workspace.\nSpring Boot application*.yml files are discovered automatically.",
        "workspace.mode.find" to "Documents (Find)",
        "workspace.mode.agg" to "Aggregations",
        "workspace.mode.shell" to "Mongo Shell",

        // Find Query Bar
        "find.filter" to "Filter",
        "find.project" to "Project",
        "find.projectAdd" to "+ Project",
        "find.projectHide" to "- Project",
        "find.sort" to "Sort",
        "find.limit" to "Limit",
        "find.skip" to "Skip",
        "find.run" to "▶ Find",
        "find.runTooltip" to "Execute query (Ctrl+Enter / Cmd+Enter)",
        "find.reset" to "Reset",
        "find.smartIn" to "📋 Smart \$in",
        "find.smartInTooltip" to "Convert newline/comma/space-separated IDs into a \$in query.",
        "find.toShell" to "Shell",
        "find.toShellTooltip" to "Convert current Find query into Mongo Shell (mongosh) script and switch to Shell tab.",
        "find.copyShell" to "Copy Shell",
        "find.copyShellTooltip" to "Copy mongosh find command to clipboard.",
        "find.savedQueries" to "⭐ Saved / History",
        "find.savedQueries.save" to "+ Save Current Query",
        "find.savedQueries.savedTab" to "Saved Queries",
        "find.savedQueries.historyTab" to "Recent History",
        "find.savedQueries.clearHistory" to "Clear All History",
        "find.savedQueries.clearHistoryConfirm" to "Clear all recent query history entries?",
        "find.savedQueries.deleteTooltip" to "Delete this item",
        "find.savedQueries.empty" to "(No items)",
        "find.savedQueries.promptName" to "Enter a name for this query:",
        "find.savedQueries.savedToast" to "Query '{0}' saved.",
        "find.limitDecreaseTooltip" to "Decrease limit and re-query immediately.",
        "find.limitIncreaseTooltip" to "Increase limit and re-query immediately.",

        // Smart $in Dialog
        "smartIn.dialog.title" to "Smart \$in Query Builder",
        "smartIn.dialog.fieldLabel" to "Target Field:",
        "smartIn.dialog.inputLabel" to "Paste IDs (newline, comma, or space separated — auto-detects ObjectId / Number / String):",
        "smartIn.dialog.previewLabel" to "Generated Query Preview:",
        "smartIn.dialog.apply" to "Apply to Filter",
        "smartIn.dialog.applyAndRun" to "Apply & Run",

        // Result Viewer
        "result.summary" to "{0} - {1} / {2} docs ({3}ms)",
        "result.summaryApprox" to "{0} - {1} / {2}+ docs ({3}ms)",
        "result.summaryEmpty" to "0 docs ({0}ms)",
        "result.view.tree" to "Tree",
        "result.view.json" to "JSON",
        "result.view.table" to "Table",
        "result.tree.expandAll" to "Expand All",
        "result.tree.collapseAll" to "Collapse All",
        "result.badge.ixscan" to "IXSCAN",
        "result.badge.collscan" to "COLLSCAN",
        "result.badge.ixscanTooltip" to "Index scan used: {0}",
        "result.badge.collscanTooltip" to "Warning: Full collection scan (COLLSCAN) detected without index.",
        "result.prev" to "◀ Prev",
        "result.next" to "Next ▶",
        "result.empty" to "No documents found.",
        "result.doc.copyJson" to "Copy JSON",
        "result.doc.copyId" to "Copy _id",
        "result.doc.edit" to "Edit Document",
        "result.doc.editData" to "Edit Data...",
        "result.doc.copyKeyValue" to "Copy Key / Value",
        "result.doc.clone" to "Clone Document",
        "result.doc.delete" to "Delete Document",
        "result.doc.noSelection" to "Please select a document first.",
        "result.doc.editTitle" to "Edit Data",
        "result.doc.editSuccess" to "Document saved successfully.",
        "result.doc.cloneSuccess" to "Document cloned with a new ObjectId.",
        "result.doc.deleteConfirm" to "Permanently delete the entire document (_id: {0})?\nThis action cannot be undone.",
        "result.doc.deleteTitle" to "Confirm Delete Entire Document",
        "result.doc.deleteSuccess" to "Document deleted.",
        "edit.dialog.readOnlyBanner" to "Production Read-Only Mode (Edit/Delete Locked)",
        "edit.dialog.mode.structured" to "Structured (Key-Value)",
        "edit.dialog.mode.rawJson" to "Raw JSON Edit",
        "edit.dialog.copyId" to "Copy _id",
        "edit.dialog.copyFullJson" to "Copy Full JSON",
        "edit.dialog.col.key" to "Key",
        "edit.dialog.col.type" to "BSON Type",
        "edit.dialog.col.value" to "Value",
        "edit.dialog.col.action" to "",
        "edit.dialog.addField" to "+ Add Key/Value",
        "edit.dialog.addFieldTitle" to "Add New Field",
        "edit.dialog.addFieldKey" to "Key Name",
        "edit.dialog.addFieldType" to "BSON Type",
        "edit.dialog.addFieldValue" to "Value",
        "edit.dialog.addFieldKeyRequired" to "Please enter a key name.",
        "edit.dialog.deleteKeyTooltip" to "Delete this key line",
        "edit.dialog.deleteDocBtn" to "🗑 Delete Entire Document (_id)",
        "edit.dialog.save" to "Save",
        "edit.dialog.cancel" to "Cancel",
        "edit.dialog.syncError" to "Cannot switch due to JSON syntax error: {0}",
        "edit.dialog.validationError" to "Invalid value for field '{0}' ({1}): {2}",
        "result.table.copyCell" to "Copy Cell Value",
        "result.table.copyColumn" to "Copy Column Name",
        "result.table.copyRowJson" to "Copy Row JSON",
        "result.date.kstTooltip" to "KST (UTC+9): {0}",
        "result.copied" to "Copied to clipboard.",

        // Aggregation Builder
        "agg.mode.ui" to "🧩 Stage Builder (UI)",
        "agg.mode.raw" to "{ } Raw Script",
        "agg.addStage" to "+ Add Stage",
        "agg.run" to "▶ Run Pipeline",
        "agg.copy" to "Copy Pipeline",
        "agg.toShell" to "Convert to Shell",
        "agg.toShellTooltip" to "Convert pipeline to db.<col>.aggregate([...]) and switch to Mongo Shell tab.",
        "agg.stage.on" to "ON",
        "agg.stage.off" to "OFF",
        "agg.stage.preview" to "Preview (5)",
        "agg.stage.previewTooltip" to "Execute pipeline up to this stage with \$limit: 5.",
        "agg.stage.delete" to "🗑 Delete",
        "agg.stage.moveUp" to "Move Up",
        "agg.stage.moveDown" to "Move Down",
        "agg.emptyStages" to "Click '[+ Add Stage]' to build your aggregation pipeline.",
        "agg.error.parseRaw" to "Raw JSON parse error (your script was preserved): {0}",
        "agg.error.notArray" to "Pipeline must be a JSON array ([ ... ]).",
        "agg.error.invalidStage" to "Stage #{0} must be a JSON object with a single operator key (e.g., \"\$match\").",
        "agg.previewBanner" to "Preview up to Stage #{0} ({1}) — max 5 docs",

        // Mongo Shell Smart Helper
        "shell.run" to "▶ Run",
        "shell.runTooltip" to "Execute Mongo Shell script (Ctrl+Enter / Cmd+Enter)",
        "shell.templates" to "⚡ Insert Template ▾",
        "shell.cheatsheet" to "📖 Syntax Guide",
        "shell.previewVars" to "🔍 Preview Vars",
        "shell.variables" to "{{ }} Variables",
        "shell.variables.title" to "Mongo Shell Variables",
        "shell.variables.desc" to "Manage global/local {{var}} placeholders. Built-in dynamic variables: {{\$now}}, {{\$today}}, {{\$objectId}}.",
        "shell.variables.key" to "Variable",
        "shell.variables.value" to "Value",
        "shell.variables.add" to "+ Add Variable",
        "shell.variables.remove" to "Remove Selected",
        "shell.preview.title" to "Smart Variable Substitution Preview",
        "shell.preview.desc" to "Final resolved script that will be executed against MongoDB:",
        "shell.cheatsheet.title" to "mongosh Syntax & Examples Cheatsheet",
        "shell.cheatsheet.search" to "Filter operators, syntax, keywords...",
        "shell.cheatsheet.copy" to "Copy",
        "shell.cheatsheet.insert" to "Insert",
        "shell.tpl.cat.basic" to "1. Basic Query & Sort",
        "shell.tpl.cat.operators" to "2. Comparison / Date / Search",
        "shell.tpl.cat.agg" to "3. Aggregation Pipelines",
        "shell.tpl.cat.write" to "4. Write / Delete (Dev Only - PRD Blocked)",
        "shell.tpl.basic.findSortLimit" to "Find + Sort + Skip + Limit",
        "shell.tpl.basic.projection" to "Select Specific Fields (Projection)",
        "shell.tpl.basic.count" to "Count Matching Documents (countDocuments)",
        "shell.tpl.basic.distinct" to "Distinct Field Values (distinct)",
        "shell.tpl.op.in" to "Match Multiple IDs (\$in)",
        "shell.tpl.op.dateRange" to "Date Range Query (ISODate \$gte ~ \$lte)",
        "shell.tpl.op.regex" to "Substring Search (\$regex case-insensitive)",
        "shell.tpl.op.existsNull" to "Field Existence & Non-Null (\$exists / \$ne)",
        "shell.tpl.agg.groupSum" to "Count & Sum by Status (\$match → \$group → \$sort)",
        "shell.tpl.agg.unwind" to "Unwind Array Field (\$unwind)",
        "shell.tpl.agg.lookup" to "Join Another Collection (\$lookup)",
        "shell.tpl.write.updateOne" to "Update Single Doc (updateOne + \$set)",
        "shell.tpl.write.updateMany" to "Bulk Update (updateMany)",
        "shell.tpl.write.deleteOne" to "Delete Single Doc (deleteOne)",
        "shell.cheat.1.title" to "1. Chaining Structure",
        "shell.cheat.1.desc" to "Chain filter, projection, sort, skip, and limit for flexible queries.",
        "shell.cheat.2.title" to "2. Comparison Operators (\$eq, \$ne, \$gt, \$gte, \$lt, \$lte, \$in, \$nin)",
        "shell.cheat.2.desc" to "Compare numbers, dates, strings, and match against lists.",
        "shell.cheat.3.title" to "3. Logical / Element / String (\$and, \$or, \$exists, \$type, \$regex, \$elemMatch)",
        "shell.cheat.3.desc" to "Combine conditions, check field presence/type, regex search, and array element matching.",
        "shell.cheat.4.title" to "4. BSON Type Helpers (ObjectId, ISODate, NumberLong, NumberDecimal)",
        "shell.cheat.4.desc" to "Write standard mongosh helpers directly; they are automatically normalized to BSON.",
        "shell.cheat.5.title" to "5. Global & Dynamic Variables ({{var}}, {{\$now}}, {{\$today}}, {{\$objectId}})",
        "shell.cheat.5.desc" to "Inside quotes (\"{{var}}\") substitutes raw string; outside quotes ({{var}}) auto-detects ObjectId/Number/Date."
    )
}

typealias I18nBundle = I18n
