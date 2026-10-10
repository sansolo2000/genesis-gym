package io.github.sansolo2000.genesisgym

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Etapa E1 — lógica pura de los avisos de prueba (sin Android, para probarla con JUnit).
 * Un aviso de prueba guarda cuándo se programó para sonar, con qué modo, y cuándo llegó de verdad.
 * No contiene datos de salud.
 */
data class Aviso(
    val id: Int,
    val programadoMs: Long,
    val modo: String,          // "exacto" o "inexacto"
    val llegoMs: Long? = null
) {
    fun aTexto(): String = "$id|$programadoMs|$modo|${llegoMs ?: ""}"

    companion object {
        fun deTexto(t: String): Aviso? {
            val p = t.split("|")
            if (p.size != 4) return null
            return Aviso(
                id = p[0].toIntOrNull() ?: return null,
                programadoMs = p[1].toLongOrNull() ?: return null,
                modo = p[2],
                llegoMs = p[3].toLongOrNull()
            )
        }
    }
}

object Avisos {
    const val MODO_EXACTO = "exacto"
    const val MODO_INEXACTO = "inexacto"

    private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val formatoHora = DateTimeFormatter.ofPattern("EEE d HH:mm:ss", java.util.Locale.forLanguageTag("es-CL"))

    /** "HH:MM" → la próxima vez que llega esa hora (hoy si aún no pasa, si no mañana). null si el texto no es válido. */
    fun proximaOcurrencia(hhmm: String, ahora: ZonedDateTime): ZonedDateTime? {
        val m = HHMM.matchEntire(hhmm.trim()) ?: return null
        val hora = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        var t = ahora.with(hora).withSecond(0).withNano(0)
        if (!t.isAfter(ahora)) t = t.plusDays(1)
        return t
    }

    /** Hora sugerida: ahora + minutos, en formato "HH:MM". */
    fun sugerida(ahora: ZonedDateTime, minutos: Long): String =
        ahora.plusMinutes(minutos).format(DateTimeFormatter.ofPattern("HH:mm"))

    fun hora(ms: Long, zona: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zona).format(formatoHora)

    /** Atraso en palabras: "a la hora", "+2 min 5 s", "−3 s" (si llegó antes). */
    fun atraso(programadoMs: Long, llegoMs: Long): String {
        val d = (llegoMs - programadoMs) / 1000
        val signo = if (d < 0) "−" else "+"
        val a = kotlin.math.abs(d)
        return when {
            a < 5 -> "a la hora"
            a < 60 -> "$signo$a s"
            else -> "$signo${a / 60} min ${a % 60} s"
        }
    }

    fun estado(a: Aviso, zona: ZoneId): String {
        val base = "Aviso ${a.id} · ${a.modo} · programado ${hora(a.programadoMs, zona)}"
        return if (a.llegoMs == null) "$base · pendiente"
        else "$base · llegó ${hora(a.llegoMs, zona)} (${atraso(a.programadoMs, a.llegoMs)})"
    }
}
