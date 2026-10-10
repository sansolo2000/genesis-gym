package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Validador del contrato FORMATO-RUTINA v1.0 — traducción a Kotlin de `validador-rutina.js` (1.0, md5 211363f4…).
 * Debe dar EXACTAMENTE los mismos errores y avisos, en el mismo orden: lo comprueban las pruebas con los casos
 * generados por el validador JS (`src/test/resources/validador-casos.json`).
 * Regla: nunca completa ni corrige datos. Cualquier error ⇒ rutina rechazada.
 */
object ValidadorRutina {
    data class Resultado(val ok: Boolean, val errores: List<String>, val avisos: List<String>)

    private val DIAS = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    private val SLUG = Regex("^[a-z0-9][a-z0-9-]{1,60}$")
    private val FECHA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** Valida el texto de un archivo. Si no es JSON, lo dice (la 1.0 lo hacía antes de llamar al validador). */
    fun validarTexto(texto: String): Resultado {
        val raiz = try { Json.parseToJsonElement(texto) } catch (e: Exception) {
            return Resultado(false, listOf("El archivo no es un JSON válido"), emptyList())
        }
        return validar(raiz)
    }

    // ---- ayudas que imitan las de JavaScript ----
    private fun esObj(v: JsonElement?) = v is JsonObject
    private fun esStr(v: JsonElement?) = v is JsonPrimitive && v.isString && v.content.trim().isNotEmpty()
    private fun num(v: JsonElement?): Double? =
        if (v is JsonPrimitive && !v.isString && v !is JsonNull) v.content.toDoubleOrNull()?.takeIf { it.isFinite() } else null
    private fun esInt(v: JsonElement?, min: Int, max: Int): Boolean {
        val d = num(v) ?: return false
        return d == Math.floor(d) && d >= min && d <= max
    }
    private fun esNum(v: JsonElement?, min: Double, max: Double): Boolean {
        val d = num(v) ?: return false
        return d >= min && d <= max
    }
    private fun esFecha(v: JsonElement?): Boolean {
        if (!(v is JsonPrimitive && v.isString && FECHA.matches(v.content))) return false
        return try { LocalDate.parse(v.content, FMT); true } catch (e: Exception) { false }
    }
    private fun esTexto(v: JsonElement?) = v is JsonPrimitive && v.isString
    private fun textoDe(v: JsonElement?): String? = if (v is JsonPrimitive && v.isString) v.content else null

    /** Cómo JavaScript convierte un valor a texto dentro de `${...}`. */
    private fun js(v: JsonElement?): String = when (v) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonObject -> "[object Object]"
        is JsonArray -> v.joinToString(",") { if (it is JsonNull) "" else js(it) }
        is JsonPrimitive -> if (v.isString) v.content else {
            val d = v.content.toDoubleOrNull()
            if (d != null && d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
            else if (d != null) d.toString() else v.content
        }
    }
    private fun verdadero(v: JsonElement?): Boolean = when (v) {
        null, is JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> if (v.isString) v.content.isNotEmpty()
            else v.content == "true" || (v.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false)
    }
    private fun ruta(base: String, v: JsonElement?): String {
        val id = (v as? JsonObject)?.get("id")
        return if (v != null && verdadero(v) && verdadero(id)) "$base (${js(id)})" else base
    }

