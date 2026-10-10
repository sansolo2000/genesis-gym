package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Lógica del gimnasio, traducida de las pantallas de la 1.0/2.0 (`index.html`): mismos documentos, mismos campos
 * y el mismo CSV para el Evaluador. Lógica pura (sin Android): la comprueban pruebas con resultados del código JS.
 */
object Gimnasio {
    val DIAS = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")
    val ORDEN_SEMANA = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    val DIA_LABEL = mapOf("lunes" to "Lunes", "martes" to "Martes", "miercoles" to "Miércoles", "jueves" to "Jueves",
        "viernes" to "Viernes", "sabado" to "Sábado", "domingo" to "Domingo")
    const val RUTINA_EJEMPLO = "ejemplo-formato"

    // ---------- lectura cómoda de JSON ----------
    fun JsonObject.txt(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()
    fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    fun JsonObject.esNulo(k: String): Boolean = this[k] == null || this[k] is JsonNull

    val ZONA: java.time.ZoneId = java.time.ZoneId.of("America/Santiago")
    fun hoyIso(): String = LocalDate.now(ZONA).toString()
    /** Instante como toISOString() de JavaScript: siempre con milisegundos y en UTC. */
    fun ahoraIso(): String = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())

    fun diaDe(fecha: String): String = DIAS[LocalDate.parse(fecha).dayOfWeek.value % 7]
    fun docIdSesion(fecha: String, sesionId: String) = "${fecha}_$sesionId"

    /** Número como lo imprime JavaScript con String(v). */
    private fun jsNum(e: JsonElement?): String = (e as? JsonPrimitive)?.let { JsJson.numero(it.content) } ?: ""

    /** Copia exacta de nuevoBorrador() de la 2.0: el registro de una sesión, con lo prescrito por la rutina. */
    fun nuevoBorrador(fecha: String, ses: JsonObject, rutinaArchivo: JsonObject, creadoIso: String): JsonObject {
        val r = rutinaArchivo.obj("rutina")!!
        val catalogo = rutinaArchivo.arr("ejercicios")
        return buildJsonObject {
            put("fecha", fecha); put("dia_semana", diaDe(fecha))
            put("sesion_id", ses.txt("id")); put("sesion_nombre", ses.txt("nombre"))
            put("rutina_id", r.txt("id")); put("rutina_version", r["version"]!!)
            put("estado", "en_curso")
            put("duracion_estimada_min", ses["duracion_estimada_min"]!!)
            put("ejercicios", buildJsonArray {
                for (x in ses.arr("ejercicios").sortedBy { it.num("orden") }) add(buildJsonObject {
                    val id = x.txt("ejercicio_id")!!
                    put("ejercicio_id", id)
                    put("nombre", catalogo.firstOrNull { it.txt("id") == id }?.txt("nombre") ?: id)
                    put("orden", x["orden"]!!); put("descanso_seg", x["descanso_seg"]!!)
                    put("notas", x.txt("notas") ?: "")
                    put("series", buildJsonArray {
                        for (se in x.arr("series")) add(buildJsonObject {
                            val modo = if (se.containsKey("duracion_seg")) "tiempo" else if (se.containsKey("reps")) "reps" else "rango"
                            put("n", se["n"]!!); put("tipo", se["tipo"]!!); put("modo", modo)
                            put("prescrito_reps", when (modo) {
                                "reps" -> JsonPrimitive(jsNum(se["reps"]))
                                "rango" -> JsonPrimitive("${jsNum(se["reps_min"])}–${jsNum(se["reps_max"])}")
                                else -> JsonNull
                            })
                            put("prescrito_seg", if (modo == "tiempo") se["duracion_seg"]!! else JsonNull)
                            put("carga_prescrita_kg", se["carga_kg"] ?: JsonNull)
                            put("carga_indicacion", se.txt("carga_indicacion")?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("rpe_objetivo", se["rpe_objetivo"]?.takeIf { it !is JsonNull } ?: JsonNull)
                            put("carga_real_kg", JsonNull); put("reps_reales", JsonNull); put("seg_reales", JsonNull)
                        })
                    })
                })
            })
            put("rpe_sesion", JsonNull); put("duracion_min", JsonNull); put("nota", "")
            put("creado", creadoIso); put("actualizado", JsonNull)
        }
    }

    /** Devuelve una copia del documento con un campo cambiado (los JsonObject son inmutables). */
    fun JsonObject.con(k: String, v: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).also { it[k] = v })

