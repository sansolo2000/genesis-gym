package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * M-10 (Héctor, 14-oct) — alertas configurables por tipo. Lógica pura, sin Android, para probarla con JUnit.
 * Cada uno de los 5 tipos tiene su tono (selector de Android), su volumen (% del volumen de ALARMAS del celular; la app no
 * cambia ajustes del celular), vibración, "sonar en silencio / No molestar" y, salvo el Descanso, minutos de posponer.
 * Comidas, Tareas, Entrenamientos y Reporte suenan hasta "Detener" o "Posponer", con un tope de 2 minutos; el Descanso
 * (temporizador de series y descansos) suena una vez al llegar a 0. Si coinciden, suena el de mayor prioridad.
 */
object Alertas {
    const val DESCANSO = "descanso"
    /** Módulo Medicamentos (15-oct): tipo nuevo. Se activa por tratamiento y espera Confirmar, Posponer u Omitir. */
    const val MEDICAMENTO = "medicamento"
    /** Los 5 tipos de M-10. */
    val TIPOS = listOf(Recordatorios.COMIDA, Recordatorios.TAREA, Recordatorios.ENTRENAMIENTO, DESCANSO, Recordatorios.REPORTE)
    /** Todos los tipos con su configuración en Avisos: los de M-10 y Medicamentos. */
    val TODOS = TIPOS + MEDICAMENTO
    val NOMBRE = mapOf(Recordatorios.COMIDA to "Comidas", Recordatorios.TAREA to "Tareas de cocina", Recordatorios.ENTRENAMIENTO to "Entrenamientos",
        DESCANSO to "Descanso en entrenamiento", Recordatorios.REPORTE to "Reporte diario", MEDICAMENTO to "Medicamentos")
    /**
     * Decisión del Coordinador (14-oct): Entrenamiento > Comidas > Tareas > Reporte. El Descanso no compite: suena una vez.
     * Medicamentos primero: propuesta de Desarrollador 3 (15-oct), PENDIENTE de la decisión de Héctor.
     */
    val PRIORIDAD = listOf(MEDICAMENTO, Recordatorios.ENTRENAMIENTO, Recordatorios.COMIDA, Recordatorios.TAREA, Recordatorios.REPORTE)
    /** Decisión de Héctor (14-oct): después de 2 minutos se calla y la notificación queda con Detener y Posponer. */
    const val TOPE_MS = 120_000L
    val POSPONER = listOf(5, 10, 15)
    const val POSPONER_POR_DEFECTO = 10

    data class Tipo(val sonido: String?, val volumen: Int, val vibrar: Boolean, val enSilencio: Boolean, val posponerMin: Int)

    /** Lo que había antes de M-10 (un solo tono para todo) es el valor inicial de cada tipo. */
    fun inicial(sonidoAnterior: String?, volumenAnterior: Int, alarmaAnterior: Boolean) =
        Tipo(sonidoAnterior, volumenAnterior.coerceIn(0, 100), true, alarmaAnterior, POSPONER_POR_DEFECTO)

    /** Suena hasta Detener o Posponer (todos menos el Descanso). */
    fun insiste(tipo: String): Boolean = tipo != DESCANSO

    /** Medicamentos: su notificación queda hasta Confirmar, Posponer u Omitir; "Detener" de otra alerta la calla pero no la quita. */
    fun esperaRespuesta(tipo: String): Boolean = tipo == MEDICAMENTO

    /** De los tipos que suenan a la vez, el que se escucha. */
    fun ganador(tipos: Collection<String>): String? = PRIORIDAD.firstOrNull { it in tipos } ?: tipos.firstOrNull()

    /** Si [nuevo] llega mientras suena [actual], ¿pasa a sonar [nuevo]? */
    fun reemplaza(actual: String?, nuevo: String): Boolean = actual == null || ganador(listOf(actual, nuevo)) == nuevo && nuevo != actual

    fun posponerHasta(ahoraMs: Long, minutos: Int): Long = ahoraMs + minutos * 60_000L

    // ---------- silencio y No molestar ----------
    // Constantes de NotificationManager, copiadas para no depender de Android en las pruebas.
    const val FILTRO_TODO = 1; const val FILTRO_PRIORIDAD = 2; const val FILTRO_NADA = 3; const val FILTRO_ALARMAS = 4
    const val TIMBRE_SILENCIO = 0; const val TIMBRE_VIBRAR = 1; const val TIMBRE_NORMAL = 2

    /** No molestar deja pasar las alarmas del celular. [alarmasPermitidas]: la categoría "Alarmas" en el modo Prioridad (null si no se sabe). */
    fun noMolestarDejaAlarmas(filtro: Int, alarmasPermitidas: Boolean?): Boolean = when (filtro) {
        FILTRO_TODO, FILTRO_ALARMAS -> true
        FILTRO_NADA -> false
        FILTRO_PRIORIDAD -> alarmasPermitidas ?: true
        else -> true
    }

    /** ¿Suena el tono? Con "sonar en silencio" usa el canal de alarmas; sin él, respeta el silencio, la vibración y No molestar. */
    fun suena(t: Tipo, timbre: Int, filtro: Int, alarmasPermitidas: Boolean?): Boolean =
        if (t.enSilencio) noMolestarDejaAlarmas(filtro, alarmasPermitidas) else timbre == TIMBRE_NORMAL && filtro == FILTRO_TODO

    /** ¿Vibra? Sin "sonar en silencio", en modo silencio no vibra. */
    fun vibra(t: Tipo, timbre: Int): Boolean = t.vibrar && (t.enSilencio || timbre != TIMBRE_SILENCIO)

