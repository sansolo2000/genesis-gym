package io.github.sansolo2000.genesisgym

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Al encender el celular (o al actualizar la app), vuelve a programar los avisos pendientes.
 * Medicamentos: también al cambiar la zona horaria o la hora del celular, para que una toma de las 09:00 siga a las 09:00 locales.
 */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Alarmas.reprogramarPendientes(c)
                runCatching { Programador.reprogramar(c) }
                runCatching { AvisosMedicamentos.reprogramar(c) }
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> runCatching { AvisosMedicamentos.reprogramar(c) }
        }
    }
}
