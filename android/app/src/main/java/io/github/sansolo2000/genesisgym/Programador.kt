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
import android.os.VibrationEffect
import android.os.VibratorManager
import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import java.time.ZonedDateTime

/**
 * Etapa E5 — avisos reales (comidas, tareas de cocina, entrenamiento y reporte del día).
 * - La configuración vive en este celular y entra al respaldo en config/avisos (M-04).
 * - Se programan los próximos 7 días con AlarmManager y se vuelven a calcular al abrir o salir de la app,
 *   al guardar la configuración, cuando llega un aviso y al reiniciar el celular.
 * - M-10 (14-oct): cada tipo de alerta tiene su canal (silencioso), su tono, su volumen (% del volumen de alarmas
 *   del celular), su vibración, "sonar en silencio" y su tiempo de posponer. El sonido y la vibración los maneja la app.
 *   Comidas, Tareas, Entrenamientos y Reporte suenan en AlertaServicio hasta Detener o Posponer (tope de 2 min).
 */
object Programador {
    private const val PREFS = "e5-avisos"
    private const val CANAL_ANTERIOR = "genesis-avisos-v1"
    private const val BASE_CODIGO = 2000
    private const val CODIGO_POSPUESTO = 2600
    const val EXTRA_TITULO = "titulo"; const val EXTRA_TEXTO = "texto"; const val EXTRA_VISTA = "vista"
    const val EXTRA_TIPO = "tipo"; const val EXTRA_CLAVES = "claves"; const val EXTRA_POSPUESTO = "pospuesto"; const val EXTRA_ID = "id"
    const val EXTRA_DETENER = "detener_alerta"
    const val DURACION_MS = 8000L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(c: Context): Recordatorios.Config = prefs(c).let {
        Recordatorios.Config(
            comidas = it.getBoolean("comidas", false), minutosAntes = it.getInt("minutos", 0), tareas = it.getBoolean("tareas", false),
            entrenamiento = it.getBoolean("entrenamiento", false), horaEntrenamiento = it.getString("hora_entrenamiento", "") ?: "",
            reporte = it.getBoolean("reporte", false), horaReporte = it.getString("hora_reporte", "21:00") ?: "21:00",
            descanso = it.getBoolean("descanso", true)
        )
    }

    fun guardarConfig(c: Context, k: Recordatorios.Config) {
        prefs(c).edit().putBoolean("comidas", k.comidas).putInt("minutos", k.minutosAntes).putBoolean("tareas", k.tareas)
            .putBoolean("entrenamiento", k.entrenamiento).putString("hora_entrenamiento", k.horaEntrenamiento.trim())
            .putBoolean("reporte", k.reporte).putString("hora_reporte", k.horaReporte.trim()).putBoolean("descanso", k.descanso).apply()
    }

    // ---------- sonido anterior a M-10 (un solo tono): queda como valor inicial de cada tipo ----------
    data class Sonido(val uri: Uri?, val alarma: Boolean, val volumen: Int)   // volumen 0–100 (% del volumen del celular)

    fun sonido(c: Context): Sonido = prefs(c).let { Sonido(it.getString("sonido", null)?.let(Uri::parse), it.getBoolean("alarma", false), it.getInt("volumen", 70)) }
    fun guardarSonido(c: Context, s: Sonido) { prefs(c).edit().putString("sonido", s.uri?.toString()).putBoolean("alarma", s.alarma).putInt("volumen", s.volumen.coerceIn(0, 100)).apply() }

    fun uriSonido(s: Sonido): Uri = s.uri ?: RingtoneManager.getDefaultUri(if (s.alarma) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)

