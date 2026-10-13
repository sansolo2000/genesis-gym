package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * M-07 — registro de cintura (formato del Evaluador #5, 13-oct). Lógica pura, sin Android, para probarla con JUnit.
 *  - 2 mediciones en cm con 1 decimal; si difieren en más de 1,0 cm se pide una 3.ª.
 *  - cintura_cm = promedio de TODAS las mediciones, redondeado a 2 decimales [E: QA #23 preguntó al Evaluador si con 3
 *    mediciones va el promedio de las 3 o de las 2 más cercanas; mientras no responda, las 3].
 *  - "Dato anterior": solo el promedio y cuántas mediciones lo formaron, sin valores individuales (ej.: 13-oct, 93,0, n = 2).
 *  - CSV semanal de sábado a viernes con las convenciones de los hc-… (";", coma decimal, UTF-8 con BOM, CRLF, -vN).
 * Documentos en la base (entran en el respaldo): cintura/<AAAA-MM-DD>_<HHMM>. Guardar con la misma fecha y hora reemplaza.
 * Los datos quedan solo en el celular y en el Drive de Héctor; nunca en el repositorio.
 */
object Cintura {
    const val COLECCION = "cintura"
    const val PROTOCOLO = "Huincha a la altura del ombligo, al final de una exhalación normal, sin apretar."
    val COLUMNAS = listOf("fecha", "hora", "cintura_cm", "n_mediciones", "medicion_1_cm", "medicion_2_cm", "medicion_3_cm", "ayunas", "nota")
    const val MIN = 40.0
    const val MAX = 200.0
    const val NOTA_MAX = 200

    data class Registro(val fecha: String, val hora: String, val cm: Double, val n: Int, val mediciones: List<Double>,
                        val ayunas: Boolean, val nota: String?)

    /** "93,5" o "93.5" → 93.5; null si no es un número con a lo más 1 decimal. */
    fun leerCm(s: String): Double? {
        val t = s.trim().replace(',', '.')
        if (!Regex("^\\d{1,3}(\\.\\d)?$").matches(t)) return null
        return t.toDouble()
    }

    private fun decimas(x: Double): Long = Math.round(x * 10)
    /** Más de 1,0 cm de diferencia (comparado en décimas para no depender del redondeo binario). */
    fun pideTercera(m1: Double, m2: Double): Boolean = Math.abs(decimas(m1) - decimas(m2)) > 10

    fun promedio(m: List<Double>): Double =
        BigDecimal(m.sumOf { decimas(it) }).divide(BigDecimal(m.size * 10), 2, RoundingMode.HALF_UP).toDouble()

    /** Revisa lo escrito en la pantalla y arma el registro, o devuelve los errores. */
    fun armar(fecha: String, hora: String, textos: List<String>, soloPromedio: Boolean, nTexto: String,
              ayunas: Boolean, nota: String, hoy: LocalDate): Pair<Registro?, List<String>> {
        val err = mutableListOf<String>()
        if (!Apoyo.fechaOk(fecha)) err.add("Fecha: usa AAAA-MM-DD.")
        else if (LocalDate.parse(fecha).isAfter(hoy)) err.add("Fecha: no puede ser futura.")
        if (!Apoyo.horaOk(hora)) err.add("Hora: usa HH:MM (24 horas).")
        if (nota.length > NOTA_MAX) err.add("Nota: máximo $NOTA_MAX caracteres.")
        fun cm(t: String, que: String): Double? {
            val v = leerCm(t)
            if (v == null) { err.add("$que: escribe los cm con a lo más 1 decimal (ej.: 93,5)."); return null }
            if (v < MIN || v > MAX) { err.add("$que: debe estar entre ${MIN.toInt()} y ${MAX.toInt()} cm."); return null }
            return v
        }
        val n: Int; val med: List<Double>; val prom: Double?
        if (soloPromedio) {
            val p = cm(textos.getOrElse(0) { "" }, "Promedio")
            val nn = nTexto.trim().toIntOrNull()
            if (nn == null || nn !in 1..3) err.add("Mediciones: escribe 1, 2 o 3.")
            n = nn ?: 0; med = emptyList(); prom = p
        } else {
            val m1 = cm(textos.getOrElse(0) { "" }, "Medición 1"); val m2 = cm(textos.getOrElse(1) { "" }, "Medición 2")
            val l = mutableListOf<Double>()
            if (m1 != null && m2 != null) {
                l.add(m1); l.add(m2)
                if (pideTercera(m1, m2)) {
                    val t3 = textos.getOrElse(2) { "" }
                    if (t3.isBlank()) err.add("Las 2 mediciones difieren en más de 1,0 cm: haz una 3.ª.")
                    else cm(t3, "Medición 3")?.let { l.add(it) }
                }
            }
            n = l.size; med = l; prom = if (l.size >= 2) promedio(l) else null
        }
        if (err.isNotEmpty() || prom == null) return null to err
        return Registro(fecha, hora, prom, n, med, ayunas, nota.trim().ifEmpty { null }) to emptyList()
    }

    fun ruta(r: Registro): String = "$COLECCION/${r.fecha}_${r.hora.replace(":", "")}"

    fun aJson(r: Registro, guardadoEn: String): JsonObject = Apoyo.objeto(
        "fecha" to JsonPrimitive(r.fecha), "hora" to JsonPrimitive(r.hora), "cintura_cm" to JsJson.primitivo(r.cm),
        "n_mediciones" to JsonPrimitive(r.n), "mediciones_cm" to JsonArray(r.mediciones.map { JsJson.primitivo(it) }),
        "ayunas" to JsonPrimitive(r.ayunas), "nota" to (r.nota?.let { JsonPrimitive(it) } ?: JsonNull), "guardado_en" to JsonPrimitive(guardadoEn))

    fun desdeJson(o: JsonObject): Registro? = runCatching {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun d(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        Registro(s("fecha")!!, s("hora")!!, d("cintura_cm")!!, d("n_mediciones")!!.toInt(),
            (o["mediciones_cm"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: emptyList(),
            (o["ayunas"] as? JsonPrimitive)?.content == "true", s("nota"))
    }.getOrNull()

    /** Registros de la semana sábado–viernes que empieza en [desde], ordenados por fecha y hora. */
    fun deSemana(todos: List<Registro>, desde: LocalDate): List<Registro> {
        val a = desde.toString(); val b = desde.plusDays(6).toString()
        return todos.filter { it.fecha in a..b }.sortedWith(compareBy({ it.fecha }, { it.hora }))
    }

    private fun campo(s: String): String = if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Coma decimal con al menos 1 decimal: 93 → "93,0", 93.25 → "93,25". Vacío si no hay dato. */
    fun cmTexto(v: Double?): String {
        if (v == null) return ""
        val r = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return (if (r.scale() < 1) r.setScale(1) else r).toPlainString().replace('.', ',')
    }

    fun csv(filas: List<Registro>): String = "﻿" + (listOf(COLUMNAS) + filas.map { r ->
        listOf(r.fecha, r.hora, cmTexto(r.cm), r.n.toString(),
            cmTexto(r.mediciones.getOrNull(0)), cmTexto(r.mediciones.getOrNull(1)), cmTexto(r.mediciones.getOrNull(2)),
            if (r.ayunas) "si" else "no", r.nota ?: "")
    }).joinToString("\r\n") { f -> f.joinToString(";") { campo(it) } } + "\r\n"

    fun nombre(perfil: String, desde: LocalDate, version: Int): String =
        "cintura-$perfil-$desde-${desde.plusDays(6)}" + (if (version > 1) "-v$version" else "") + ".csv"
}
