package com.maekom.mongolens.mongo

import com.maekom.mongolens.config.MongoLensSettings
import com.mongodb.client.MongoCursor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Callback bridge passed into `MongoConnectionManager` query methods so per-tab execution controls
 * can register/close the active client-side `MongoCursor` safely (NEVER using admin `killOp`).
 */
data class QueryExecutionControl(
    val runId: Long,
    val isCancelled: () -> Boolean,
    val onCursorOpened: (MongoCursor<*>?) -> Unit
)

/**
 * Pure Kotlin (ZERO IntelliJ UI dependencies) per-tab query lifecycle & race-condition guard.
 *
 * Uses a monotonically increasing `currentRunId` token so that:
 * - Double-clicks during an active run are ignored safely.
 * - Clicking Stop invalidates the running token immediately and sets `isStopRequested = true`.
 * - A late `finally` block from an invalidated run (`runId = 1`) NEVER overwrites the running state
 *   of a newly started run (`runId = 2`).
 * - Timeout guide popups are displayed at most once per run and ONLY when `isTimedOut && !isStopRequested`.
 */
class QueryRunTracker {
    val currentRunId = AtomicLong(0L)

    @Volatile
    var isQueryRunning: Boolean = false
        private set

    @Volatile
    var isStopRequested: Boolean = false
        private set

    @Volatile
    var isTimedOut: Boolean = false
        private set

    private val timeoutAlertShown = AtomicBoolean(false)

    /**
     * Attempts to start a new run when idle. Returns the new `runId`, or `null` if already running.
     */
    @Synchronized
    fun tryBeginRun(): Long? {
        if (isQueryRunning) return null
        return beginNewRunUnchecked()
    }

    /**
     * Starts a new run unconditionally (used when programmatic pagination/refresh supersedes an earlier run).
     */
    @Synchronized
    fun beginNewRun(): Long = beginNewRunUnchecked()

    private fun beginNewRunUnchecked(): Long {
        val nextId = currentRunId.incrementAndGet()
        isQueryRunning = true
        isStopRequested = false
        isTimedOut = false
        timeoutAlertShown.set(false)
        return nextId
    }

    /**
     * Triggered when the user manually clicks the Stop button (or closes the tab):
     * marks manual stop, invalidates the current runId token, and resets `isQueryRunning = false` immediately.
     */
    @Synchronized
    fun requestStop(): Long {
        isStopRequested = true
        isTimedOut = false
        isQueryRunning = false
        return currentRunId.incrementAndGet()
    }

    /**
     * Triggered by the client watchdog timer when elapsed time reaches `queryTimeoutSeconds`.
     * Sets `isTimedOut = true` (distinct from manual stop) ONLY if `myRunId` is still the active run.
     */
    @Synchronized
    fun markTimedOutIfActive(myRunId: Long): Boolean {
        if (currentRunId.get() != myRunId || isStopRequested) return false
        isTimedOut = true
        return true
    }

    /**
     * Checks whether a background worker belonging to `myRunId` should abort early.
     */
    fun isCancelledFor(myRunId: Long): Boolean {
        return isStopRequested || isTimedOut || currentRunId.get() != myRunId
    }

    /**
     * Returns `true` ONLY if `myRunId` is still the active run, meaning its `finally` block
     * is allowed to clear `isQueryRunning` and restore the `▶ Find` button without clobbering a newer run.
     */
    @Synchronized
    fun finishRunIfCurrent(myRunId: Long): Boolean {
        if (currentRunId.get() != myRunId) return false
        isQueryRunning = false
        return true
    }

    /**
     * Returns `true` at most once per timed-out run (and NEVER when manually stopped).
     */
    fun tryAcquireTimeoutAlertSlot(myRunId: Long, serverTimeoutException: Boolean = false): Boolean {
        if (currentRunId.get() != myRunId) return false
        if (serverTimeoutException && !isStopRequested) {
            isTimedOut = true
        }
        if (!MongoLensSettings.shouldShowTimeoutGuide(isTimedOut = isTimedOut, isStopRequested = isStopRequested)) {
            return false
        }
        return timeoutAlertShown.compareAndSet(false, true)
    }
}

/**
 * Pure Kotlin policy determining which toolbar controls are enabled/disabled when Join View is active.
 */
object JoinToolbarStatePolicy {

    data class ToolbarControlStates(
        val filterInputEnabled: Boolean,
        val projectInputEnabled: Boolean,
        val sortInputEnabled: Boolean,
        val resetEnabled: Boolean,
        val smartInEnabled: Boolean,
        val toShellEnabled: Boolean,
        val savedQueriesEnabled: Boolean,
        val importJsonEnabled: Boolean,
        val indexInspectorEnabled: Boolean,
        val runOrStopEnabled: Boolean,
        val joinConfigEnabled: Boolean,
        val copyPipelineEnabled: Boolean,
        val exportEnabled: Boolean,
        val pageSizeEnabled: Boolean,
        val paginationEnabled: Boolean
    )

    fun compute(isJoinActive: Boolean): ToolbarControlStates {
        val singleCollectionEnabled = !isJoinActive
        return ToolbarControlStates(
            filterInputEnabled = singleCollectionEnabled,
            projectInputEnabled = singleCollectionEnabled,
            sortInputEnabled = singleCollectionEnabled,
            resetEnabled = singleCollectionEnabled,
            smartInEnabled = singleCollectionEnabled,
            toShellEnabled = singleCollectionEnabled,
            savedQueriesEnabled = singleCollectionEnabled,
            importJsonEnabled = singleCollectionEnabled,
            indexInspectorEnabled = singleCollectionEnabled,
            // Always active in both standard Find and Join views
            runOrStopEnabled = true,
            joinConfigEnabled = true,
            copyPipelineEnabled = true,
            exportEnabled = true,
            pageSizeEnabled = true,
            paginationEnabled = true
        )
    }
}
