package com.tymewear.karoo.screens

import com.tymewear.karoo.VentilationThresholds

data class SettingsFormInput(
    val sensorId: String,
    val endurance: String,
    val vt1: String,
    val vt2: String,
    val topZ4: String,
    val vo2max: String,
    val restingBr: String,
    val maxBr: String,
    val maxHr: String,
    val restingHr: String,
)

data class ValidatedSettings(
    val sensorId: String,
    val thresholds: VentilationThresholds,
    val restingBr: Float,
    val maxBr: Float,
    val maxHr: Float,
    val restingHr: Float,
)

sealed interface SettingsValidationResult {
    data class Valid(val settings: ValidatedSettings) : SettingsValidationResult
    data class Invalid(val message: String) : SettingsValidationResult
}

private val SENSOR_ID_PATTERN = Regex("[0-9A-F]{4}")

/** Uppercase the editable ID while leaving invalid ASCII letters visible for validation. */
fun normalizeSensorIdInput(value: String): String = value
    .uppercase()
    .filter { it in '0'..'9' || it in 'A'..'Z' }
    .take(4)

/** Pure settings validation so it can be exercised without Android or Compose. */
fun validateSettings(input: SettingsFormInput): SettingsValidationResult {
    if (input.sensorId.isNotEmpty() && !SENSOR_ID_PATTERN.matches(input.sensorId)) {
        return SettingsValidationResult.Invalid(
            "Sensor ID must be blank or exactly 4 uppercase hex characters (0-9, A-F).",
        )
    }

    val thresholdText = listOf(
        input.endurance,
        input.vt1,
        input.vt2,
        input.topZ4,
        input.vo2max,
    )
    val thresholdValues = thresholdText.map { text ->
        if (text.isBlank()) 0f else text.toFloatOrNull()
    }
    if (thresholdValues.any { it == null || !it.isFinite() }) {
        return SettingsValidationResult.Invalid(
            "Thresholds must be finite numbers, or all left blank to disable zones.",
        )
    }

    val numericThresholds = thresholdValues.map { requireNotNull(it) }
    val thresholds = VentilationThresholds(
        endurance = numericThresholds[0].toDouble(),
        vt1 = numericThresholds[1].toDouble(),
        vt2 = numericThresholds[2].toDouble(),
        topZ4 = numericThresholds[3].toDouble(),
        vo2max = numericThresholds[4].toDouble(),
    )
    if (!thresholds.isDisabled && numericThresholds.any { it <= 0f }) {
        return SettingsValidationResult.Invalid(
            "Enter all five positive thresholds, or leave all five blank/zero to disable zones.",
        )
    }
    if (!thresholds.isDisabled && !thresholds.isConfigured) {
        return SettingsValidationResult.Invalid(
            "Thresholds must be in order: Endurance < VT1 < VT2 < Top Z4 < VO2max.",
        )
    }

    val miText = listOf(input.restingBr, input.maxBr, input.maxHr, input.restingHr)
    val miValues = miText.map { text ->
        if (text.isBlank()) 0f else text.toFloatOrNull()
    }
    if (miValues.any { it == null || !it.isFinite() }) {
        return SettingsValidationResult.Invalid(
            "MI values must be finite numbers, or all left blank to disable MI.",
        )
    }

    val restingBr = requireNotNull(miValues[0])
    val maxBr = requireNotNull(miValues[1])
    val maxHr = requireNotNull(miValues[2])
    val restingHr = requireNotNull(miValues[3])
    val numericMiValues = listOf(restingBr, maxBr, maxHr, restingHr)
    val miDisabled = numericMiValues.all { it == 0f }
    if (!miDisabled && numericMiValues.any { it <= 0f }) {
        return SettingsValidationResult.Invalid(
            "Enter all four positive MI values, or leave all four blank/zero to disable MI.",
        )
    }
    if (!miDisabled && restingBr >= maxBr) {
        return SettingsValidationResult.Invalid("Resting BR must be less than Max BR.")
    }
    if (!miDisabled && restingHr >= maxHr) {
        return SettingsValidationResult.Invalid("Resting HR must be less than Max HR.")
    }

    return SettingsValidationResult.Valid(
        ValidatedSettings(
            sensorId = input.sensorId,
            thresholds = thresholds,
            restingBr = restingBr,
            maxBr = maxBr,
            maxHr = maxHr,
            restingHr = restingHr,
        ),
    )
}
