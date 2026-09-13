package com.tymewear.karoo

import android.util.Log
import timber.log.Timber

internal const val VITAL_BREATHE_LOG_TAG = "VitalBreathe"

private val BLUETOOTH_ADDRESS = Regex(
    "(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])",
)
private val ADVERTISED_SENSOR_ID = Regex("(?i)\\bTYME-[0-9a-f]{4}\\b")
private val SENSOR_ID_VALUE = Regex("(?i)\\b(sensor\\s*id\\s*[:=]\\s*)([0-9a-f]{4})\\b")

/** Removes stable strap identifiers before operational logs leave a release build. */
internal fun redactOperationalLog(message: String): String = message
    .replace(BLUETOOTH_ADDRESS, "redacted-device")
    .replace(ADVERTISED_SENSOR_ID, "TYME-[redacted]")
    .replace(SENSOR_ID_VALUE) { match -> "${match.groupValues[1]}[redacted]" }

/** Gives every in-process operational log one stable, app-specific tag. */
class VitalBreatheLogTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String = VITAL_BREATHE_LOG_TAG

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (BuildConfig.DEBUG) {
            super.log(priority, tag, message, t)
            return
        }

        val completeMessage = if (t == null) {
            message
        } else {
            "$message\n${Log.getStackTraceString(t)}"
        }
        super.log(priority, tag, redactOperationalLog(completeMessage), null)
    }
}
