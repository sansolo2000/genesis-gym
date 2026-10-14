package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Datos diarios a Drive, entrega 1 (claude/CONTEXTO-DATOS-DIARIOS v3, §1 y §11). Lógica pura, sin Android, para probarla con JUnit.
 *  - Por cada día D cerrado (12:00 de D+1, hora de Chile), 5 CSV SOLO de D y un manifest, en datos-diarios/AAAA-MM-DD/.
 *  - Se reutilizan los CSV vigentes (gimnasio, hc-, cintura) filtrados a un día; comidas-… es nuevo.
 *  - Nunca se reemplaza un archivo: lo que cambió va como -vN y después un manifest nuevo; si nada cambió, no se sube nada.
 * Los datos quedan solo en el celular y en el Drive de Héctor; nunca en el repositorio.
 */
object DatosDiarios {
    const val CARPETA = "datos-diarios"
    val ZONA = Gimnasio.ZONA
    val TIPOS = listOf("fuerza", "hc-diario", "hc-actividades", "cintura", "comidas")
    const val VENTANA_DIAS = 7L
    val CIERRE: LocalTime = LocalTime.of(12, 0)
    val COLUMNAS_COMIDAS = listOf("fecha", "tipo", "hora_programada", "preparacion_indicada", "estado", "hecha", "intercambio", "version", "registrado_en")

    // ---------- días ----------
    /** Cierre de los datos de D: 12:00 de D+1 en Santiago (§1.6). */
    fun cierre(d: LocalDate): ZonedDateTime = ZonedDateTime.of(d.plusDays(1), CIERRE, ZONA)
    fun cerrado(d: LocalDate, ahora: ZonedDateTime): Boolean = !ahora.isBefore(cierre(d))

    /** Días que se revisan al tocar "Subir ahora": los 7 anteriores a hoy que ya cerraron, del más antiguo al más nuevo (§1.5.7). */
    fun ventana(ahora: ZonedDateTime): List<LocalDate> {
        val hoy = ahora.withZoneSameInstant(ZONA).toLocalDate()
        return (VENTANA_DIAS downTo 1).map { hoy.minusDays(it) }.filter { cerrado(it, ahora) }
    }

    /** Antes de las 12:00, ayer todavía no cierra (§1.6). */
    fun ayerAbierto(ahora: ZonedDateTime): Boolean = !cerrado(ahora.withZoneSameInstant(ZONA).toLocalDate().minusDays(1), ahora)

    // ---------- nombres ----------
    private fun sufijo(v: Int) = if (v > 1) "-v$v" else ""
    fun nombre(tipo: String, perfil: String, d: LocalDate, v: Int): String = when (tipo) {
        "fuerza" -> "fuerza-$perfil-$d"
        "hc-diario" -> "hc-$perfil-diario-$d"
        "hc-actividades" -> "hc-$perfil-actividades-$d"
        "cintura" -> "cintura-$perfil-$d"
        "comidas" -> "comidas-$perfil-$d"
        else -> throw IllegalArgumentException(tipo)
    } + sufijo(v) + ".csv"
    fun nombreManifest(perfil: String, d: LocalDate, v: Int): String = "manifest-$perfil-$d${sufijo(v)}.json"

    // ---------- los 5 archivos ----------
    data class Archivo(val tipo: String, val texto: String, val filas: Int) { val sha256: String get() = JsJson.sha256(texto) }

    /** Las 20 columnas del CSV de gimnasio, solo con las sesiones cuya fecha es D (aunque se hayan registrado otro día). */
    fun fuerza(sesiones: List<JsonObject>, d: LocalDate): Archivo = with(Gimnasio) {
        val del = sesiones.filter { it.txt("fecha") == d.toString() }
        Archivo("fuerza", "﻿" + Gimnasio.csv(del) + "\r\n", del.sumOf { s -> s.arr("ejercicios").sumOf { it.arr("series").size } })
    }

