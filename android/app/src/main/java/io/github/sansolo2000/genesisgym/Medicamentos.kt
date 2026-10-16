package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Apoyo.a
import io.github.sansolo2000.genesisgym.Apoyo.b
import io.github.sansolo2000.genesisgym.Apoyo.n
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Módulo Medicamentos (Héctor, 15-oct; requerimiento en claude/Vademecum.md, contrato en CONTEXTO-MODULO-MEDICAMENTOS.md).
 * Lógica pura, sin Android, para probarla con JUnit.
 * - Cada tratamiento es de Héctor o de Majo. El medicamento sale del catálogo, que llega con el plan Génesis (no va al repositorio).
 * - La unidad y la condición de fraccionable salen del catálogo y de la configuración (config-medicamentos.json), nunca del nombre.
 * - Una sola modalidad de frecuencia por tratamiento. Los horarios sugeridos son editables y no son recomendaciones médicas.
 * - Las horas se guardan como hora local (HH:MM) y se calculan en la zona vigente del celular: una toma de las 09:00
 *   sigue a las 09:00 si cambia la zona o el horario de verano.
 * - El historial no se reescribe: cada respuesta es un documento nuevo con los datos del tratamiento de ese momento.
 */
object Medicamentos {
    const val HECTOR = "hector"; const val MAJO = "majo"
    val PERSONAS = linkedMapOf(HECTOR to "Héctor", MAJO to "Majo")

    const val VECES_DIA = "veces_al_dia"; const val INTERVALO_HORAS = "intervalo_horas"; const val INTERVALO_DIAS = "intervalo_dias"
    const val DIAS_SEMANA = "dias_semana"; const val CICLOS = "ciclos"; const val EVENTOS = "eventos"
    val MODALIDADES = linkedMapOf(VECES_DIA to "Veces al día", INTERVALO_HORAS to "Cada N horas", INTERVALO_DIAS to "Cada N días",
        DIAS_SEMANA to "Días de la semana", CICLOS to "Ciclos", EVENTOS to "Con un evento")
    const val SEGUN_NECESIDAD = "segun_necesidad"

    const val DUR_DIAS = "dias"; const val DUR_FECHA = "fecha"; const val DUR_INDEFINIDA = "indefinida"

    const val CONFIRMADA = "confirmada"; const val POSTERGADA = "postergada"; const val OMITIDA = "omitida"; const val SIN_RESPUESTA = "sin_respuesta"
    val ESTADOS = linkedMapOf(CONFIRMADA to "Confirmada", POSTERGADA to "Postergada", OMITIDA to "Omitida", SIN_RESPUESTA to "Sin respuesta")
    val POSTERGAR = listOf(15, 30, 60, 90)
    /** Sin respuesta, la alerta se repite cada 10 minutos; a las 2 horas de la primera se cierra como "sin respuesta". */
    const val REPETIR_MIN = 10L
    const val VENTANA_MIN = 120L

    const val SECCION_TIPO = "genesis-medicamentos"
    const val SECCION_VERSION = "1.0"
    const val COLECCION = "medicamentos"

    val DIAS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    /** Texto fijo junto a toda sugerencia de horario (CLAUDE.md §3: ninguna función sugiere cambiar la medicación). */
    const val AVISO_SUGERENCIA = "Horario sugerido y editable. No es una recomendación médica: la receta o la indicación de tu médico siempre prevalece."

    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    fun horaValida(h: String?): Boolean = h != null && HHMM.matches(h)
    fun hora(h: String): LocalTime = LocalTime.parse(h)
    private val FMT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val FMT_CLAVE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
    fun isoUtc(i: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(i)

    // ---------- catálogo ----------
    data class Med(val nombre: String, val ids: List<String>, val tag: String? = null, val unidadDosis: String? = null,
                   val pasoMinimo: String? = null, val fraccionable: String? = null)

    /** Minúsculas, sin tildes ni diéresis (la ñ queda como n) y con un solo espacio entre palabras. */
    fun normalizar(t: String): String = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    fun medDesdeJson(e: JsonElement?): Med? {
        val o = e as? JsonObject ?: return null
        val nombre = o.s("nombre")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ids = o.a("ids")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: return null
        if (ids.isEmpty()) return null
        return Med(nombre, ids, o.s("tag"), o.s("unidad_dosis"), o.s("paso_minimo"), o.s("fraccionable"))
    }

    fun medAJson(m: Med): JsonObject = Apoyo.objeto("nombre" to JsonPrimitive(m.nombre), "ids" to JsonArray(m.ids.map { JsonPrimitive(it) }),
        "tag" to Apoyo.txt(m.tag), "unidad_dosis" to Apoyo.txt(m.unidadDosis), "fraccionable" to Apoyo.txt(m.fraccionable))

    /** El catálogo en memoria, con los nombres ya normalizados. La búsqueda devuelve solo los primeros [max]. */
    class Catalogo(val lista: List<Med>) {
        private val claves: List<String> = lista.map { normalizar(it.nombre) }
        val tamano: Int get() = lista.size

        /**
         * Coincidencia parcial, sin distinguir mayúsculas ni tildes: cada palabra escrita debe estar dentro del nombre.
         * Primero los que empiezan por lo escrito, luego los que tienen una palabra que empieza así y al final el resto.
         * Si se escriben solo dígitos, también busca por identificador. Devuelve (resultados, total de coincidencias).
         */
        fun buscar(texto: String, max: Int = 100): Pair<List<Med>, Int> {
            val q = normalizar(texto)
            if (q.isEmpty()) return emptyList<Med>() to 0
            val palabras = q.split(' ')
            val digitos = q.all { it.isDigit() }
            val hits = ArrayList<Pair<Int, Int>>()
            for (i in lista.indices) {
                val k = claves[i]
                val rango = when {
                    k.startsWith(q) -> 0
                    palabras.all { k.contains(it) } -> if (k.split(' ').any { it.startsWith(palabras[0]) }) 1 else 2
                    digitos && lista[i].ids.any { it.startsWith(q) } -> 3
                    else -> -1
                }
                if (rango >= 0) hits.add(rango to i)
            }
            hits.sortWith(compareBy<Pair<Int, Int>>({ it.first }, { claves[it.second] }))
            return hits.take(max).map { lista[it.second] } to hits.size
        }
    }

    // ---------- configuración (config-medicamentos.json o la sección del plan) ----------
    data class Sugerencia(val tipo: String, val administraciones: Int?, val intervaloHoras: Int?, val horarios: List<String>)
    data class EventoCfg(val id: String, val nombre: String, val hora: String?)
    data class Unidad(val id: String, val nombre: String, val paso: Double?)
    data class Config(
        val versionHorarios: Int, val horaPorDefecto: String, val sugerencias: List<Sugerencia>, val eventos: List<EventoCfg>,
        val versionDosis: Int, val pasoPorFraccionable: Map<String, Double>, val unidades: List<Unidad>, val unidadesPorCatalogo: Map<String, List<String>>
    ) {
        fun evento(id: String?): EventoCfg? = eventos.firstOrNull { it.id == id }
        fun unidad(id: String?): Unidad? = unidades.firstOrNull { it.id == id }
    }

    private fun entero(o: JsonObject?, k: String): Int? = o.n(k)?.takeIf { it == Math.floor(it) }?.toInt()
    private fun textos(e: JsonElement?): List<String>? = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }

    /** Lee config_horarios; devuelve null y los errores si no cumple. */
    fun horariosDesdeJson(o: JsonObject, err: MutableList<String>): Triple<Int, String, Pair<List<Sugerencia>, List<EventoCfg>>>? {
        val n0 = err.size
        val version = entero(o, "version")?.takeIf { it >= 1 } ?: run { err.add("config_horarios.version: debe ser un entero mayor o igual a 1."); 0 }
        val defecto = o.s("hora_por_defecto") ?: "09:00"
        if (!horaValida(defecto)) err.add("config_horarios.hora_por_defecto: debe ser HH:MM.")
        val sug = (o.a("sugerencias") ?: JsonArray(emptyList())).mapIndexedNotNull { i, e ->
            val x = e as? JsonObject
            val tipo = x.s("tipo_frecuencia")
            val hs = textos(x?.get("horarios"))
            if (x == null || tipo !in MODALIDADES.keys || hs == null || hs.isEmpty() || hs.any { !horaValida(it) }) {
                err.add("config_horarios.sugerencias[$i]: necesita tipo_frecuencia válido y horarios HH:MM."); null
            } else Sugerencia(tipo!!, entero(x, "administraciones"), entero(x, "intervalo_horas"), hs)
        }
        val ev = (o.a("eventos") ?: JsonArray(emptyList())).mapIndexedNotNull { i, e ->
            val x = e as? JsonObject
            val id = x.s("id"); val nombre = x.s("nombre"); val h = x.s("hora_sugerida")
            if (id.isNullOrBlank() || nombre.isNullOrBlank() || (h != null && !horaValida(h))) { err.add("config_horarios.eventos[$i]: necesita id, nombre y hora_sugerida HH:MM o null."); null }
            else EventoCfg(id, nombre, h)
        }
        if (ev.map { it.id }.toSet().size != ev.size) err.add("config_horarios.eventos: hay ids repetidos.")
        return if (err.size == n0) Triple(version, defecto, sug to ev) else null
    }

    /** Lee config_dosis; devuelve null y los errores si no cumple. */
    fun dosisDesdeJson(o: JsonObject, err: MutableList<String>): Triple<Int, Map<String, Double>, Pair<List<Unidad>, Map<String, List<String>>>>? {
        val n0 = err.size
        val version = entero(o, "version")?.takeIf { it >= 1 } ?: run { err.add("config_dosis.version: debe ser un entero mayor o igual a 1."); 0 }
        val pasos = LinkedHashMap<String, Double>()
        o.o("paso_por_fraccionable")?.forEach { (k, v) ->
            val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (d == null || d <= 0) { err.add("config_dosis.paso_por_fraccionable[$k]: debe ser un número mayor que 0.") } else { pasos[k] = d }
        } ?: err.add("config_dosis.paso_por_fraccionable: falta.")
        if ("sin_dato" !in pasos) err.add("config_dosis.paso_por_fraccionable: falta \"sin_dato\" (el paso cuando el catálogo no lo trae).")
        val unidades = (o.a("unidades") ?: JsonArray(emptyList())).mapIndexedNotNull { i, e ->
            val x = e as? JsonObject
            val id = x.s("id"); val nombre = x.s("nombre"); val paso = x.n("paso")
            if (id.isNullOrBlank() || nombre.isNullOrBlank() || (paso != null && paso <= 0)) { err.add("config_dosis.unidades[$i]: necesita id, nombre y un paso mayor que 0 (u omitido)."); null }
            else Unidad(id, nombre, paso)
        }
        if (unidades.isEmpty()) err.add("config_dosis.unidades: falta o está vacía.")
        val ids = unidades.map { it.id }.toSet()
        if (ids.size != unidades.size) err.add("config_dosis.unidades: hay ids repetidos.")
        val porCat = LinkedHashMap<String, List<String>>()
        o.o("unidades_por_catalogo")?.forEach { (k, v) ->
            val l = textos(v)
            if (l == null || l.isEmpty() || l.any { it !in ids }) { err.add("config_dosis.unidades_por_catalogo[$k]: debe ser una lista de ids de unidades.") } else { porCat[k] = l }
        }
        return if (err.size == n0) Triple(version, pasos, unidades to porCat) else null
    }

    /** Arma la configuración. Lo que no venga en [horarios] o [dosis] se toma de [base] (la que trae la app). */
    fun config(horarios: JsonObject?, dosis: JsonObject?, base: Config?): Pair<Config?, List<String>> {
        val err = mutableListOf<String>()
        val h = horarios?.let { horariosDesdeJson(it, err) }
        val d = dosis?.let { dosisDesdeJson(it, err) }
        if (err.isNotEmpty()) return null to err
        if ((h == null || d == null) && base == null) return null to listOf("Falta la configuración de medicamentos (config_horarios y config_dosis).")
        return Config(
            h?.first ?: base!!.versionHorarios, h?.second ?: base!!.horaPorDefecto, h?.third?.first ?: base!!.sugerencias, h?.third?.second ?: base!!.eventos,
            d?.first ?: base!!.versionDosis, d?.second ?: base!!.pasoPorFraccionable, d?.third?.first ?: base!!.unidades, d?.third?.second ?: base!!.unidadesPorCatalogo
        ) to emptyList()
    }

