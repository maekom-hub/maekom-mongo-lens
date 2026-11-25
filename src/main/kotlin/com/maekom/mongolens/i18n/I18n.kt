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

        // Connections Tree
        "tree.header" to "Connections",
        "tree.empty" to "탐색된 MongoDB 설정이 없습니다.\nYAML 파일을 추가하거나 '+ Custom URI'를 클릭하세요.",
        "tree.loading" to "불러오는 중...",
        "tree.errorNode" to "연결 실패: {0}",
        "tree.emptyCollections" to "(컬렉션 없음)",
        "tree.emptyDatabases" to "(데이터베이스 없음)",
        "tree.badge.readOnly" to "READ-ONLY 🔒",
        "tree.badge.unlocked" to "세션 잠금해제 🔓",
        "tree.ctx.openFind" to "새 Find 탭 열기",
        "tree.ctx.openAgg" to "새 Aggregation 탭 열기",
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
        "workspace.welcome.desc" to "좌측 Connections 트리에서 프로필을 확장하고 컬렉션을 클릭하여 Find 또는 Aggregation 탭을 여세요.\nSpring Boot의 application*.yml 설정은 자동으로 인식됩니다.",
        "workspace.mode.find" to "Documents (Find)",
        "workspace.mode.agg" to "Aggregations",

        // Find Query Bar
        "find.filter" to "Filter",
        "find.project" to "Project",
        "find.sort" to "Sort",
        "find.limit" to "Limit",
        "find.skip" to "Skip",
        "find.run" to "▶ 조회 (Find)",
        "find.runTooltip" to "쿼리를 실행합니다 (Ctrl+Enter / Cmd+Enter)",
        "find.reset" to "초기화",
        "find.smartIn" to "📋 Smart \$in",
        "find.smartInTooltip" to "줄바꿈/쉼표/공백으로 구분된 ID 목록을 \$in 쿼리로 자동 변환합니다.",
        "find.copyShell" to "Shell 복사",
        "find.copyShellTooltip" to "mongosh find 명령어를 클립보드에 복사합니다.",
        "find.savedQueries" to "⭐ 쿼리 저장/기록",
        "find.savedQueries.save" to "현재 쿼리 저장...",
        "find.savedQueries.savedHeader" to "저장된 쿼리",
        "find.savedQueries.historyHeader" to "최근 실행 기록",
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
        "result.doc.clone" to "문서 복제",
        "result.doc.delete" to "문서 삭제",
        "result.doc.noSelection" to "먼저 문서를 선택하세요.",
        "result.doc.editTitle" to "문서 수정 (replaceOne)",
        "result.doc.editSuccess" to "문서가 수정되었습니다.",
        "result.doc.cloneSuccess" to "새 ObjectId로 문서가 복제되었습니다.",
        "result.doc.deleteConfirm" to "선택한 문서(_id: {0})를 정말로 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.",
        "result.doc.deleteTitle" to "문서 삭제 확인",
        "result.doc.deleteSuccess" to "문서가 삭제되었습니다.",
        "result.date.kstTooltip" to "KST (UTC+9): {0}",
        "result.copied" to "클립보드에 복사되었습니다.",

        // Aggregation Builder
        "agg.mode.ui" to "🧩 스테이지 빌더 (UI)",
        "agg.mode.raw" to "{ } Raw 스크립트",
        "agg.addStage" to "+ 스테이지 추가",
        "agg.run" to "▶ 파이프라인 실행",
        "agg.copy" to "파이프라인 복사",
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
        "agg.previewBanner" to "스테이지 #{0} ({1})까지 미리보기 결과 (최대 5건)"
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

        // Connections Tree
        "tree.header" to "Connections",
        "tree.empty" to "No MongoDB configurations found.\nAdd Spring YAML config or click '+ Custom URI'.",
        "tree.loading" to "Loading...",
        "tree.errorNode" to "Connection failed: {0}",
        "tree.emptyCollections" to "(No collections)",
        "tree.emptyDatabases" to "(No databases)",
        "tree.badge.readOnly" to "READ-ONLY 🔒",
        "tree.badge.unlocked" to "Session Unlocked 🔓",
        "tree.ctx.openFind" to "Open New Find Tab",
        "tree.ctx.openAgg" to "Open New Aggregation Tab",
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
        "workspace.welcome.desc" to "Expand a profile in the Connections tree and click a collection to open a Find or Aggregation workspace.\nSpring Boot application*.yml files are discovered automatically.",
        "workspace.mode.find" to "Documents (Find)",
        "workspace.mode.agg" to "Aggregations",

        // Find Query Bar
        "find.filter" to "Filter",
        "find.project" to "Project",
        "find.sort" to "Sort",
        "find.limit" to "Limit",
        "find.skip" to "Skip",
        "find.run" to "▶ Find",
        "find.runTooltip" to "Execute query (Ctrl+Enter / Cmd+Enter)",
        "find.reset" to "Reset",
        "find.smartIn" to "📋 Smart \$in",
        "find.smartInTooltip" to "Convert newline/comma/space-separated IDs into a \$in query.",
        "find.copyShell" to "Copy Shell",
        "find.copyShellTooltip" to "Copy mongosh find command to clipboard.",
        "find.savedQueries" to "⭐ Queries / History",
        "find.savedQueries.save" to "Save Current Query...",
        "find.savedQueries.savedHeader" to "Saved Queries",
        "find.savedQueries.historyHeader" to "Recent History",
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
        "result.doc.clone" to "Clone Document",
        "result.doc.delete" to "Delete Document",
        "result.doc.noSelection" to "Please select a document first.",
        "result.doc.editTitle" to "Edit Document (replaceOne)",
        "result.doc.editSuccess" to "Document updated successfully.",
        "result.doc.cloneSuccess" to "Document cloned with a new ObjectId.",
        "result.doc.deleteConfirm" to "Permanently delete document (_id: {0})?\nThis action cannot be undone.",
        "result.doc.deleteTitle" to "Confirm Delete Document",
        "result.doc.deleteSuccess" to "Document deleted.",
        "result.date.kstTooltip" to "KST (UTC+9): {0}",
        "result.copied" to "Copied to clipboard.",

        // Aggregation Builder
        "agg.mode.ui" to "🧩 Stage Builder (UI)",
        "agg.mode.raw" to "{ } Raw Script",
        "agg.addStage" to "+ Add Stage",
        "agg.run" to "▶ Run Pipeline",
        "agg.copy" to "Copy Pipeline",
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
        "agg.previewBanner" to "Preview up to Stage #{0} ({1}) — max 5 docs"
    )
}
