package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * Etapa E5 — módulo Comidas, traducción a Kotlin de `alimentacion.js` (2.0).
 * Sin Android: lo prueban las pruebas JUnit contra lo que hace la 2.0 con los mismos pasos
 * (`src/test/resources/prueba-comidas-escenario.json` y `prueba-programa-casos.json`, con datos inventados).
 * La app muestra los números del programa tal cual: no calcula calorías nuevas ni corrige el menú.
 */
object Comidas {
    val TIPOS = listOf("desayuno", "colacion_am", "almuerzo", "colacion_pm", "cena")
    val ETQ = mapOf("desayuno" to "Desayuno", "colacion_am" to "Colación AM", "almuerzo" to "Almuerzo", "colacion_pm" to "Colación PM", "cena" to "Cena")
    val ESTADOS = listOf("indicado" to "Comí lo indicado", "otro" to "Comí otra cosa o parcial", "no-comi" to "No comí")
    const val CARPETA = "Genesis Gym - registro comidas"

    data class Resultado(val ok: Boolean, val errores: List<String>, val avisos: List<String>, val yaActivo: Boolean = false)

    // ---------- cómo se comporta JavaScript con valores sueltos ----------
    internal fun js(v: JsonElement?): String = when (v) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonObject -> "[object Object]"
        is JsonArray -> v.joinToString(",") { if (it is JsonNull) "" else js(it) }
        is JsonPrimitive -> if (v.isString) v.content else numeroJs(v.content)
    }
    private fun numeroJs(literal: String): String = if (literal == "true" || literal == "false") literal else JsJson.numero(literal)
    internal fun verdadero(v: JsonElement?): Boolean = when (v) {
        null, is JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> if (v.isString) v.content.isNotEmpty()
            else v.content == "true" || (v.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false)
    }
    private fun prop(v: JsonElement?, k: String): JsonElement? = when (v) {
        is JsonObject -> v[k]
        is JsonArray -> k.toIntOrNull()?.let { v.getOrNull(it) }
        else -> null
    }
    /** Object.entries: un objeto da sus claves; un arreglo, sus posiciones. */
    private fun entradas(v: JsonElement?): List<Pair<String, JsonElement>> = when (v) {
        is JsonObject -> v.entries.map { it.key to it.value }
        is JsonArray -> v.mapIndexed { i, e -> i.toString() to e }
        else -> emptyList()
    }
    private fun claves(v: JsonElement?): Set<String> = entradas(v).map { it.first }.toSet()
    private fun o(v: JsonElement?): JsonElement? = if (verdadero(v)) v else null   // "v || {}" cuando solo se leen claves
    private fun numero(v: JsonElement?): Double? = (v as? JsonPrimitive)?.takeIf { !it.isString && it.content != "true" && it.content != "false" }?.content?.toDoubleOrNull()
    private fun aTextoJs(d: Double): String = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else JsJson.numero(d.toString())

    private val FECHA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val HORA = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    /** Como fechaOk de la 2.0: JavaScript acepta el 30 de febrero (lo pasa a marzo), pero no el mes 13 ni el día 32. */
    fun fechaOk(v: JsonElement?): Boolean {
        val f = js(v)
        if (!FECHA.matches(f)) return false
        val m = f.substring(5, 7).toInt(); val d = f.substring(8, 10).toInt()
        return m in 1..12 && d in 1..31
    }
    fun horaOk(v: JsonElement?): Boolean = HORA.matches(js(v))

    private fun fecha(f: String): LocalDate? = runCatching { LocalDate.of(f.substring(0, 4).toInt(), f.substring(5, 7).toInt(), 1).plusDays(f.substring(8, 10).toLong() - 1) }.getOrNull()
    private val DIAS = listOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")
    private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    fun fechaLarga(f: String): String = fecha(f)?.let { "${DIAS[it.dayOfWeek.value % 7]} ${it.dayOfMonth}-${MESES[it.monthValue - 1]}" } ?: f
    fun sumarDias(f: String, n: Long): String = fecha(f)?.plusDays(n)?.toString() ?: f

    // ---------- validación del programa (FORMATO §5 de CONTEXTO-MODULO-ALIMENTACION) ----------
    fun validarPrograma(o: JsonElement?): Resultado {
        val errores = mutableListOf<String>(); val avisos = mutableListOf<String>()
        fun err(m: String) { errores.add(m) }
        if (o !is JsonObject) return Resultado(false, listOf("El archivo no es un objeto JSON."), avisos)
        if (!(o["formato"] is JsonPrimitive && (o["formato"] as JsonPrimitive).isString && js(o["formato"]) == "programa-alimentacion")) err("formato: debe ser \"programa-alimentacion\".")
        if (!(o["version_formato"] is JsonPrimitive && (o["version_formato"] as JsonPrimitive).isString && js(o["version_formato"]) == "1.0")) err("version_formato: debe ser \"1.0\".")
        val p = o(o["programa"])
        if (!verdadero(prop(p, "id"))) err("programa.id: falta.")
        if (!verdadero(prop(p, "nombre"))) err("programa.nombre: falta.")
        val desde = prop(p, "desde"); val hasta = prop(p, "hasta")
        if (!fechaOk(desde)) err("programa.desde: fecha inválida (AAAA-MM-DD).")
        if (!fechaOk(hasta)) err("programa.hasta: fecha inválida (AAAA-MM-DD).")
        val rango = fechaOk(desde) && fechaOk(hasta)
        if (rango && js(desde) > js(hasta)) err("programa: \"desde\" es posterior a \"hasta\".")
        val perfiles = (o["perfiles"] as? JsonArray) ?: JsonArray(emptyList())
        if (perfiles.isEmpty()) err("perfiles: debe haber al menos uno.")
        val idsPerfil = perfiles.mapNotNull { (prop(it, "id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
        perfiles.forEachIndexed { i, x -> if (!verdadero(x) || !verdadero(prop(x, "id"))) err("perfiles[$i].id: falta.") }
        val alimentos = (o["alimentos"] as? JsonObject) ?: (o["alimentos"] as? JsonArray)
        val preps: JsonElement? = (o["preparaciones"] as? JsonObject) ?: (o["preparaciones"] as? JsonArray)
        val clavesAlimentos = claves(alimentos); val clavesPreps = claves(preps)
        if (verdadero(o["horario_por_defecto"])) for ((k, h) in entradas(o["horario_por_defecto"])) if (!horaOk(h)) err("horario_por_defecto.$k: hora inválida \"${js(h)}\" (HH:MM).")
        for ((id, pr) in entradas(preps)) {
            for ((pid, lista) in entradas(if (verdadero(pr)) o(prop(pr, "porciones")) else null)) {
                if (pid !in idsPerfil) err("preparaciones.$id.porciones: el perfil \"$pid\" no existe en perfiles.")
                ((lista as? JsonArray) ?: emptyList<JsonElement>()).forEachIndexed { i, x ->
                    if (!verdadero(x) || js(prop(x, "alimento")) !in clavesAlimentos)
                        err("preparaciones.$id.porciones.$pid[$i]: el alimento \"${if (verdadero(x)) js(prop(x, "alimento")) else js(x)}\" no existe en alimentos.")
                }
            }
            for (pid in claves(if (verdadero(pr)) o(prop(pr, "aporte_estimado")) else null)) if (pid !in idsPerfil) err("preparaciones.$id.aporte_estimado: el perfil \"$pid\" no existe en perfiles.")
        }
        val dias = (o["dias"] as? JsonArray) ?: JsonArray(emptyList())
        if (dias.isEmpty()) err("dias: no hay ningún día.")
        val vistas = mutableSetOf<String>()
        dias.forEachIndexed { i, d ->
            val r = "dias[$i]"
            if (!verdadero(d) || !fechaOk(prop(d, "fecha"))) { err("$r.fecha: fecha inválida."); return@forEachIndexed }
            val f = js(prop(d, "fecha"))
            if (f in vistas) err("$r.fecha: $f está repetida.")
            vistas.add(f)
            if (rango && (f < js(desde) || f > js(hasta))) err("$r.fecha: $f está fuera del rango del programa.")
            ((prop(d, "comidas") as? JsonArray) ?: emptyList<JsonElement>()).forEachIndexed { k, c ->
                val tipo = prop(c, "tipo")
                if (!verdadero(c) || !(tipo is JsonPrimitive && tipo.isString && tipo.content in TIPOS)) err("$r.comidas[$k].tipo: debe ser ${TIPOS.joinToString(", ")}.")
                if (!verdadero(c) || !horaOk(prop(c, "hora"))) err("$r.comidas[$k].hora: hora inválida (HH:MM).")
                if (!verdadero(c) || js(prop(c, "preparacion")) !in clavesPreps) err("$r.comidas[$k].preparacion: \"${if (verdadero(c)) js(prop(c, "preparacion")) else js(c)}\" no existe en preparaciones.")
            }
            ((prop(d, "tareas") as? JsonArray) ?: emptyList<JsonElement>()).forEachIndexed { k, t -> if (!verdadero(t) || !horaOk(prop(t, "hora"))) err("$r.tareas[$k].hora: hora inválida (HH:MM).") }
            for (pid in claves(o(prop(d, "total_estimado_kcal")))) if (pid !in idsPerfil) err("$r.total_estimado_kcal: el perfil \"$pid\" no existe en perfiles.")
        }
        if (errores.isEmpty()) {
            val p0 = perfiles[0]; val pid = js(prop(p0, "id"))
            val nombre0 = if (verdadero(prop(p0, "nombre"))) js(prop(p0, "nombre")) else pid
            if (perfiles.size > 1) avisos.add("El programa trae ${perfiles.size} perfiles; este celular usará \"$nombre0\". Pide a Alimentación un archivo por persona.")
            for ((id, pr) in entradas(preps)) if (!verdadero(prop(o(prop(pr, "porciones")), pid)))
                avisos.add("La preparación \"${if (verdadero(prop(pr, "nombre"))) js(prop(pr, "nombre")) else id}\" no tiene porción para $nombre0.")
            for (d in dias) {
                val tot = numero(prop(o(prop(d, "total_estimado_kcal")), pid))
                var suma = 0.0
                for (c in (prop(d, "comidas") as? JsonArray) ?: emptyList<JsonElement>())
                    suma += numero(prop(prop(o(prop(prop(preps, js(prop(c, "preparacion"))), "aporte_estimado")), pid), "kcal"))?.takeIf { it != 0.0 } ?: 0.0
                if (tot != null && suma != 0.0 && kotlin.math.abs(tot - suma) > 0.1 * suma)
                    avisos.add("${js(prop(d, "fecha"))}: el total estimado (${aTextoJs(tot)} kcal) difiere más de 10 % de la suma de las comidas (${aTextoJs(suma)} kcal).")
            }
        }
        return Resultado(errores.isEmpty(), errores, avisos)
    }

    /** Lee el archivo elegido. Si es igual al programa activo, no hay nada que importar. */
    fun revisarTexto(texto: String, activo: JsonObject?): Pair<JsonObject?, Resultado> {
        if (texto.isBlank()) return null to Resultado(false, listOf("El archivo llegó vacío al celular."), emptyList())
        val obj = try { Json.parseToJsonElement(texto) } catch (e: Exception) {
            return null to Resultado(false, listOf("El texto no es JSON válido."), emptyList())
        }
        val r = validarPrograma(obj)
        if (r.ok && activo != null && JsJson.stringify(activo) == JsJson.stringify(obj)) return obj as JsonObject to r.copy(ok = false, yaActivo = true)
        return (obj as? JsonObject) to r
    }

    fun clave(f: String, t: String) = "${f}_$t"
    /** La clave es "AAAA-MM-DD_tipo"; el tipo puede llevar guion bajo (colacion_am), así que se corta solo en el primero. */
    fun partir(k: String): Pair<String, String> = k.take(10) to k.drop(11)

    fun objeto(vararg pares: Pair<String, JsonElement?>): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().also { m -> for ((k, v) in pares) if (v != null) m[k] = v })   // null = undefined: no se escribe
    fun txt(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
}

/**
 * El estado del módulo (lo que en la 2.0 es la variable M) y sus operaciones. Cada operación devuelve los documentos
 * que hay que escribir en la base, con la misma forma que escribe la 2.0, para que el respaldo sea compatible.
 */
class EstadoComidas(
    val programa: JsonObject?,
    cambiosGuardados: JsonObject? = null,
    registros: List<Respaldo.Doc> = emptyList(),
    fotos: Set<String> = emptySet()
) {
    val cambios = LinkedHashMap<String, JsonObject>()
    val registros = LinkedHashMap<String, JsonObject>()
    val fotos = fotos.toMutableSet()
    val perfil: JsonObject? = (programa?.get("perfiles") as? JsonArray)?.firstOrNull() as? JsonObject
    val pid: String get() = Comidas.js(perfil?.get("id"))
    var mensaje: Pair<String, String>? = null   // (tipo, texto): "ok", "err" o "info"

    init {
        val idPrograma = (programa?.get("programa") as? JsonObject)?.get("id")
        if (programa != null && cambiosGuardados != null && cambiosGuardados["programa_id"] == idPrograma)
            (cambiosGuardados["cambios"] as? JsonObject)?.forEach { (k, v) -> if (v is JsonObject) cambios[k] = v }
        for (d in registros) this.registros[d.ruta.removePrefix("comidas/")] = d.data
    }

    private fun info(): JsonObject? = programa?.get("programa") as? JsonObject
    fun preparaciones(): JsonObject = (programa?.get("preparaciones") as? JsonObject) ?: JsonObject(emptyMap())
    fun dias(): List<JsonObject> = (programa?.get("dias") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    fun diaDe(f: String): JsonObject? = dias().firstOrNull { (it["fecha"] as? JsonPrimitive)?.content == f }
    fun comidaPlan(f: String, t: String): JsonObject? = ((diaDe(f)?.get("comidas") as? JsonArray) ?: emptyList<JsonElement>())
        .mapNotNull { it as? JsonObject }.firstOrNull { (it["tipo"] as? JsonPrimitive)?.content == t }

    /** La comida que toca en ese lugar, con los intercambios aplicados: la hora es la del lugar; el plato viene del otro. */
    fun comidaDe(f: String, t: String): JsonObject? {
        val c = comidaPlan(f, t) ?: return null
        val x = cambios[Comidas.clave(f, t)] ?: return c
        return JsonObject(LinkedHashMap(c).also { it["preparacion"] = x["preparacion"] ?: JsonNull; it["conservacion"] = x["conservacion"] ?: JsonNull; it["intercambio"] = x })
    }

    fun desde(): String = (info()?.get("desde") as? JsonPrimitive)?.content ?: ""
    fun hasta(): String = (info()?.get("hasta") as? JsonPrimitive)?.content ?: ""
    fun elegirDia(hoy: String): String = if (programa == null) hoy else if (hoy < desde()) desde() else if (hoy > hasta()) hasta() else hoy

    fun etiquetaLugar(k: String): String { val (f, t) = Comidas.partir(k); return "${Comidas.ETQ[t] ?: t} del ${Comidas.fechaLarga(f)}" }

    private fun version(r: JsonObject?): Long = (r?.get("version") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L

    fun guardarRegistro(f: String, t: String, cambiosRegistro: Map<String, JsonElement>, ahora: String): Respaldo.Doc {
        val k = Comidas.clave(f, t)
        val previo = registros[k] ?: Comidas.objeto("fecha" to JsonPrimitive(f), "tipo" to JsonPrimitive(t), "version" to JsonPrimitive(0))
        val r = LinkedHashMap<String, JsonElement>(previo)
        r.putAll(cambiosRegistro)
        r["version"] = JsonPrimitive(version(previo) + 1)
        r["actualizado"] = JsonPrimitive(ahora)
        val nuevo = JsonObject(r)
        registros[k] = nuevo
        return Respaldo.Doc("comidas/$k", nuevo)
    }

    fun estadoDe(k: String): String? = (registros[k]?.get("estado") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** "Comí lo indicado", "otra cosa" o "no comí". Si ya estaba así, no escribe nada. */
    fun marcar(f: String, t: String, estado: String, ahora: String): List<Respaldo.Doc> {
        mensaje = null
        if (estadoDe(Comidas.clave(f, t)) == estado) return emptyList()
        return listOf(guardarRegistro(f, t, mapOf("estado" to JsonPrimitive(estado)), ahora))
    }

    fun anotar(f: String, t: String, nota: String, ahora: String): List<Respaldo.Doc> {
        val previa = (registros[Comidas.clave(f, t)]?.get("nota") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (previa != nota) listOf(guardarRegistro(f, t, mapOf("nota" to JsonPrimitive(nota)), ahora)) else emptyList()
    }

    fun ponerFoto(f: String, t: String, dataUrl: String, ahora: String): List<Respaldo.Doc> {
        val k = Comidas.clave(f, t)
        val foto = Respaldo.Doc("fotos/$k", Comidas.objeto("dataUrl" to JsonPrimitive(dataUrl), "fecha" to JsonPrimitive(f), "tipo" to JsonPrimitive(t)))
        fotos.add(k)
        return listOf(foto, guardarRegistro(f, t, mapOf("foto" to JsonPrimitive(true)), ahora))
    }

    fun intercambiar(kA: String, kB: String, ahora: String): List<Respaldo.Doc> {
        val (fA, tA) = Comidas.partir(kA); val (fB, tB) = Comidas.partir(kB)
        val a = comidaDe(fA, tA); val b = comidaDe(fB, tB)
        if (a == null || b == null || kA == kB) return emptyList()
        fun nulo(v: JsonElement?): JsonElement = if (Comidas.verdadero(v)) v!! else JsonNull
        fun nuevo(k: String, c: JsonObject, desde: String): JsonObject? {
            val (f, t) = Comidas.partir(k); val plan = comidaPlan(f, t)!!
            return if (plan["preparacion"] == c["preparacion"] && nulo(plan["conservacion"]) == nulo(c["conservacion"])) null
            else Comidas.objeto("preparacion" to c["preparacion"], "conservacion" to nulo(c["conservacion"]), "desde" to JsonPrimitive(desde))
        }
        val xA = nuevo(kA, b, kB); val xB = nuevo(kB, a, kA)
        if (xA != null) cambios[kA] = xA else cambios.remove(kA)
        if (xB != null) cambios[kB] = xB else cambios.remove(kB)
        mensaje = "ok" to "Intercambiadas: ${etiquetaLugar(kA)} ↔ ${etiquetaLugar(kB)}."
        val docs = mutableListOf(Respaldo.Doc("alimentacion/intercambios", Comidas.objeto(
            "programa_id" to info()?.get("id"), "cambios" to JsonObject(LinkedHashMap(cambios)), "actualizado" to JsonPrimitive(ahora))))
        // Si alguna ya estaba marcada, su registro cambia de plato: queda por enviar de nuevo.
        for ((f, t) in listOf(fA to tA, fB to tB)) if (estadoDe(Comidas.clave(f, t)) != null) docs.add(guardarRegistro(f, t, emptyMap(), ahora))
        return docs
    }

    fun deshacer(k: String, ahora: String): List<Respaldo.Doc> {
        val desde = (cambios[k]?.get("desde") as? JsonPrimitive)?.content ?: return emptyList()
        return intercambiar(k, desde, ahora)
    }

    private fun enviadoVersion(r: JsonObject): Long? = (r["enviado"] as? JsonObject)?.let { version(it) }
    fun enviado(k: String): Boolean = registros[k]?.let { enviadoVersion(it) == version(it) } ?: false

    fun pendientes(): List<JsonObject> = registros.values.filter { Comidas.verdadero(it["estado"]) && (it["enviado"] !is JsonObject || enviadoVersion(it) != version(it)) }
        .sortedBy { Comidas.js(it["fecha"]) + Comidas.js(it["tipo"]) }

    private fun nombreBase(r: JsonObject): String {
        val base = "comida-$pid-${Comidas.js(r["fecha"])}-${Comidas.js(r["tipo"])}"
        return if (Comidas.verdadero(r["enviado"])) "$base-v${version(r)}" else base
    }

    fun registroJSON(r: JsonObject, nombreFoto: String?, app: String): JsonObject {
        val f = Comidas.js(r["fecha"]); val t = Comidas.js(r["tipo"])
        val c = comidaDe(f, t)
        val pr = c?.let { preparaciones()[Comidas.js(it["preparacion"])] as? JsonObject }
        val x = c?.get("intercambio") as? JsonObject
        return Comidas.objeto(
            "formato" to JsonPrimitive("registro-comida"), "version_formato" to JsonPrimitive("1.0"),
            "perfil" to Comidas.objeto("id" to perfil?.get("id"), "nombre" to (perfil?.get("nombre")?.takeIf { Comidas.verdadero(it) } ?: perfil?.get("id"))),
            "programa" to Comidas.objeto("id" to info()?.get("id"), "nombre" to info()?.get("nombre")),
            "fecha" to r["fecha"], "tipo" to r["tipo"], "hora_programada" to (if (c != null) c["hora"] else JsonNull),
            "indicado" to (if (c != null && pr != null) Comidas.objeto(
                "preparacion" to c["preparacion"], "nombre" to pr["nombre"],
                "porciones" to ((pr["porciones"] as? JsonObject)?.get(pid)?.takeIf { Comidas.verdadero(it) } ?: JsonArray(emptyList())),
                "aporte_estimado" to ((pr["aporte_estimado"] as? JsonObject)?.get(pid)?.takeIf { Comidas.verdadero(it) } ?: JsonNull)
            ) else JsonNull),
            "intercambio" to (if (x != null) {
                val (fc, tc) = Comidas.partir(Comidas.js(x["desde"]))
                Comidas.objeto("con" to Comidas.objeto("fecha" to JsonPrimitive(fc), "tipo" to JsonPrimitive(tc)),
                    "preparacion_planificada" to (comidaPlan(f, t)?.get("preparacion")?.takeIf { Comidas.verdadero(it) } ?: JsonNull))
            } else JsonNull),
            "estado" to r["estado"], "nota" to (r["nota"]?.takeIf { Comidas.verdadero(it) } ?: JsonPrimitive("")), "foto" to Comidas.txt(nombreFoto),
            "registrado_en" to r["actualizado"], "version" to r["version"], "app" to JsonPrimitive(app)
        )
    }

    /** Un archivo por subir: el JSON del registro o la foto (en ese caso [texto] es null y se sube la foto guardada). */
    data class Archivo(val nombre: String, val mime: String, val clave: String, val texto: String?)

    /** Lo que se sube al tocar "Enviar a Alimentación". null si no hay nada o falta una foto (queda en [mensaje]). */
    fun preparaEnvio(app: String): List<Archivo>? {
        val lista = pendientes()
        if (lista.isEmpty()) { mensaje = "info" to "No hay nada nuevo que enviar."; return null }
        val sinFoto = lista.filter { Comidas.js(it["estado"]) == "otro" && Comidas.clave(Comidas.js(it["fecha"]), Comidas.js(it["tipo"])) !in fotos }
        if (sinFoto.isNotEmpty()) {
            mensaje = "err" to "Falta la foto de: ${sinFoto.joinToString(", ") { "${Comidas.ETQ[Comidas.js(it["tipo"])]} del ${Comidas.fechaLarga(Comidas.js(it["fecha"]))}" }}. Agrégala antes de enviar."
            return null
        }
        val archivos = mutableListOf<Archivo>()
        for (r in lista) {
            val base = nombreBase(r); val k = Comidas.clave(Comidas.js(r["fecha"]), Comidas.js(r["tipo"]))
            var nombreFoto: String? = null
            if (Comidas.js(r["estado"]) == "otro") { nombreFoto = "$base.jpg"; archivos.add(Archivo(nombreFoto, "image/jpeg", "$k|foto", null)) }
            archivos.add(Archivo("$base.json", "application/json", "$k|json", JsJson.indentado(registroJSON(r, nombreFoto, app))))
        }
        return archivos
    }

    private val subidos = HashMap<String, HashMap<String, String>>()

    /** Se llama por cada archivo que Drive confirmó. Al confirmar el JSON, el registro queda como enviado. */
    fun alListo(clave: String, id: String, ahora: String): Respaldo.Doc? {
        val k = clave.substringBefore('|'); val que = clave.substringAfter('|')
        subidos.getOrPut(k) { HashMap() }[que] = id
        if (que != "json") return null
        val r = registros[k] ?: return null
        val nuevo = JsonObject(LinkedHashMap(r).also {
            it["enviado"] = Comidas.objeto("version" to r["version"], "json" to JsonPrimitive(id), "foto" to Comidas.txt(subidos[k]?.get("foto")), "en" to JsonPrimitive(ahora))
        })
        registros[k] = nuevo
        return Respaldo.Doc("comidas/$k", nuevo)
    }

    fun mensajeEnviado(comidas: Int, archivos: Int) {
        mensaje = "ok" to "Enviado a Alimentación: $comidas comida${if (comidas == 1) "" else "s"} ($archivos archivo${if (archivos == 1) "" else "s"}) en la carpeta \"${Comidas.CARPETA}\" de tu Drive."
    }

    companion object {
        /** Documentos que escribe la 2.0 al activar un programa. */
        fun documentosActivacion(obj: JsonObject, ahora: String): List<Respaldo.Doc> {
            val id = Comidas.js((obj["programa"] as? JsonObject)?.get("id"))
            val d = Comidas.objeto("programa" to obj, "importado_en" to JsonPrimitive(ahora))
            return listOf(Respaldo.Doc("alimentacion/programa_activo", d), Respaldo.Doc("programas_alimentacion/$id", d))
        }
    }
}