    /** Cambia un valor de una serie (carga_real_kg, reps_reales o seg_reales) del registro. */
    fun conValorSerie(b: JsonObject, iEj: Int, iSe: Int, clave: String, valor: Double?): JsonObject {
        val ejs = b.arr("ejercicios").toMutableList()
        val ej = ejs[iEj]
        val series = ej.arr("series").toMutableList()
        series[iSe] = series[iSe].con(clave, valor?.let { JsJson.primitivo(it) } ?: JsonNull)
        ejs[iEj] = ej.con("series", JsonArray(series))
        return b.con("ejercicios", JsonArray(ejs))
    }

    /** "= Indicado": copia lo prescrito a lo real, igual que la 2.0. */
    fun copiarIndicado(b: JsonObject, iEj: Int, iSe: Int): JsonObject {
        val se = b.arr("ejercicios")[iEj].arr("series")[iSe]
        var r = b
        if (!se.esNulo("carga_prescrita_kg")) r = conValorSerie(r, iEj, iSe, "carga_real_kg", se.num("carga_prescrita_kg"))
        if (se.txt("modo") == "reps") r = conValorSerie(r, iEj, iSe, "reps_reales", se.txt("prescrito_reps")?.toDoubleOrNull())
        if (se.txt("modo") == "tiempo") r = conValorSerie(r, iEj, iSe, "seg_reales", se.num("prescrito_seg"))
        return r
    }

    /**
     * 0.6.0: bloque de un ejercicio de la sesión, deducido de sus series (la rutina no trae bloques):
     * todas de calentamiento → "cal"; todas por tiempo → "elong" (elongación); el resto → "fuerza".
     */
    fun bloqueDe(ej: JsonObject): String {
        val se = ej.arr("series")
        return when {
            se.isNotEmpty() && se.all { it.txt("tipo") == "calentamiento" } -> "cal"
            se.isNotEmpty() && se.all { it.txt("modo") == "tiempo" } -> "elong"
            else -> "fuerza"
        }
    }
    fun ejercicioListo(ej: JsonObject) = ej.arr("series").all { registrada(it) }
    /** Primer ejercicio con alguna serie sin anotar; -1 si todo está anotado. */
    fun primerPendiente(b: JsonObject): Int = b.arr("ejercicios").indexOfFirst { !ejercicioListo(it) }

    fun registrada(se: JsonObject) = !se.esNulo("carga_real_kg") || !se.esNulo("reps_reales") || !se.esNulo("seg_reales")
    fun seriesRegistradas(s: JsonObject) = s.arr("ejercicios").sumOf { e -> e.arr("series").count { registrada(it) } }
    fun seriesTotales(s: JsonObject) = s.arr("ejercicios").sumOf { it.arr("series").size }

    /** Último registro anterior de la misma serie (sesiones ordenadas de la más nueva a la más antigua). */
    fun anterior(sesiones: List<JsonObject>, ejercicioId: String, n: Double, fecha: String): Pair<JsonObject, JsonObject>? {
        for (s in sesiones) {
            if ((s.txt("fecha") ?: "") >= fecha) continue
            val ej = s.arr("ejercicios").firstOrNull { it.txt("ejercicio_id") == ejercicioId }
            val se = ej?.arr("series")?.firstOrNull { it.num("n") == n }
            if (se != null && registrada(se)) return s to se
        }
        return null
    }

