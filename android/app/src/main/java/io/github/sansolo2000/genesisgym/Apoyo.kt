package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Módulo Apoyo psicológico — lógica pura (sin Android), según `claude/CONTEXTO-MODULO-APOYO.md` versión 2 del contrato.
 * Formatos: `programa-apoyo` 1.0 (se importa), `reporte-diario` 1.0 y `resumen-semanal` 1.0 (se envían a Drive).
 * La app registra, calcula y muestra los textos del programa TAL CUAL. No aconseja ni interpreta.
 *
 * Documentos en la base (entran en el respaldo `genesis-respaldo`):
 *   programas_apoyo/activo       { programa: <archivo importado>, importado_en }
 *   programas_apoyo/<id>         la misma copia, como historial
 *   reportes_apoyo/<AAAA-MM-DD>  el reporte-diario tal como se envía, más "enviado" (lo que ya subió a Drive)
 *   resumenes_apoyo/<viernes>    el resumen-semanal, más "enviado"
 */
object Apoyo {
    const val CARPETA = "Genesis Gym - reportes apoyo"
    val IDS_CONOCIDOS = listOf("energia", "hambre", "animo", "palabra", "sueno_h", "agua", "alarma", "novedad", "nota")
    val OBLIGATORIAS = listOf("energia", "hambre", "animo", "agua", "alarma")
    val TIPOS_PREGUNTA = listOf("escala", "numero", "si_no", "texto")
    val PLAN = listOf("fuerza", "mtb", "descanso")
    val HECHO = listOf("fuerza", "mtb", "descanso", "otro", "nada")
    val DIAS_PLAN = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    val ESTADOS_COMIDA = listOf("indicado", "otro", "no-comi")
    val MARCADORES = setOf("racha", "mejor_racha")

    data class Resultado(val ok: Boolean, val errores: List<String>, val avisos: List<String>, val yaActivo: Boolean = false)