    fun validar(r: JsonElement): Resultado {
        val e = mutableListOf<String>()
        val a = mutableListOf<String>()

        fun claves(o: JsonObject, ruta: String, req: List<String>, opt: List<String>) {
            for (k in req) if (!o.containsKey(k)) e.add("$ruta: falta el campo obligatorio \"$k\"")
            for (k in o.keys) if (k !in req && k !in opt) e.add("$ruta: campo no reconocido \"$k\" (¿error de escritura?)")
        }
        fun listaStr(v: JsonElement?, ruta: String, min: Int) {
            if (v !is JsonArray) { e.add("$ruta: debe ser una lista de textos"); return }
            if (v.size < min) e.add("$ruta: debe tener al menos $min elemento(s)")
            v.forEachIndexed { i, s -> if (!esStr(s)) e.add("$ruta[$i]: debe ser un texto no vacío") }
        }

        if (r !is JsonObject) return Resultado(false, listOf("El archivo no contiene un objeto JSON"), emptyList())
        claves(r, "raíz", listOf("formato", "version_formato", "rutina", "sesiones", "ejercicios"), emptyList())
        if (r.containsKey("formato") && textoDe(r["formato"]) != "genesis-rutina") e.add("raíz.formato: debe ser \"genesis-rutina\"")
        if (r.containsKey("version_formato") && textoDe(r["version_formato"]) != "1.0") e.add("raíz.version_formato: esta app acepta solo \"1.0\"")

        // rutina
        if (r.containsKey("rutina")) {
            val ru = r["rutina"]
            if (ru !is JsonObject) e.add("rutina: debe ser un objeto")
            else {
                claves(ru, "rutina", listOf("id", "nombre", "version", "vigente_desde", "autor"), listOf("notas"))
                if (ru.containsKey("id") && !(esTexto(ru["id"]) && SLUG.matches(textoDe(ru["id"])!!))) e.add("rutina.id: solo minúsculas, números y guiones (2–61 caracteres)")
                if (ru.containsKey("nombre") && !esStr(ru["nombre"])) e.add("rutina.nombre: texto no vacío")
                if (ru.containsKey("version") && !esInt(ru["version"], 1, 9999)) e.add("rutina.version: entero ≥ 1")
                if (ru.containsKey("vigente_desde") && !esFecha(ru["vigente_desde"])) e.add("rutina.vigente_desde: fecha válida AAAA-MM-DD")
                if (ru.containsKey("autor") && textoDe(ru["autor"]) != "Entrenamiento") e.add("rutina.autor: debe ser \"Entrenamiento\"")
                if (ru.containsKey("notas") && !esTexto(ru["notas"])) e.add("rutina.notas: debe ser texto")
            }
        }

        // ejercicios (catálogo)
        val idsEj = linkedSetOf<String>()
        if (r.containsKey("ejercicios")) {
            val lista = r["ejercicios"]
            if (lista !is JsonArray || lista.isEmpty()) e.add("ejercicios: lista con al menos 1 ejercicio")
            else lista.forEachIndexed { i, ej ->
                val p = ruta("ejercicios[$i]", ej)
                if (ej !is JsonObject) { e.add("$p: debe ser un objeto"); return@forEachIndexed }
                claves(ej, p, listOf("id", "nombre", "descripcion", "musculos_principales", "musculos_secundarios", "equipo", "pasos", "errores_comunes"), listOf("precauciones"))
                if (ej.containsKey("id")) {
                    val id = textoDe(ej["id"])
                    if (!(id != null && SLUG.matches(id))) e.add("$p.id: solo minúsculas, números y guiones")
                    else if (id in idsEj) e.add("$p.id: \"$id\" está repetido")
                    else idsEj.add(id)
                }
                for (k in listOf("nombre", "descripcion", "equipo")) if (ej.containsKey(k) && !esStr(ej[k])) e.add("$p.$k: texto no vacío")
                if (ej.containsKey("musculos_principales")) listaStr(ej["musculos_principales"], "$p.musculos_principales", 1)
                if (ej.containsKey("musculos_secundarios")) listaStr(ej["musculos_secundarios"], "$p.musculos_secundarios", 0)
                if (ej.containsKey("pasos")) listaStr(ej["pasos"], "$p.pasos", 2)
                if (ej.containsKey("errores_comunes")) listaStr(ej["errores_comunes"], "$p.errores_comunes", 1)
                if (ej.containsKey("precauciones")) listaStr(ej["precauciones"], "$p.precauciones", 0)
            }
        }

        // sesiones
        val usados = mutableSetOf<String>()
        if (r.containsKey("sesiones")) {
            val ses = r["sesiones"]
            if (ses !is JsonArray || ses.isEmpty()) e.add("sesiones: lista con al menos 1 sesión")
            else {
                val idsSes = mutableSetOf<String>(); val dias = mutableSetOf<String>()
                ses.forEachIndexed { i, s ->
                    val p = ruta("sesiones[$i]", s)
                    if (s !is JsonObject) { e.add("$p: debe ser un objeto"); return@forEachIndexed }
                    claves(s, p, listOf("id", "dia_semana", "nombre", "duracion_estimada_min", "ejercicios"), listOf("notas"))
                    if (s.containsKey("id")) {
                        val id = textoDe(s["id"])
                        if (!(id != null && SLUG.matches(id))) e.add("$p.id: solo minúsculas, números y guiones")
                        else if (id in idsSes) e.add("$p.id: \"$id\" está repetido") else idsSes.add(id)
                    }
                    if (s.containsKey("dia_semana")) {
                        val d = textoDe(s["dia_semana"])
                        if (d == null || d !in DIAS) e.add("$p.dia_semana: uno de ${DIAS.joinToString(", ")} (sin tildes)")
                        else if (d in dias) e.add("$p.dia_semana: \"$d\" tiene más de una sesión")
                        else dias.add(d)
                    }
                    if (s.containsKey("nombre") && !esStr(s["nombre"])) e.add("$p.nombre: texto no vacío")
                    if (s.containsKey("duracion_estimada_min") && !esInt(s["duracion_estimada_min"], 10, 180)) e.add("$p.duracion_estimada_min: entero entre 10 y 180")
                    if (s.containsKey("notas") && !esTexto(s["notas"])) e.add("$p.notas: debe ser texto")
                    if (!s.containsKey("ejercicios")) return@forEachIndexed
                    val ejs = s["ejercicios"]
                    if (ejs !is JsonArray || ejs.isEmpty()) { e.add("$p.ejercicios: al menos 1"); return@forEachIndexed }
                    val ordenes = mutableSetOf<Double>()
                    ejs.forEachIndexed { j, x ->
                        val q = "$p.ejercicios[$j]"
                        if (x !is JsonObject) { e.add("$q: debe ser un objeto"); return@forEachIndexed }
                        claves(x, q, listOf("ejercicio_id", "orden", "descanso_seg", "series"), listOf("notas"))
                        if (x.containsKey("ejercicio_id")) {
                            val id = textoDe(x["ejercicio_id"])
                            if (id == null || id !in idsEj) e.add("$q.ejercicio_id: \"${js(x["ejercicio_id"])}\" no existe en el catálogo \"ejercicios\"")
                            else usados.add(id)
                        }
                        if (x.containsKey("orden")) {
                            if (!esInt(x["orden"], 1, 50)) e.add("$q.orden: entero ≥ 1")
                            else if (num(x["orden"])!! in ordenes) e.add("$q.orden: ${js(x["orden"])} repetido en la sesión")
                            else ordenes.add(num(x["orden"])!!)
                        }
                        if (x.containsKey("descanso_seg") && !esInt(x["descanso_seg"], 0, 600)) e.add("$q.descanso_seg: entero entre 0 y 600")
                        if (x.containsKey("notas") && !esTexto(x["notas"])) e.add("$q.notas: debe ser texto")
                        if (!x.containsKey("series")) return@forEachIndexed
                        val series = x["series"]
                        if (series !is JsonArray || series.isEmpty() || series.size > 10) { e.add("$q.series: entre 1 y 10 series"); return@forEachIndexed }
                        series.forEachIndexed { k, se ->
                            val t = "$q.series[$k]"
                            if (se !is JsonObject) { e.add("$t: debe ser un objeto"); return@forEachIndexed }
                            claves(se, t, listOf("n", "tipo", "carga_kg"), listOf("reps", "reps_min", "reps_max", "duracion_seg", "carga_indicacion", "rpe_objetivo"))
                            if (se.containsKey("n") && num(se["n"]) != (k + 1).toDouble()) e.add("$t.n: debe ser ${k + 1} (numeración correlativa desde 1)")
                            if (se.containsKey("tipo") && textoDe(se["tipo"]) !in listOf("calentamiento", "efectiva")) e.add("$t.tipo: \"calentamiento\" o \"efectiva\"")
                            val modos = listOf(se.containsKey("reps"), se.containsKey("reps_min") || se.containsKey("reps_max"), se.containsKey("duracion_seg")).count { it }
                            if (modos != 1) e.add("$t: indicar exactamente UNA de estas opciones: \"reps\", o \"reps_min\"+\"reps_max\", o \"duracion_seg\"")
                            if (se.containsKey("reps") && !esInt(se["reps"], 1, 100)) e.add("$t.reps: entero entre 1 y 100")
                            if (se.containsKey("reps_min") || se.containsKey("reps_max")) {
                                if (!esInt(se["reps_min"], 1, 100) || !esInt(se["reps_max"], 1, 100) || num(se["reps_min"])!! >= num(se["reps_max"])!!)
                                    e.add("$t: \"reps_min\" y \"reps_max\" enteros (1–100) con reps_min < reps_max")
                            }
                            if (se.containsKey("duracion_seg") && !esInt(se["duracion_seg"], 5, 600)) e.add("$t.duracion_seg: entero entre 5 y 600")
                            if (se.containsKey("carga_kg")) {
                                if (se["carga_kg"] is JsonNull) {
                                    if (!esStr(se["carga_indicacion"])) e.add("$t: si \"carga_kg\" es null, \"carga_indicacion\" es obligatoria (p. ej. \"peso corporal\")")
                                } else if (!esNum(se["carga_kg"], 0.0, 500.0)) e.add("$t.carga_kg: número entre 0 y 500, o null")
                            }
                            if (se.containsKey("carga_indicacion") && !esStr(se["carga_indicacion"])) e.add("$t.carga_indicacion: texto no vacío")
                            if (se.containsKey("rpe_objetivo") && !esNum(se["rpe_objetivo"], 1.0, 10.0)) e.add("$t.rpe_objetivo: número entre 1 y 10")
                        }
                    }
                }
                if (!listOf("lunes", "miercoles", "viernes").all { it in dias }) a.add("No hay sesión para todos los días de fuerza del plan (lunes, miércoles y viernes)")
            }
        }
        for (id in idsEj) if (id !in usados) a.add("El ejercicio \"$id\" está en el catálogo pero ninguna sesión lo usa")
        return Resultado(e.isEmpty(), e, a)
    }
}
