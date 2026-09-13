package com.tymewear.karoo

import android.graphics.Color
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Single source of truth for constants used across multiple files.
 */
object Constants {

    // -------------------------------------------------------------------------
    // Zone colors
    // -------------------------------------------------------------------------

    /** Solid zone colors for backgrounds and bar charts. */
    val ZONE_COLORS_SOLID = intArrayOf(
        Color.parseColor("#4DB6AC"),   // Z1 Teal (Endurance)
        Color.parseColor("#0277BD"),   // Z2 Blue (VT1)
        Color.parseColor("#F57F17"),   // Z3 Amber (VT2)
        Color.parseColor("#EF6C00"),   // Z4 Orange (Top Z4)
        Color.parseColor("#C62828"),   // Z5 Red (VO2Max)
    )

    /** Semi-transparent zone colors for graph background bands. */
    val ZONE_COLORS_ALPHA = intArrayOf(
        Color.argb(120, 77, 182, 172),   // Z1 Teal (Endurance)
        Color.argb(120, 2, 119, 189),    // Z2 Blue (VT1)
        Color.argb(120, 245, 127, 23),   // Z3 Amber (VT2)
        Color.argb(120, 239, 108, 0),    // Z4 Orange (Top Z4)
        Color.argb(120, 198, 40, 40),    // Z5 Red (VO2Max)
    )

    /** Background color when no data / no zone. */
    val NO_DATA_COLOR = Color.parseColor("#424242")

    /**
     * Get zone style (label + solid color) for a ventilation zone.
     * Zone 0 or invalid returns NO_DATA_COLOR.
     */
    fun zoneStyle(zone: Int): Pair<String, Int> = when (zone) {
        in 1..5 -> "Z$zone" to ZONE_COLORS_SOLID[zone - 1]
        else -> "" to NO_DATA_COLOR
    }

    // -------------------------------------------------------------------------
    // Default thresholds
    // -------------------------------------------------------------------------

    // Threshold-test results are personal and sport-specific. Zero disables
    // zone colors until the rider enters all five values from Tymewear.
    const val DEFAULT_ENDURANCE = 0f
    const val DEFAULT_VT1 = 0f
    const val DEFAULT_VT2 = 0f
    const val DEFAULT_TOP_Z4 = 0f
    const val DEFAULT_VO2MAX = 0f

    // -------------------------------------------------------------------------
    // Default MI parameters
    // -------------------------------------------------------------------------

    // Mobilization Index is personal and experimental. Zero keeps it disabled
    // until the rider explicitly enters all four measured values.
    const val DEFAULT_RESTING_BR = 0f
    const val DEFAULT_MAX_BR = 0f
    const val DEFAULT_MAX_HR = 0f
    const val DEFAULT_RESTING_HR = 0f

    // -------------------------------------------------------------------------
    // Bitmap sizes
    // -------------------------------------------------------------------------

    const val VE_GRAPH_WIDTH = 400
    const val VE_GRAPH_HEIGHT = 200
    const val MI_BATTERY_WIDTH = 300
    const val MI_BATTERY_HEIGHT = 150
    const val ZONES_BITMAP_WIDTH = 400
    const val ZONES_BITMAP_HEIGHT = 200

    // -------------------------------------------------------------------------
    // BLE reconnect parameters — two-phase strategy
    // Phase 1 (rapid): short fixed delays, direct connect for fast recovery
    // Phase 2 (slow): autoConnect=true, lets Android handle scanning efficiently
    // -------------------------------------------------------------------------

    /** Phase 1: rapid reconnect attempts (2s apart, up to 10 tries = ~20s) */
    const val BLE_RAPID_PHASE_ATTEMPTS = 10
    const val BLE_RAPID_PHASE_DELAY_MS = 2000L

    /** Phase 2: use autoConnect=true (Android-native power-efficient scanning).
     *  Manual retry interval as fallback if autoConnect fails. */
    const val BLE_SLOW_PHASE_DELAY_MS = 60000L

    /** Force recovery if no valid breath summary arrives for this long. */
    const val BLE_DATA_WATCHDOG_TIMEOUT_MS = 20000L

    /** How often the watchdog checks for data freshness */
    const val BLE_DATA_WATCHDOG_INTERVAL_MS = 1000L

    /** Initial connect: time to wait for device to appear in a targeted scan
     *  before falling back to autoConnect=true. */
    const val BLE_INITIAL_SCAN_TIMEOUT_MS = 8000L

    /** Keep a physical connection alive while Karoo replaces a device subscriber. */
    const val BLE_SUBSCRIBER_GRACE_MS = 5000L

    /** Maximum time for a direct GATT attempt to reach primary subscription readiness. */
    const val BLE_SETUP_TIMEOUT_MS = 20000L

    /** autoConnect may wait for a new advertisement, so its setup window is longer. */
    const val BLE_AUTOCONNECT_SETUP_TIMEOUT_MS = 75000L

    /** Primary notification subscription succeeded but no valid breath arrived. */
    const val BLE_FIRST_PACKET_TIMEOUT_MS = 35000L

    // -------------------------------------------------------------------------
    // Data bounds (for protocol validation)
    // -------------------------------------------------------------------------

    const val MAX_BREATHING_RATE = 120.0
    const val MAX_TIDAL_VOLUME_L = 5.0
    const val MAX_MINUTE_VENTILATION = 250.0

    // -------------------------------------------------------------------------
    // Coroutine error handler
    // -------------------------------------------------------------------------

    val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "Uncaught coroutine exception")
    }
}