    /** El archivo config-medicamentos.json de la app: { config_horarios, config_dosis }. */
    fun configDeArchivo(o: JsonObject): Pair<Config?, List<String>> = config(o.o("config_horarios"), o.o("config_dosis"), null)

    // ---------- sugerencias de horario ----------
    private fun hhmm(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

    /** n tomas repartidas en el día desde [desde] (cuando la configuración no trae esa cantidad). */
    fun repartir(n: Int, desde: String): List<String> {
        val ini = hora(desde).toSecondOfDay() / 60
        return (0 until n).map { hhmm(ini + it * (1440 / n)) }
    }

    /** Las horas de un día para "cada [h] horas" partiendo en [inicio] (solo para mostrar). */
    fun horasDelIntervalo(inicio: String, h: Int): List<String> {
        if (!horaValida(inicio) || h <= 0) return emptyList()
        val ini = hora(inicio).toSecondOfDay() / 60
        return if (h >= 24) listOf(inicio) else (0 until 24 / h + (if (24 % h == 0) 0 else 1)).map { ini + it * h * 60 }.filter { it < ini + 1440 }.map { hhmm(it) }
    }

    /** Horarios sugeridos para la modalidad (§4.1 y §5). Siempre editables. */
    fun sugerir(cfg: Config, modalidad: String, administraciones: Int? = null, intervaloHoras: Int? = null): List<String> {
        cfg.sugerencias.firstOrNull {
            it.tipo == modalidad && (modalidad != VECES_DIA || it.administraciones == administraciones) && (modalidad != INTERVALO_HORAS || it.intervaloHoras == intervaloHoras)
        }?.let { return it.horarios }
        return when (modalidad) {
            VECES_DIA -> { val n = (administraciones ?: 1).coerceIn(1, 12); if (n == 1) listOf(cfg.horaPorDefecto) else repartir(n, "07:00") }
            INTERVALO_HORAS -> horasDelIntervalo(cfg.horaPorDefecto, intervaloHoras ?: 24)
            else -> listOf(cfg.horaPorDefecto)
        }
    }

    // ---------- dosis ----------
    fun leerCantidad(t: String): Double? = t.trim().replace(',', '.').takeIf { Regex("^\\d+(\\.\\d+)?$").matches(it) }?.toDoubleOrNull()
    fun numeroTexto(d: Double): String = JsJson.numero(BigDecimal(d.toString()).toPlainString()).replace('.', ',')

    /** Unidades del catálogo para este medicamento (la primera es la predeterminada). Vacío si el catálogo no la trae o no está en la configuración. */
    fun unidadesDe(cfg: Config, m: Med?): List<String> = m?.unidadDosis?.trim()?.let { cfg.unidadesPorCatalogo[it] } ?: emptyList()
    fun unidadPorDefecto(cfg: Config, m: Med?): String? = unidadesDe(cfg, m).firstOrNull()

    /** Paso mínimo: el mayor entre el de "fraccionable" (o "sin_dato") y el de la unidad elegida. */
    fun paso(cfg: Config, fraccionable: String?, unidad: String?): Double {
        val pf = cfg.pasoPorFraccionable[fraccionable?.trim() ?: "sin_dato"] ?: cfg.pasoPorFraccionable["sin_dato"] ?: 0.25
        return maxOf(pf, cfg.unidad(unidad)?.paso ?: 0.0)
    }

    fun esMultiplo(x: Double, p: Double): Boolean = BigDecimal(x.toString()).remainder(BigDecimal(p.toString())).signum() == 0

    /** null si la dosis es válida; si no, el mensaje para la pantalla. */
    fun validarDosis(cfg: Config, cantidad: Double?, unidad: String?, fraccionable: String?): String? {
        if (cantidad == null) return "Escribe la cantidad de la dosis (por ejemplo 1 o 0,5)."
        if (cantidad <= 0) return "La cantidad debe ser mayor que cero."
        if (unidad.isNullOrBlank() || cfg.unidad(unidad) == null) return "Elige la unidad de la dosis."
        val p = paso(cfg, fraccionable, unidad)
        if (esMultiplo(cantidad, p)) return null
        return when {
            p >= 1.0 && fraccionable?.trim() == "No" -> "Este medicamento no es fraccionable: la cantidad debe ser un número entero."
            p >= 1.0 -> "Con esta unidad la cantidad debe ser un número entero."
            p == 0.25 -> "Solo se aceptan fracciones de 0,25 o 0,5 (por ejemplo 0,25 · 0,5 · 0,75 · 1 · 1,5)."
            else -> "La cantidad debe ser múltiplo de ${numeroTexto(p)}."
        }
    }

    // ---------- frecuencia ----------
    data class Toma(val evento: String, val hora: String?)
    data class Frecuencia(
        val modalidad: String,
        val veces: Int? = null,
        val intervaloHoras: Int? = null,
        val intervaloDias: Int? = null,
        val dias: List<Int> = emptyList(),          // 1 = lunes … 7 = domingo
        val diasToma: Int? = null, val diasDescanso: Int? = null, val ciclosMax: Int? = null,
        val horarios: List<String> = emptyList(),   // veces al día, días de la semana y ciclos; en "cada N horas" y "cada N días", la hora de la toma
        val eventos: List<Toma> = emptyList()
    ) {
        val segunNecesidad: Boolean get() = modalidad == EVENTOS && eventos.any { it.evento == SEGUN_NECESIDAD }
    }

    /** Qué modalidad admite cada campo: un campo de otra modalidad es un error (las modalidades son excluyentes). */
    private fun camposUsados(f: Frecuencia): Map<String, Set<String>> = buildMap {
        if (f.veces != null) put("veces", setOf(VECES_DIA))
        if (f.intervaloHoras != null) put("intervalo_horas", setOf(INTERVALO_HORAS))
        if (f.intervaloDias != null) put("intervalo_dias", setOf(INTERVALO_DIAS))
        if (f.dias.isNotEmpty()) put("dias", setOf(DIAS_SEMANA))
        if (f.diasToma != null || f.diasDescanso != null || f.ciclosMax != null) put("ciclos", setOf(CICLOS))
        if (f.horarios.isNotEmpty()) put("horarios", setOf(VECES_DIA, INTERVALO_HORAS, INTERVALO_DIAS, DIAS_SEMANA, CICLOS))
        if (f.eventos.isNotEmpty()) put("eventos", setOf(EVENTOS))
    }

    fun validarFrecuencia(f: Frecuencia, cfg: Config): List<String> {
        val e = mutableListOf<String>()
        if (f.modalidad !in MODALIDADES) return listOf("Elige una modalidad de frecuencia.")
        val nombre = MODALIDADES.getValue(f.modalidad)
        for ((campo, admite) in camposUsados(f)) if (f.modalidad !in admite)
            e.add("Solo se puede usar una modalidad de frecuencia: \"$nombre\" no se combina con $campo.")
        if (e.isNotEmpty()) return e
        if (f.horarios.any { !horaValida(it) }) e.add("Escribe cada horario como HH:MM, por ejemplo 08:30.")
        else if (f.horarios.toSet().size != f.horarios.size) e.add("Hay horarios repetidos.")
        when (f.modalidad) {
            VECES_DIA -> {
                val v = f.veces
                if (v == null || v !in 1..12) e.add("Veces al día: escribe un número entero entre 1 y 12.")
                else if (f.horarios.size != v) e.add("Veces al día: indica $v ${if (v == 1) "horario" else "horarios"}.")
            }
            INTERVALO_HORAS -> {
                if (f.intervaloHoras == null || f.intervaloHoras !in 1..72) e.add("Cada N horas: escribe un número entero de horas entre 1 y 72.")
                if (f.horarios.size != 1) e.add("Cada N horas: indica la hora de la primera toma.")
            }
            INTERVALO_DIAS -> {
                if (f.intervaloDias == null || f.intervaloDias !in 2..90) e.add("Cada N días: escribe un número entero de días entre 2 y 90.")
                if (f.horarios.size != 1) e.add("Cada N días: indica la hora de la toma.")
            }
            DIAS_SEMANA -> {
                if (f.dias.isEmpty() || f.dias.any { it !in 1..7 } || f.dias.toSet().size != f.dias.size) e.add("Días de la semana: elige uno o más días.")
                if (f.horarios.isEmpty()) e.add("Días de la semana: indica al menos un horario.")
            }
            CICLOS -> {
                if (f.diasToma == null || f.diasToma !in 1..365) e.add("Ciclos: los días de toma deben ser un entero entre 1 y 365.")
                if (f.diasDescanso == null || f.diasDescanso !in 1..365) e.add("Ciclos: los días de descanso deben ser un entero entre 1 y 365.")
                if (f.ciclosMax != null && f.ciclosMax !in 1..100) e.add("Ciclos: la cantidad de ciclos debe ser un entero entre 1 y 100, o indefinida.")
                if (f.horarios.isEmpty()) e.add("Ciclos: indica al menos un horario.")
            }
            EVENTOS -> {
                if (f.eventos.isEmpty()) e.add("Con un evento: elige al menos un evento.")
                if (f.eventos.any { cfg.evento(it.evento) == null }) e.add("Con un evento: hay un evento que no está en la configuración.")
                if (f.segunNecesidad && f.eventos.size > 1) e.add("\"Según necesidad\" no se combina con otros eventos.")
                if (f.eventos.any { it.evento != SEGUN_NECESIDAD && !horaValida(it.hora) }) e.add("Con un evento: indica la hora de cada evento como HH:MM.")
                val tomas = f.eventos.filter { it.evento != SEGUN_NECESIDAD }.map { it.hora }
                if (tomas.toSet().size != tomas.size) e.add("Con un evento: dos eventos no pueden tener la misma hora.")
            }
        }
        return e
    }

    /** Resumen para la pantalla y la alerta, por ejemplo "2 veces al día · 09:00, 21:00". */
    fun resumen(f: Frecuencia, cfg: Config?): String = when (f.modalidad) {
        VECES_DIA -> "${f.veces} ${if (f.veces == 1) "vez" else "veces"} al día · ${f.horarios.joinToString(", ")}"
        INTERVALO_HORAS -> "Cada ${f.intervaloHoras} h desde las ${f.horarios.firstOrNull() ?: "?"}"
        INTERVALO_DIAS -> "Cada ${f.intervaloDias} días a las ${f.horarios.firstOrNull() ?: "?"}"
        DIAS_SEMANA -> f.dias.sorted().joinToString(", ") { DIAS[it - 1] } + " · " + f.horarios.joinToString(", ")
        CICLOS -> "${f.diasToma} días de toma y ${f.diasDescanso} de descanso" + (f.ciclosMax?.let { ", $it ciclos" } ?: ", sin límite de ciclos") + " · " + f.horarios.joinToString(", ")
        EVENTOS -> f.eventos.joinToString(", ") { t -> (cfg?.evento(t.evento)?.nombre ?: t.evento) + (t.hora?.let { " ($it)" } ?: "") }
        else -> ""
    }

    // ---------- duración ----------
    data class Duracion(val tipo: String, val dias: Int? = null, val fin: LocalDate? = null)

    /** Fecha de término (inclusive). Con una cantidad de días, se cuenta desde la fecha de inicio: 7 días desde el lunes terminan el domingo. */
    fun fechaFin(inicio: LocalDate, d: Duracion): LocalDate? = when (d.tipo) {
        DUR_DIAS -> d.dias?.let { inicio.plusDays(it - 1L) }
        DUR_FECHA -> d.fin
        else -> null
    }

    fun validarDuracion(inicio: LocalDate, d: Duracion): List<String> = when (d.tipo) {
        DUR_DIAS -> if (d.dias == null || d.dias !in 1..3650) listOf("Duración: escribe una cantidad de días entre 1 y 3650.") else emptyList()
        DUR_FECHA -> if (d.fin == null) listOf("Duración: escribe la fecha de término como AAAA-MM-DD.")
            else if (d.fin < inicio) listOf("Duración: la fecha de término no puede ser anterior a la de inicio.") else emptyList()
        DUR_INDEFINIDA -> emptyList()
        else -> listOf("Elige la duración: cantidad de días, fecha de término o indefinida.")
    }

    // ---------- tratamiento ----------
    data class Tratamiento(
        val id: String, val persona: String, val med: Med,
        val cantidad: Double, val unidad: String,
        val frecuencia: Frecuencia, val inicio: LocalDate, val duracion: Duracion,
        val alertas: Boolean,
        val version: Int = 1, val vigenteDesde: String = "", val creadoEn: String = "",
        /** D-15: las versiones anteriores vigentes en los últimos días, para que una toma que sonaba al editar siga su cadena. */
        val anteriores: List<Tratamiento> = emptyList(),
        /** O-40 (Héctor, 16-oct): archivado = oculto y sin alertas; el historial se conserva. */
        val archivado: Boolean = false
    ) {
        /** Genera alertas: con alertas activas y sin archivar. */
        val avisa: Boolean get() = alertas && !archivado
        val fin: LocalDate? get() = fechaFin(inicio, duracion)
        fun vencido(hoy: LocalDate): Boolean = fin?.let { it < hoy } ?: false
        val dosisTexto: String get() = "${numeroTexto(cantidad)} $unidad"
    }

    fun validar(t: Tratamiento, cfg: Config): List<String> {
        val e = mutableListOf<String>()
        if (t.persona !in PERSONAS) e.add("Elige la persona: Héctor o Majo.")
        if (t.med.nombre.isBlank() || t.med.ids.isEmpty()) e.add("Elige el medicamento en el buscador.")
        validarDosis(cfg, t.cantidad, t.unidad, t.med.fraccionable)?.let { e.add(it) }
        e.addAll(validarFrecuencia(t.frecuencia, cfg))
        e.addAll(validarDuracion(t.inicio, t.duracion))
        return e
    }

    // ---------- ocurrencias (hora local fija en la zona vigente del celular) ----------
    /** [local]: la fecha y la hora configuradas; [cuando]: el instante en la zona. Si la hora no existe (cambio de hora), Java la corre hacia adelante. */
    data class Ocurrencia(val local: LocalDateTime, val cuando: ZonedDateTime, val evento: String?)

    private fun aplicaElDia(t: Tratamiento, d: LocalDate): Boolean {
        if (d < t.inicio) return false
        val fin = t.fin
        if (fin != null && d > fin) return false
        val f = t.frecuencia
        val n = ChronoUnit.DAYS.between(t.inicio, d)
        return when (f.modalidad) {
            VECES_DIA, EVENTOS -> true
            INTERVALO_DIAS -> n % (f.intervaloDias ?: 1) == 0L
            DIAS_SEMANA -> d.dayOfWeek.value in f.dias
            CICLOS -> {
                val largo = (f.diasToma ?: 1) + (f.diasDescanso ?: 0)
                val ciclo = n / largo
                (f.ciclosMax == null || ciclo < f.ciclosMax) && n % largo < (f.diasToma ?: 1)
            }
            else -> false
        }
    }

    private fun tomasDelDia(f: Frecuencia): List<Pair<String, String?>> =
        if (f.modalidad == EVENTOS) f.eventos.filter { it.evento != SEGUN_NECESIDAD && it.hora != null }.map { it.hora!! to it.evento }
        else f.horarios.map { it to null }

    /** Tomas con instante en (desde, hasta], en la zona [zona]. "Según necesidad" no tiene horario: no genera tomas ni alertas. */
    fun ocurrencias(t: Tratamiento, desde: ZonedDateTime, hasta: ZonedDateTime, zona: ZoneId): List<Ocurrencia> {
        val f = t.frecuencia
        if (f.segunNecesidad) return emptyList()
        val fin = t.fin
        val d0 = maxOf(t.inicio, desde.withZoneSameInstant(zona).toLocalDate().minusDays(1))
        var d1 = hasta.withZoneSameInstant(zona).toLocalDate().plusDays(1)
        if (fin != null && fin < d1) d1 = fin
        if (d1 < d0) return emptyList()
        val locales = mutableListOf<Pair<LocalDateTime, String?>>()
        if (f.modalidad == INTERVALO_HORAS) {
            val h = (f.intervaloHoras ?: return emptyList()).toLong()
            val ancla = LocalDateTime.of(t.inicio, hora(f.horarios.firstOrNull() ?: return emptyList()))
            val ventana = d0.atStartOfDay()
            var k = if (ventana > ancla) ChronoUnit.HOURS.between(ancla, ventana) / h else 0L
            while (true) {
                val x = ancla.plusHours(k * h)
                if (x.toLocalDate() > d1) break
                if (x.toLocalDate() >= t.inicio) locales.add(x to null)
                k++
            }
        } else {
            val tomas = tomasDelDia(f)
            var d = d0
            while (d <= d1) {
                if (aplicaElDia(t, d)) for ((h, ev) in tomas) locales.add(LocalDateTime.of(d, hora(h)) to ev)
                d = d.plusDays(1)
            }
        }
        return locales.map { (l, ev) -> Ocurrencia(l, ZonedDateTime.of(l, zona), ev) }
            .filter { it.cuando.isAfter(desde) && !it.cuando.isAfter(hasta) }
            .sortedWith(compareBy<Ocurrencia>({ it.cuando.toInstant() }, { it.local }))
    }

    /** §6: la primera administración es el próximo horario válido posterior a [ahora] (hoy, mañana o la próxima fecha que corresponda). */
    fun proxima(t: Tratamiento, ahora: ZonedDateTime): Ocurrencia? {
        var desde = ahora
        repeat(30) {
            val hasta = desde.plusDays(31)
            ocurrencias(t, desde, hasta, ahora.zone).firstOrNull()?.let { return it }
            val fin = t.fin
            if (fin != null && hasta.toLocalDate() > fin) return null
            desde = hasta
        }
        return null
    }

    // ---------- alertas: repetición cada 10 minutos por 2 horas ----------
    /** [sonar]: suena ahora; [proximoMs]: cuándo volver a revisar; [cerrar]: pasaron las 2 horas sin respuesta. */
    data class Paso(val sonar: Boolean, val proximoMs: Long?, val cerrar: Boolean)

    /** Qué hacer al llegar la alarma de una cadena que empezó en [inicioMs] (la toma, o el fin de una postergación). */
    fun paso(inicioMs: Long, ahoraMs: Long): Paso {
        val rep = REPETIR_MIN * 60_000L
        val fin = inicioMs + VENTANA_MIN * 60_000L
        if (ahoraMs < inicioMs) return Paso(false, inicioMs, false)
        if (ahoraMs >= fin) return Paso(false, null, true)
        val n = (ahoraMs - inicioMs) / rep + 1
        return Paso(true, minOf(inicioMs + n * rep, fin), false)
    }

    /** Los avisos de una cadena sin respuesta: a los 0, 10, …, 110 minutos. A los 120 se cierra como "sin respuesta". */
    fun avisosDeCadena(inicioMs: Long): List<Long> {
        val l = mutableListOf<Long>(); var t = inicioMs
        while (true) { val p = paso(inicioMs, t); if (!p.sonar) break; l.add(t); t = p.proximoMs ?: break }
        return l
    }

    // ---------- historial ----------
    fun clave(tratamientoId: String, local: LocalDateTime): String = "${tratamientoId}_${local.format(FMT_CLAVE)}"
    fun rutaTratamiento(id: String) = "$COLECCION/t_$id"
    /** Cada respuesta es un documento nuevo; "sin respuesta" tiene ruta fija para no duplicarse. */
    fun rutaRegistro(tratamientoId: String, local: LocalDateTime, estado: String, respuestaMs: Long): String =
        if (estado == SIN_RESPUESTA) "$COLECCION/a_${clave(tratamientoId, local)}_$estado" else "$COLECCION/a_${clave(tratamientoId, local)}_${estado}_$respuestaMs"

    /** Registro inmutable de una respuesta, con los datos del tratamiento vigentes en ese momento. */
    fun registro(t: Tratamiento, oc: Ocurrencia, estado: String, respondidaEn: Instant, postergacionMin: Int?, origen: String): JsonObject {
        val hasta = if (estado == POSTERGADA && postergacionMin != null) isoUtc(respondidaEn.plusSeconds(postergacionMin * 60L)) else null
        return Apoyo.objeto(
            "tipo" to JsonPrimitive("administracion"),
            "tratamiento_id" to JsonPrimitive(t.id),
            "persona" to JsonPrimitive(t.persona),
            "programada" to JsonPrimitive(oc.local.format(FMT_LOCAL)),
            "programada_instante" to JsonPrimitive(isoUtc(oc.cuando.toInstant())),
            "zona" to JsonPrimitive(oc.cuando.zone.id),
            "evento" to Apoyo.txt(oc.evento),
            "estado" to JsonPrimitive(estado),
            "respondida_en" to JsonPrimitive(isoUtc(respondidaEn)),
            "postergacion_min" to Apoyo.num(postergacionMin),
            "postergada_hasta" to Apoyo.txt(hasta),
            "origen" to JsonPrimitive(origen),
            "tratamiento" to Apoyo.objeto(
                "version" to JsonPrimitive(t.version),
                "medicamento" to medAJson(t.med),
                "dosis" to Apoyo.objeto("cantidad" to JsJson.primitivo(t.cantidad), "unidad" to JsonPrimitive(t.unidad)),
                "frecuencia" to frecuenciaAJson(t.frecuencia),
                "inicio" to JsonPrimitive(t.inicio.toString()),
                "fin" to Apoyo.txt(t.fin?.toString())
            )
        )
    }

    data class Registro(val ruta: String, val tratamientoId: String, val programada: LocalDateTime, val estado: String, val respondidaEn: Instant,
                        val postergadaHasta: Instant?, val evento: String?, val data: JsonObject)

    fun registroDesde(ruta: String, o: JsonObject): Registro? = runCatching {
        Registro(ruta, o.s("tratamiento_id")!!, LocalDateTime.parse(o.s("programada")!!, FMT_LOCAL), o.s("estado")!!,
            Instant.parse(o.s("respondida_en")!!), o.s("postergada_hasta")?.let { Instant.parse(it) }, o.s("evento"), o)
    }.getOrNull()

    /** La última respuesta de una toma (null si nunca se respondió). */
    fun ultima(registros: List<Registro>): Registro? = registros.maxWithOrNull(compareBy<Registro>({ it.respondidaEn }, { it.ruta }))

    /**
     * Desde cuándo corre la cadena de alertas de una toma programada en [programadaMs]: desde la toma si no hay respuesta,
     * desde el fin de la última postergación, o null si ya está confirmada, omitida o cerrada sin respuesta.
     */
    fun inicioCadena(programadaMs: Long, registros: List<Registro>): Long? {
        val u = ultima(registros) ?: return programadaMs
        return if (u.estado == POSTERGADA) u.postergadaHasta?.toEpochMilli() else null
    }

    private fun instante(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    /** Cada versión con su vigencia [desde, hasta): las anteriores guardadas y la actual (sin fin). */
    fun tramos(t: Tratamiento): List<Triple<Tratamiento, Instant?, Instant?>> {
        val l = t.anteriores + t.copy(anteriores = emptyList())
        return l.mapIndexed { i, v -> Triple(v, instante(v.vigenteDesde), l.getOrNull(i + 1)?.let { instante(it.vigenteDesde) }) }
    }

    /** La versión que regía cuando se programó una toma (para la copia del historial). */
    fun versionDe(t: Tratamiento, cuando: Instant): Tratamiento =
        tramos(t).lastOrNull { (_, d, h) -> (d == null || !cuando.isBefore(d)) && (h == null || cuando.isBefore(h)) }?.first ?: t.copy(anteriores = emptyList())

    /**
     * Tomas con instante en (desde, hasta], cada una según la versión que regía a esa hora (con alertas).
     * D-15: así una toma de la versión anterior que ya estaba sonando sigue su cadena; una hora nueva no genera tomas hacia atrás.
     */
    private fun tomasPorVersion(t: Tratamiento, desde: ZonedDateTime, hasta: ZonedDateTime): List<Ocurrencia> {
        val r = LinkedHashMap<LocalDateTime, Ocurrencia>()
        for ((v, d, h) in tramos(t)) {
            if (!v.alertas) continue
            val a = if (d != null && d.isAfter(desde.toInstant())) d.atZone(desde.zone) else desde
            val b = if (h != null && !h.isAfter(hasta.toInstant())) h.minusMillis(1).atZone(hasta.zone) else hasta
            if (!b.isAfter(a)) continue
            for (oc in ocurrencias(v, a, b, desde.zone)) r.putIfAbsent(oc.local, oc)
        }
        return r.values.sortedBy { it.cuando.toInstant() }
    }

    /**
     * Tomas cuya cadena de alertas sigue abierta en [ahora], con el inicio de su cadena: sin ninguna respuesta dentro de
     * sus 2 horas, o con una postergación que todavía no cierra. [registros]: los de este tratamiento, por clave.
     * Cada toma cuenta según la versión que regía a su hora; si el tratamiento ya no tiene alertas, no abre cadenas.
     */
    fun cadenasAbiertas(t: Tratamiento, ahora: ZonedDateTime, registros: Map<String, List<Registro>>): List<Pair<Ocurrencia, Long>> {
        if (!t.avisa || t.frecuencia.segunNecesidad) return emptyList()
        val ahoraMs = ahora.toInstant().toEpochMilli()
        val r = mutableListOf<Pair<Ocurrencia, Long>>()
        for (oc in tomasPorVersion(t, ahora.minusMinutes(VENTANA_MIN), ahora))
            if (registros[clave(t.id, oc.local)].isNullOrEmpty()) r.add(oc to oc.cuando.toInstant().toEpochMilli())
        for (l in registros.values) {
            val u = ultima(l) ?: continue
            val h = u.postergadaHasta?.toEpochMilli() ?: continue
            if (u.estado == POSTERGADA && h + VENTANA_MIN * 60_000L > ahoraMs) r.add(Ocurrencia(u.programada, ZonedDateTime.of(u.programada, ahora.zone), u.evento) to h)
        }
        return r
    }

    /** Tomas con alertas de los últimos [dias] días que cerraron sus 2 horas sin ninguna respuesta (si el celular estuvo apagado, por ejemplo). */
    fun cerradasSinRespuesta(t: Tratamiento, ahora: ZonedDateTime, conRegistro: Set<String>, dias: Long = 3): List<Ocurrencia> {
        if (!t.avisa) return emptyList()
        val hasta = ahora.minusMinutes(VENTANA_MIN)
        val desde = ahora.minusDays(dias)
        if (!hasta.isAfter(desde)) return emptyList()
        return tomasPorVersion(t, desde, hasta).filter { clave(t.id, it.local) !in conRegistro }
    }

    /** D-16: tomas cuya última respuesta es "postergada" y cuya ventana de 2 horas ya cerró (por ejemplo, con el celular apagado). */
    fun postergadasVencidas(registros: Map<String, List<Registro>>, ahoraMs: Long): List<Registro> =
        registros.values.mapNotNull { ultima(it) }.filter { u ->
            u.estado == POSTERGADA && u.postergadaHasta != null && u.postergadaHasta.toEpochMilli() + VENTANA_MIN * 60_000L <= ahoraMs
        }

    // ---------- JSON del tratamiento (documento medicamentos/t_<id>) ----------
    fun frecuenciaAJson(f: Frecuencia): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["modalidad"] = JsonPrimitive(f.modalidad)
        f.veces?.let { m["veces"] = JsonPrimitive(it) }
        f.intervaloHoras?.let { m["intervalo_horas"] = JsonPrimitive(it) }
        f.intervaloDias?.let { m["intervalo_dias"] = JsonPrimitive(it) }
        if (f.dias.isNotEmpty()) m["dias"] = JsonArray(f.dias.map { JsonPrimitive(it) })
        f.diasToma?.let { m["dias_toma"] = JsonPrimitive(it) }
        f.diasDescanso?.let { m["dias_descanso"] = JsonPrimitive(it) }
        if (f.modalidad == CICLOS) m["ciclos_max"] = Apoyo.num(f.ciclosMax)
        if (f.horarios.isNotEmpty()) m["horarios"] = JsonArray(f.horarios.map { JsonPrimitive(it) })
        if (f.eventos.isNotEmpty()) m["eventos"] = JsonArray(f.eventos.map { Apoyo.objeto("evento" to JsonPrimitive(it.evento), "hora" to Apoyo.txt(it.hora)) })
        return JsonObject(m)
    }

    fun frecuenciaDesdeJson(o: JsonObject?): Frecuencia? {
        val mod = o.s("modalidad") ?: return null
        fun ints(k: String) = o.a(k)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() } ?: emptyList()
        return Frecuencia(mod, entero(o, "veces"), entero(o, "intervalo_horas"), entero(o, "intervalo_dias"), ints("dias"),
            entero(o, "dias_toma"), entero(o, "dias_descanso"), entero(o, "ciclos_max"),
            textos(o?.get("horarios")) ?: emptyList(),
            o.a("eventos")?.mapNotNull { e -> (e as? JsonObject)?.let { x -> x.s("evento")?.let { Toma(it, x.s("hora")) } } } ?: emptyList())
    }

