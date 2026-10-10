package io.github.sansolo2000.genesisgym

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al encender el celular (o al actualizar la app), vuelve a programar los avisos pendientes. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Alarmas.reprogramarPendientes(c)
                runCatching { Programador.reprogramar(c) }
            }
        }
    }
}