    /** 1 fila: la regla del CSV semanal (sueño de la noche que termina en D, primer peso, última FC en reposo). */
    fun hcDiario(d: LocalDate, fc: List<SaludCsv.Fc>, sueno: List<SaludCsv.Sueno>, peso: List<SaludCsv.Peso>): Archivo {
        val filas = SaludCsv.diario(SaludCsv.sabadoDe(d), fc, sueno, peso, ZONA).filter { it.fecha == d }
        return Archivo("hc-diario", SaludCsv.csvDiario(filas, ZONA), filas.size)
    }

    /** Las actividades que EMPIEZAN en D. */
    fun hcActividades(d: LocalDate, lista: List<SaludCsv.Actividad>): Archivo {
        val filas = SaludCsv.actividades(SaludCsv.sabadoDe(d), lista, ZONA).filter { it.fecha == d }
        return Archivo("hc-actividades", SaludCsv.csvActividades(filas), filas.size)
    }

    fun cintura(registros: List<Cintura.Registro>, d: LocalDate): Archivo {
        val filas = registros.filter { it.fecha == d.toString() }.sortedWith(compareBy({ it.fecha }, { it.hora }))
        return Archivo("cintura", Cintura.csv(filas), filas.size)
    }

    /** Siempre 5 filas, una por comida, marcada o no (§1.3.5). Nunca fotos, notas ni kcal. */
    fun filasComidas(e: EstadoComidas, d: LocalDate): List<List<String>> = Comidas.TIPOS.map { t ->
        val f = d.toString()
        val c = e.comidaDe(f, t)
        val r = e.registros[Comidas.clave(f, t)]
        val estado = (r?.get("estado") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        fun texto(v: JsonElement?) = if (Comidas.verdadero(v)) Comidas.js(v) else ""
        listOf(f, t, texto(c?.get("hora")), texto(c?.get("preparacion")), estado ?: "",
            if (estado == "indicado" || estado == "otro") "si" else "no",
            if (estado == null) "" else if (c?.get("intercambio") is JsonObject) "si" else "no",
            if (estado == null) "" else ((r?.get("version") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()?.toString() ?: ""),
            if (estado == null) "" else texto(r?.get("actualizado")))
    }

    fun comidas(e: EstadoComidas, d: LocalDate): Archivo {
        val filas = filasComidas(e, d)
        return Archivo("comidas", csv(COLUMNAS_COMIDAS, filas), filas.size)
    }

    /**
     * D-09: el programa de alimentación que cubre D (desde ≤ D ≤ hasta). Primero el activo; si no lo cubre, el guardado más
     * reciente (importado_en) en programas_alimentacion/<id> que lo cubra. [guardados]: (programa, importado_en).
     */
    fun programaDe(d: LocalDate, activo: JsonObject?, guardados: List<Pair<JsonObject, String>>): JsonObject? {
        fun cubre(p: JsonObject?): Boolean {
            val i = p?.get("programa") as? JsonObject ?: return false
            val desde = (i["desde"] as? JsonPrimitive)?.content ?: return false; val hasta = (i["hasta"] as? JsonPrimitive)?.content ?: return false
            return d.toString() in desde..hasta
        }
        if (cubre(activo)) return activo
        return guardados.filter { cubre(it.first) }.maxByOrNull { it.second }?.first
    }

    /**
     * D-09: las comidas de D con el programa que lo cubre y sus intercambios. Si no hay programa que lo cubra, o los intercambios
     * guardados son de otro programa (se perdieron al importar el siguiente), un día ya subido no se recalcula (null): queda vigente
     * lo subido, para no subir un -vN con menos datos.
     */
    fun comidasDelDia(d: LocalDate, activo: JsonObject?, guardados: List<Pair<JsonObject, String>>, intercambios: JsonObject?,
                      registros: List<Respaldo.Doc>, yaSubido: Boolean): Archivo? {
        val p = programaDe(d, activo, guardados)
        val id = (p?.get("programa") as? JsonObject)?.get("id")
        val completo = p != null && (intercambios == null || intercambios["programa_id"] == id)
        if (!completo && yaSubido) return null
        return comidas(EstadoComidas(p, intercambios, registros), d)
    }

    private fun campo(s: String): String = if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun csv(columnas: List<String>, filas: List<List<String>>): String =
        "﻿" + (listOf(columnas) + filas).joinToString("\r\n") { f -> f.joinToString(";") { campo(it) } } + "\r\n"

    // ---------- lo que ya se subió (por día y por tipo) ----------
    /** [permisos]: en un hc-, los permisos de Health Connect con que se leyó ese archivo (§1.5.8); null si no se sabe. */
    data class Subido(val v: Int, val nombre: String, val sha256: String, val filas: Int, val permisos: Set<String>? = null)
    /** [version]: la del último manifest subido. [manifestPendiente]: se subió un CSV y falta su manifest (p. ej. se cortó internet). */
    data class EstadoDia(val version: Int, val tipos: Map<String, Subido>, val manifestPendiente: Boolean)

    fun estadoAJson(e: EstadoDia): String = JsJson.stringify(Apoyo.objeto(
        "version" to JsonPrimitive(e.version), "manifest_pendiente" to JsonPrimitive(e.manifestPendiente),
        "tipos" to JsonObject(e.tipos.mapValues { (_, s) -> Apoyo.objeto("v" to JsonPrimitive(s.v), "nombre" to JsonPrimitive(s.nombre),
            "sha256" to JsonPrimitive(s.sha256), "filas" to JsonPrimitive(s.filas),
            "permisos" to (s.permisos?.let { p -> JsonArray(p.sorted().map { JsonPrimitive(it) }) } ?: JsonNull)) })))

    fun estadoDesdeJson(s: String?): EstadoDia? = if (s == null) null else runCatching {
        val o = Json.parseToJsonElement(s).jsonObject
        fun JsonObject.i(k: String) = (this[k] as JsonPrimitive).content.toInt()
        EstadoDia(o.i("version"), (o["tipos"] as JsonObject).mapValues { (_, e) -> val v = e as JsonObject
            Subido(v.i("v"), (v["nombre"] as JsonPrimitive).content, (v["sha256"] as JsonPrimitive).content, v.i("filas"),
                (v["permisos"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet()) },
            (o["manifest_pendiente"] as JsonPrimitive).content == "true")
    }.getOrNull()

    /** Los archivos que hay que subir: los nuevos o los que cambiaron (sha256 distinto), cada uno con su siguiente -vN. */
    fun cambios(archivos: List<Archivo>, previo: EstadoDia?): List<Pair<Archivo, Int>> = archivos.mapNotNull { a ->
        val p = previo?.tipos?.get(a.tipo)
        if (p != null && p.sha256 == a.sha256) null else a to ((p?.v ?: 0) + 1)
    }

    /**
     * Mejora §1.5.6 (v3.1): la mayor -vN que ya hay en la carpeta del día en Drive, por tipo y para "manifest" (sin sufijo = 1).
     * Así, tras reinstalar la app o cambiar de celular, la numeración sigue desde Drive y no repite nombres.
     */
    fun versionesEnDrive(nombres: List<String>, perfil: String, d: LocalDate): Map<String, Int> {
        val bases = TIPOS.associateWith { nombre(it, perfil, d, 1).removeSuffix(".csv") to ".csv" } + ("manifest" to (nombreManifest(perfil, d, 1).removeSuffix(".json") to ".json"))
        return bases.mapNotNull { (tipo, b) ->
            val r = Regex("^" + Regex.escape(b.first) + "(?:-v(\\d+))?" + Regex.escape(b.second) + "$")
            nombres.mapNotNull { n -> r.find(n)?.let { it.groupValues[1].ifEmpty { "1" }.toInt() } }.maxOrNull()?.let { tipo to it }
        }.toMap()
    }

    /** Hay que subir algo si cambió un archivo o quedó un manifest sin subir. Tocar el botón dos veces no crea versiones. */
    fun hayQueSubir(archivos: List<Archivo>, previo: EstadoDia?): Boolean = cambios(archivos, previo).isNotEmpty() || previo?.manifestPendiente == true

    /** Los archivos que salen de la base del celular (sin Health Connect). [com] null = comidas no se recalcula (D-09). */
    fun locales(ses: List<JsonObject>, cin: List<Cintura.Registro>, com: (LocalDate) -> Archivo?, d: LocalDate): List<Archivo> =
        listOfNotNull(fuerza(ses, d), cintura(cin, d), com(d))

    /** "N días por subir": días de la ventana sin manifest, con un archivo local cambiado o con un manifest pendiente. */
    fun porSubir(dias: List<LocalDate>, locales: (LocalDate) -> List<Archivo>, previo: (LocalDate) -> EstadoDia?): Int = dias.count { d ->
        val p = previo(d)
        p == null || p.version == 0 || hayQueSubir(locales(d), p)
    }

    // ---------- manifest (§1.4) ----------
    data class Faltante(val tipo: String, val motivo: String, val detalle: String)
    data class LecturaHc(val leidoEn: ZonedDateTime?, val segundoPlano: Boolean, val permisosFaltantes: List<String>)

    fun iso(z: ZonedDateTime): String = z.withZoneSameInstant(ZONA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** reporte_apoyo del manifest, desde reportes_apoyo/D (el reporte no se copia: el análisis lo lee en su carpeta). */
    fun reporteApoyo(r: JsonObject?): JsonObject = if (r == null)
        Apoyo.objeto("estado" to JsonPrimitive("no-guardado"), "version" to JsonNull, "tardio" to JsonNull, "enviado_drive" to JsonPrimitive(false))
    else with(Apoyo) {
        val v = r.n("version")?.toInt()
        Apoyo.objeto("estado" to JsonPrimitive("guardado"), "version" to Apoyo.num(v), "tardio" to (r.b("tardio")?.let { JsonPrimitive(it) } ?: JsonNull),
            "enviado_drive" to JsonPrimitive(v != null && r.o("enviado").n("version")?.toInt() == v))
    }

    /** El manifest nombra el archivo vigente de cada tipo, aunque no haya cambiado. Un tipo que nunca se exportó no aparece. */
    fun manifest(perfil: String, d: LocalDate, version: Int, generadoEn: ZonedDateTime, appVersion: String, appCodigo: Int, motivo: String,
                 vigentes: Map<String, Subido>, faltantes: List<Faltante>, hc: LecturaHc, reporte: JsonObject?): String {
        // Regla de cobertura (v3.1): cada tipo está en vigentes o en faltantes; uno que nunca se exportó y no vino como faltante se agrega aquí.
        val falt = faltantes + TIPOS.filter { t -> t !in vigentes && faltantes.none { it.tipo == t } }.map { Faltante(it, "error", "No se exportó.") }
        val vig = TIPOS.mapNotNull { t -> vigentes[t]?.let { s -> Apoyo.objeto("tipo" to JsonPrimitive(t), "nombre" to JsonPrimitive(s.nombre),
            "filas" to JsonPrimitive(s.filas), "sha256" to JsonPrimitive(s.sha256)) } }
        val o = Apoyo.objeto(
            "formato" to JsonPrimitive("datos-diarios-manifest"), "version_formato" to JsonPrimitive("1.0"),
            "perfil" to JsonPrimitive(perfil), "fecha" to JsonPrimitive(d.toString()), "zona_horaria" to JsonPrimitive(ZONA.id),
            "version" to JsonPrimitive(version), "generado_en" to JsonPrimitive(iso(generadoEn)),
            "app" to Apoyo.objeto("version" to JsonPrimitive(appVersion), "codigo" to JsonPrimitive(appCodigo)),
            "motivo" to JsonPrimitive(motivo),
            "vigentes" to JsonArray(vig),
            "faltantes" to JsonArray(falt.map { Apoyo.objeto("tipo" to JsonPrimitive(it.tipo), "motivo" to JsonPrimitive(it.motivo), "detalle" to JsonPrimitive(it.detalle)) }),
            "health_connect" to Apoyo.objeto("leido_en" to Apoyo.txt(hc.leidoEn?.let { iso(it) }), "segundo_plano" to JsonPrimitive(hc.segundoPlano),
                "permisos_faltantes" to JsonArray(hc.permisosFaltantes.map { JsonPrimitive(it) })),
            "reporte_apoyo" to reporteApoyo(reporte),
            "completo" to JsonPrimitive(falt.isEmpty() && TIPOS.all { it in vigentes }))
        return JsJson.indentado(o)
    }

    /** "manual" en la primera subida del día; lo que cambia después es "tardio" (§1.6). La entrega 2 agrega "automatico". */
    fun motivo(previo: EstadoDia?): String = if (previo == null || previo.version == 0) "manual" else "tardio"

    // ---------- Health Connect: qué falta ----------
    /** Nombres de permisos del manifest, en el orden de §1.4. */
    val PERMISOS = listOf("fc_reposo", "sueno", "peso", "ejercicio", "distancia", "desnivel", "fc")

    /** Los permisos que usa cada hc- (los demás no cambian su contenido). */
    val PERMISOS_DE = mapOf("hc-diario" to setOf("fc_reposo", "sueno", "peso"), "hc-actividades" to setOf("ejercicio", "distancia", "desnivel", "fc"))
    fun permisosDe(tipo: String, concedidos: Set<String>): Set<String> = concedidos intersect (PERMISOS_DE[tipo] ?: emptySet())

    const val AVISO_PERMISOS = "Health Connect tiene menos permisos que antes; revisa los permisos de Génesis Gym."

    /**
     * §1.5.8 (v3.3): un hc- leído con menos permisos que los de la lectura que produjo su archivo vigente no se sube (no hay -vN):
     * el anterior sigue vigente y el tipo va a faltantes con "permisos-reducidos". Devuelve (los hc- que sí se pueden subir, faltantes).
     */
    fun filtrarPermisos(hc: List<Archivo>, concedidos: Set<String>, previo: EstadoDia?): Pair<List<Archivo>, List<Faltante>> {
        val (bloq, ok) = hc.partition { a ->
            val antes = previo?.tipos?.get(a.tipo)?.permisos ?: return@partition false
            !permisosDe(a.tipo, concedidos).containsAll(permisosDe(a.tipo, antes))
        }
        return ok to bloq.map { a ->
            val faltan = permisosDe(a.tipo, previo!!.tipos[a.tipo]!!.permisos!!) - concedidos
            Faltante(a.tipo, "permisos-reducidos", "Se leyó sin: ${faltan.sorted().joinToString(", ")}. Sigue vigente el archivo anterior.")
        }
    }

    /** Un archivo hc- que no se puede exportar (no se escribe y va a faltantes). Con permisos parciales sí se escribe, con columnas vacías. */
    fun faltantesHc(disponible: Boolean, error: String?, concedidos: Set<String>): List<Faltante> = when {
        !disponible -> listOf("hc-diario", "hc-actividades").map { Faltante(it, "health-connect-no-disponible", "Health Connect no está disponible en este celular.") }
        error != null -> listOf("hc-diario", "hc-actividades").map { Faltante(it, "error", error) }
        else -> listOfNotNull(
            if (concedidos.none { it in setOf("fc_reposo", "sueno", "peso") }) Faltante("hc-diario", "sin-permiso-health-connect", "Sin permiso de FC en reposo, sueño ni peso.") else null,
            if ("ejercicio" !in concedidos) Faltante("hc-actividades", "sin-permiso-health-connect", "Sin permiso de ejercicio.") else null)
    }
}
