package io.github.sansolo2000.genesisgym

/**
 * Etapa E3 — piezas puras de la prueba de Drive (sin Android ni red, para probarlas con JUnit).
 */
object DriveTexto {
    const val CARPETA_PRUEBA = "Genesis Gym N - pruebas"
    const val ALCANCE = "https://www.googleapis.com/auth/drive.file"

    /** Consulta de la API de Drive para encontrar una carpeta por nombre (escapa comillas y barras). */
    fun consultaCarpeta(nombre: String): String {
        val n = nombre.replace("\\", "\\\\").replace("'", "\\'")
        return "name='$n' and mimeType='application/vnd.google-apps.folder' and trashed=false"
    }

    /** Cuerpo multipart/related para subir un archivo de texto con sus metadatos. */
    fun multipart(limite: String, metadatosJson: String, contenido: String, mime: String): ByteArray =
        ("--$limite\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadatosJson + "\r\n" +
            "--$limite\r\n" +
            "Content-Type: $mime; charset=UTF-8\r\n\r\n" +
            contenido + "\r\n" +
            "--$limite--\r\n").toByteArray(Charsets.UTF_8)

    /** Lee el campo "formato" de un JSON sin interpretar el resto (no se muestra el contenido del archivo). */
    fun formatoDe(texto: String): String? =
        Regex("\"formato\"\\s*:\\s*\"([^\"]{1,60})\"").find(texto)?.groupValues?.get(1)

    /** Escapa un texto para ponerlo dentro de comillas JSON. */
    fun json(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
}
