package io.github.sansolo2000.genesisgym

/**
 * Etapa E2 — lógica pura de la prueba de Health Connect (sin Android, para probarla con JUnit).
 * Solo se cuentan registros y de qué app vienen. Nunca se leen ni muestran valores (peso, horas, pulsaciones).
 */
object Salud {
    /** Tipos que se prueban en E2, en el orden en que se muestran. */
    val TIPOS = listOf("Peso", "Sueño", "Ejercicio", "FC en reposo")

    private val NOMBRES = mapOf(
        "com.moving.movinglife" to "movinglife",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.google.android.apps.fitness" to "Google Fit"
    )

    fun nombreApp(paquete: String): String = NOMBRES[paquete] ?: paquete

    /** Lista de paquetes de origen (uno por registro) → "movinglife 8 · Samsung Health 8", de mayor a menor. */
    fun resumen(origenes: List<String>): String {
        if (origenes.isEmpty()) return "0 registros"
        val porApp = origenes.groupingBy { nombreApp(it) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val n = if (origenes.size == 1) "1 registro" else "${origenes.size} registros"
        return "$n: " + porApp.joinToString(" · ") { "${it.key} ${it.value}" }
    }
}
