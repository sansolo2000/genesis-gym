package io.github.sansolo2000.genesisgym

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import java.time.ZonedDateTime

/**
 * Etapa E5 — avisos reales (comidas, tareas de cocina, entrenamiento y reporte del día).
 * - La configuración vive solo en este celular (no va en el respaldo).
 * - Se programan los próximos 7 días con AlarmManager y se vuelven a calcular al abrir o salir de la app,
 *   al guardar la configuración, cuando llega un aviso y al reiniciar el celular.
 * - El canal de notificación es silencioso: el sonido lo toca la app, para poder fijar su volumen.
 */
object Programador {
    private const val PREFS = "e5-avisos"
    private const val CANAL = "genesis-avisos-v1"
    private const val BASE_CODIGO = 2000
    const val EXTRA_TITULO = "titulo"; const val EXTRA_TEXTO = "texto"; const val EXTRA_VISTA = "vista"
    const val DURACION_MS = 8000L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(c: Context): Recordatorios.Config = prefs(c).let {
        Recordatorios.Config(
            comidas = it.getBoolean("comidas", false), minutosAntes = it.getInt("minutos", 0), tareas = it.getBoolean("tareas", false),
            entrenamiento = it.getBoolean("entrenamiento", false), horaEntrenamiento = it.getString("hora_entrenamiento", "") ?: "",
            reporte = it.getBoolean("reporte", false), horaReporte = it.getString("hora_reporte", "21:00") ?: "21:00"
        )
    }

    fun guardarConfig(c: Context, k: Recordatorios.Config) {
        prefs(c).edit().putBoolean("comidas", k.comidas).putInt("minutos", k.minutosAntes).putBoolean("tareas", k.tareas)
            .putBoolean("entrenamiento", k.entrenamiento).putString("hora_entrenamiento", k.horaEntrenamiento.trim())
            .putBoolean("reporte", k.reporte).putString("hora_reporte", k.horaReporte.trim()).apply()
    }

    // ---------- sonido ----------
    data class Sonido(val uri: Uri?, val alarma: Boolean, val volumen: Int)   // volumen 0–100 (% del volumen del celular)

    fun sonido(c: Context): Sonido = prefs(c).let { Sonido(it.getString("sonido", null)?.let(Uri::parse), it.getBoolean("alarma", false), it.getInt("volumen", 70)) }
    fun guardarSonido(c: Context, s: Sonido) { prefs(c).edit().putString("sonido", s.uri?.toString()).putBoolean("alarma", s.alarma).putInt("volumen", s.volumen.coerceIn(0, 100)).apply() }

    fun uriSonido(s: Sonido): Uri = s.uri ?: RingtoneManager.getDefaultUri(if (s.alarma) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)

    fun nombreSonido(c: Context, s: Sonido): String {
        val u = s.uri ?: return if (s.alarma) "el sonido de alarma del celular" else "el sonido de notificación del celular"
        return try { RingtoneManager.getRingtone(c, u)?.getTitle(c) ?: u.toString() } catch (e: Exception) { u.toString() }
    }

    /** Volumen del celular para el tipo de sonido elegido, como (actual, máximo). */
    fun volumenSistema(c: Context, alarma: Boolean): Pair<Int, Int> {
        val am = c.getSystemService(AudioManager::class.java)
        val s = if (alarma) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        return am.getStreamVolume(s) to am.getStreamMaxVolume(s)
    }

    private var sonando: MediaPlayer? = null

