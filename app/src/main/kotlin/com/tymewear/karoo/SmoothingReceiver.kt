package com.tymewear.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SmoothingMode(val windowMs: Long, val label: String) {
    INSTANT(0L, "VE live"),
    SMOOTH_5(5_000L, "VE 5s"),
    SMOOTH_15(15_000L, "VE 15s"),
    SMOOTH_30(30_000L, "VE 30s"),
    SMOOTH_60(60_000L, "VE 60s"),
}

object SmoothingState {
    private val _mode = MutableStateFlow(SmoothingMode.SMOOTH_30)
    val mode = _mode.asStateFlow()

    fun cycle() {
        _mode.value = when (_mode.value) {
            SmoothingMode.INSTANT -> SmoothingMode.SMOOTH_5
            SmoothingMode.SMOOTH_5 -> SmoothingMode.SMOOTH_15
            SmoothingMode.SMOOTH_15 -> SmoothingMode.SMOOTH_30
            SmoothingMode.SMOOTH_30 -> SmoothingMode.SMOOTH_60
            SmoothingMode.SMOOTH_60 -> SmoothingMode.INSTANT
        }
    }
}

class SmoothingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SmoothingState.cycle()
    }

    companion object {
        const val ACTION = "com.tymewear.karoo.CYCLE_SMOOTHING"
    }
}
