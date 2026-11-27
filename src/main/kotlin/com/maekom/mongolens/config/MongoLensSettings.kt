package com.maekom.mongolens.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for Maekom Mongo Lens.
 * Persists `queryTimeoutSeconds` (default 40s, clamped to 5..600s) to `maekom-mongo-lens.xml`.
 */
@Service(Service.Level.APP)
@State(
    name = "MaekomMongoLensSettings",
    storages = [Storage("maekom-mongo-lens.xml")]
)
class MongoLensSettings : PersistentStateComponent<MongoLensSettings.State> {

    data class State(
        var queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS
    )

    private var myState = State()

    var queryTimeoutSeconds: Int
        get() = clampTimeoutSeconds(myState.queryTimeoutSeconds)
        set(value) {
            myState.queryTimeoutSeconds = clampTimeoutSeconds(value)
        }

    fun queryTimeoutMillis(): Long = queryTimeoutSeconds * 1000L

    override fun getState(): State = State(queryTimeoutSeconds = clampTimeoutSeconds(myState.queryTimeoutSeconds))

    override fun loadState(state: State) {
        myState = State(queryTimeoutSeconds = clampTimeoutSeconds(state.queryTimeoutSeconds))
    }

    companion object {
        const val DEFAULT_QUERY_TIMEOUT_SECONDS: Int = 40
        const val MIN_QUERY_TIMEOUT_SECONDS: Int = 5
        const val MAX_QUERY_TIMEOUT_SECONDS: Int = 600

        private val headlessFallback = MongoLensSettings()

        fun clampTimeoutSeconds(raw: Int): Int =
            raw.coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS)

        fun toTimeoutMillis(seconds: Int): Long =
            clampTimeoutSeconds(seconds) * 1000L

        /**
         * Returns true ONLY when a query actually timed out and was NOT manually stopped by the user.
         */
        fun shouldShowTimeoutGuide(isTimedOut: Boolean, isStopRequested: Boolean): Boolean =
            isTimedOut && !isStopRequested

        fun getInstance(): MongoLensSettings {
            val app = runCatching { ApplicationManager.getApplication() }.getOrNull()
            return if (app != null && !app.isDisposed) {
                runCatching { app.getService(MongoLensSettings::class.java) }.getOrNull() ?: headlessFallback
            } else {
                headlessFallback
            }
        }
    }
}
