package com.tymewear.karoo.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tymewear.karoo.Constants
import com.tymewear.karoo.BleDiagnostics
import com.tymewear.karoo.TymewearData
import com.tymewear.karoo.currentBuildIdentity
import kotlinx.coroutines.delay

data class PrefsData(
    val sensorId: String,
    val endurance: Float,
    val vt1: Float,
    val vt2: Float,
    val topZ4: Float,
    val vo2max: Float,
    val restingBr: Float,
    val maxBr: Float,
    val maxHr: Float,
    val restingHr: Float,
)

@Composable
fun MainScreen(
    blePermissionsGranted: Boolean,
    onRequestBlePermissions: () -> Unit,
    onSave: (PrefsData) -> Unit,
    loadPrefs: () -> PrefsData,
) {
    var sensorId by remember { mutableStateOf("") }
    var endurance by remember { mutableStateOf("") }
    var vt1 by remember { mutableStateOf("") }
    var vt2 by remember { mutableStateOf("") }
    var topZ4 by remember { mutableStateOf("") }
    var vo2max by remember { mutableStateOf("") }
    var restingBr by remember { mutableStateOf("") }
    var maxBr by remember { mutableStateOf("") }
    var maxHr by remember { mutableStateOf("") }
    var restingHr by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val isConnected by TymewearData.isConnected.collectAsState()
    val batteryPercent by TymewearData.batteryPercent.collectAsState()
    val zoneRecordingState by TymewearData.zoneRecordingState.collectAsState()
    val diagnostics by BleDiagnostics.state.collectAsState()
    val buildLabel = remember { currentBuildIdentity().displayLabel() }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        val prefs = loadPrefs()
        sensorId = normalizeSensorIdInput(prefs.sensorId)
        endurance = prefs.endurance.asEditablePositiveValue()
        vt1 = prefs.vt1.asEditablePositiveValue()
        vt2 = prefs.vt2.asEditablePositiveValue()
        topZ4 = prefs.topZ4.asEditablePositiveValue()
        vo2max = prefs.vo2max.asEditablePositiveValue()
        restingBr = prefs.restingBr.asEditablePositiveValue()
        maxBr = prefs.maxBr.asEditablePositiveValue()
        maxHr = prefs.maxHr.asEditablePositiveValue()
        restingHr = prefs.restingHr.asEditablePositiveValue()
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowElapsedMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "VitalBreathe",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "For Tymewear VitalPro · unofficial community beta",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = buildLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "No account, analytics, or Internet permission. Breathing data may be " +
                "included in Karoo ride FIT files. Training information only; not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (!blePermissionsGranted) {
            Text(
                text = "Sensor access is required to find and connect to the VitalPro. " +
                    "If no prompt opens, enable the requested app permission in Android settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
            )
            Button(
                onClick = onRequestBlePermissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow sensor access")
            }
        }

        // Connection status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isConnected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                        shape = MaterialTheme.shapes.small,
                    ),
            )
            Text(
                text = when {
                    isConnected && batteryPercent >= 0 -> "Connected · battery $batteryPercent%"
                    isConnected -> "Connected"
                    else -> "Not connected"
                },
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        val lastBreathAge = diagnostics.lastBreathElapsedMs?.let {
            ((nowElapsedMs - it).coerceAtLeast(0L) / 1_000L)
        }
        Text(
            text = buildString {
                append("Bluetooth: ${diagnostics.phase}")
                if (diagnostics.reconnectAttempt > 0) {
                    append(" · retry ${diagnostics.reconnectAttempt}")
                }
                if (lastBreathAge != null) {
                    append(" · last breath ${lastBreathAge}s ago")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        diagnostics.lastError?.takeIf { !isConnected }?.let { error ->
            Text(
                text = "Last connection issue: $error",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFEF9A9A),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Pair the strap in Settings > Sensors > Add Sensor > Extensions. " +
                "Adding a ride field does not pair it automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Sensor ID
        OutlinedTextField(
            value = sensorId,
            onValueChange = {
                sensorId = normalizeSensorIdInput(it)
                saved = false
            },
            label = { Text("Sensor ID (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Leave blank, or enter exactly 4 hex characters: 0-9 and A-F") },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Ventilation Zone Thresholds ---
        Text(
            text = "Ventilation Zone Thresholds",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Text(
            text = "Copy all five VE values from your latest Tymewear threshold test. " +
                "Leave all five blank to keep zones off. There are no safe universal defaults.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = endurance,
            onValueChange = { endurance = it; saved = false },
            label = { Text("Endurance transition (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Boundary from Z1 to Z2") },
        )

        OutlinedTextField(
            value = vt1,
            onValueChange = { vt1 = it; saved = false },
            label = { Text("VT1 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Boundary from Z2 to Z3") },
        )

        OutlinedTextField(
            value = vt2,
            onValueChange = { vt2 = it; saved = false },
            label = { Text("VT2 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Boundary from Z3 to Z4") },
        )

        OutlinedTextField(
            value = topZ4,
            onValueChange = { topZ4 = it; saved = false },
            label = { Text("Top Z4 threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Boundary from Z4 to Z5") },
        )

        OutlinedTextField(
            value = vo2max,
            onValueChange = { vo2max = it; saved = false },
            label = { Text("VO2max threshold (L/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("Ceiling of Z5; values above remain Z5") },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Mobilization Index Parameters ---
        Text(
            text = "Mobilization Index",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Experimental. Leave all four blank to keep MI off, or enter your own " +
                "measured resting and maximum values.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OutlinedTextField(
            value = restingBr,
            onValueChange = { restingBr = it; saved = false },
            label = { Text("Resting BR (breaths/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = maxBr,
            onValueChange = { maxBr = it; saved = false },
            label = { Text("Max BR (breaths/min)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = maxHr,
            onValueChange = { maxHr = it; saved = false },
            label = { Text("Max HR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        OutlinedTextField(
            value = restingHr,
            onValueChange = { restingHr = it; saved = false },
            label = { Text("Resting HR (bpm)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (validationError != null) {
            Text(
                text = validationError!!,
                color = Color(0xFFEF5350),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(
            onClick = {
                val result = validateSettings(
                    SettingsFormInput(
                        sensorId = sensorId,
                        endurance = endurance,
                        vt1 = vt1,
                        vt2 = vt2,
                        topZ4 = topZ4,
                        vo2max = vo2max,
                        restingBr = restingBr,
                        maxBr = maxBr,
                        maxHr = maxHr,
                        restingHr = restingHr,
                    ),
                )

                when (result) {
                    is SettingsValidationResult.Invalid -> {
                        validationError = result.message
                        saved = false
                    }
                    is SettingsValidationResult.Valid -> {
                        val values = result.settings
                        validationError = null
                        onSave(
                            PrefsData(
                                sensorId = values.sensorId,
                                endurance = values.thresholds.endurance.toFloat(),
                                vt1 = values.thresholds.vt1.toFloat(),
                                vt2 = values.thresholds.vt2.toFloat(),
                                topZ4 = values.thresholds.topZ4.toFloat(),
                                vo2max = values.thresholds.vo2max.toFloat(),
                                restingBr = values.restingBr,
                                maxBr = values.maxBr,
                                maxHr = values.maxHr,
                                restingHr = values.restingHr,
                            ),
                        )
                        saved = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save Settings")
        }

        if (saved && zoneRecordingState.isRecording) {
            Text(
                text = "Saved. Zone and MI changes will apply after this recording.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun Float.asEditablePositiveValue(): String = if (this > 0f) toString() else ""