    // ---------- M-10: configuración por tipo ----------
    fun tipo(c: Context, t: String): Alertas.Tipo {
        val p = prefs(c); val s = sonido(c)
        val ini = Alertas.inicial(s.uri?.toString(), s.volumen, s.alarma)
        if (!p.contains("t.$t.volumen")) return ini
        return Alertas.Tipo(p.getString("t.$t.sonido", null), p.getInt("t.$t.volumen", ini.volumen).coerceIn(0, 100), p.getBoolean("t.$t.vibrar", ini.vibrar),
            p.getBoolean("t.$t.silencio", ini.enSilencio), p.getInt("t.$t.posponer", ini.posponerMin).takeIf { it in Alertas.POSPONER } ?: Alertas.POSPONER_POR_DEFECTO)
    }

    fun guardarTipo(c: Context, t: String, v: Alertas.Tipo) {
        prefs(c).edit().putString("t.$t.sonido", v.sonido).putInt("t.$t.volumen", v.volumen.coerceIn(0, 100)).putBoolean("t.$t.vibrar", v.vibrar)
            .putBoolean("t.$t.silencio", v.enSilencio).putInt("t.$t.posponer", v.posponerMin).apply()
    }

    fun tonoDe(v: Alertas.Tipo): Uri = v.sonido?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    fun nombreTono(c: Context, v: Alertas.Tipo): String {
        val u = v.sonido?.let(Uri::parse) ?: return "el sonido de alarma del celular"
        return try { RingtoneManager.getRingtone(c, u)?.getTitle(c) ?: u.toString() } catch (e: Exception) { u.toString() }
    }

    /** Volumen de alarmas del celular, como (actual, máximo). Decisión de Héctor: la app no lo cambia. */
    fun volumenAlarmas(c: Context): Pair<Int, Int> {
        val am = c.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(AudioManager.STREAM_ALARM) to am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    }