    /** Orden del historial de la 2.0: fecha descendente y, si empatan, la actualizada más reciente primero. */
    fun ordenarSesiones(s: List<JsonObject>): List<JsonObject> = s.filter { !it.txt("fecha").isNullOrEmpty() }
        .sortedWith(compareByDescending<JsonObject> { it.txt("fecha") }.thenByDescending { it.txt("actualizado") ?: "" })

    /** Texto de lo indicado, como prescritoTexto() de la 2.0 (decimales con coma). */
    fun prescritoTexto(se: JsonObject): String {
        val reps = if (se.txt("modo") == "tiempo") "${jsNum(se["prescrito_seg"])} s" else "${se.txt("prescrito_reps")} reps"
        return if (!se.esNulo("carga_prescrita_kg")) "$reps × ${kg(se["carga_prescrita_kg"])} kg" else reps
    }
    fun kg(e: JsonElement?): String = if (e == null || e is JsonNull) "" else if (e is JsonPrimitive && e.isString) e.content.replaceFirst('.', ',') else jsNum(e).replaceFirst('.', ',')

    /** "17,5" o "17.5" → 17.5; vacío → null; inválido → error (igual que parseNum de la 2.0). */
    sealed class Num { data class Valor(val v: Double?) : Num(); object Error : Num() }
    fun parseNum(txt: String, entero: Boolean): Num {
        val t = txt.trim().replaceFirst(',', '.')
        if (t.isEmpty()) return Num.Valor(null)
        val n = t.toDoubleOrNull() ?: return Num.Error
        if (!n.isFinite() || n < 0 || (entero && n != Math.floor(n))) return Num.Error
        return Num.Valor(n)
    }

    // ---------- CSV para el Evaluador (mismas 20 columnas y formato que la 2.0) ----------
    val COLUMNAS = listOf("fecha", "dia_semana", "sesion_id", "sesion_nombre", "rutina_id", "rutina_version", "estado_sesion", "ejercicio_id",
        "ejercicio_nombre", "serie_n", "serie_tipo", "carga_prescrita_kg", "carga_indicacion", "reps_prescritas", "carga_real_kg", "reps_reales",
        "seg_reales", "rpe_sesion", "duracion_sesion_min", "nota_sesion")