    /** Texto para la pantalla si "sonar en silencio" no alcanza: No molestar no deja pasar las alarmas. */
    fun avisoNoMolestar(filtro: Int, alarmasPermitidas: Boolean?): String? =
        if (noMolestarDejaAlarmas(filtro, alarmasPermitidas)) null
        else "No molestar está activo y no deja pasar las alarmas: las alertas con \"sonar en silencio\" no van a sonar. " +
            "Para que suenen, en Ajustes → Notificaciones → No molestar activa \"Alarmas\"."

    /** Riesgo de QA (14-oct): "Probar" dice por qué no suena. null si suena. */
    fun porQueNoSuena(t: Tipo, timbre: Int, filtro: Int, alarmasPermitidas: Boolean?): String? = when {
        t.enSilencio && !noMolestarDejaAlarmas(filtro, alarmasPermitidas) -> avisoNoMolestar(filtro, alarmasPermitidas)
        !t.enSilencio && timbre == TIMBRE_SILENCIO -> "No suena porque el celular está en silencio y \"Sonar aunque esté en silencio / No molestar\" está apagado."
        !t.enSilencio && timbre == TIMBRE_VIBRAR -> "No suena porque el celular está en vibración y \"Sonar aunque esté en silencio / No molestar\" está apagado."
        !t.enSilencio && filtro != FILTRO_TODO -> "No suena porque No molestar está activo y \"Sonar aunque esté en silencio / No molestar\" está apagado."
        t.volumen == 0 -> "No se escucha porque el volumen de esta alerta está en 0 %."
        else -> null
    }

    // ---------- O-33 (Coordinador, 14-oct): qué calla una alerta que suena ----------
    /** Tocar la notificación de una alerta que insiste cuenta como Detener. */
    fun tocarDetiene(tipo: String): Boolean = insiste(tipo)
    const val COMIDA_MARCADA = "comida_marcada"; const val TAREA_HECHA = "tarea_hecha"; const val SESION_ABIERTA = "sesion_abierta"; const val REPORTE_GUARDADO = "reporte_guardado"
    /** Lo que se hace en la app y la alerta que calla: marcar la comida o la tarea, abrir la sesión, guardar el reporte. */
    fun tipoQueCalla(evento: String): String? = when (evento) {
        COMIDA_MARCADA -> Recordatorios.COMIDA; TAREA_HECHA -> Recordatorios.TAREA
        SESION_ABIERTA -> Recordatorios.ENTRENAMIENTO; REPORTE_GUARDADO -> Recordatorios.REPORTE
        else -> null
    }

    // ---------- respaldo (dentro de config/avisos) ----------
    fun aJson(m: Map<String, Tipo>): JsonObject = JsonObject(TODOS.filter { it in m }.associateWith { k ->
        val t = m.getValue(k)
        Apoyo.objeto("sonido" to (t.sonido?.let { JsonPrimitive(it) } ?: JsonNull), "volumen" to JsonPrimitive(t.volumen), "vibrar" to JsonPrimitive(t.vibrar),
            "en_silencio" to JsonPrimitive(t.enSilencio), "posponer_min" to JsonPrimitive(t.posponerMin))
    })

    /** Lee los tipos del respaldo; un tipo que falta no aparece (queda su valor del celular) y un campo malo usa su valor por defecto. */
    fun desdeJson(o: JsonObject?, base: Tipo): Map<String, Tipo> {
        if (o == null) return emptyMap()
        return TODOS.mapNotNull { k ->
            val t = o[k] as? JsonObject ?: return@mapNotNull null
            fun b(c: String, d: Boolean) = (t[c] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: d
            fun i(c: String) = (t[c] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
            val sonido = (t["sonido"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val pos = i("posponer_min")?.takeIf { it in POSPONER } ?: POSPONER_POR_DEFECTO
            k to Tipo(sonido, (i("volumen") ?: base.volumen).coerceIn(0, 100), b("vibrar", base.vibrar), b("en_silencio", base.enSilencio), pos)
        }.toMap()
    }

    // ---------- posponer: no se repite lo que ya se hizo ----------
    /**
     * Al llegar un aviso pospuesto: sigue vigente si el tipo sigue activo (O-34) y al menos una de sus claves sigue pendiente.
     * Claves: comida "AAAA-MM-DD_tipo", tarea "AAAA-MM-DD_HH:MM_n" (la de Cocinar), entrenamiento y reporte "AAAA-MM-DD".
     */
    fun vigente(tipo: String, activo: Boolean, claves: List<String>, comidaMarcada: (String) -> Boolean, tareaHecha: (String) -> Boolean,
                entrenoRegistrado: (String) -> Boolean, reporteGuardado: (String) -> Boolean): Boolean {
        if (!activo) return false
        if (claves.isEmpty()) return true
        return claves.any { k -> when (tipo) {
            Recordatorios.COMIDA -> !comidaMarcada(k)
            Recordatorios.TAREA -> !tareaHecha(k)
            Recordatorios.ENTRENAMIENTO -> !entrenoRegistrado(k)
            Recordatorios.REPORTE -> !reporteGuardado(k)
            else -> true
        } }
    }

    /** ¿El tipo está activo en Avisos? */
    fun activo(cfg: Recordatorios.Config, tipo: String): Boolean = when (tipo) {
        Recordatorios.COMIDA -> cfg.comidas; Recordatorios.TAREA -> cfg.tareas; Recordatorios.ENTRENAMIENTO -> cfg.entrenamiento
        Recordatorios.REPORTE -> cfg.reporte; DESCANSO -> cfg.descanso
        MEDICAMENTO -> true   // se activa en cada tratamiento
        else -> false
    }
}