    /** Estado del celular para decidir si suena: (timbre, filtro de No molestar, ¿No molestar deja pasar alarmas?). */
    data class Estado(val timbre: Int, val filtro: Int, val alarmasPermitidas: Boolean?)
    fun estado(c: Context): Estado {
        val am = c.getSystemService(AudioManager::class.java); val nm = c.getSystemService(NotificationManager::class.java)
        val alarmas = runCatching { (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0 }.getOrNull()
        return Estado(am.ringerMode, nm.currentInterruptionFilter, alarmas)
    }

    /** Prepara el tono del tipo en el canal de alarmas, con su volumen. Un tono que ya no existe cae al de alarma del celular. */
    fun preparar(c: Context, v: Alertas.Tipo, enBucle: Boolean): MediaPlayer? {
        fun intento(u: Uri): MediaPlayer? {
            val mp = MediaPlayer()
            return try {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(c, u)
                val vol = v.volumen / 100f
                mp.setVolume(vol, vol)
                mp.isLooping = enBucle
                mp.prepare()
                mp
            } catch (e: Exception) { mp.release(); null }
        }
        return intento(tonoDe(v)) ?: if (v.sonido != null) intento(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) else null
    }

    private val PATRON = longArrayOf(0, 600, 400, 600, 1200)
    fun vibrar(c: Context, repetir: Boolean) {
        val vib = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { vib.vibrate(VibrationEffect.createWaveform(PATRON, if (repetir) 0 else -1)) }
    }
    fun dejarDeVibrar(c: Context) { runCatching { c.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() } }

    private var sonando: MediaPlayer? = null

    /** Suena una vez (hasta 8 s) con la configuración del tipo: el Descanso, el botón "Probar" y el plan B si no parte el servicio. */
    fun tocar(c: Context, t: String, v: Alertas.Tipo = tipo(c, t), alTerminar: () -> Unit = {}) {
        val e = estado(c)
        detener()
        if (Alertas.vibra(v, e.timbre)) vibrar(c, false)
        if (!Alertas.suena(v, e.timbre, e.filtro, e.alarmasPermitidas)) { alTerminar(); return }
        val mp = preparar(c, v, false) ?: run { alTerminar(); return }
        sonando = mp
        var listo = false
        val fin = { if (!listo) { listo = true; if (sonando === mp) sonando = null; runCatching { mp.stop() }; mp.release(); alTerminar() } }
        mp.setOnCompletionListener { fin() }
        Handler(Looper.getMainLooper()).postDelayed({ fin() }, DURACION_MS)
        mp.start()
    }

    fun detener() { sonando?.let { runCatching { it.stop() }; it.release() }; sonando = null }

    // ---------- notificación: un canal por tipo ----------
    fun canal(t: String) = "genesis-$t-v1"

    fun crearCanal(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        for (t in Alertas.TODOS) if (nm.getNotificationChannel(canal(t)) == null)
            nm.createNotificationChannel(NotificationChannel(canal(t), Alertas.NOMBRE[t], NotificationManager.IMPORTANCE_HIGH).apply {
                description = "El tono, el volumen y la vibración se eligen en Avisos, dentro de la app."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        if (nm.getNotificationChannel(CANAL_ANTERIOR) != null) runCatching { nm.deleteNotificationChannel(CANAL_ANTERIOR) }
    }

    fun puedeNotificar(c: Context) = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun accion(c: Context, accion: String, id: Int, datos: Intent): PendingIntent =
        PendingIntent.getBroadcast(c, id * 4 + (if (accion == AccionAlerta.POSPONER) 1 else if (accion == AccionAlerta.BORRADA) 2 else 0),
            Intent(datos).setClass(c, AccionAlerta::class.java).setAction(accion), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** La notificación de un aviso. Los tipos que insisten llevan "Detener" y "Posponer N min". */
    fun construir(c: Context, datos: Intent, id: Int): Notification {
        crearCanal(c)
        val t = datos.getStringExtra(EXTRA_TIPO) ?: Recordatorios.COMIDA
        if (t == Alertas.MEDICAMENTO) return AvisosMedicamentos.construir(c, datos, id)   // Confirmar, Posponer… y Omitir
        val titulo = datos.getStringExtra(EXTRA_TITULO) ?: "Génesis Gym N"; val texto = datos.getStringExtra(EXTRA_TEXTO) ?: ""
        val vista = datos.getStringExtra(EXTRA_VISTA) ?: "hoy"
        // O-33: tocar la notificación de una alerta que insiste abre la app y cuenta como Detener.
        val abrir = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).putExtra(EXTRA_VISTA, vista).putExtra(EXTRA_DETENER, Alertas.tocarDetiene(t))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publica = Notification.Builder(c, canal(t)).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Génesis Gym N").setContentText("Tienes un aviso").build()
        val b = Notification.Builder(c, canal(t))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(if (Alertas.insiste(t)) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publica)
            .setContentIntent(abrir).setAutoCancel(true)
        if (Alertas.insiste(t)) {
            val d = Intent(datos).putExtra(EXTRA_ID, id)
            b.addAction(Notification.Action.Builder(null, "Detener", accion(c, AccionAlerta.DETENER, id, d)).build())
            b.addAction(Notification.Action.Builder(null, "Posponer ${tipo(c, t).posponerMin} min", accion(c, AccionAlerta.POSPONER, id, d)).build())
            b.setDeleteIntent(accion(c, AccionAlerta.BORRADA, id, d))
        }
        return b.build()
    }

    fun mostrar(c: Context, datos: Intent, id: Int) {
        if (!puedeNotificar(c)) return
        c.getSystemService(NotificationManager::class.java).notify(id, construir(c, datos, id))
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
        val registradas = Gimnasio.clavesRegistradas(base.coleccion("sesiones").map { it.data })   // O-27: sin series anotadas no cuenta
        val apoyo = base.get("programas_apoyo/activo")?.obj("programa")
        val reportados = base.rutas("reportes_apoyo").map { it.removePrefix("reportes_apoyo/") }.toSet()
        val texto = apoyo?.obj("textos")?.txt("recordatorio_reporte") ?: Recordatorios.TEXTO_REPORTE
        val cfg = conModulos(config(c), Perfil.leer(base.get(Perfil.RUTA)))
        return Recordatorios.juntar(Recordatorios.calcular(cfg, comidas, sesionesRutina, registradas, ahora, reportados = reportados, textoReporte = texto))
    }

    /** Multiusuario: un módulo oculto (o que el plan de la persona no trae) no avisa, aunque su aviso siga encendido en Avisos. */
    fun conModulos(k: Recordatorios.Config, p: Perfil.Datos): Recordatorios.Config = k.copy(
        comidas = k.comidas && p.visible(Perfil.COMIDAS), tareas = k.tareas && p.visible(Perfil.COMIDAS),
        entrenamiento = k.entrenamiento && p.visible(Perfil.ENTRENAMIENTO), reporte = k.reporte && p.visible(Perfil.APOYO))

    private fun datos(c: Context, titulo: String, texto: String, vista: String, tipo: String, claves: List<String>): Intent =
        Intent(c, RecordatorioReceiver::class.java).putExtra(EXTRA_TITULO, titulo).putExtra(EXTRA_TEXTO, texto).putExtra(EXTRA_VISTA, vista)
            .putExtra(EXTRA_TIPO, tipo).putExtra(EXTRA_CLAVES, claves.toTypedArray())

    private fun pendiente(c: Context, i: Int, a: Recordatorios.Aviso?): PendingIntent {
        val intent = if (a != null) datos(c, a.titulo, a.texto, a.vista, a.tipo, a.claves) else Intent(c, RecordatorioReceiver::class.java)
        return PendingIntent.getBroadcast(c, BASE_CODIGO + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun exacta(c: Context, pi: PendingIntent, finMs: Long, ventanaMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finMs, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, finMs, ventanaMs, pi)
    }

    // ---------- 0.6.0: fin del descanso en la sesión; M-01: fin de una serie por tiempo (tipo Descanso en M-10) ----------
    private const val CODIGO_DESCANSO = 1999
    private const val CODIGO_SERIE = 1998

    private fun pendienteSesion(c: Context, codigo: Int, titulo: String, texto: String?): PendingIntent {
        val intent = if (texto != null) datos(c, titulo, texto, "sesion", Alertas.DESCANSO, emptyList()) else Intent(c, RecordatorioReceiver::class.java)
        return PendingIntent.getBroadcast(c, codigo, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Programa el aviso de fin de descanso (suena aunque la pantalla esté apagada), si el tipo Descanso está activo. */
    fun programarDescanso(c: Context, finMs: Long, texto: String) { if (config(c).descanso) exacta(c, pendienteSesion(c, CODIGO_DESCANSO, "Descanso terminado", texto), finMs, 60_000L) }
    fun cancelarDescanso(c: Context) { c.getSystemService(AlarmManager::class.java).cancel(pendienteSesion(c, CODIGO_DESCANSO, "", null)) }

    /** M-01: aviso al cumplirse el tiempo de una serie por tiempo (elíptica, elongaciones). */
    fun programarSerie(c: Context, finMs: Long, texto: String) { if (config(c).descanso) exacta(c, pendienteSesion(c, CODIGO_SERIE, "Tiempo cumplido", texto), finMs, 60_000L) }
    fun cancelarSerie(c: Context) { c.getSystemService(AlarmManager::class.java).cancel(pendienteSesion(c, CODIGO_SERIE, "", null)) }

    /** M-10: "Posponer" vuelve a avisar en N minutos con el mismo texto (no lo borra reprogramar: tiene su propio código). */
    fun posponer(c: Context, datos: Intent) {
        val t = datos.getStringExtra(EXTRA_TIPO) ?: return
        val i = Intent(datos).setClass(c, RecordatorioReceiver::class.java).setAction(null).putExtra(EXTRA_POSPUESTO, true)
        val pi = PendingIntent.getBroadcast(c, CODIGO_POSPUESTO + Alertas.TIPOS.indexOf(t), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        exacta(c, pi, Alertas.posponerHasta(System.currentTimeMillis(), tipo(c, t).posponerMin), 60_000L)
    }

    /** Al llegar un aviso pospuesto: ¿sigue haciendo falta? (la comida no se marcó, el entrenamiento o el reporte no se registró). */
    fun vigente(c: Context, t: String, claves: List<String>): Boolean {
        val base = BaseLocal(c)
        val hechas = (base.get("alimentacion/tareas_hechas")?.get("hechas") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet() ?: emptySet()
        return Alertas.vigente(t, Alertas.activo(config(c), t), claves,
            comidaMarcada = { k -> base.get("comidas/$k")?.txt("estado")?.isNotEmpty() == true },
            tareaHecha = { k -> k in hechas },
            entrenoRegistrado = { f -> Gimnasio.clavesRegistradas(base.coleccion("sesiones").map { it.data }).any { it.startsWith("${f}_") } },
            reporteGuardado = { f -> base.get("reportes_apoyo/$f") != null })
    }

    // ---------- M-04: Avisos y sonido en el respaldo ----------
    /** Guarda la configuración de Avisos y de cada tipo en config/avisos, para que entre en el respaldo. */
    fun respaldarEn(c: Context, base: BaseLocal) {
        val s = sonido(c)
        base.put("config/avisos", Recordatorios.avisosAJson(Recordatorios.Respaldados(config(c), s.uri?.toString(), s.alarma, s.volumen,
            Alertas.TODOS.associateWith { tipo(c, it) })))
    }

    /** Después de restaurar: si el respaldo trae config/avisos, la aplica y vuelve a programar los avisos. */
    fun restaurarDesde(c: Context, base: BaseLocal) {
        val o = base.get("config/avisos") ?: return
        val r = Recordatorios.avisosDesdeJson(o)
        guardarConfig(c, r.config)
        guardarSonido(c, Sonido(r.sonido?.let(Uri::parse), r.alarma, r.volumen))
        for ((t, v) in r.tipos) guardarTipo(c, t, v)
        reprogramar(c)
    }

    /** Avisos toma la hora del reporte del programa de apoyo como hora inicial (Recordatorios.horaReporteAAplicar). */
    fun horaReporteDesdeApoyo(c: Context, nueva: String?, anterior: String?) {
        val h = Recordatorios.horaReporteAAplicar(nueva, anterior) ?: return
        guardarConfig(c, config(c).copy(horaReporte = h))
        reprogramar(c)
    }

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

/**
 * Llega un aviso real. M-10: los tipos que insisten suenan en AlertaServicio hasta Detener o Posponer (tope 2 min);
 * el Descanso suena una vez. Si el servicio no puede partir (por ejemplo, sin alarmas exactas), queda el sonido corto.
 */
class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        intent.getStringExtra(Programador.EXTRA_TITULO) ?: return
        // Un aviso programado por una versión anterior no trae el tipo: el de la sesión es Descanso; el resto, Comidas.
        val t = intent.getStringExtra(Programador.EXTRA_TIPO)?.takeIf { it in Alertas.TIPOS }
            ?: if (intent.getStringExtra(Programador.EXTRA_VISTA) == "sesion") Alertas.DESCANSO else Recordatorios.COMIDA
        val datos = Intent(intent).putExtra(Programador.EXTRA_TIPO, t)
        val claves = intent.getStringArrayExtra(Programador.EXTRA_CLAVES)?.toList() ?: emptyList()
        val r = goAsync()
        try {
            if (intent.getBooleanExtra(Programador.EXTRA_POSPUESTO, false) && !Programador.vigente(c, t, claves)) { r.finish(); return }
            if (!intent.getBooleanExtra(Programador.EXTRA_POSPUESTO, false)) runCatching { Programador.reprogramar(c) }
        } catch (_: Exception) {}
        val id = 300 + Alertas.TIPOS.indexOf(t) * 1000 + (System.currentTimeMillis() / 60000 % 1000).toInt()
        if (Alertas.insiste(t) && Programador.puedeNotificar(c)) {
            val ok = runCatching { c.startForegroundService(AlertaServicio.sonar(c, datos, id)) }.isSuccess
            if (ok) { r.finish(); return }
        }
        Programador.mostrar(c, datos, id)
        Programador.tocar(c, t) { r.finish() }
    }
}