    fun tratamientoAJson(t: Tratamiento): JsonObject = Apoyo.objeto(
        "tipo" to JsonPrimitive("tratamiento"),
        "id" to JsonPrimitive(t.id),
        "persona" to JsonPrimitive(t.persona),
        "medicamento" to medAJson(t.med),
        "dosis" to Apoyo.objeto("cantidad" to JsJson.primitivo(t.cantidad), "unidad" to JsonPrimitive(t.unidad)),
        "frecuencia" to frecuenciaAJson(t.frecuencia),
        "inicio" to JsonPrimitive(t.inicio.toString()),
        "duracion" to Apoyo.objeto("tipo" to JsonPrimitive(t.duracion.tipo), "dias" to Apoyo.num(t.duracion.dias), "fin" to Apoyo.txt(t.duracion.fin?.toString())),
        "fin" to Apoyo.txt(t.fin?.toString()),
        "alertas" to JsonPrimitive(t.alertas),
        "version" to JsonPrimitive(t.version),
        "vigente_desde" to JsonPrimitive(t.vigenteDesde),
        "creado_en" to JsonPrimitive(t.creadoEn),
        "versiones_anteriores" to (if (t.anteriores.isEmpty()) null else JsonArray(t.anteriores.map { tratamientoAJson(it.copy(anteriores = emptyList())) }))
    ).let { o -> if (t.anteriores.isEmpty()) JsonObject(o - "versiones_anteriores") else o }
        .let { o -> if (t.archivado) JsonObject(o + ("archivado" to JsonPrimitive(true))) else o }

