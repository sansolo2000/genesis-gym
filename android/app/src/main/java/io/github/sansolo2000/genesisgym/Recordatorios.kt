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
     */
    fun calcular(
        cfg: Config, comidas: EstadoComidas?, sesionesRutina: List<Triple<String, String, String>>, registradas: Set<String>,
        ahora: ZonedDateTime, dias: Int = 7
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
                if (cuando != null) for ((ds, id, nombre) in sesionesRutina)
                    if (ds == Gimnasio.diaDe(f) && "${f}_$id" !in registradas)
                        lista.add(Recordatorio(cuando, ENTRENAMIENTO, "Entrenamiento · ${cfg.horaEntrenamiento.trim()}", nombre, "hoy"))
            }
            if (cfg.reporte) en(cfg.horaReporte)?.let { lista.add(Recordatorio(it, REPORTE, "Reporte del día · ${cfg.horaReporte.trim()}", TEXTO_REPORTE, "hoy")) }
        }
        return lista.filter { it.cuando.isAfter(ahora) }.sortedBy { it.cuando.toInstant() }
    }

    fun juntar(lista: List<Recordatorio>): List<Aviso> = lista.groupBy { it.cuando.toInstant() }.values.map { g ->
        if (g.size == 1) Aviso(g[0].cuando, g[0].titulo, g[0].texto, g[0].vista)
        else Aviso(g[0].cuando, "${g.size} avisos · ${g[0].cuando.format(DateTimeFormatter.ofPattern("HH:mm"))}",
            g.joinToString("\n") { "${it.titulo}: ${it.texto}" }, if (g.any { it.vista == "comidas" }) "comidas" else "hoy")
    }

    private val formato = DateTimeFormatter.ofPattern("EEE d HH:mm", java.util.Locale.forLanguageTag("es-CL"))
    fun etiqueta(a: Aviso, zona: ZoneId): String = "${a.cuando.withZoneSameInstant(zona).format(formato)} · ${a.titulo.substringBefore(" · ")}"
}