    // ---------- lectura cómoda ----------
    internal fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
    internal fun JsonObject?.a(k: String): JsonArray? = this?.get(k) as? JsonArray
    internal fun JsonObject?.s(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    internal fun JsonObject?.n(k: String): Double? = (this?.get(k) as? JsonPrimitive)?.takeIf { !it.isString && it.content != "true" && it.content != "false" }?.content?.toDoubleOrNull()
    internal fun JsonObject?.b(k: String): Boolean? = (this?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { if (it == "true") true else if (it == "false") false else null }
    fun objeto(vararg pares: Pair<String, JsonElement?>): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().also { m -> for ((k, v) in pares) m[k] = v ?: JsonNull })
    fun txt(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    fun num(d: Double?): JsonElement = if (d == null) JsonNull else JsJson.primitivo(d)
    fun num(i: Int?): JsonElement = if (i == null) JsonNull else JsonPrimitive(i)

    private val FECHA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val HORA = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    fun fechaOk(s: String?): Boolean = s != null && FECHA.matches(s) && runCatching { LocalDate.parse(s) }.isSuccess
    fun horaOk(s: String?): Boolean = s != null && HORA.matches(s)
    private fun etqVal(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else JsJson.numero(d.toString())

    // ---------- §8 validación del programa ----------
    fun validarPrograma(e: JsonElement?): Resultado {
        val err = mutableListOf<String>(); val av = mutableListOf<String>()
        val o = e as? JsonObject ?: return Resultado(false, listOf("El archivo no es un objeto JSON."), av)
        if (o.s("formato") != "programa-apoyo") err.add("formato: debe ser \"programa-apoyo\".")
        if (o.s("version_formato") != "1.0") err.add("version_formato: debe ser \"1.0\".")
        val p = o.o("programa")
        if (p == null) err.add("programa: falta.") else {
            if (p.s("id").isNullOrBlank()) err.add("programa.id: falta.")
            val v = p.n("version"); if (v == null || v != Math.floor(v)) err.add("programa.version: debe ser un número entero.")
            if (!fechaOk(p.s("vigente_desde"))) err.add("programa.vigente_desde: fecha inválida (AAAA-MM-DD).")
            val z = p.s("zona_horaria")
            if (z != null && runCatching { ZoneId.of(z) }.isFailure) err.add("programa.zona_horaria: \"$z\" no es una zona horaria válida.")
        }
        val perfil = o["perfil"]
        val perfilOk = when (perfil) {
            is JsonObject -> !perfil.s("id").isNullOrBlank()
            is JsonArray -> perfil.size == 1 && !(perfil[0] as? JsonObject).s("id").isNullOrBlank()
            else -> false
        }
        if (!perfilOk) err.add("perfil: debe haber exactamente un perfil, con id.")
        val sem = o.o("semana")
        if (sem != null && sem.s("inicio") != null && sem.s("inicio") != "sabado") err.add("semana.inicio: debe ser \"sabado\".")
        val h = o.o("horario")
        if (!horaOk(h.s("reporte"))) err.add("horario.reporte: hora inválida (HH:MM).")
        if (!horaOk(h.s("plazo_dia_siguiente"))) err.add("horario.plazo_dia_siguiente: hora inválida (HH:MM).")
        val plan = o.o("plan_semanal")
        for (d in DIAS_PLAN) { val v = plan.s(d); if (v == null || v !in PLAN) err.add("plan_semanal.$d: debe ser fuerza, mtb o descanso.") }
        val preguntas = o.a("preguntas")?.map { it as? JsonObject } ?: emptyList()
        if (preguntas.isEmpty()) err.add("preguntas: no hay ninguna.")
        val ids = mutableSetOf<String>()
        preguntas.forEachIndexed { i, q ->
            val r = "preguntas[$i]"
            if (q == null) { err.add("$r: no es un objeto."); return@forEachIndexed }
            val id = q.s("id")
            if (id.isNullOrBlank()) err.add("$r.id: falta.")
            else { if (!ids.add(id)) err.add("$r.id: \"$id\" está repetido."); if (id !in IDS_CONOCIDOS) av.add("La pregunta \"$id\" no es conocida: se mostrará y se guardará en \"extra\", sin cálculos.") }
            val tipo = q.s("tipo")
            if (tipo !in TIPOS_PREGUNTA) err.add("$r.tipo: debe ser escala, numero, si_no o texto.")
            if (q.s("texto").isNullOrBlank()) err.add("$r.texto: falta.")
            if (q.b("obligatoria") == null) err.add("$r.obligatoria: debe ser true o false.")
            if (tipo == "escala") {
                val mn = q.n("min"); val mx = q.n("max")
                if (mn == null || mx == null || mn != Math.floor(mn) || mx != Math.floor(mx) || mn >= mx) err.add("$r: una escala necesita min y max enteros, con min < max.")
                else { val et = q.o("etiquetas"); for (k in mn.toInt()..mx.toInt()) if (et.s("$k").isNullOrBlank()) err.add("$r.etiquetas: falta la etiqueta del valor $k.") }
            }
            if (tipo == "numero") {
                val mn = q.n("min"); val mx = q.n("max")
                if (mn != null && mx != null && mn > mx) err.add("$r: min es mayor que max.")
                val paso = q.n("paso"); if (paso != null && paso <= 0) err.add("$r.paso: debe ser mayor que 0.")
            }
            if (tipo == "texto") { val m = q.n("max_caracteres"); if (m != null && (m < 1 || m != Math.floor(m))) err.add("$r.max_caracteres: debe ser un entero positivo.") }
            if (id in listOf("agua", "alarma") && tipo != null && tipo != "si_no") err.add("$r: \"$id\" debe ser de tipo si_no.")
            if (id in listOf("energia", "hambre", "animo") && tipo != null && tipo != "escala") err.add("$r: \"$id\" debe ser de tipo escala.")
        }
        for (id in OBLIGATORIAS) if (id !in ids) err.add("preguntas: falta \"$id\", que usan las reglas.")
        val textos = o.o("textos")
        if (textos == null) err.add("textos: falta.")
        val com = o.o("comidas")
        val cch = com.a("cuenta_como_hecha")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        if (com == null) err.add("comidas: falta.")
        else {
            if (com.n("total") != 5.0) err.add("comidas.total: debe ser 5.")
            if (cch.isEmpty()) err.add("comidas.cuenta_como_hecha: falta.")
            for (x in cch) if (x !in ESTADOS_COMIDA) err.add("comidas.cuenta_como_hecha: \"$x\" no es un estado de registro-comida (indicado, otro, no-comi).")
        }
        val dm = o.o("dia_minimo")
        if (dm == null || dm.n("comidas_hechas") == null) err.add("dia_minimo.comidas_hechas: falta.")
        o.a("alertas")?.forEachIndexed { i, x ->
            val al = x as? JsonObject
            val r = "alertas[$i]"
            if (al == null) { err.add("$r: no es un objeto."); return@forEachIndexed }
            if (al.s("id").isNullOrBlank()) err.add("$r.id: falta.")
            if (al.s("campo") !in ids) err.add("$r.campo: \"${al.s("campo")}\" no es una pregunta del programa.")
            if (al.s("operador") !in listOf("<=", ">=")) err.add("$r.operador: debe ser <= o >=.")
            if (al.n("valor") == null) err.add("$r.valor: falta.")
            val ds = al.n("dias_seguidos"); if (ds == null || ds < 1 || ds != Math.floor(ds)) err.add("$r.dias_seguidos: debe ser un entero positivo.")
            if (textos.s(al.s("texto") ?: "") == null) err.add("$r.texto: \"${al.s("texto")}\" no existe en textos.")
        }
        val ai = o.o("alarma_inmediata")
        if (ai != null) {
            if (ai.s("campo") !in ids) err.add("alarma_inmediata.campo: \"${ai.s("campo")}\" no es una pregunta del programa.")
            if (textos.s(ai.s("texto") ?: "") == null) err.add("alarma_inmediata.texto: \"${ai.s("texto")}\" no existe en textos.")
        }
        val bar = o.a("barreras")?.mapNotNull { it as? JsonObject } ?: emptyList()
        if (bar.isEmpty()) err.add("barreras: no hay ninguna.")
        bar.forEachIndexed { i, b -> if (b.s("id").isNullOrBlank() || b.s("etiqueta").isNullOrBlank()) err.add("barreras[$i]: falta id o etiqueta.") }
        textos?.forEach { (k, v) ->
            val t = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (t == null) { err.add("textos.$k: debe ser un texto."); return@forEach }
            for (m in Regex("\\{([^}]*)\\}").findAll(t)) if (m.groupValues[1] !in MARCADORES) av.add("textos.$k: el marcador {${m.groupValues[1]}} no es conocido; se mostrará tal cual.")
        }
        Frases.validar(o, err, av)   // M-06, §8
        return Resultado(err.isEmpty(), err, av)
    }

    fun revisarTexto(texto: String, activo: JsonObject?): Pair<JsonObject?, Resultado> {
        if (texto.isBlank()) return null to Resultado(false, listOf("El archivo llegó vacío al celular."), emptyList())
        val obj = try { Json.parseToJsonElement(texto.removePrefix("﻿")) } catch (e: Exception) {
            return null to Resultado(false, listOf("El texto no es JSON válido."), emptyList())
        }
        val r = validarPrograma(obj)
        if (r.ok && activo != null && JsJson.stringify(activo) == JsJson.stringify(obj)) return obj as JsonObject to r.copy(ok = false, yaActivo = true)
        return (obj as? JsonObject) to r
    }

    fun documentosActivacion(obj: JsonObject, ahora: String): List<Respaldo.Doc> {
        val d = objeto("programa" to obj, "importado_en" to JsonPrimitive(ahora))
        return listOf(Respaldo.Doc("programas_apoyo/activo", d), Respaldo.Doc("programas_apoyo/${obj.o("programa").s("id")}", d))
    }

    /** Reemplaza {racha} y {mejor_racha}; un marcador desconocido queda tal cual. */
    fun rellenar(t: String, racha: Int, mejor: Int): String = t.replace("{racha}", racha.toString()).replace("{mejor_racha}", mejor.toString())

    // ---------- fechas ----------
    fun sabadoDe(f: String): String = LocalDate.parse(f).let { d -> d.minusDays(((d.dayOfWeek.value + 1) % 7).toLong()) }.toString()   // sábado que abre la semana
    fun viernesDe(f: String): String = LocalDate.parse(sabadoDe(f)).plusDays(6).toString()
    fun dia(f: String, n: Long): String = LocalDate.parse(f).plusDays(n).toString()
    fun diaPlan(f: String): String = DIAS_PLAN[LocalDate.parse(f).dayOfWeek.value - 1]
    fun esViernes(f: String): Boolean = LocalDate.parse(f).dayOfWeek.value == 5
    val ISO_ZONA: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
}

/**
 * El programa activo, los reportes y los resúmenes, con los cálculos del contrato (§4).
 * Cada operación devuelve los documentos que hay que escribir en la base.
 */
class EstadoApoyo(val programa: JsonObject?, reportes: List<Respaldo.Doc> = emptyList(), resumenes: List<Respaldo.Doc> = emptyList()) {
    val reportes = sortedMapOf<String, JsonObject>()
    val resumenes = sortedMapOf<String, JsonObject>()
    init {
        for (d in reportes) this.reportes[d.ruta.removePrefix("reportes_apoyo/")] = d.data
        for (d in resumenes) this.resumenes[d.ruta.removePrefix("resumenes_apoyo/")] = d.data
    }

    private fun p() = programa
    private fun info(): JsonObject? = with(Apoyo) { p().o("programa") }
    val perfilId: String get() = with(Apoyo) {
        when (val x = p()?.get("perfil")) { is JsonObject -> x.s("id"); is JsonArray -> (x.firstOrNull() as? JsonObject).s("id"); else -> null } ?: "sin-perfil"
    }
    val zona: ZoneId get() = with(Apoyo) { info().s("zona_horaria")?.let { runCatching { ZoneId.of(it) }.getOrNull() } } ?: Gimnasio.ZONA
    fun texto(id: String?): String? = with(Apoyo) { if (id == null) null else p().o("textos").s(id) }
    fun preguntas(): List<JsonObject> = with(Apoyo) { p().a("preguntas")?.mapNotNull { it as? JsonObject } ?: emptyList() }
    fun pregunta(id: String): JsonObject? = with(Apoyo) { preguntas().firstOrNull { it.s("id") == id } }
    fun cuentaComoHecha(): List<String> = with(Apoyo) { p().o("comidas").a("cuenta_como_hecha")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("indicado", "otro") }
    fun comidasMinimas(): Int = with(Apoyo) { p().o("dia_minimo").n("comidas_hechas")?.toInt() ?: 5 }
    fun aguaMinima(): Boolean = with(Apoyo) { p().o("dia_minimo").b("agua") ?: true }
    fun planDe(f: String): String = with(Apoyo) { p().o("plan_semanal").s(Apoyo.diaPlan(f)) ?: "descanso" }
    fun barreras(): List<Pair<String, String>> = with(Apoyo) { p().a("barreras")?.mapNotNull { it as? JsonObject }?.map { (it.s("id") ?: "") to (it.s("etiqueta") ?: "") } ?: emptyList() }

    // ---------- plazo ----------
    private fun hora(k: String, defecto: String): LocalTime = with(Apoyo) { p().o("horario").s(k) ?: defecto }.let { LocalTime.parse(it) }
    fun horaReporte(): String = with(Apoyo) { p().o("horario").s("reporte") } ?: "21:00"
    /** El reporte del día D se puede guardar (y sumar a la racha) hasta las 12:00 del día D+1. */
    fun plazo(f: String): ZonedDateTime = ZonedDateTime.of(LocalDate.parse(f).plusDays(1), hora("plazo_dia_siguiente", "12:00"), zona)
    fun dentroDelPlazo(f: String, ahora: ZonedDateTime): Boolean = !ahora.isAfter(plazo(f))
    fun hoy(ahora: ZonedDateTime): String = ahora.withZoneSameInstant(zona).toLocalDate().toString()

    // ---------- §4.2 día mínimo y racha ----------
    fun esDiaMinimo(r: JsonObject?): Boolean = with(Apoyo) {
        if (r == null || r.b("tardio") == true) return false
        val hechas = r.o("comidas").n("hechas")?.toInt() ?: 0
        hechas >= comidasMinimas() && (!aguaMinima() || r.b("agua") == true)
    }
    /** Días mínimos consecutivos que terminan en f (0 si f no fue día mínimo). */
    fun rachaHasta(f: String): Int { var n = 0; var d = f; while (esDiaMinimo(reportes[d])) { n++; d = Apoyo.dia(d, -1) }; return n }
    /**
     * Racha actual a esta hora. Hoy cuenta apenas se guarda como día mínimo. Un día sin reporte corta la racha solo
     * cuando vence su plazo: mientras tanto, la racha llega hasta el día anterior.
     */
    fun rachaActual(ahora: ZonedDateTime): Int {
        val hoy = hoy(ahora)
        if (esDiaMinimo(reportes[hoy])) return rachaHasta(hoy)
        val ayer = Apoyo.dia(hoy, -1)
        if (esDiaMinimo(reportes[ayer])) return rachaHasta(ayer)
        if (reportes[ayer] == null && dentroDelPlazo(ayer, ahora)) return rachaHasta(Apoyo.dia(ayer, -1))
        return 0
    }
    fun mejorRacha(): Int { var mejor = 0; for (f in reportes.keys) mejor = maxOf(mejor, rachaHasta(f)); return mejor }
    /** "Ayer fuera": el día anterior ya no puede ser día mínimo (se guardó sin serlo, o venció su plazo sin reporte). */
    fun ayerFuera(f: String, ahora: ZonedDateTime): Boolean {
        val ayer = Apoyo.dia(f, -1)
        val r = reportes[ayer]
        return if (r != null) !esDiaMinimo(r) else !dentroDelPlazo(ayer, ahora) && reportes.keys.any { it < ayer }
    }
    /** La racha se cortó: ayer quedó fuera y anteayer era parte de una racha. */
    fun rachaReinicio(f: String, ahora: ZonedDateTime): Boolean = ayerFuera(f, ahora) && esDiaMinimo(reportes[Apoyo.dia(f, -2)])

    // ---------- §4.3 alertas ----------
    private fun valorDe(r: JsonObject?, campo: String): JsonElement? = r?.get(campo) ?: with(Apoyo) { r.o("extra")?.get(campo) }
    private fun cumple(r: JsonObject?, campo: String, op: String, valor: Double): Boolean {
        val v = (valorDe(r, campo) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return false
        return if (op == "<=") v <= valor else v >= valor
    }
    /** Ids de las alertas activas el día f, con los reportes guardados (o con [propio] como reporte de f). */
    fun alertasActivas(f: String, propio: JsonObject? = null): List<String> = with(Apoyo) {
        val res = mutableListOf<String>()
        for (al in p().a("alertas")?.mapNotNull { it as? JsonObject } ?: emptyList()) {
            val campo = al.s("campo") ?: continue; val op = al.s("operador") ?: continue; val valor = al.n("valor") ?: continue
            val dias = al.n("dias_seguidos")?.toInt() ?: continue
            val ok = (0 until dias).all { k -> val d = dia(f, -k.toLong()); cumple(if (k == 0 && propio != null) propio else reportes[d], campo, op, valor) }
            if (ok) res.add(al.s("id") ?: campo)
        }
        res
    }
    fun textoAlerta(id: String): String? = with(Apoyo) { p().a("alertas")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.s("id") == id }?.s("texto")?.let { texto(it) } }
    fun alarmaActiva(r: JsonObject?): Boolean = with(Apoyo) {
        val ai = p().o("alarma_inmediata") ?: return false
        val campo = ai.s("campo") ?: return false
        val esperado = ai["valor"] ?: JsonPrimitive(true)
        valorDe(r, campo)?.let { JsJson.stringify(it) == JsJson.stringify(esperado) } ?: false
    }
    fun textoAlarma(): String? = with(Apoyo) { texto(p().o("alarma_inmediata").s("texto")) }

    // ---------- reto ----------
    /** El reto vigente para el día f: el reto_siguiente del último resumen cerrado antes de f. */
    fun retoVigente(f: String): String? = with(Apoyo) { resumenes.filterKeys { it < f }.values.lastOrNull()?.s("reto_siguiente")?.takeIf { it.isNotBlank() } }

    // ---------- §6 reporte-diario ----------
    data class Comidas5(val detalle: Map<String, String?>, val origen: String)
    data class Entreno(val planificado: String, val hecho: String, val minutos: Int?, val rpe: Int?, val sesion: String?, val origen: String)

    /** Precarga de comidas desde la pestaña Comidas (solo si hay programa de alimentación con ese día). */
    fun comidasDesdeApp(comidas: EstadoComidas?, f: String): Comidas5? {
        if (comidas?.programa == null || comidas.diaDe(f) == null) return null
        return Comidas5(Comidas.TIPOS.associateWith { comidas.estadoDe(Comidas.clave(f, it)) }, "app")
    }
    /** Precarga del entrenamiento: las sesiones registradas ese día o, si no hay, el plan semanal. */
    fun entrenoPrecargado(f: String, sesiones: List<JsonObject>): Entreno = with(Apoyo) {
        val del = sesiones.filter { it.s("fecha") == f }
        val plan = planDe(f)
        if (del.isNotEmpty()) {
            val min = del.mapNotNull { it.n("duracion_min") }.takeIf { it.isNotEmpty() }?.sum()?.toInt()
            val rpe = del.mapNotNull { it.n("rpe_sesion") }.maxOrNull()?.toInt()
            return Entreno(plan, "fuerza", min, rpe, del.mapNotNull { it.s("sesion_nombre") ?: it.s("sesion_id") }.joinToString(" + ").ifEmpty { null }, "app")
        }
        Entreno(plan, plan, null, null, null, "manual")
    }

    fun version(r: JsonObject?): Int = with(Apoyo) { r.n("version")?.toInt() ?: 0 }

    /**
     * Arma el reporte del día f con las respuestas. Devuelve el documento a guardar, o los errores (§8) si no se puede.
     * [respuestas]: id de pregunta → valor (Int para escalas, Double para números, Boolean para si_no, String para texto; null = sin respuesta).
     */
    fun armarReporte(f: String, respuestas: Map<String, Any?>, comidas: Comidas5, entreno: Entreno, ahora: ZonedDateTime): Pair<JsonObject?, List<String>> = with(Apoyo) {
        val errores = mutableListOf<String>()
        val hoy = hoy(ahora)
        if (f > hoy) errores.add("No se puede guardar el reporte de un día futuro.")
        val previo = reportes[f]
        if (previo != null && !dentroDelPlazo(f, ahora)) errores.add("El plazo de este reporte venció (${plazo(f).toLocalTime()} del día siguiente): ya no se puede cambiar.")
        val vals = LinkedHashMap<String, JsonElement>()
        val extra = LinkedHashMap<String, JsonElement>()
        for (q in preguntas()) {
            val id = q.s("id") ?: continue
            val tipo = q.s("tipo"); val nombre = q.s("texto") ?: id
            val v = respuestas[id].let { if (it is String && it.isBlank()) null else it }
            val el: JsonElement = when {
                v == null -> { if (q.b("obligatoria") == true) errores.add("Falta responder: $nombre"); JsonNull }
                tipo == "escala" -> {
                    val x = (v as? Number)?.toDouble()
                    val mn = q.n("min") ?: 1.0; val mx = q.n("max") ?: 5.0
                    if (x == null || x != Math.floor(x) || x < mn || x > mx) { errores.add("$nombre: debe ser un valor entre ${etq(mn)} y ${etq(mx)}."); JsonNull } else JsonPrimitive(x.toInt())
                }
                tipo == "numero" -> {
                    val x = (v as? Number)?.toDouble() ?: (v as? String)?.replace(',', '.')?.toDoubleOrNull()
                    val mn = q.n("min"); val mx = q.n("max"); val paso = q.n("paso") ?: if (id == "sueno_h") 0.5 else null
                    when {
                        x == null -> { errores.add("$nombre: no es un número."); JsonNull }
                        (mn != null && x < mn) || (mx != null && x > mx) -> { errores.add("$nombre: debe estar entre ${etq(mn ?: x)} y ${etq(mx ?: x)}."); JsonNull }
                        paso != null && Math.abs(x / paso - Math.rint(x / paso)) > 1e-9 -> { errores.add("$nombre: debe ir de ${etq(paso).replace('.', ',')} en ${etq(paso).replace('.', ',')}."); JsonNull }
                        else -> JsJson.primitivo(x)
                    }
                }
                tipo == "si_no" -> if (v is Boolean) JsonPrimitive(v) else { errores.add("$nombre: responde sí o no."); JsonNull }
                else -> {
                    val s = v.toString().trim(); val max = q.n("max_caracteres")?.toInt()
                    if (max != null && s.length > max) { errores.add("$nombre: máximo $max caracteres."); JsonNull } else JsonPrimitive(s)
                }
            }
            if (id in IDS_CONOCIDOS) vals[id] = el else extra[id] = el
        }
        if (entreno.hecho !in HECHO) errores.add("Entrenamiento: elige qué hiciste.")
        if (entreno.rpe != null && entreno.rpe !in 1..10) errores.add("RPE: debe ir de 1 a 10.")
        if (entreno.minutos != null && entreno.minutos !in 0..600) errores.add("Minutos de entrenamiento: entre 0 y 600.")
        if (errores.isNotEmpty()) return null to errores

        val cch = cuentaComoHecha()
        val hechas = Comidas.TIPOS.count { comidas.detalle[it] in cch }
        val tardio = previo?.b("tardio") ?: !dentroDelPlazo(f, ahora)
        val ver = version(previo) + 1
        fun v(id: String) = vals[id] ?: JsonNull
        val base = objeto(
            "formato" to JsonPrimitive("reporte-diario"), "version_formato" to JsonPrimitive("1.0"),
            "perfil" to JsonPrimitive(perfilId), "fecha" to JsonPrimitive(f),
            "registrado_en" to JsonPrimitive(ahora.withZoneSameInstant(zona).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(ISO_ZONA)),
            "version" to JsonPrimitive(ver), "tardio" to JsonPrimitive(tardio),
            "programa" to objeto("id" to info()?.get("id"), "version" to info()?.get("version")),
            "energia" to v("energia"), "hambre" to v("hambre"), "animo" to v("animo"),
            "palabra" to v("palabra"), "sueno_h" to v("sueno_h"), "agua" to v("agua"), "alarma" to v("alarma"),
            "comidas" to objeto("hechas" to JsonPrimitive(hechas), "de" to JsonPrimitive(5),
                "detalle" to objeto(*Comidas.TIPOS.map { it to txt(comidas.detalle[it]) }.toTypedArray()), "origen" to JsonPrimitive(comidas.origen)),
            "entrenamiento" to objeto("planificado" to JsonPrimitive(entreno.planificado), "hecho" to JsonPrimitive(entreno.hecho),
                "minutos" to num(entreno.minutos), "rpe" to num(entreno.rpe), "sesion" to txt(entreno.sesion), "origen" to JsonPrimitive(entreno.origen)),
            "novedad" to v("novedad"), "nota" to v("nota")
        )
        // Cálculos con este reporte como si ya estuviera guardado.
        val antes = reportes[f]
        reportes[f] = base
        val alertas = alertasActivas(f)
        val racha = rachaHasta(f); val mejor = mejorRacha()
        if (antes == null) reportes.remove(f) else reportes[f] = antes
        val completo = JsonObject(LinkedHashMap(base).also {
            it["calculado"] = objeto("dia_minimo" to JsonPrimitive(esDiaMinimo(base)), "racha" to JsonPrimitive(racha), "mejor_racha" to JsonPrimitive(mejor),
                "alertas_activas" to JsonArray(alertas.map { a -> JsonPrimitive(a) }), "alarma_mostrada" to JsonPrimitive(alarmaActiva(base)))
            it["reto_vigente"] = txt(retoVigente(f))
            it["extra"] = JsonObject(extra)
            // Lo ya enviado se conserva: así se sabe que la próxima subida es una corrección (-vN).
            (antes?.get("enviado"))?.let { e -> it["enviado"] = e }
        })
        completo to emptyList()
    }

    private fun etq(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else JsJson.numero(d.toString())

    fun guardar(f: String, r: JsonObject): Respaldo.Doc { reportes[f] = r; return Respaldo.Doc("reportes_apoyo/$f", r) }

    /** Textos que se muestran al guardar (en orden): resultado, alertas y alarma. */
    fun mensajesAlGuardar(r: JsonObject): List<Pair<String, String>> = with(Apoyo) {
        val out = mutableListOf<Pair<String, String>>()
        val calc = r.o("calculado")
        val racha = calc.n("racha")?.toInt() ?: 0; val mejor = calc.n("mejor_racha")?.toInt() ?: 0
        if (alarmaActiva(r)) textoAlarma()?.let { out.add("alarma" to it) }
        when {
            r.b("tardio") == true -> texto("reporte_tardio")?.let { out.add("info" to it) }
            calc.b("dia_minimo") == true -> texto("reporte_dia_minimo")?.let { out.add("ok" to rellenar(it, racha, mejor)) }
            else -> texto("reporte_no_minimo")?.let { out.add("info" to rellenar(it, racha, mejor)) }
        }
        for (a in calc.a("alertas_activas")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()) textoAlerta(a)?.let { out.add("alerta" to rellenar(it, racha, mejor)) }
        out
    }

    /**
     * Ajuste 3: comidas marcadas después del reporte. Hasta el plazo, si el reporte tomó las comidas de la app,
     * se recalcula con las comidas nuevas y queda una versión nueva (se enviará como -vN). Después del plazo no cambia.
     */
    fun sincronizarComidas(f: String, comidas: EstadoComidas?, ahora: ZonedDateTime): Respaldo.Doc? = with(Apoyo) {
        val r = reportes[f] ?: return null
        if (r.o("comidas").s("origen") != "app" || !dentroDelPlazo(f, ahora)) return null
        val nuevas = comidasDesdeApp(comidas, f) ?: return null
        val actuales = Comidas.TIPOS.associateWith { r.o("comidas").o("detalle").s(it) }
        if (actuales == nuevas.detalle) return null
        val resp = respuestasDe(r)
        val e = r.o("entrenamiento")
        val ent = Entreno(e.s("planificado") ?: planDe(f), e.s("hecho") ?: "nada", e.n("minutos")?.toInt(), e.n("rpe")?.toInt(), e.s("sesion"), e.s("origen") ?: "manual")
        val (nuevo, err) = armarReporte(f, resp, nuevas, ent, ahora)
        if (nuevo == null || err.isNotEmpty()) return null
        guardar(f, nuevo)
    }

    /** Las respuestas de un reporte guardado, para editarlo. */
    fun respuestasDe(r: JsonObject?): Map<String, Any?> = with(Apoyo) {
        val m = LinkedHashMap<String, Any?>()
        if (r == null) return m
        for (q in preguntas()) {
            val id = q.s("id") ?: continue
            val e = (r[id] ?: r.o("extra")?.get(id)) as? JsonPrimitive ?: continue
            m[id] = when (q.s("tipo")) {
                "escala" -> e.content.toDoubleOrNull()?.toInt()
                "numero" -> e.content.toDoubleOrNull()
                "si_no" -> if (e.content == "true") true else if (e.content == "false") false else null
                else -> if (e.isString) e.content else null
            }
        }
        m
    }

    // ---------- envío a Drive ----------
    private fun enviadoVersion(r: JsonObject): Int? = with(Apoyo) { r.o("enviado").n("version")?.toInt() }
    fun pendienteEnvio(r: JsonObject): Boolean = enviadoVersion(r) != version(r)
    fun pendientes(): List<Pair<String, String>> =
        reportes.filterValues { pendienteEnvio(it) }.keys.map { "reporte" to it } + resumenes.filterValues { pendienteEnvio(it) }.keys.map { "resumen" to it }

    /** Nombre del archivo en Drive: el primero sin sufijo; una corrección ya enviada antes lleva -vN. */
    fun nombreArchivo(tipo: String, f: String): String {
        val r = (if (tipo == "reporte") reportes[f] else resumenes[f])!!
        val base = "$tipo-$perfilId-$f"
        return if (r["enviado"] is JsonObject) "$base-v${version(r)}.json" else "$base.json"
    }
    /** El JSON que se sube: el documento sin la marca interna "enviado". */
    fun textoEnvio(tipo: String, f: String): String {
        val r = (if (tipo == "reporte") reportes[f] else resumenes[f])!!
        return JsJson.indentado(JsonObject(LinkedHashMap(r).also { it.remove("enviado") }))
    }
    fun marcarEnviado(tipo: String, f: String, id: String, ahoraIso: String): Respaldo.Doc {
        val col = if (tipo == "reporte") reportes else resumenes
        val r = col[f]!!
        val nuevo = JsonObject(LinkedHashMap(r).also { it["enviado"] = Apoyo.objeto("version" to r["version"], "id" to JsonPrimitive(id), "en" to JsonPrimitive(ahoraIso)) })
        col[f] = nuevo
        return Respaldo.Doc("${if (tipo == "reporte") "reportes_apoyo" else "resumenes_apoyo"}/$f", nuevo)
    }

    // ---------- §7 resumen-semanal ----------
    /** El resumen del viernes f se puede generar después de guardar el reporte de ese viernes. */
    fun puedeResumir(viernes: String): Boolean = Apoyo.esViernes(viernes) && reportes[viernes] != null

    data class Cierre(val cumplido: String?, val barrera: String?, val detalle: String?, val funciono: String?, val retoSiguiente: String?)

    fun armarResumen(viernes: String, c: Cierre, ahora: ZonedDateTime): Pair<JsonObject?, List<String>> = with(Apoyo) {
        val errores = mutableListOf<String>()
        if (!puedeResumir(viernes)) errores.add("El resumen se genera desde el viernes, después de guardar su reporte.")
        val desde = sabadoDe(viernes)
        val reto = retoVigente(desde)
        val cumplido = if (reto == null) "sin-reto" else c.cumplido
        if (reto != null && cumplido !in listOf("si", "parcial", "no")) errores.add("Marca si cumpliste el reto de la semana.")
        if (c.barrera == null || c.barrera !in barreras().map { it.first }) errores.add("Elige la barrera que más te costó.")
        if (c.retoSiguiente.isNullOrBlank()) errores.add("Escribe el reto de la próxima semana.")
        if (errores.isNotEmpty()) return null to errores

        val dias = (0..6).map { dia(desde, it.toLong()) }
        val rs = dias.mapNotNull { d -> reportes[d]?.let { d to it } }
        val cch = cuentaComoHecha()
        var hechas = 0; var noComi = 0; var sinMarcar = 0
        for (d in dias) {
            val det = reportes[d].o("comidas").o("detalle")
            if (det == null) { sinMarcar += 5; continue }
            for (t in Comidas.TIPOS) { val e = det.s(t); if (e in cch) hechas++ else if (e == "no-comi") noComi++ else if (e == null) sinMarcar++ }
        }
        fun prom(k: String): Pair<JsonElement, Int> {
            val v = rs.mapNotNull { it.second.n(k) }
            return (if (v.isEmpty()) JsonNull else JsJson.primitivo(Math.round(v.average() * 10) / 10.0)) to v.size
        }
        val (pe, ne) = prom("energia"); val (ph, nh) = prom("hambre"); val (pa, na) = prom("animo")
        // Rangos de cada alerta dentro de la semana.
        val rangos = mutableListOf<JsonObject>()
        val abiertas = LinkedHashMap<String, Pair<String, String>>()
        for (d in dias) {
            val act = reportes[d].o("calculado").a("alertas_activas")?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
            for (id in abiertas.keys.toList()) if (id !in act) { val (a, b) = abiertas.remove(id)!!; rangos.add(objeto("id" to JsonPrimitive(id), "desde" to JsonPrimitive(a), "hasta" to JsonPrimitive(b))) }
            for (id in act) abiertas[id] = (abiertas[id]?.first ?: d) to d
        }
        for ((id, ab) in abiertas) rangos.add(objeto("id" to JsonPrimitive(id), "desde" to JsonPrimitive(ab.first), "hasta" to JsonPrimitive(ab.second)))
        val previo = resumenes[viernes]
        val limite = ZonedDateTime.of(LocalDate.parse(viernes).plusDays(1), hora("plazo_dia_siguiente", "12:00"), zona)
        val r = objeto(
            "formato" to JsonPrimitive("resumen-semanal"), "version_formato" to JsonPrimitive("1.0"),
            "perfil" to JsonPrimitive(perfilId), "desde" to JsonPrimitive(desde), "hasta" to JsonPrimitive(viernes),
            "registrado_en" to JsonPrimitive(ahora.withZoneSameInstant(zona).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(ISO_ZONA)),
            "version" to JsonPrimitive(version(previo) + 1),
            "parcial" to JsonPrimitive(rs.size < 7), "tardio" to JsonPrimitive(ahora.isAfter(limite)),
            "programa" to objeto("id" to info()?.get("id"), "version" to info()?.get("version")),
            "dias_reportados" to JsonPrimitive(rs.size), "de" to JsonPrimitive(7),
            "dias_tardios" to JsonPrimitive(rs.count { it.second.b("tardio") == true }),
            "dias_minimos" to JsonPrimitive(rs.count { esDiaMinimo(it.second) }),
            "racha" to objeto("actual" to JsonPrimitive(rachaHasta(viernes)), "mejor" to JsonPrimitive(mejorRacha())),
            "comidas" to objeto("hechas" to JsonPrimitive(hechas), "de" to JsonPrimitive(35), "no_comi" to JsonPrimitive(noComi), "sin_marcar" to JsonPrimitive(sinMarcar)),
            "agua_dias" to JsonPrimitive(rs.count { it.second.b("agua") == true }),
            "promedios" to objeto("energia" to pe, "hambre" to ph, "animo" to pa,
                "n" to objeto("energia" to JsonPrimitive(ne), "hambre" to JsonPrimitive(nh), "animo" to JsonPrimitive(na))),
            "dias_bajos" to objeto(
                "energia_le2" to JsonPrimitive(rs.count { (it.second.n("energia") ?: 9.0) <= 2 }),
                "animo_le2" to JsonPrimitive(rs.count { (it.second.n("animo") ?: 9.0) <= 2 }),
                "hambre_ge4" to JsonPrimitive(rs.count { (it.second.n("hambre") ?: 0.0) >= 4 })),
            "alertas" to JsonArray(rangos),
            "alarmas" to JsonArray(rs.filter { alarmaActiva(it.second) }.map { JsonPrimitive(it.first) }),
            "novedades" to JsonArray(rs.mapNotNull { (d, x) -> x.s("novedad")?.takeIf { it.isNotBlank() }?.let { objeto("fecha" to JsonPrimitive(d), "texto" to JsonPrimitive(it)) } }),
            "reto" to objeto("texto" to txt(reto), "cumplido" to JsonPrimitive(cumplido!!)),
            "barrera" to objeto("id" to JsonPrimitive(c.barrera!!), "detalle" to txt(c.detalle?.trim()?.ifEmpty { null })),
            "lo_que_funciono" to txt(c.funciono?.trim()?.ifEmpty { null }),
            "reto_siguiente" to JsonPrimitive(c.retoSiguiente!!.trim())
        )
        val completo = if (previo?.get("enviado") != null) JsonObject(LinkedHashMap(r).also { it["enviado"] = previo["enviado"]!! }) else r
        completo to emptyList()
    }

    fun guardarResumen(viernes: String, r: JsonObject): Respaldo.Doc { resumenes[viernes] = r; return Respaldo.Doc("resumenes_apoyo/$viernes", r) }
}