    fun tratamientoDesdeJson(o: JsonObject?): Tratamiento? = runCatching {
        val m = medDesdeJson(o?.get("medicamento"))!!
        val d = o.o("duracion")
        Tratamiento(o.s("id")!!, o.s("persona")!!, m, o.o("dosis").n("cantidad")!!, o.o("dosis").s("unidad")!!,
            frecuenciaDesdeJson(o.o("frecuencia"))!!, LocalDate.parse(o.s("inicio")!!),
            Duracion(d.s("tipo") ?: DUR_INDEFINIDA, entero(d, "dias"), d.s("fin")?.let { LocalDate.parse(it) }),
            o.b("alertas") ?: true, entero(o, "version") ?: 1, o.s("vigente_desde") ?: "", o.s("creado_en") ?: "",
            o.a("versiones_anteriores")?.mapNotNull { tratamientoDesdeJson(it as? JsonObject) } ?: emptyList(),
            o.b("archivado") ?: false)
    }.getOrNull()

    /** Días que se guardan las versiones anteriores (los mismos que revisa el cierre "sin respuesta"). */
    const val DIAS_VERSIONES = 3L

    /**
     * Guardar (crear o editar). Una edición sube la versión y rige desde [ahora] para las tomas futuras;
     * el historial no se toca (cada registro guarda su propia copia de los datos).
     * D-15: la versión anterior queda en [Tratamiento.anteriores] mientras haya regido en los últimos días.
     */
    fun guardar(t: Tratamiento, anterior: Tratamiento?, ahora: Instant): Tratamiento {
        val iso = isoUtc(ahora)
        if (anterior == null) return t.copy(version = 1, vigenteDesde = iso, creadoEn = iso, anteriores = emptyList())
        val previas = anterior.anteriores + anterior.copy(anteriores = emptyList())
        val limite = ahora.minusSeconds(DIAS_VERSIONES * 86_400)
        val vigentes = previas.filterIndexed { i, _ -> (previas.getOrNull(i + 1)?.let { instante(it.vigenteDesde) } ?: ahora).isAfter(limite) }
        return t.copy(id = anterior.id, version = anterior.version + 1, vigenteDesde = iso, creadoEn = anterior.creadoEn, anteriores = vigentes)
    }