    /**
     * Toca el sonido hasta 8 segundos. Como notificación, respeta el modo silencio/vibrar del celular;
     * como alarma, suena aunque el celular esté en silencio (según el volumen de alarmas del celular).
     */
    fun tocar(c: Context, s: Sonido = sonido(c), alTerminar: () -> Unit = {}) {
        val am = c.getSystemService(AudioManager::class.java)
        if (!s.alarma && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) { alTerminar(); return }
        detener()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (s.alarma) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            mp.setDataSource(c, uriSonido(s))
            val v = s.volumen / 100f
            mp.setVolume(v, v)
            mp.isLooping = s.alarma
            mp.prepare()
        } catch (e: Exception) { mp.release(); alTerminar(); return }
        sonando = mp
        var listo = false
        val fin = { if (!listo) { listo = true; if (sonando === mp) sonando = null; runCatching { mp.stop() }; mp.release(); alTerminar() } }
        mp.setOnCompletionListener { fin() }
        Handler(Looper.getMainLooper()).postDelayed({ fin() }, DURACION_MS)
        mp.start()
    }

    fun detener() { sonando?.let { runCatching { it.stop() }; it.release() }; sonando = null }

    // ---------- notificación ----------
    fun crearCanal(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(NotificationChannel(CANAL, "Avisos de comidas y entrenamiento", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Comidas, tareas de cocina, entrenamiento y reporte del día. El sonido y su volumen se eligen en la app."
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun mostrar(c: Context, titulo: String, texto: String, vista: String, id: Int) {
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        crearCanal(c)
        val abrir = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).putExtra(EXTRA_VISTA, vista)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publica = Notification.Builder(c, CANAL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Génesis Gym N").setContentText("Tienes un aviso").build()
        val n = Notification.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(if (sonido(c).alarma) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publica)
            .setContentIntent(abrir).setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // ---------- programación ----------
    fun proximos(c: Context, ahora: ZonedDateTime = ZonedDateTime.now(Gimnasio.ZONA)): List<Recordatorios.Aviso> {
        val base = BaseLocal(c)
        val prog = base.get("alimentacion/programa_activo")?.obj("programa")
        val comidas = prog?.let {
            EstadoComidas(it, base.get("alimentacion/intercambios"), base.coleccion("comidas"), emptySet())
        }
        val rutina = base.get("config/rutina_activa")?.obj("rutina")   // el archivo de la rutina: trae "sesiones"
        val sesionesRutina = rutina?.arr("sesiones")?.mapNotNull { s -> val d = s.txt("dia_semana"); val id = s.txt("id"); if (d != null && id != null) Triple(d, id, s.txt("nombre") ?: id) else null } ?: emptyList()
        val registradas = base.coleccion("sesiones").mapNotNull { d -> d.data.txt("fecha")?.let { f -> d.data.txt("sesion_id")?.let { "${f}_$it" } } }.toSet()
        return Recordatorios.juntar(Recordatorios.calcular(config(c), comidas, sesionesRutina, registradas, ahora))
    }

    private fun pendiente(c: Context, i: Int, a: Recordatorios.Aviso?): PendingIntent {
        val intent = Intent(c, RecordatorioReceiver::class.java)
        if (a != null) intent.putExtra(EXTRA_TITULO, a.titulo).putExtra(EXTRA_TEXTO, a.texto).putExtra(EXTRA_VISTA, a.vista)
        return PendingIntent.getBroadcast(c, BASE_CODIGO + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---------- 0.6.0: fin del descanso en la sesión ----------
    private const val CODIGO_DESCANSO = 1999

    private fun pendienteDescanso(c: Context, texto: String?): PendingIntent {
        val intent = Intent(c, RecordatorioReceiver::class.java)
        if (texto != null) intent.putExtra(EXTRA_TITULO, "Descanso terminado").putExtra(EXTRA_TEXTO, texto).putExtra(EXTRA_VISTA, "sesion")
        return PendingIntent.getBroadcast(c, CODIGO_DESCANSO, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Programa el aviso de fin de descanso (suena con la configuración de Avisos, aunque la pantalla esté apagada). */
    fun programarDescanso(c: Context, finMs: Long, texto: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finMs, pendienteDescanso(c, texto))
        else am.setWindow(AlarmManager.RTC_WAKEUP, finMs, 60_000L, pendienteDescanso(c, texto))
    }

    fun cancelarDescanso(c: Context) { c.getSystemService(AlarmManager::class.java).cancel(pendienteDescanso(c, null)) }

    /** Borra los avisos anteriores y programa los de los próximos 7 días. Devuelve cuántos quedaron. */
    fun reprogramar(c: Context): Int {
        val am = c.getSystemService(AlarmManager::class.java)
        val antes = prefs(c).getInt("programados", 0)
        for (i in 0 until antes) am.cancel(pendiente(c, i, null))
        val lista = try { proximos(c).take(200) } catch (e: Exception) { emptyList() }
        val exactas = am.canScheduleExactAlarms()
        lista.forEachIndexed { i, a ->
            val ms = a.cuando.toInstant().toEpochMilli()
            if (exactas) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pendiente(c, i, a))
            else am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60 * 1000L, pendiente(c, i, a))
        }
        prefs(c).edit().putInt("programados", lista.size).putBoolean("exactas", exactas).apply()
        return lista.size
    }
}

/** Llega un aviso real: muestra la notificación, toca el sonido (hasta 8 s) y vuelve a calcular los próximos avisos. */
class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val titulo = intent.getStringExtra(Programador.EXTRA_TITULO) ?: return
        val texto = intent.getStringExtra(Programador.EXTRA_TEXTO) ?: ""
        val vista = intent.getStringExtra(Programador.EXTRA_VISTA) ?: "hoy"
        Programador.mostrar(c, titulo, texto, vista, 300 + (System.currentTimeMillis() / 60000 % 1000).toInt())
        val r = goAsync()
        try { Programador.reprogramar(c) } catch (_: Exception) {}
        Programador.tocar(c) { r.finish() }
    }
}
