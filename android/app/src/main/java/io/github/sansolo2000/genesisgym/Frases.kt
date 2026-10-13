package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Apoyo.a
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * M-06 — frase del día en Hoy (`CONTEXTO-MODULO-APOYO.md` §5.1, decisiones de Héctor del 13-oct).
 * Tipo del día D = plan_semanal[día de D] (lo planificado, no lo registrado).
 * k = días entre `desde` (incluido) y D (excluido) con el mismo tipo; frase = lista[tipo][k mod largo]; si D < desde, k = 0.
 * Sin el campo o con la lista vacía: no hay frase (no es error). Se muestra tal cual. Lógica pura, sin Android.
 */
object Frases {
    private val DIAS = Apoyo.DIAS_PLAN   // lunes … domingo, igual que DayOfWeek.value - 1

    fun tipoDe(programa: JsonObject, d: LocalDate): String? = programa.o("plan_semanal").s(DIAS[d.dayOfWeek.value - 1])

    private fun lista(programa: JsonObject, tipo: String): List<String> =
        programa.o("frases_diarias").a(tipo)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    fun delDia(programa: JsonObject?, d: LocalDate): String? {
        val fr = programa?.o("frases_diarias") ?: return null
        val tipo = tipoDe(programa, d) ?: return null
        val l = lista(programa, tipo)
        if (l.isEmpty()) return null
        val desde = fr.s("desde")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        var k = 0L
        if (d.isAfter(desde)) {
            val total = ChronoUnit.DAYS.between(desde, d)
            // Cada semana completa aporta los mismos días de cada tipo; el resto se cuenta día a día.
            val semanas = total / 7
            val porSemana = DIAS.count { programa.o("plan_semanal").s(it) == tipo }.toLong()
            k = semanas * porSemana
            var x = desde.plusDays(semanas * 7)
            while (x.isBefore(d)) { if (tipoDe(programa, x) == tipo) k++; x = x.plusDays(1) }
        }
        return l[(k % l.size).toInt()]
    }

    /** Zona del programa (zona_horaria) para decidir el día; si falta o no es válida, la de Chile. */
    fun zona(programa: JsonObject?): ZoneId =
        programa?.o("programa").s("zona_horaria")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Gimnasio.ZONA

    val ETIQUETA = mapOf("fuerza" to "Fuerza", "mtb" to "MTB", "descanso" to "Descanso")

    /** (tipo, frase) de hoy en la zona del programa; null si no hay frase. */
    fun deHoy(programa: JsonObject?): Pair<String, String>? {
        val d = LocalDate.now(zona(programa))
        val f = delDia(programa, d) ?: return null
        return (tipoDe(programa!!, d) ?: "") to f
    }

    /** §8: errores (no importa) y advertencias del campo opcional. */
    fun validar(programa: JsonObject, err: MutableList<String>, av: MutableList<String>) {
        val fr = programa["frases_diarias"] ?: return
        if (fr !is JsonObject) { err.add("frases_diarias: debe ser un objeto."); return }
        if (fr.s("rotacion") != "por_tipo_dia") err.add("frases_diarias.rotacion: debe ser \"por_tipo_dia\".")
        if (!Apoyo.fechaOk(fr.s("desde"))) err.add("frases_diarias.desde: debe ser una fecha AAAA-MM-DD válida.")
        for (tipo in Apoyo.PLAN) {
            val v = fr[tipo] ?: continue
            if (v !is kotlinx.serialization.json.JsonArray) { err.add("frases_diarias.$tipo: debe ser una lista de textos."); continue }
            v.forEachIndexed { i, x ->
                val t = (x as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (t == null || t.isEmpty() || t.length > 140) err.add("frases_diarias.$tipo[$i]: debe ser un texto de 1 a 140 caracteres.")
                else if (t.contains('{') || t.contains('}')) av.add("frases_diarias.$tipo[$i]: tiene { o }; se mostrará tal cual.")
            }
        }
        val usados = DIAS.mapNotNull { programa.o("plan_semanal").s(it) }.toSet()
        for (tipo in usados) if ((fr[tipo] as? kotlinx.serialization.json.JsonArray).isNullOrEmpty())
            av.add("frases_diarias: el plan semanal tiene días de $tipo y no hay frases para ese tipo; esos días Hoy no muestra frase.")
    }
}
