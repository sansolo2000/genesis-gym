package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Etapa E5 — qué avisos reales tocan en los próximos días (lógica pura, sin Android, para probarla con JUnit).
 * Salen del programa de alimentación activo (con los intercambios), de la rutina activa y de lo que Héctor configuró.
 * No se avisa una comida que ya está marcada ni un entrenamiento que ya está registrado.
 */
object Recordatorios {
    data class Config(
        val comidas: Boolean = false,
        val minutosAntes: Int = 0,
        val tareas: Boolean = false,
        val entrenamiento: Boolean = false,
        val horaEntrenamiento: String = "",
        val reporte: Boolean = false,
        val horaReporte: String = "21:00"
    )

    // ---------- M-04 (12-oct): Avisos y sonido dentro del respaldo (documento config/avisos) ----------
    data class Respaldados(val config: Config, val sonido: String?, val alarma: Boolean, val volumen: Int)

    fun avisosAJson(r: Respaldados): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("comidas", JsonPrimitive(r.config.comidas)); put("minutos_antes", JsonPrimitive(r.config.minutosAntes))
        put("tareas", JsonPrimitive(r.config.tareas)); put("entrenamiento", JsonPrimitive(r.config.entrenamiento))
        put("hora_entrenamiento", JsonPrimitive(r.config.horaEntrenamiento)); put("reporte", JsonPrimitive(r.config.reporte))
        put("hora_reporte", JsonPrimitive(r.config.horaReporte))
        put("sonido", r.sonido?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("sonido_alarma", JsonPrimitive(r.alarma)); put("volumen", JsonPrimitive(r.volumen))
    }

    /** Lee config/avisos; si falta un campo o viene mal, usa el valor por defecto de Avisos. */
    fun avisosDesdeJson(o: JsonObject): Respaldados {
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: d
        fun i(k: String, d: Int) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt() ?: d
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val d = Config()
        return Respaldados(
            Config(b("comidas", d.comidas), i("minutos_antes", d.minutosAntes).coerceIn(0, 120), b("tareas", d.tareas), b("entrenamiento", d.entrenamiento),
                s("hora_entrenamiento") ?: d.horaEntrenamiento, b("reporte", d.reporte), s("hora_reporte")?.takeIf { horaValida(it) } ?: d.horaReporte),
            s("sonido"), b("sonido_alarma", false), i("volumen", 70).coerceIn(0, 100)
        )
    }

    /**
     * Avisos toma la hora del reporte del programa de apoyo (acordado con Apoyo #5) como hora inicial:
     * se aplica si el programa nuevo trae una hora distinta de la del anterior (o no había anterior),
     * para no pisar un cambio que Héctor haya hecho a mano en Avisos.
     */
    fun horaReporteAAplicar(nueva: String?, anterior: String?): String? =
        nueva?.takeIf { horaValida(it) && it != anterior }

    data class Recordatorio(val cuando: ZonedDateTime, val categoria: String, val titulo: String, val texto: String, val vista: String)

    /** Un aviso del celular: los recordatorios del mismo minuto se juntan en una sola notificación. */
    data class Aviso(val cuando: ZonedDateTime, val titulo: String, val texto: String, val vista: String)

