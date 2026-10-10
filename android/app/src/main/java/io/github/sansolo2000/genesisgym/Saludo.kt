package io.github.sansolo2000.genesisgym

/**
 * Textos de la pantalla de la etapa E0. Es lógica pura (sin Android), así se prueba en la nube con JUnit.
 */
object Saludo {
    fun titulo(): String = "Hola, Génesis"

    fun version(versionName: String, versionCode: Int, commit: String): String =
        "Versión $versionName (código $versionCode) · compilación $commit"

    /** Cuántas veces se abrió la app. Si este número sigue subiendo después de instalar
     *  una versión nueva encima, la actualización conservó los datos. */
    fun aperturas(n: Int): String = when {
        n <= 1 -> "Primera vez que se abre en este celular."
        else -> "Se ha abierto $n veces en este celular."
    }
}
