package io.github.sansolo2000.genesisgym

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Etapa E3 — llamadas mínimas a la API REST de Google Drive con el permiso drive.file.
 * El token de acceso llega de AuthorizationClient y queda solo en memoria (dura ~1 hora).
 * Nunca sobrescribe: cada subida crea un archivo nuevo.
 */
object DriveApi {
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val SUBIDA = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name"

    class ErrorDrive(val codigo: Int, mensaje: String) : Exception(mensaje)

    private fun leer(c: HttpURLConnection): JSONObject {
        val codigo = c.responseCode
        val cuerpo = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (codigo !in 200..299) throw ErrorDrive(codigo, "Drive respondió $codigo")
        return JSONObject(cuerpo)
    }

    private fun abrir(url: String, token: String, metodo: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
        }

    /** Busca la carpeta propia de la app; si no existe, la crea. Devuelve su id. */
    suspend fun carpetaPropia(token: String, nombre: String): String = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(DriveTexto.consultaCarpeta(nombre), "UTF-8")
        val lista = leer(abrir("$API?q=$q&fields=files(id,name)&spaces=drive", token)).getJSONArray("files")
        if (lista.length() > 0) return@withContext lista.getJSONObject(0).getString("id")
        val c = abrir("$API?fields=id", token, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        c.outputStream.use { it.write("{\"name\":\"${DriveTexto.json(nombre)}\",\"mimeType\":\"application/vnd.google-apps.folder\"}".toByteArray()) }
        leer(c).getString("id")
    }

    /** Sube un archivo de texto NUEVO a la carpeta. Devuelve el nombre con que quedó. */
    suspend fun subirTexto(token: String, carpeta: String, nombre: String, contenido: String): String = withContext(Dispatchers.IO) {
        val limite = "genesis" + System.currentTimeMillis()
        val meta = "{\"name\":\"${DriveTexto.json(nombre)}\",\"parents\":[\"${DriveTexto.json(carpeta)}\"],\"mimeType\":\"text/plain\"}"
        val cuerpo = DriveTexto.multipart(limite, meta, contenido, "text/plain")
        val c = abrir(SUBIDA, token, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$limite")
            setFixedLengthStreamingMode(cuerpo.size)
        }
        c.outputStream.use { it.write(cuerpo) }
        leer(c).getString("name")
    }
}
