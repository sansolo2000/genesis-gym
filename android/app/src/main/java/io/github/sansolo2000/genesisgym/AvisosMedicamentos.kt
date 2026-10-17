package io.github.sansolo2000.genesisgym

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Módulo Medicamentos en el celular: catálogo (archivo aparte, llega con el plan), configuración, alarmas y respuestas.
 * - Las horas se calculan en la zona vigente del celular (ZoneId.systemDefault()); al cambiar la zona o la hora se reprograma.
 * - Una alarma por toma (su URI la identifica): suena a la hora, se repite cada 10 min y a las 2 h se cierra "sin respuesta".
 *   El estado de cada cadena sale del historial, así que reprogramar (al abrir, al reiniciar, al responder) la retoma sin perderla.
 * - Suena con AlertaServicio (M-10) como el tipo Medicamentos: tono, volumen y vibración se eligen en Avisos.
 */
object AvisosMedicamentos {
    private const val PREFS = "medicamentos-avisos"
    const val ARCHIVO_CATALOGO = "medicamentos/catalogo.json"
    const val ASSET_CONFIG = "medicamentos/config-medicamentos.json"
    const val EXTRA_TRATAMIENTO = "med_tratamiento"; const val EXTRA_LOCAL = "med_local"; const val EXTRA_EVENTO = "med_evento"
    const val CONFIRMAR = "confirmar"; const val OMITIR = "omitir"
    private const val DIAS = 7L
    private const val MAXIMO = 300

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun zona(): ZoneId = ZoneId.systemDefault()

    // ---------- datos de la base ----------
    /** Con las omitidas y "sin respuesta" del historial, que alargan un tratamiento por cantidad de días (O-36). */
    fun tratamientos(base: BaseLocal): List<Medicamentos.Tratamiento> {
        val docs = base.coleccion(Medicamentos.COLECCION)
        val regs = docs.filter { it.ruta.startsWith("${Medicamentos.COLECCION}/a_") }.mapNotNull { Medicamentos.registroDesde(it.ruta, it.data) }
        return docs.filter { it.ruta.startsWith("${Medicamentos.COLECCION}/t_") }.mapNotNull { Medicamentos.tratamientoDesdeJson(it.data) }
            .map { Medicamentos.conHistorial(it, regs) }
    }

    fun tratamiento(base: BaseLocal, id: String): Medicamentos.Tratamiento? =
        Medicamentos.tratamientoDesdeJson(base.get(Medicamentos.rutaTratamiento(id)))?.let { Medicamentos.conHistorial(it, registros(base)) }

    fun registros(base: BaseLocal): List<Medicamentos.Registro> =
        base.coleccion(Medicamentos.COLECCION).filter { it.ruta.startsWith("${Medicamentos.COLECCION}/a_") }.mapNotNull { Medicamentos.registroDesde(it.ruta, it.data) }

    fun porClave(l: List<Medicamentos.Registro>): Map<String, List<Medicamentos.Registro>> = l.groupBy { Medicamentos.clave(it.tratamientoId, it.programada) }

    // ---------- configuración y catálogo ----------
    private var configApp: Medicamentos.Config? = null
    /** La del archivo de la app; si el plan trajo config_horarios o config_dosis, esas la reemplazan. */
    fun config(c: Context, base: BaseLocal): Medicamentos.Config {
        val app = configApp ?: Medicamentos.configDeArchivo(Json.parseToJsonElement(c.assets.open(ASSET_CONFIG).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject).first!!
            .also { configApp = it }
        val doc = base.get("config/medicamentos") ?: return app
        return Medicamentos.config(doc.o("config_horarios"), doc.o("config_dosis"), app).first ?: app
    }

    private var cache: Pair<Long, Medicamentos.Catalogo>? = null
    private fun archivo(c: Context) = File(c.filesDir, ARCHIVO_CATALOGO)
    fun hayCatalogo(c: Context) = archivo(c).isFile

    /** Lee el catálogo del archivo (una vez por versión del archivo). null si todavía no se importó con el plan. */
    fun catalogo(c: Context): Medicamentos.Catalogo? {
        val f = archivo(c)
        if (!f.isFile) return null
        cache?.let { if (it.first == f.lastModified()) return it.second }
        val cat = Medicamentos.catalogoDesdeJson(Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject)
        cache = f.lastModified() to cat
        return cat
    }

    /** Guarda el catálogo de la sección del plan (primero en un archivo temporal, para no dejarlo a medias). */
    fun guardarCatalogo(c: Context, seccion: JsonObject) {
        val f = archivo(c); f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "catalogo.tmp")
        tmp.writeText(JsJson.stringify(seccion.o("catalogo") ?: throw IllegalStateException("sin catálogo")), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IllegalStateException("no se pudo guardar el catálogo") }
        cache = null
    }