    const val COMIDA = "comida"; const val TAREA = "tarea"; const val ENTRENAMIENTO = "entrenamiento"; const val REPORTE = "reporte"
    const val TEXTO_REPORTE = "Un minuto para tu reporte y cierras el día."
    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    fun horaValida(h: String) = HHMM.matches(h.trim())
    private fun hora(h: String): LocalTime? = HHMM.matchEntire(h.trim())?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    /**
     * @param sesionesRutina (dia_semana, id, nombre) de cada sesión de la rutina activa.
     * @param registradas claves "fecha_sesionId" de las sesiones ya guardadas.
     * @param reportados fechas (AAAA-MM-DD) con el reporte de Apoyo ya guardado.
     */
    fun calcular(
        cfg: Config, comidas: EstadoComidas?, sesionesRutina: List<Triple<String, String, String>>, registradas: Set<String>,
        ahora: ZonedDateTime, dias: Int = 7,
        reportados: Set<String> = emptySet(), textoReporte: String = TEXTO_REPORTE
    ): List<Recordatorio> {
        val zona = ahora.zone
        val lista = mutableListOf<Recordatorio>()
        val hoy = ahora.toLocalDate()
        for (n in 0 until dias) {
            val fecha: LocalDate = hoy.plusDays(n.toLong()); val f = fecha.toString()
            fun en(h: String): ZonedDateTime? = hora(h)?.let { ZonedDateTime.of(fecha, it, zona) }
            val dia = comidas?.diaDe(f)
            if (cfg.comidas && comidas != null && dia != null) for (t in Comidas.TIPOS) {
                val c = comidas.comidaDe(f, t) ?: continue
                if (comidas.estadoDe(Comidas.clave(f, t)) != null) continue
                val h = (c["hora"] as? JsonPrimitive)?.content ?: continue
                val cuando = en(h)?.minusMinutes(cfg.minutosAntes.toLong()) ?: continue
                val pr = comidas.preparaciones()[(c["preparacion"] as? JsonPrimitive)?.content ?: ""] as? JsonObject
                val nombre = (pr?.get("nombre") as? JsonPrimitive)?.content ?: (c["preparacion"] as? JsonPrimitive)?.content ?: ""
                val cons = (c["conservacion"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                lista.add(Recordatorio(cuando, COMIDA, "${Comidas.ETQ[t]} · $h", nombre + (cons?.let { " · desde el $it" } ?: ""), "comidas"))
            }
            if (cfg.tareas && dia != null) for (t in (dia["tareas"] as? JsonArray) ?: emptyList()) {
                t as? JsonObject ?: continue
                val h = (t["hora"] as? JsonPrimitive)?.content ?: continue
                val cuando = en(h) ?: continue
                lista.add(Recordatorio(cuando, TAREA, "Tarea de cocina · $h", (t["texto"] as? JsonPrimitive)?.content ?: "", "comidas"))
            }
            if (cfg.entrenamiento) {
                val cuando = en(cfg.horaEntrenamiento)
                // D-01 (QA): si ese día ya hay CUALQUIER sesión registrada (por ejemplo, la A movida a un miércoles), no se avisa.
                val yaEntreno = registradas.any { it.startsWith("${f}_") }
                // M-08 (Héctor, Coordinador #83): tampoco si la sesión del día ya se registró antes en esa semana (desde el lunes).
                val lunes = fecha.minusDays((fecha.dayOfWeek.value - 1).toLong()).toString()
                fun hechaEnLaSemana(id: String) = registradas.any { it.length > 11 && it.substring(0, 10) in lunes..f && it.substring(11) == id }
                if (cuando != null && !yaEntreno) for ((ds, id, nombre) in sesionesRutina)
                    if (ds == Gimnasio.diaDe(f) && !hechaEnLaSemana(id))
                        lista.add(Recordatorio(cuando, ENTRENAMIENTO, "Entrenamiento · ${cfg.horaEntrenamiento.trim()}", nombre, "hoy"))
            }
            // Módulo Apoyo (contrato v2 §3.6): no avisa si el reporte de ese día ya está guardado; el texto viene del programa.
            if (cfg.reporte && f !in reportados) en(cfg.horaReporte)?.let { lista.add(Recordatorio(it, REPORTE, "Reporte del día · ${cfg.horaReporte.trim()}", textoReporte, "reporte")) }
        }
        return lista.filter { it.cuando.isAfter(ahora) }.sortedBy { it.cuando.toInstant() }
    }

    fun juntar(lista: List<Recordatorio>): List<Aviso> = lista.groupBy { it.cuando.toInstant() }.values.map { g ->
        if (g.size == 1) Aviso(g[0].cuando, g[0].titulo, g[0].texto, g[0].vista)
        else Aviso(g[0].cuando, "${g.size} avisos · ${g[0].cuando.format(DateTimeFormatter.ofPattern("HH:mm"))}",
            g.joinToString("\n") { "${it.titulo}: ${it.texto}" }, if (g.any { it.vista == "comidas" }) "comidas" else if (g.any { it.vista == "reporte" }) "reporte" else "hoy")
    }

    private val formato = DateTimeFormatter.ofPattern("EEE d HH:mm", java.util.Locale.forLanguageTag("es-CL"))
    fun etiqueta(a: Aviso, zona: ZoneId): String = "${a.cuando.withZoneSameInstant(zona).format(formato)} · ${a.titulo.substringBefore(" · ")}"
}
