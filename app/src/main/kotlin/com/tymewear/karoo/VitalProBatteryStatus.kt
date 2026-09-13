package com.tymewear.karoo

import io.hammerhead.karooext.models.BatteryStatus

/** Keep VitalPro battery labels aligned with Hammerhead's official boundaries. */
fun vitalProBatteryStatus(percent: Int): BatteryStatus =
    BatteryStatus.fromPercentage(percent)