    private fun campo(v: JsonElement?): String {
        if (v == null || v is JsonNull) return ""
        var s = if (v is JsonPrimitive && !v.isString && v.content != "true" && v.content != "false") jsNum(v).replaceFirst('.', ',')
            else if (v is JsonPrimitive) v.content else JsJson.stringify(v)
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) s = "\"" + s.replace("\"", "\"\"") + "\""
        return s
    }

    fun csv(sesiones: List<JsonObject>): String {
        val filas = mutableListOf(COLUMNAS.joinToString(";"))
        for (s in sesiones.sortedWith { a, b -> (a.txt("fecha") ?: "").compareTo(b.txt("fecha") ?: "") }) {
            for (e in s.arr("ejercicios")) for (se in e.arr("series")) {
                val reps: JsonElement? = if (se.txt("modo") == "tiempo") JsonPrimitive("${jsNum(se["prescrito_seg"])} s") else se["prescrito_reps"]
                filas.add(listOf(s["fecha"], s["dia_semana"], s["sesion_id"], s["sesion_nombre"], s["rutina_id"], s["rutina_version"], s["estado"],
                    e["ejercicio_id"], e["nombre"], se["n"], se["tipo"], se["carga_prescrita_kg"], se["carga_indicacion"], reps,
                    se["carga_real_kg"], se["reps_reales"], se["seg_reales"], s["rpe_sesion"], s["duracion_min"], s["nota"]).joinToString(";") { campo(it) })
            }
        }
        return filas.joinToString("\r\n")
    }

    // ---------- importar (reglas extra de la 2.0 sobre el validador) ----------
    data class Importacion(val resultado: ValidadorRutina.Resultado, val yaActiva: Boolean, val rutina: JsonObject?)

    fun revisarImportacion(texto: String, activa: JsonObject?): Importacion {
        val base = ValidadorRutina.validarTexto(texto)
        if (!base.ok) return Importacion(base, false, null)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(texto) as JsonObject
        val nueva = obj.obj("rutina")!!
        val archivoActivo = activa?.obj("rutina")          // config/rutina_activa = { rutina: <archivo>, importada_en }
        val act = archivoActivo?.obj("rutina")
        val errores = base.errores.toMutableList(); val avisos = base.avisos.toMutableList()
        if (act != null && act.txt("id") != RUTINA_EJEMPLO && nueva.txt("id") != act.txt("id"))
            avisos.add(0, "Esta rutina (${nueva.txt("id")}) es distinta de la activa (${act.txt("id")}). ¿Es de otra persona? Cada celular debe tener solo la rutina de su dueño.")
        if (act != null && act.txt("id") == nueva.txt("id") && act.txt("id") != RUTINA_EJEMPLO) {
            if (JsJson.stringify(archivoActivo) == JsJson.stringify(obj)) return Importacion(ValidadorRutina.Resultado(false, errores, avisos), true, obj)
            if ((nueva.num("version") ?: 0.0) <= (act.num("version") ?: 0.0))
                errores.add("rutina.version: debe ser mayor que la versión activa (${jsNum(act["version"])}). Pide a Entrenamiento el archivo con la versión aumentada.")
        }
        return Importacion(ValidadorRutina.Resultado(errores.isEmpty(), errores, avisos), false, obj)
    }

    /** Documentos que escribe "Activar esta rutina": config/rutina_activa y rutinas/<id>_v<versión>. */
    fun documentosActivacion(rutinaArchivo: JsonObject, ahoraIso: String): List<Respaldo.Doc> {
        val ru = rutinaArchivo.obj("rutina")!!
        val data = buildJsonObject { put("rutina", rutinaArchivo); put("importada_en", ahoraIso) }
        return listOf(Respaldo.Doc("config/rutina_activa", data), Respaldo.Doc("rutinas/${ru.txt("id")}_v${jsNum(ru["version"])}", data))
    }

    // ---------- cambiar fecha (mismas reglas que la 2.0) ----------
    fun errorCambioFecha(nueva: String, hoy: String, b: JsonObject, sesiones: List<JsonObject>): String? {
        val valida = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(nueva) && runCatching { LocalDate.parse(nueva) }.isSuccess
        if (!valida) return "Elige una fecha válida."
        if (nueva > hoy) return "La fecha no puede ser futura."
        if (sesiones.any { it.txt("fecha") == nueva && it.txt("sesion_id") == b.txt("sesion_id") })
            return "Ya hay un registro de \"${b.txt("sesion_nombre")}\" el ${fechaCorta(nueva)}. Elimina ese registro o elige otra fecha."
        return null
    }
    fun conFechaCambiada(b: JsonObject, nueva: String, ahoraIso: String): JsonObject {
        val cambios = ((b["cambios_fecha"] as? JsonArray)?.toMutableList() ?: mutableListOf())
        cambios.add(buildJsonObject { put("de", b.txt("fecha")); put("a", nueva); put("en", ahoraIso) })
        return b.con("fecha", JsonPrimitive(nueva)).con("dia_semana", JsonPrimitive(diaDe(nueva)))
            .con("cambios_fecha", JsonArray(cambios)).con("actualizado", JsonPrimitive(ahoraIso))
    }

    private val MES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")
    private val DIA_CORTO = mapOf("lunes" to "lun", "martes" to "mar", "miercoles" to "mié", "jueves" to "jue", "viernes" to "vie", "sabado" to "sáb", "domingo" to "dom")
    fun fechaCorta(f: String): String = runCatching { val d = LocalDate.parse(f); "${DIA_CORTO[diaDe(f)]}, ${d.dayOfMonth} ${MES[d.monthValue - 1]}" }.getOrDefault(f)
    fun fechaLarga(f: String): String = runCatching {
        val d = LocalDate.parse(f)
        val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
        "${DIA_LABEL[diaDe(f)]}, ${d.dayOfMonth} de ${meses[d.monthValue - 1]}"
    }.getOrDefault(f)
}
