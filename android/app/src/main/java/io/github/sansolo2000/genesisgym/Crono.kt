package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * M-09 — cronómetro de la sesión ("Iniciar rutina"), formato del Evaluador (CONTEXTO-DATOS-DIARIOS v3.4 §1.3.1).
 * Lógica pura, sin Android, para probarla con JUnit. Queda en el registro de la sesión (entra al respaldo):
 *   crono = { inicio: ISO, fin: ISO | null, fin_tipo: "auto" | "manual" | null, ultima: { cal | fuerza | elong: ISO } }
 * "ultima" es el momento en que se marcó por última vez una serie de cada bloque mientras el cronómetro corría.
 * Al detenerse guarda "en_curso" (bloque del primer ejercicio pendiente): con "Terminar" o al cerrar, el tramo final es de ese bloque.
 * Una vez detenido no se reabre ni se recalcula (Coordinador, 14-oct). Nunca se inventa un tiempo: sin "Iniciar", todo vacío.
 */
object Crono {
    val BLOQUES = listOf("cal", "fuerza", "elong")
    val NOMBRE = mapOf("cal" to "calentamiento", "fuerza" to "fuerza", "elong" to "elongación")
    val COLUMNAS = listOf("crono_inicio", "crono_fin", "crono_fin_tipo", "crono_total_min", "crono_calentamiento_min", "crono_fuerza_min", "crono_elongacion_min")
    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

    // Lectura de JSON (las mismas de Gimnasio, sin depender de importar sus extensiones).
    private fun JsonObject.txt(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.con(k: String, v: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).also { it[k] = v })

