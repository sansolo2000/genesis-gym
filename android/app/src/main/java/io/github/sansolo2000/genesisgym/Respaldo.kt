package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Formato `genesis-respaldo` v1, el mismo de la 2.0 (`respaldo.js`): mismas validaciones, mismos mensajes
 * y la misma huella SHA-256. Un respaldo creado en la 2.0 se restaura en la nativa y al revés.
 */
object Respaldo {
    const val FORMATO = "genesis-respaldo"
    const val VERSION = 1
    /** Colecciones válidas (2.0 desde la 0.7.5). Las de Comidas se conservan aunque la nativa aún no las use. */
    val COLECCIONES = listOf("config", "rutinas", "sesiones", "imagenes", "alimentacion", "programas_alimentacion", "comidas", "fotos")
    private val BASE_CONTEOS = listOf("config", "rutinas", "sesiones", "imagenes")
    private val RUTA = Regex("^[A-Za-z0-9_\\-.~:@+]{1,200}/[A-Za-z0-9_\\-.~:@+]{1,200}$")

    data class Doc(val ruta: String, val data: JsonObject)
    data class Leido(
        val docs: List<Doc>, val conteos: Map<String, Int>, val rango: Pair<String, String>?,
        val perfil: String?, val exportadoEn: String?, val versionApp: String?, val app: String?
    )
    sealed class Resultado {
        data class Ok(val leido: Leido) : Resultado()
        data class Error(val mensaje: String) : Resultado()
    }

    fun ordenar(docs: List<Doc>): List<Doc> = docs.sortedWith { a, b -> a.ruta.compareTo(b.ruta) }

    /** Huella: SHA-256 de JSON.stringify([{ruta, data}, …]) con los documentos ordenados por ruta. */
    fun huella(docs: List<Doc>): String {
        val lista = buildJsonArray { for (d in ordenar(docs)) add(buildJsonObject { put("ruta", d.ruta); put("data", d.data) }) }
        return JsJson.sha256(JsJson.stringify(lista))
    }

    fun conteos(docs: List<Doc>): Map<String, Int> {
        val c = LinkedHashMap<String, Int>(); for (k in BASE_CONTEOS) c[k] = 0
        for (d in docs) { val col = d.ruta.substringBefore('/'); c[col] = (c[col] ?: 0) + 1 }
        return c
    }

    private fun rango(docs: List<Doc>): Pair<String, String>? {
        val f = docs.filter { it.ruta.startsWith("sesiones/") }
            .mapNotNull { (it.data["fecha"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }.sorted()
        return if (f.isEmpty()) null else f.first() to f.last()
    }
    private fun textoOpcional(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Lee y valida un archivo de respaldo, con los mismos controles y mensajes que la 2.0. */
    fun leer(texto: String): Resultado {
        fun mal(m: String) = Resultado.Error(m)
        val doc = try { Json.parseToJsonElement(texto) } catch (e: Exception) { return mal("El archivo no es un respaldo válido (no es JSON).") }
        if (doc !is JsonObject || textoOpcional(doc["formato"]) != FORMATO)
            return mal("El archivo no es un respaldo de Génesis Gym (falta \"formato\": \"genesis-respaldo\"). ¿Elegiste una rutina en vez de un respaldo?")
        val v = doc["version"]
        if (!(v is JsonPrimitive && !v.isString && v.content.toDoubleOrNull() == VERSION.toDouble()))
            return mal("Versión de respaldo no soportada: ${v?.let { if (it is JsonPrimitive) it.content else JsJson.stringify(it) } ?: "undefined"}.")
        val lista = doc["documentos"] as? JsonArray ?: return mal("El respaldo no trae la lista de documentos.")
        val docs = mutableListOf<Doc>()
        for (d in lista) {
            val o = d as? JsonObject ?: return mal("El respaldo tiene un documento con formato inválido.")
            val ruta = textoOpcional(o["ruta"])
            val data = o["data"]
            if (ruta == null || !RUTA.matches(ruta) || data !is JsonObject) return mal("El respaldo tiene un documento con formato inválido.")
            val col = ruta.substringBefore('/')
            if (col !in COLECCIONES) return mal("El respaldo trae una colección desconocida: $col.")
            docs.add(Doc(ruta, data))
        }
        if (docs.map { it.ruta }.toSet().size != docs.size) return mal("El respaldo tiene documentos repetidos.")
        if (huella(docs) != textoOpcional(doc["huella_sha256"]))
            return mal("La huella del respaldo no coincide: el archivo está incompleto o fue modificado. No se restauró nada.")
        return Resultado.Ok(Leido(docs, conteos(docs), rango(docs), textoOpcional(doc["perfil"]), textoOpcional(doc["exportado_en"]), textoOpcional(doc["version_app"]), textoOpcional(doc["app"])))
    }

    /** Crea el texto de un respaldo con los documentos de la base. */
    fun crear(docs: List<Doc>, perfil: String?, versionApp: String, ahoraIso: String): String {
        val orden = ordenar(docs)
        val archivo = buildJsonObject {
            put("formato", FORMATO); put("version", VERSION)
            put("app", "genesis-gym-n"); put("version_app", versionApp)
            put("exportado_en", ahoraIso)
            put("perfil", perfil?.let { JsonPrimitive(it) } ?: JsonNull)
            put("conteos", buildJsonObject { for ((k, n) in conteos(orden)) put(k, n) })
            put("huella_sha256", huella(orden))
            put("documentos", buildJsonArray { for (d in orden) add(buildJsonObject { put("ruta", d.ruta); put("data", d.data) }) })
        }
        return JsJson.stringify(archivo)
    }

    /** Nombre del archivo, igual que la 2.0: genesis-respaldo-<perfil>-AAAA-MM-DD.json */
    fun nombreArchivo(perfil: String?, fechaIso: String): String {
        val slug = java.text.Normalizer.normalize(perfil ?: "sin-perfil", java.text.Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "sin-perfil" }
        return "genesis-respaldo-$slug-$fechaIso.json"
    }
}
