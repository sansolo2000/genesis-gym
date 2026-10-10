package io.github.sansolo2000.genesisgym

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Etapa E1 — programa los avisos de prueba con AlarmManager y los guarda en el celular
 * (SharedPreferences) para volver a programarlos después de reiniciar.
 */
object Alarmas {
    const val CANAL = "pruebas-avisos"
    const val EXTRA_ID = "aviso_id"
    private const val PREFS = "e1-avisos"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lista(c: Context): List<Aviso> =
        (1..3).mapNotNull { id -> prefs(c).getString("aviso$id", null)?.let { Aviso.deTexto(it) } }

    fun guardar(c: Context, a: Aviso) { prefs(c).edit().putString("aviso${a.id}", a.aTexto()).apply() }

    fun borrarTodo(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        for (a in lista(c)) am.cancel(pendiente(c, a.id))
        prefs(c).edit().clear().apply()
    }

    fun puedeExactas(c: Context): Boolean = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun crearCanal(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Avisos de prueba", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Etapa E1: comprobar que los avisos llegan a la hora"
                }
            )
        }
    }

    private fun pendiente(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, AlarmaReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Programa (o reprograma) un aviso. Devuelve el aviso con el modo realmente usado. */
    fun programar(c: Context, id: Int, cuandoMs: Long): Aviso {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pendiente(c, id)
        val modo = if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuandoMs, pi)
            Avisos.MODO_EXACTO
        } else {
            // Sin el permiso de alarmas exactas: Android puede correrlo dentro de una ventana.
            am.setWindow(AlarmManager.RTC_WAKEUP, cuandoMs, 10 * 60 * 1000L, pi)
            Avisos.MODO_INEXACTO
        }
        val a = Aviso(id, cuandoMs, modo)
        guardar(c, a)
        return a
    }

    /** Después de reiniciar el celular, Android olvida las alarmas: se vuelven a programar las pendientes futuras. */
    fun reprogramarPendientes(c: Context) {
        val ahora = System.currentTimeMillis()
        for (a in lista(c)) if (a.llegoMs == null && a.programadoMs > ahora) programar(c, a.id, a.programadoMs)
    }
}
