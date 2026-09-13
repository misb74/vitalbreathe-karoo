package com.tymewear.karoo.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {

    @Test
    fun `blank sensor and thresholds can be saved with valid MI values`() {
        val result = validateSettings(validInput())

        assertTrue(result is SettingsValidationResult.Valid)
        val settings = (result as SettingsValidationResult.Valid).settings
        assertEquals("", settings.sensorId)
        assertTrue(settings.thresholds.isDisabled)
    }

    @Test
    fun `all blank or zero MI values keep the experimental metric disabled`() {
        val blank = validInput().copy(
            sensorId = "BEEF",
            restingBr = "",
            maxBr = "",
            maxHr = "",
            restingHr = "",
        )
        val zero = blank.copy(
            restingBr = "0",
            maxBr = "0.0",
            maxHr = "0",
            restingHr = "0.0",
        )

        listOf(blank, zero).forEach { input ->
            val result = validateSettings(input)
            assertTrue(result is SettingsValidationResult.Valid)
            val settings = (result as SettingsValidationResult.Valid).settings
            assertEquals(0f, settings.restingBr)
            assertEquals(0f, settings.maxBr)
            assertEquals(0f, settings.maxHr)
            assertEquals(0f, settings.restingHr)
        }
    }

    @Test
    fun `zero thresholds are the same disabled state as blanks`() {
        val result = validateSettings(
            validInput().copy(
                sensorId = "BEEF",
                endurance = "0",
                vt1 = "0.0",
                vt2 = "",
                topZ4 = "0",
                vo2max = "0",
            ),
        )

        assertTrue(result is SettingsValidationResult.Valid)
        val settings = (result as SettingsValidationResult.Valid).settings
        assertEquals("BEEF", settings.sensorId)
        assertTrue(settings.thresholds.isDisabled)
    }

    @Test
    fun `sensor ID must be blank or four uppercase hex characters`() {
        listOf("BEE", "BEEFF", "BGEF", "beef").forEach { sensorId ->
            assertInvalid(validInput().copy(sensorId = sensorId))
        }
        assertValid(validInput().copy(sensorId = "CAFE"))
        assertEquals("BEEF", normalizeSensorIdInput("beef"))
    }

    @Test
    fun `all five finite positive ordered thresholds can be saved`() {
        assertValid(configuredInput())
    }

    @Test
    fun `partial nonpositive or unordered thresholds are rejected`() {
        assertInvalid(configuredInput().copy(vt2 = ""))
        assertInvalid(configuredInput().copy(vt2 = "0"))
        assertInvalid(configuredInput().copy(vt2 = "-90"))
        assertInvalid(configuredInput().copy(vt2 = "70"))
    }

    @Test
    fun `nonfinite thresholds are rejected`() {
        assertInvalid(configuredInput().copy(vt2 = "NaN"))
        assertInvalid(configuredInput().copy(vo2max = "Infinity"))
    }

    @Test
    fun `MI values must be finite positive and ordered`() {
        assertInvalid(validInput().copy(restingBr = ""))
        assertInvalid(validInput().copy(restingBr = "NaN"))
        assertInvalid(validInput().copy(maxHr = "Infinity"))
        assertInvalid(validInput().copy(restingBr = "0"))
        assertInvalid(validInput().copy(restingBr = "55"))
        assertInvalid(validInput().copy(restingHr = "190"))
    }

    private fun configuredInput() = validInput().copy(
        endurance = "50",
        vt1 = "70",
        vt2 = "90",
        topZ4 = "110",
        vo2max = "130",
    )

    private fun validInput() = SettingsFormInput(
        sensorId = "",
        endurance = "",
        vt1 = "",
        vt2 = "",
        topZ4 = "",
        vo2max = "",
        restingBr = "12",
        maxBr = "55",
        maxHr = "190",
        restingHr = "60",
    )

    private fun assertValid(input: SettingsFormInput) {
        assertTrue(validateSettings(input) is SettingsValidationResult.Valid)
    }

    private fun assertInvalid(input: SettingsFormInput) {
        assertTrue(validateSettings(input) is SettingsValidationResult.Invalid)
    }
}
