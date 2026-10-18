package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Apoyo.n
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 0.19.0 (CONTEXTO-MODULO-MEDICAMENTOS §10, aprobado por Héctor): el vademécum va aparte del plan. Héctor lo sube a mano a
 * Drive (Genesis Gym/catalogos/, compartida como lectura) y cada persona lo elige una vez por versión con el selector.
 * El plan lleva solo la referencia "catalogo_medicamentos" (versión y huella). Lógica pura, sin Android, para JUnit.
 *
 * Documento `config/vademecum` (entra al respaldo; el vademécum no):
 *   { version, registros, huella_sha256, importado_en, origen: "archivo" | "plan", esperado: { version, huella_sha256, registros, archivo } }
 */
object Vademecum {
    const val TIPO = "genesis-vademecum"
    const val VERSION = "1.0"
    const val RUTA = "config/vademecum"
    const val CLAVE_PLAN = "catalogo_medicamentos"
    private val HEX = Regex("^[0-9a-f]{64}$")

    /** La referencia que trae el plan. [config]: config_horarios y config_dosis opcionales (§7), nunca en null. */
    data class Ref(val version: String, val huella: String, val registros: Int?, val archivo: String?, val config: JsonObject?)

    /** Lo que hay en el celular y lo que pide el último plan. */
    data class Estado(val version: String?, val huella: String?, val registros: Int?, val importadoEn: String?, val esperado: Ref?)

    // ---------- la referencia del plan ----------
    fun revisarRef(e: JsonElement?): Pair<Ref?, List<String>> {
        val o = e as? JsonObject ?: return null to listOf("$CLAVE_PLAN: debe ser un objeto.")
        val err = mutableListOf<String>()
        val version = o.s("version")?.trim()
        if (version.isNullOrEmpty()) err.add("$CLAVE_PLAN.version: falta.")
        val huella = o.s("huella_sha256")
        if (huella == null || !HEX.matches(huella)) err.add("$CLAVE_PLAN.huella_sha256: deben ser 64 caracteres hexadecimales en minúscula.")
        val registros = o["registros"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toDoubleOrNull()?.takeIf { d -> d >= 1 && d == Math.floor(d) }?.toInt()
            ?: run { err.add("$CLAVE_PLAN.registros: debe ser un entero mayor que 0."); null } }
        val archivo = o["archivo"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: run { err.add("$CLAVE_PLAN.archivo: debe ser texto."); null } }
        val config = o["config"]?.let { c ->
            val co = c as? JsonObject ?: run { err.add("$CLAVE_PLAN.config: debe ser un objeto (o no ir)."); null }
            co?.let { x ->
                for (k in listOf("config_horarios", "config_dosis")) if (x[k] != null && x[k] !is JsonObject) err.add("$CLAVE_PLAN.config.$k: debe ser un objeto (o no ir; nunca null).")
                if (err.isEmpty()) Medicamentos.config(x.o("config_horarios"), x.o("config_dosis"), Medicamentos.PLACEHOLDER).second.forEach { m -> err.add("$CLAVE_PLAN.config.$m") }
                x
            }
        }
        if (err.isNotEmpty()) return null to err
        return Ref(version!!, huella!!, registros, archivo, config) to emptyList()
    }

