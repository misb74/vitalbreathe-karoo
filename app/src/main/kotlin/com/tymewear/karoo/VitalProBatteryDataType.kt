package com.tymewear.karoo

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Exact battery percentage read from the VitalPro's standard battery service. */
@OptIn(FlowPreview::class)
class VitalProBatteryDataType(extension: String) : DataTypeImpl(extension, "vp_battery") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        scope.launch {
            // Coalesce the initial unavailable marker and a fast GATT read so Karoo's view
            // rate limit cannot discard the only real battery value.
            TymewearData.batteryPercent.debounce(1_000L).collect { percent ->
                val view = RemoteViews(context.packageName, R.layout.view_vitalpro_battery)
                view.setTextViewText(
                    R.id.text_battery_value,
                    if (percent >= 0) percent.toString() else "—",
                )
                view.setFloat(R.id.text_battery_value, "setTextSize", valueSize)
                view.setFloat(R.id.text_battery_unit, "setTextSize", unitSize)
                val color = when {
                    percent < 0 -> Constants.NO_DATA_COLOR
                    percent <= 10 -> Color.parseColor("#C62828")
                    percent <= 25 -> Color.parseColor("#EF6C00")
                    else -> Color.parseColor("#2E7D32")
                }
                view.setInt(R.id.battery_container, "setBackgroundColor", color)
                emitter.updateView(view)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
