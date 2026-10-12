package io.github.sansolo2000.genesisgym

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Etapa E6 — Health Connect para el Evaluador (formato del Evaluador #2, aprobado en estado-genesis v35).
 * Dos CSV semanales, de sábado a viernes: diario (FC en reposo, sueño, peso) y actividades.
 * Un dato que no existe va VACÍO: nunca se estima ni se rellena. Lógica pura, sin Android, para probarla con JUnit.
 * Los datos solo quedan en el celular y en el Drive de Héctor; nunca en el repositorio.
 *
 * Formato del archivo: el mismo del CSV del gimnasio (UTF-8 con BOM, separador ";", coma decimal, fin de línea CRLF).
 * Pendiente [P]: alinear los nombres de columna con el CSV del script de Garmin cuando Héctor pase su cabecera.
 */
object SaludCsv {
    const val CARPETA = "Genesis Gym - health connect"

    // ---------- lo que se lee de Health Connect (ya convertido a datos simples) ----------
    data class Fc(val en: Instant, val lpm: Long, val origen: String)
    /** Una etapa del sueño: tipo con los códigos de Health Connect (1 despierto, 4 ligero, 5 profundo, 6 REM, 7 despierto en cama, …). */
    data class Etapa(val inicio: Instant, val fin: Instant, val tipo: Int)
    data class Sueno(val inicio: Instant, val fin: Instant, val etapas: List<Etapa>, val origen: String)
    data class Peso(val en: Instant, val kg: Double, val origen: String)
    /** Una actividad con sus totales ya agregados por Health Connect en su intervalo (null si no hay dato). */
    data class Actividad(val inicio: Instant, val fin: Instant, val tipoHc: Int, val titulo: String?,
                         val distanciaM: Double?, val desnivelM: Double?, val fcMedia: Double?, val origen: String)

    // Códigos de Health Connect (SleepSessionRecord y ExerciseSessionRecord), copiados para no depender de Android en las pruebas.
    const val DESPIERTO = 1; const val DORMIDO = 2; const val FUERA_DE_CAMA = 3; const val LIGERO = 4
    const val PROFUNDO = 5; const val REM = 6; const val DESPIERTO_EN_CAMA = 7
    const val BICICLETA = 8; const val BICICLETA_ESTATICA = 9

    val COLUMNAS_DIARIO = listOf("fecha", "fc_reposo_lpm", "sueno_total_min", "sueno_profundo_min", "sueno_rem_min", "sueno_ligero_min",
        "sueno_despierto_min", "peso_kg", "peso_hora", "origen")
    val COLUMNAS_ACTIVIDADES = listOf("fecha", "hora_inicio", "tipo", "duracion_min", "distancia_km", "desnivel_m", "fc_media_lpm", "origen")

    // ---------- semana ----------
    /** Sábado que abre la semana de f (las semanas del plan van de sábado a viernes). */
    fun sabadoDe(f: LocalDate): LocalDate = f.minusDays(((f.dayOfWeek.value + 1) % 7).toLong())
    /** Semana por defecto: la última cerrada (el viernes ya pasó). El viernes mismo propone la semana en curso. */
    fun semanaPorDefecto(hoy: LocalDate): LocalDate = if (hoy.dayOfWeek.value == 5) sabadoDe(hoy) else sabadoDe(hoy).minusDays(7)
    fun dias(desde: LocalDate): List<LocalDate> = (0..6L).map { desde.plusDays(it) }

    // ---------- nombre de la app de origen ----------
    fun origenes(lista: List<String>): String = lista.map { Salud.nombreApp(it) }.distinct().sorted().joinToString(" + ")

    // ---------- cálculos ----------
    private fun min(a: Instant, b: Instant): Long = Math.round((b.toEpochMilli() - a.toEpochMilli()) / 60000.0)
    private fun fechaDe(i: Instant, zona: ZoneId): LocalDate = i.atZone(zona).toLocalDate()

    /** Minutos por tipo de sueño de una noche. Sin etapas: solo el total (duración de la sesión). */
    data class Noche(val total: Long, val profundo: Long?, val rem: Long?, val ligero: Long?, val despierto: Long?)
    fun noche(s: Sueno): Noche {
        if (s.etapas.isEmpty()) return Noche(min(s.inicio, s.fin), null, null, null, null)
        fun suma(vararg t: Int) = s.etapas.filter { it.tipo in t }.sumOf { min(it.inicio, it.fin) }
        val desp = suma(DESPIERTO, DESPIERTO_EN_CAMA, FUERA_DE_CAMA)
        val dormido = s.etapas.filter { it.tipo !in setOf(DESPIERTO, DESPIERTO_EN_CAMA, FUERA_DE_CAMA) }.sumOf { min(it.inicio, it.fin) }
        return Noche(dormido, suma(PROFUNDO), suma(REM), suma(LIGERO), desp)
    }

    data class FilaDia(val fecha: LocalDate, val fc: Long?, val noche: Noche?, val peso: Peso?, val origen: String)

    /**
     * Una fila por día de la semana, siempre 7. El sueño se asigna al día en que termina (la mañana en que despierta).
     * Si hay varios valores el mismo día: FC en reposo, el último del día; sueño, se suman las sesiones; peso, el primero del día (en ayunas).
     */
    fun diario(desde: LocalDate, fc: List<Fc>, sueno: List<Sueno>, peso: List<Peso>, zona: ZoneId): List<FilaDia> = dias(desde).map { d ->
        val f = fc.filter { fechaDe(it.en, zona) == d }.maxByOrNull { it.en }
        val ss = sueno.filter { fechaDe(it.fin, zona) == d }
        val n = if (ss.isEmpty()) null else ss.map { noche(it) }.reduce { a, b ->
            fun s(x: Long?, y: Long?) = if (x == null && y == null) null else (x ?: 0) + (y ?: 0)
            Noche(a.total + b.total, s(a.profundo, b.profundo), s(a.rem, b.rem), s(a.ligero, b.ligero), s(a.despierto, b.despierto))
        }
        val p = peso.filter { fechaDe(it.en, zona) == d }.minByOrNull { it.en }
        FilaDia(d, f?.lpm, n, p, origenes(listOfNotNull(f?.origen, p?.origen) + ss.map { it.origen }))
    }

    data class FilaActividad(val fecha: LocalDate, val hora: String, val tipo: String, val duracion: Long, val km: Double?, val desnivel: Double?, val fc: Double?, val origen: String)

    fun tipo(a: Actividad): String = when (a.tipoHc) { BICICLETA -> "MTB"; BICICLETA_ESTATICA -> "rodillo"; else -> "otro" }

    fun actividades(desde: LocalDate, lista: List<Actividad>, zona: ZoneId): List<FilaActividad> {
        val hasta = desde.plusDays(6)
        return lista.filter { val d = fechaDe(it.inicio, zona); !d.isBefore(desde) && !d.isAfter(hasta) }.sortedBy { it.inicio }.map {
            val z = it.inicio.atZone(zona)
            FilaActividad(z.toLocalDate(), z.format(DateTimeFormatter.ofPattern("HH:mm")), tipo(it), min(it.inicio, it.fin),
                it.distanciaM?.let { m -> m / 1000.0 }, it.desnivelM, it.fcMedia, Salud.nombreApp(it.origen))
        }
    }

    // ---------- CSV ----------
    /** Número con coma decimal y a lo más [dec] decimales, sin ceros sobrantes (como el CSV del gimnasio). */
    fun numero(v: Double?, dec: Int): String {
        if (v == null || v.isNaN()) return ""
        val r = java.math.BigDecimal(v).setScale(dec, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        return (if (r.scale() < 0) r.setScale(0) else r).toPlainString().replace('.', ',')
    }
    private fun campo(s: String): String = if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun texto(columnas: List<String>, filas: List<List<String>>): String =
        "﻿" + (listOf(columnas) + filas).joinToString("\r\n") { f -> f.joinToString(";") { campo(it) } } + "\r\n"

    fun csvDiario(filas: List<FilaDia>, zona: ZoneId): String = texto(COLUMNAS_DIARIO, filas.map { f ->
        listOf(f.fecha.toString(), f.fc?.toString() ?: "", f.noche?.total?.toString() ?: "", f.noche?.profundo?.toString() ?: "",
            f.noche?.rem?.toString() ?: "", f.noche?.ligero?.toString() ?: "", f.noche?.despierto?.toString() ?: "",
            numero(f.peso?.kg, 2), f.peso?.en?.atZone(zona)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "", f.origen)
    })

    fun csvActividades(filas: List<FilaActividad>): String = texto(COLUMNAS_ACTIVIDADES, filas.map { f ->
        listOf(f.fecha.toString(), f.hora, f.tipo, f.duracion.toString(), numero(f.km, 2), numero(f.desnivel, 0), numero(f.fc, 0), f.origen)
    })

    fun nombre(perfil: String, que: String, desde: LocalDate, version: Int): String =
        "hc-$perfil-$que-$desde-${desde.plusDays(6)}" + (if (version > 1) "-v$version" else "") + ".csv"

    /** Perfil para el nombre del archivo: el nombre del celular sin tildes ni espacios ("Héctor" → "hector"). */
    fun slug(nombre: String?): String = java.text.Normalizer.normalize(nombre ?: "sin-perfil", java.text.Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "sin-perfil" }
}