    fun refAJson(r: Ref): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().also { m ->
        m["version"] = JsonPrimitive(r.version); m["huella_sha256"] = JsonPrimitive(r.huella)
        r.registros?.let { m["registros"] = JsonPrimitive(it) }; r.archivo?.let { m["archivo"] = JsonPrimitive(it) }
    })

    private fun refDesdeJson(o: JsonObject?): Ref? {
        val v = o.s("version") ?: return null; val h = o.s("huella_sha256") ?: return null
        return Ref(v, h, o.n("registros")?.toInt(), o.s("archivo"), null)
    }

    // ---------- el estado en el celular ----------
    /**
     * [doc]: `config/vademecum`; [legado]: `config/medicamentos` de la 0.17/0.18 (catálogo que llegó dentro del plan).
     * Sin el archivo del catálogo en el celular no hay versión, aunque los documentos digan otra cosa.
     */
    fun estado(doc: JsonObject?, legado: JsonObject?, hayArchivo: Boolean): Estado {
        val esperado = refDesdeJson(doc.o("esperado"))
        if (!hayArchivo) return Estado(null, null, null, null, esperado)
        if (doc?.s("version") != null) return Estado(doc.s("version"), doc.s("huella_sha256"), doc.n("registros")?.toInt(), doc.s("importado_en"), esperado)
        return Estado(legado.s("version_catalogo"), legado.s("huella_sha256"), legado.n("registros")?.toInt(), legado.s("importado_en"), esperado)
    }

    /** ¿El vademécum del celular es el que pide [ref]? La huella manda; la misma versión con los mismos registros también vale (catálogo de un plan de antes). */
    fun coincide(e: Estado, ref: Ref): Boolean =
        e.version != null && (e.huella == ref.huella || (e.version == ref.version && (ref.registros == null || e.registros == ref.registros)))

    const val OK = "ok"; const val FALTA = "falta"; const val DESACTUALIZADO = "desactualizado"
    fun situacion(e: Estado): String = when {
        e.version == null -> FALTA
        e.esperado != null && !coincide(e, e.esperado) -> DESACTUALIZADO
        else -> OK
    }

    const val DONDE = "Drive → Compartidos conmigo → Genesis Gym → catalogos"
    /** Línea de estado para Medicamentos. */
    fun texto(e: Estado): String = when (situacion(e)) {
        FALTA -> "Falta el vademécum" + (e.esperado?.let { " ${it.version}" } ?: "") + ". Toca \"Actualizar vademécum\" y elige " +
            (e.esperado?.archivo ?: "vademecum.json.gz") + " en $DONDE."
        DESACTUALIZADO -> "Tu plan pide el vademécum ${e.esperado!!.version} y este celular tiene el ${e.version}. Toca \"Actualizar vademécum\" y elige " +
            (e.esperado.archivo ?: "vademecum.json.gz") + " en $DONDE."
        else -> "Vademécum ${e.version}" + (e.registros?.let { " · $it medicamentos" } ?: "") + (e.importadoEn?.let { " · actualizado el ${it.take(10)}" } ?: "")
    }

    /** Lo que dice la revisión del plan sobre el vademécum. */
    fun avisoPlan(e: Estado, ref: Ref): String =
        if (coincide(e, ref)) "Vademécum ${ref.version}: ya está en este celular."
        else "Vademécum ${ref.version}: " + (if (e.version == null) "falta en este celular." else "este celular tiene el ${e.version}.") +
            " El plan se importa igual; después ve a Medicamentos → \"Actualizar vademécum\" y elige " + (ref.archivo ?: "vademecum.json.gz") + " en $DONDE."

    /** `config/vademecum` con la referencia esperada nueva (lo demás queda igual). */
    fun conEsperado(doc: JsonObject?, ref: Ref): JsonObject = JsonObject(LinkedHashMap(doc ?: JsonObject(emptyMap())).also { it["esperado"] = refAJson(ref) })

    /** ¿Importar el plan cambia lo esperado? (para importar un plan cuyas partes ya estaban activas). */
    fun cambiaEsperado(doc: JsonObject?, ref: Ref): Boolean = refDesdeJson(doc.o("esperado"))?.let { it.version != ref.version || it.huella != ref.huella } ?: true

    // ---------- el archivo elegido con el selector ----------
    /** El archivo puede venir comprimido (gzip) o no. */
    fun descomprimir(bytes: ByteArray): ByteArray =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes

    fun huella(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Lo que se guarda: el catálogo (como el archivo de la 0.17/0.18: { fuente, version, medicamentos }) y su documento. */
    data class Revisado(val catalogo: JsonObject, val version: String, val registros: Int, val huella: String, val avisos: List<String>)

    /**
     * Revisa el archivo elegido. Todo o nada. Con [esperado] (lo que pide el plan), la huella tiene que ser esa: así nunca se
     * acepta una copia vieja que el proveedor de Drive entregue justo después de una actualización (QA, §32.2).
     */
    fun revisarArchivo(crudo: ByteArray, esperado: Ref?): Pair<Revisado?, List<String>> {
        val bytes = try { descomprimir(crudo) } catch (e: Exception) { return null to listOf("El archivo está comprimido pero dañado: no se pudo descomprimir.") }
        val h = huella(bytes)
        val o = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("﻿")) as? JsonObject } catch (e: Exception) { null }
            ?: return null to listOf("El archivo no es un JSON válido (¿está incompleto?).")
        val err = mutableListOf<String>(); val av = mutableListOf<String>()
        if (o.s("tipo") != TIPO) err.add("tipo: debe ser \"$TIPO\". ¿Es el vademécum de la carpeta catalogos?")
        if (o.s("version_formato") != VERSION) err.add("version_formato: debe ser \"$VERSION\".")
        val version = o.s("version")?.trim()
        if (version.isNullOrEmpty()) err.add("version: falta.")
        val lista = o["medicamentos"] as? JsonArray
        if (lista == null || lista.isEmpty()) err.add("medicamentos: falta o está vacío.")
        val malos = lista?.withIndex()?.filter { Medicamentos.medDesdeJson(it.value) == null }?.map { it.index } ?: emptyList()
        if (malos.isNotEmpty()) err.add("medicamentos: ${malos.size} registro(s) sin nombre o sin ids (posiciones ${malos.take(5).joinToString(", ")}${if (malos.size > 5) ", …" else ""}).")
        o.n("registros")?.let { if (lista != null && it.toInt() != lista.size) err.add("registros: dice ${it.toInt()} y trae ${lista.size}.") }
        if (err.isNotEmpty()) return null to err
        if (esperado != null && h != esperado.huella)
            return null to listOf("Este archivo no es el vademécum que pide tu plan (${esperado.version}): es el ${version}. Si Héctor recién lo subió, espera unos minutos y vuelve a elegirlo.")
        if (esperado == null) av.add("Tu plan todavía no dice qué vademécum usar: se guarda este (${version}).")
        val sinDosis = lista!!.count { (it as JsonObject).s("fraccionable") == null }
        if (sinDosis > 0) av.add("$sinDosis medicamento(s) sin unidad ni fraccionable: para ellos la unidad se elige a mano.")
        val cat = Apoyo.objeto("fuente" to (o["fuente"] ?: JsonPrimitive("")), "version" to JsonPrimitive(version!!), "medicamentos" to lista)
        return Revisado(cat, version, lista.size, h, av) to emptyList()
    }

    /** `config/vademecum` después de guardar el archivo (conserva lo esperado). */
    fun docGuardado(doc: JsonObject?, r: Revisado, origen: String, ahoraIso: String): JsonObject = docGuardado(doc, r.version, r.registros, r.huella, origen, ahoraIso)

    /** Para un plan de antes con el catálogo dentro ([huella] = la de la sección, como en la 0.17/0.18). */
    fun docGuardado(doc: JsonObject?, version: String?, registros: Int, huella: String, origen: String, ahoraIso: String): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().also { m ->
        m["version"] = JsonPrimitive(version ?: "?"); m["registros"] = JsonPrimitive(registros); m["huella_sha256"] = JsonPrimitive(huella)
        m["importado_en"] = JsonPrimitive(ahoraIso); m["origen"] = JsonPrimitive(origen)
        doc?.get("esperado")?.let { m["esperado"] = it }
    })
}