    /** Huella de la sección activa, solo si el catálogo está en el celular (si falta, el plan vuelve a importarla). */
    fun huellaActiva(c: Context, base: BaseLocal): String? = if (hayCatalogo(c)) base.get("config/medicamentos")?.s("huella_sha256") else null

    // ---------- alarmas ----------
    private fun uri(tratamientoId: String, local: LocalDateTime) = Uri.parse("genesis-med://toma/$tratamientoId/${local.toString().replace(':', '-')}")
    fun idNotificacion(tratamientoId: String, local: LocalDateTime): Int = 100_000 + Math.floorMod(Medicamentos.clave(tratamientoId, local).hashCode(), 100_000)

    private fun intent(c: Context, tratamientoId: String, local: LocalDateTime, evento: String?): Intent =
        Intent(c, MedicamentoReceiver::class.java).setData(uri(tratamientoId, local))
            .putExtra(EXTRA_TRATAMIENTO, tratamientoId).putExtra(EXTRA_LOCAL, local.toString()).putExtra(EXTRA_EVENTO, evento)

    private fun pendiente(c: Context, i: Intent): PendingIntent = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alarma(c: Context, ms: Long, i: Intent) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pendiente(c, i))
        else am.setWindow(AlarmManager.RTC_WAKEUP, ms, 60_000L, pendiente(c, i))
    }

    data class Programada(val tratamientoId: String, val local: LocalDateTime, val evento: String?, val ms: Long)

    /** Lo que hay que programar ahora: las cadenas abiertas (en su próximo aviso) y las tomas de los próximos 7 días. */
    fun calcular(trats: List<Medicamentos.Tratamiento>, regs: Map<String, List<Medicamentos.Registro>>, ahora: ZonedDateTime): List<Programada> {
        val ahoraMs = ahora.toInstant().toEpochMilli()
        val r = LinkedHashMap<String, Programada>()
        for (t in trats) {
            if (!t.avisa) continue
            val propios = regs.filterKeys { it.startsWith("${t.id}_") }
            for ((oc, inicio) in Medicamentos.cadenasAbiertas(t, ahora, propios)) {
                val p = Medicamentos.paso(inicio, ahoraMs)
                r[Medicamentos.clave(t.id, oc.local)] = Programada(t.id, oc.local, oc.evento, p.proximoMs ?: ahoraMs)
            }
            for (oc in Medicamentos.ocurrencias(t, ahora, ahora.plusDays(DIAS), ahora.zone)) {
                val k = Medicamentos.clave(t.id, oc.local)
                if (k !in r && regs[k].isNullOrEmpty()) r[k] = Programada(t.id, oc.local, oc.evento, oc.cuando.toInstant().toEpochMilli())
            }
        }
        return r.values.sortedBy { it.ms }.take(MAXIMO)
    }

    /** Cierra lo vencido sin respuesta, cancela las alarmas que ya no corresponden y programa las que sí. Devuelve cuántas quedaron. */
    fun reprogramar(c: Context): Int {
        val base = BaseLocal(c)
        val ahora = ZonedDateTime.now(zona())
        // Multiusuario: con el módulo oculto (o fuera del plan de la persona) no suena nada ni se cierran tomas "sin respuesta".
        var trats = if (Perfil.leer(base.get(Perfil.RUTA)).visible(Perfil.MEDICAMENTOS)) tratamientos(base) else emptyList()
        var regs = porClave(registros(base))
        val nuevos = mutableListOf<Respaldo.Doc>()
        fun cerrar(t: Medicamentos.Tratamiento, oc: Medicamentos.Ocurrencia) {
            val ruta = Medicamentos.rutaRegistro(t.id, oc.local, Medicamentos.SIN_RESPUESTA, 0)
            if (base.get(ruta) == null && nuevos.none { it.ruta == ruta })
                nuevos.add(Respaldo.Doc(ruta, Medicamentos.registro(Medicamentos.versionDe(t, oc.cuando.toInstant()), oc, Medicamentos.SIN_RESPUESTA, ahora.toInstant(), null, "cierre")))
        }
        for (t in trats) for (oc in Medicamentos.cerradasSinRespuesta(t, ahora, regs.keys)) cerrar(t, oc)
        // D-16: una postergación cuya ventana cerró con el celular apagado queda "sin respuesta".
        for (u in Medicamentos.postergadasVencidas(regs, ahora.toInstant().toEpochMilli())) {
            val t = trats.firstOrNull { it.id == u.tratamientoId } ?: continue
            // O-36: suspendido, una toma no queda "sin respuesta".
            if (!t.avisa || u.postergadaHasta?.let { Medicamentos.enSuspension(t, it) } == true) continue
            cerrar(t, Medicamentos.Ocurrencia(u.programada, ZonedDateTime.of(u.programada, ahora.zone), u.evento))
        }
        if (nuevos.isNotEmpty()) { base.ponerVarios(nuevos); regs = porClave(registros(base)); trats = tratamientos(base) }   // O-36: las nuevas "sin respuesta" alargan (solo hay nuevas con el módulo visible)
        val lista = calcular(trats, regs, ahora)
        val am = c.getSystemService(AlarmManager::class.java)
        val ahoraSet = lista.map { "${it.tratamientoId}|${it.local}" }.toSet()
        for (k in prefs(c).getStringSet("programadas", emptySet()) ?: emptySet()) if (k !in ahoraSet) {
            val (id, l) = k.split("|", limit = 2)
            val local = LocalDateTime.parse(l)
            runCatching { am.cancel(pendiente(c, intent(c, id, local, null))) }
            // O-41: una toma que ya sonaba y dejó de corresponder (se movió de hora, se archivó…) se calla y se quita.
            if (!ZonedDateTime.of(local, ahora.zone).isAfter(ahora)) runCatching { quitarNotificacion(c, id, local) }
        }
        for (p in lista) alarma(c, p.ms, intent(c, p.tratamientoId, p.local, p.evento))
        prefs(c).edit().putStringSet("programadas", ahoraSet).apply()
        return lista.size
    }

    // ---------- responder ----------
    /**
     * Registra una respuesta (documento nuevo; el historial anterior no se toca), quita la notificación y reprograma:
     * una postergación vuelve a avisar a los N minutos; confirmar u omitir termina la cadena.
     */
    fun responder(c: Context, tratamientoId: String, local: LocalDateTime, evento: String?, estado: String, postergacionMin: Int?, origen: String): Boolean {
        val base = BaseLocal(c)
        val t = tratamiento(base, tratamientoId) ?: return false
        val ahora = Instant.now()
        val oc = Medicamentos.Ocurrencia(local, ZonedDateTime.of(local, zona()), evento)
        base.put(Medicamentos.rutaRegistro(t.id, local, estado, ahora.toEpochMilli()),
            Medicamentos.registro(Medicamentos.versionDe(t, oc.cuando.toInstant()), oc, estado, ahora, postergacionMin, origen))
        quitarNotificacion(c, tratamientoId, local)
        runCatching { reprogramar(c) }
        return true
    }

    /** O-38 (Héctor, 16-oct): quita solo la alerta de esta toma; otra alerta que suene a la vez sigue sonando. */
    fun quitarNotificacion(c: Context, tratamientoId: String, local: LocalDateTime) {
        val id = idNotificacion(tratamientoId, local)
        AlertaServicio.actual?.quitarUna(id) ?: c.getSystemService(NotificationManager::class.java).cancel(id)
    }

    /** O-40: al archivar o borrar, quita las notificaciones de sus tomas programadas o en curso. */
    fun quitarNotificaciones(c: Context, tratamientoId: String) {
        for (k in prefs(c).getStringSet("programadas", emptySet()) ?: emptySet()) {
            val (id, l) = k.split("|", limit = 2)
            if (id == tratamientoId) runCatching { quitarNotificacion(c, id, LocalDateTime.parse(l)) }
        }
    }

    // ---------- notificación ----------
    fun datos(c: Context, t: Medicamentos.Tratamiento, local: LocalDateTime, evento: String?): Intent {
        val cfg = runCatching { config(c, BaseLocal(c)) }.getOrNull()
        val ev = evento?.let { cfg?.evento(it)?.nombre ?: it }
        val quien = runCatching { Perfil.nombreDe(t.persona, Perfil.leer(BaseLocal(c).get(Perfil.RUTA))) }.getOrDefault(t.persona)
        val titulo = "Medicamento · $quien · ${local.toLocalTime()}"
        val texto = "${t.med.nombre}\n${t.dosisTexto}" + (ev?.let { " · $it" } ?: "") + "\nProgramada: ${local.toLocalDate()} ${local.toLocalTime()}"
        return intent(c, t.id, local, evento).putExtra(Programador.EXTRA_TITULO, titulo).putExtra(Programador.EXTRA_TEXTO, texto)
            .putExtra(Programador.EXTRA_VISTA, "medicamentos").putExtra(Programador.EXTRA_TIPO, Alertas.MEDICAMENTO)
    }

    private fun accion(c: Context, datos: Intent, accion: String): PendingIntent =
        PendingIntent.getBroadcast(c, 0, Intent(datos).setClass(c, AccionMedicamento::class.java).setAction(accion),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Persona, medicamento, dosis, hora y evento; Confirmar, Posponer… (abre la app para elegir 15, 30, 60 o 90 min) y Omitir. */
    fun construir(c: Context, datos: Intent, id: Int): Notification {
        val canal = Programador.canal(Alertas.MEDICAMENTO)
        val titulo = datos.getStringExtra(Programador.EXTRA_TITULO) ?: "Medicamento"; val texto = datos.getStringExtra(Programador.EXTRA_TEXTO) ?: ""
        fun abrir(posponer: Boolean) = PendingIntent.getActivity(c, id * 2 + (if (posponer) 1 else 0),
            Intent(c, MainActivity::class.java).putExtra(Programador.EXTRA_VISTA, "medicamentos").putExtra(Programador.EXTRA_DETENER, true)
                .putExtra(EXTRA_TRATAMIENTO, datos.getStringExtra(EXTRA_TRATAMIENTO)).putExtra(EXTRA_LOCAL, datos.getStringExtra(EXTRA_LOCAL))
                .putExtra(EXTRA_EVENTO, datos.getStringExtra(EXTRA_EVENTO))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publica = Notification.Builder(c, canal).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Génesis Gym N").setContentText("Tienes un aviso").build()
        return Notification.Builder(c, canal)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(titulo).setContentText(texto.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publica)
            .setContentIntent(abrir(true)).setAutoCancel(false).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Confirmar", accion(c, datos, CONFIRMAR)).build())
            .addAction(Notification.Action.Builder(null, "Posponer…", abrir(true)).build())
            .addAction(Notification.Action.Builder(null, "Omitir", accion(c, datos, OMITIR)).build())
            .build()
    }
}

/** Llega la alarma de una toma: suena (si la cadena sigue abierta), programa el próximo aviso o la cierra "sin respuesta". */
class MedicamentoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val tid = intent.getStringExtra(AvisosMedicamentos.EXTRA_TRATAMIENTO) ?: return
        val local = runCatching { LocalDateTime.parse(intent.getStringExtra(AvisosMedicamentos.EXTRA_LOCAL)) }.getOrNull() ?: return
        val evento = intent.getStringExtra(AvisosMedicamentos.EXTRA_EVENTO)
        val r = goAsync()
        try {
            val base = BaseLocal(c)
            val t = AvisosMedicamentos.tratamiento(base, tid)
            if (t == null || !t.avisa) { AvisosMedicamentos.quitarNotificacion(c, tid, local); r.finish(); return }
            val propios = AvisosMedicamentos.registros(base).filter { it.tratamientoId == tid && it.programada == local }
            val ahora = System.currentTimeMillis()
            val inicio = Medicamentos.inicioCadena(ZonedDateTime.of(local, AvisosMedicamentos.zona()).toInstant().toEpochMilli(), propios)
            if (inicio == null) { r.finish(); return }   // ya respondida
            val p = Medicamentos.paso(inicio, ahora)
            if (p.cerrar) {
                val ruta = Medicamentos.rutaRegistro(tid, local, Medicamentos.SIN_RESPUESTA, 0)
                val oc = Medicamentos.Ocurrencia(local, ZonedDateTime.of(local, AvisosMedicamentos.zona()), evento)
                if (base.get(ruta) == null) base.put(ruta, Medicamentos.registro(Medicamentos.versionDe(t, oc.cuando.toInstant()), oc,
                    Medicamentos.SIN_RESPUESTA, Instant.ofEpochMilli(ahora), null, "cierre"))
                AvisosMedicamentos.quitarNotificacion(c, tid, local)
                runCatching { AvisosMedicamentos.reprogramar(c) }
                r.finish(); return
            }
            runCatching { AvisosMedicamentos.reprogramar(c) }   // deja programado el próximo aviso de esta cadena (y las demás)
            if (!p.sonar) { r.finish(); return }
            val datos = AvisosMedicamentos.datos(c, t, local, evento)
            val id = AvisosMedicamentos.idNotificacion(tid, local)
            if (Programador.puedeNotificar(c) && runCatching { c.startForegroundService(AlertaServicio.sonar(c, datos, id)) }.isSuccess) { r.finish(); return }
            Programador.mostrar(c, datos, id)
            Programador.tocar(c, Alertas.MEDICAMENTO) { r.finish() }
        } catch (_: Exception) { r.finish() }
    }
}

/** Botones Confirmar y Omitir de la notificación de una toma. */
class AccionMedicamento : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val tid = intent.getStringExtra(AvisosMedicamentos.EXTRA_TRATAMIENTO) ?: return
        val local = runCatching { LocalDateTime.parse(intent.getStringExtra(AvisosMedicamentos.EXTRA_LOCAL)) }.getOrNull() ?: return
        val estado = if (intent.action == AvisosMedicamentos.OMITIR) Medicamentos.OMITIDA else Medicamentos.CONFIRMADA
        runCatching { AvisosMedicamentos.responder(c, tid, local, intent.getStringExtra(AvisosMedicamentos.EXTRA_EVENTO), estado, null, "alerta") }
    }
}
