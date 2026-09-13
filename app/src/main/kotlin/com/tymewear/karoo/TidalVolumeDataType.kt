package com.tymewear.karoo

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Graphical data type showing tidal volume (volume per breath) from the VitalPro sensor.
 * The paired device and graphical view both use litres per breath.
 */
@OptIn(FlowPreview::class)
class TidalVolumeDataType(extension: String) : DataTypeImpl(extension, "tv") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        scope.launch {
            TymewearData.smoothTidalVolume.sample(1000L).collect { tv ->
                val remoteViews = RemoteViews(context.packageName, R.layout.view_tidal_volume)

                val displayValue = if (tv > 0) String.format(Locale.US, "%.2f", tv) else "—"
                remoteViews.setTextViewText(R.id.text_tv_value, displayValue)
                remoteViews.setFloat(R.id.text_tv_value, "setTextSize", valueSize)
                remoteViews.setFloat(R.id.text_tv_unit, "setTextSize", unitSize)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}
