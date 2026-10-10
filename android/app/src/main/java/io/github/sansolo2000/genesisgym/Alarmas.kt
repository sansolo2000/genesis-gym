package io.github.sansolo2000.genesisgym

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/**
 * Etapa E1 — programa los avisos de prueba con AlarmManager y los guarda en el celular
 * (SharedPreferences) para volver a programarlos después de reiniciar.
 */
object Alarmas {
    private const val PREFIJO_CANAL = "pruebas-avisos"
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

    // Sonido elegido por Héctor. Android fija el sonido de un canal al crearlo: si cambia el sonido
    // o el modo (notificación/alarma), se borra el canal anterior y se crea uno nuevo con otro id.
    private const val PREFS_SONIDO = "e1-sonido"

    fun sonido(c: Context): Uri? = c.getSharedPreferences(PREFS_SONIDO, Context.MODE_PRIVATE).getString("uri", null)?.let { Uri.parse(it) }
    fun comoAlarma(c: Context): Boolean = c.getSharedPreferences(PREFS_SONIDO, Context.MODE_PRIVATE).getBoolean("alarma", false)
    private fun version(c: Context): Int = c.getSharedPreferences(PREFS_SONIDO, Context.MODE_PRIVATE).getInt("version", 1)
    fun canal(c: Context): String = "$PREFIJO_CANAL-v${version(c)}"

    fun nombreSonido(c: Context): String {
        val u = sonido(c) ?: return "el sonido de notificación del celular"
        return try { RingtoneManager.getRingtone(c, u)?.getTitle(c) ?: u.toString() } catch (e: Exception) { u.toString() }
    }

    fun cambiarSonido(c: Context, uri: Uri?, alarma: Boolean) {
        val prefs = c.getSharedPreferences(PREFS_SONIDO, Context.MODE_PRIVATE)
        prefs.edit().putString("uri", uri?.toString()).putBoolean("alarma", alarma).putInt("version", version(c) + 1).apply()
        crearCanal(c)
    }

    fun crearCanal(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val id = canal(c)
        for (viejo in nm.notificationChannels) if (viejo.id.startsWith(PREFIJO_CANAL) && viejo.id != id) nm.deleteNotificationChannel(viejo.id)
        if (nm.getNotificationChannel(id) == null) {
            val alarma = comoAlarma(c)
            val atributos = AudioAttributes.Builder()
                .setUsage(if (alarma) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val uri = sonido(c) ?: RingtoneManager.getDefaultUri(if (alarma) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            nm.createNotificationChannel(
                NotificationChannel(id, "Avisos de prueba", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Etapa E1: comprobar que los avisos llegan a la hora y suenan"
                    setSound(uri, atributos)
                    enableVibration(true)
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