    /** O-40: un tratamiento se puede borrar solo si no tiene ningún registro en el historial; si tiene, se archiva. */
    fun puedeBorrar(t: Tratamiento, registros: List<Registro>): Boolean = registros.none { it.tratamientoId == t.id }

    fun nuevoId(ahora: Instant): String = ahora.toEpochMilli().toString(36) + (100..999).random().toString(36)

    // ---------- sección "medicamentos" del plan Génesis (Consolidador) ----------
    data class Seccion(val registros: Int, val versionCatalogo: String?, val sinDosis: Int, val huella: String, val config: JsonObject)

    /**
     * Revisa la sección { tipo: "genesis-medicamentos", version_formato: "1.0", catalogo: { version, fuente, medicamentos[] },
     * config_horarios?, config_dosis? }. Todo o nada: un registro sin nombre o sin ids rechaza la sección.
     */
    fun revisarSeccion(o: JsonObject): Triple<Seccion?, List<String>, List<String>> {
        val err = mutableListOf<String>(); val av = mutableListOf<String>()
        if (o.s("tipo") != SECCION_TIPO) err.add("medicamentos.tipo: debe ser \"$SECCION_TIPO\".")
        if (o.s("version_formato") != SECCION_VERSION) err.add("medicamentos.version_formato: debe ser \"$SECCION_VERSION\".")
        val cat = o.o("catalogo")
        val lista = cat.a("medicamentos")
        if (lista == null || lista.isEmpty()) err.add("medicamentos.catalogo.medicamentos: falta o está vacío.")
        val malos = lista?.withIndex()?.filter { medDesdeJson(it.value) == null }?.map { it.index } ?: emptyList()
        if (malos.isNotEmpty()) err.add("medicamentos.catalogo.medicamentos: ${malos.size} registro(s) sin nombre o sin ids (posiciones ${malos.take(5).joinToString(", ")}${if (malos.size > 5) ", …" else ""}).")
        o["config_horarios"]?.let { if (it !is JsonObject) err.add("medicamentos.config_horarios: debe ser un objeto.") }
        o["config_dosis"]?.let { if (it !is JsonObject) err.add("medicamentos.config_dosis: debe ser un objeto.") }
        if (err.isEmpty()) config(o.o("config_horarios"), o.o("config_dosis"), PLACEHOLDER).second.let { err.addAll(it.map { m -> "medicamentos.$m" }) }
        if (err.isNotEmpty()) return Triple(null, err, av)
        val sinDosis = lista!!.count { (it as JsonObject).s("fraccionable") == null }
        if (sinDosis > 0) av.add("Catálogo: $sinDosis medicamento(s) sin unidad ni fraccionable; para ellos se usa el paso \"sin_dato\" de la configuración y la unidad se elige a mano.")
        val cfg = Apoyo.objeto("config_horarios" to o["config_horarios"], "config_dosis" to o["config_dosis"])
        return Triple(Seccion(lista.size, cat.s("version"), sinDosis, JsJson.sha256(JsJson.stringify(o)), cfg), err, av)
    }

    /** Solo para revisar una sección que trae una de las dos configuraciones: la otra la pone la app. */
    private val PLACEHOLDER = Config(1, "09:00", emptyList(), emptyList(), 1, mapOf("sin_dato" to 0.25), emptyList(), emptyMap())

    /** Documento config/medicamentos que deja el plan (va al respaldo; el catálogo no: queda en un archivo aparte del celular). */
    fun documentoActivo(s: Seccion, ahoraIso: String): JsonObject = Apoyo.objeto(
        "formato" to JsonPrimitive(SECCION_TIPO), "version_catalogo" to Apoyo.txt(s.versionCatalogo), "registros" to JsonPrimitive(s.registros),
        "huella_sha256" to JsonPrimitive(s.huella), "importado_en" to JsonPrimitive(ahoraIso),
        "config_horarios" to (s.config["config_horarios"] ?: JsonNull), "config_dosis" to (s.config["config_dosis"] ?: JsonNull))

    fun catalogoDesdeJson(o: JsonObject?): Catalogo = Catalogo(o.a("medicamentos")?.mapNotNull { medDesdeJson(it) } ?: emptyList())
}
