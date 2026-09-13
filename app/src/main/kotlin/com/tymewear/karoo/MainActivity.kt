package com.tymewear.karoo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.tymewear.karoo.screens.MainScreen
import com.tymewear.karoo.screens.PrefsData
import com.tymewear.karoo.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val blePermissionsGranted = mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        blePermissionsGranted.value = hasBlePermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blePermissionsGranted.value = hasBlePermissions()
        requestBlePermissions()
        setContent {
            AppTheme {
                MainScreen(
                    blePermissionsGranted = blePermissionsGranted.value,
                    onRequestBlePermissions = ::requestBlePermissions,
                    onSave = { prefs ->
                        getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                            .edit()
                            .putString("sensor_id", prefs.sensorId)
                            .putFloat("endurance_threshold", prefs.endurance)
                            .putFloat("vt1_threshold", prefs.vt1)
                            .putFloat("vt2_threshold", prefs.vt2)
                            .putFloat("topz4_threshold", prefs.topZ4)
                            .putFloat("vo2max_threshold", prefs.vo2max)
                            .putFloat("resting_br", prefs.restingBr)
                            .putFloat("max_br", prefs.maxBr)
                            .putFloat("max_hr", prefs.maxHr)
                            .putFloat("resting_hr", prefs.restingHr)
                            .putBoolean(
                                "mi_configured",
                                prefs.restingBr > 0f && prefs.maxBr > prefs.restingBr &&
                                    prefs.restingHr > 0f && prefs.maxHr > prefs.restingHr,
                            )
                            .apply()
                        // Apply now when idle, or queue one consistent definition
                        // until the active FIT recording ends.
                        TymewearData.loadThresholds(applicationContext)
                    },
                    loadPrefs = {
                        val p = getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
                        val miConfigured = p.getBoolean("mi_configured", false)
                        PrefsData(
                            sensorId = p.getString("sensor_id", "") ?: "",
                            endurance = p.getFloat("endurance_threshold", Constants.DEFAULT_ENDURANCE),
                            vt1 = p.getFloat("vt1_threshold", Constants.DEFAULT_VT1),
                            vt2 = p.getFloat("vt2_threshold", Constants.DEFAULT_VT2),
                            topZ4 = p.getFloat("topz4_threshold", Constants.DEFAULT_TOP_Z4),
                            vo2max = p.getFloat("vo2max_threshold", Constants.DEFAULT_VO2MAX),
                            restingBr = if (miConfigured) {
                                p.getFloat("resting_br", Constants.DEFAULT_RESTING_BR)
                            } else {
                                Constants.DEFAULT_RESTING_BR
                            },
                            maxBr = if (miConfigured) {
                                p.getFloat("max_br", Constants.DEFAULT_MAX_BR)
                            } else {
                                Constants.DEFAULT_MAX_BR
                            },
                            maxHr = if (miConfigured) {
                                p.getFloat("max_hr", Constants.DEFAULT_MAX_HR)
                            } else {
                                Constants.DEFAULT_MAX_HR
                            },
                            restingHr = if (miConfigured) {
                                p.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR)
                            } else {
                                Constants.DEFAULT_RESTING_HR
                            },
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        blePermissionsGranted.value = hasBlePermissions()
    }

    private fun requestBlePermissions() {
        val needed = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            blePermissionsGranted.value = true
        }
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredBlePermissions(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
        return listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