    private fun iso(i: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(i)
    private fun instante(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun de(b: JsonObject?): JsonObject? = b?.obj("crono")
    fun iniciado(b: JsonObject?): Boolean = de(b)?.txt("inicio") != null
    fun corriendo(b: JsonObject?): Boolean = iniciado(b) && de(b)?.txt("fin") == null

    /** "Iniciar rutina": solo si no se había iniciado. */
    fun iniciar(b: JsonObject, ahora: Instant): JsonObject =
        if (iniciado(b)) b else b.con("crono", Apoyo.objeto("inicio" to JsonPrimitive(iso(ahora)), "fin" to JsonNull, "fin_tipo" to JsonNull, "ultima" to JsonObject(emptyMap())))

    /** Coordinador (14-oct): mientras no haya ninguna serie anotada, "Reiniciar cronómetro" vuelve a partir desde ahora. */
    /** D-14 (QA, 14-oct): solo mientras el cronómetro corre; una vez detenido (Terminar o cierre) no se reabre. */
    fun puedeReiniciar(b: JsonObject?): Boolean = b != null && corriendo(b) && Gimnasio.seriesRegistradas(b) == 0 && b.txt("estado") != "cerrada"
    fun reiniciar(b: JsonObject, ahora: Instant): JsonObject =
        if (!puedeReiniciar(b)) b else b.con("crono", Apoyo.objeto("inicio" to JsonPrimitive(iso(ahora)), "fin" to JsonNull, "fin_tipo" to JsonNull, "ultima" to JsonObject(emptyMap())))

    private fun detener(b: JsonObject, ahora: Instant, tipo: String): JsonObject {
        val c = de(b) ?: return b
        // "en_curso": el bloque del primer ejercicio con series sin anotar al detenerse (null si no queda ninguno).
        val i = Gimnasio.primerPendiente(b)
        val enCurso: JsonElement = if (i < 0) JsonNull else JsonPrimitive(Gimnasio.bloques(b.arr("ejercicios"))[i])
        return b.con("crono", c.con("fin", JsonPrimitive(iso(ahora))).con("fin_tipo", JsonPrimitive(tipo)).con("en_curso", enCurso))
    }

    /** "Terminar": el bloque en curso termina aquí. */
    fun terminar(b: JsonObject, ahora: Instant): JsonObject = if (corriendo(b)) detener(b, ahora, "manual") else b

    /** Series anotadas por bloque (por posición). */
    private fun anotadas(b: JsonObject): Map<String, Int> {
        val ejs = b.arr("ejercicios"); val bl = Gimnasio.bloques(ejs)
        return BLOQUES.associateWith { k -> ejs.indices.filter { bl[it] == k }.sumOf { i -> ejs[i].arr("series").count { Gimnasio.registrada(it) } } }
    }

    /**
     * Al guardar la sesión con el cronómetro corriendo: el bloque que sumó una serie anotada guarda la hora; si con esto
     * quedó anotada la última serie de la sesión, el cronómetro se detiene solo ("auto").
     */
    fun alGuardar(antes: JsonObject?, nuevo: JsonObject, ahora: Instant): JsonObject {
        if (!corriendo(nuevo)) return nuevo
        val a = antes?.let { anotadas(it) } ?: emptyMap(); val n = anotadas(nuevo)
        val sube = BLOQUES.filter { (n[it] ?: 0) > (a[it] ?: 0) }
        if (sube.isEmpty()) return nuevo
        val c = de(nuevo)!!
        val ultima = LinkedHashMap<String, JsonElement>(c.obj("ultima") ?: JsonObject(emptyMap()))
        for (k in sube) ultima[k] = JsonPrimitive(iso(ahora))
        val r = nuevo.con("crono", c.con("ultima", JsonObject(ultima)))
        return if (Gimnasio.primerPendiente(r) < 0 && r.arr("ejercicios").isNotEmpty()) detener(r, ahora, "auto") else r
    }

    data class Medida(val inicio: Instant, val fin: Instant?, val tipo: String, val totalMs: Long?, val bloquesMs: Map<String, Long?>)

    /** Bloques contiguos: calentamiento de "Iniciar" a su última serie; fuerza de ahí a la suya; el último bloque iniciado, hasta el fin. */
    fun medir(b: JsonObject?): Medida? {
        val c = de(b) ?: return null
        val ini = instante(c.txt("inicio")) ?: return null
        val fin = instante(c.txt("fin"))
        val tipo = c.txt("fin_tipo") ?: "abierto"
        if (fin == null) return Medida(ini, null, "abierto", null, BLOQUES.associateWith { null })
        val ult = c.obj("ultima")
        val iniciados = BLOQUES.filter { instante(ult?.txt(it)) != null }
        // El tramo final (hasta el fin) es del bloque en curso al detenerse; si no hay uno posterior, del último bloque iniciado.
        val enCurso = c.txt("en_curso")?.takeIf { it in BLOQUES }
        val ultimoIni = iniciados.lastOrNull()
        val cierre = if (enCurso != null && (ultimoIni == null || BLOQUES.indexOf(enCurso) >= BLOQUES.indexOf(ultimoIni))) enCurso else ultimoIni
        var desde = ini
        val bloques = BLOQUES.associateWith { k ->
            if (cierre == null || BLOQUES.indexOf(k) > BLOQUES.indexOf(cierre)) return@associateWith null
            val hasta = if (k == cierre) fin else (instante(ult?.txt(k)) ?: return@associateWith null).let { u -> minOf(maxOf(u, desde), fin) }
            (hasta.toEpochMilli() - desde.toEpochMilli()).also { desde = hasta }
        }
        return Medida(ini, fin, tipo, fin.toEpochMilli() - ini.toEpochMilli(), bloques)
    }

    /** Minutos con 1 decimal y coma decimal ("62,4"). */
    fun minutos(ms: Long?): String = if (ms == null) "" else BigDecimal(ms).divide(BigDecimal(60000), 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    /** Las 7 columnas del CSV de fuerza, en hora local de Chile. Vacías si no se usó "Iniciar". */
    fun campos(b: JsonObject?): List<String> {
        val m = medir(b) ?: return List(COLUMNAS.size) { "" }
        fun hora(i: Instant?) = i?.atZone(Gimnasio.ZONA)?.format(HORA) ?: ""
        return listOf(hora(m.inicio), hora(m.fin), m.tipo, minutos(m.totalMs)) + BLOQUES.map { minutos(m.bloquesMs[it]) }
    }

    /** Minutos redondeados para mostrar ("62 min"). */
    private fun min(ms: Long): String = "${Math.round(ms / 60000.0)} min"

    /** "Sesión B · 62 min" y, aparte, los bloques ("calentamiento 9 min · fuerza 45 min · elongación 8 min"). null si no hay medida cerrada. */
    fun resumen(b: JsonObject?): Pair<String, String>? {
        val m = medir(b) ?: return null
        val total = m.totalMs ?: return null
        val bl = BLOQUES.mapNotNull { k -> m.bloquesMs[k]?.let { "${NOMBRE[k]} ${min(it)}" } }.joinToString(" · ")
        return "${b?.txt("sesion_nombre") ?: "Sesión"} · ${min(total)}${if (m.tipo == "manual") " (terminada a mano)" else ""}" to bl
    }

    /** Tiempo corrido desde "Iniciar", para la barra ("18:42"). */
    fun transcurrido(b: JsonObject?, ahora: Instant): String? {
        val ini = instante(de(b)?.txt("inicio")) ?: return null
        val seg = ((ahora.toEpochMilli() - ini.toEpochMilli()) / 1000).coerceAtLeast(0)
        val h = seg / 3600; val mm = (seg % 3600) / 60; val ss = seg % 60
        return (if (h > 0) "$h:${mm.toString().padStart(2, '0')}" else "$mm") + ":" + ss.toString().padStart(2, '0')
    }

    /** Bloque en curso, para la barra: el del primer ejercicio con series sin anotar. */
    fun bloqueActual(b: JsonObject): String? {
        val i = Gimnasio.primerPendiente(b); if (i < 0) return null
        return NOMBRE[Gimnasio.bloques(b.arr("ejercicios"))[i]]
    }
}
