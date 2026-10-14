package io.github.sansolo2000.genesisgym

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Imágenes de los ejercicios: las MISMAS de la 2.0 (carpeta `imagenes/` del repositorio, copiadas al APK al compilar,
 * sin modificar) y su catálogo con crédito y validación de Entrenamiento. Como en la 2.0, para los ejercicios del
 * catálogo manda el catálogo; si no está, se usa la imagen que haya en la base (por ejemplo, migrada desde la 1.0).
 */
class Imagenes(private val context: Context) {
    private val catalogo: JsonObject by lazy {
        fun leer(n: String): Map<String, kotlinx.serialization.json.JsonElement> =
            runCatching { Json.parseToJsonElement(context.assets.open(n).bufferedReader().readText()).jsonObject["imagenes"]!!.jsonObject.toMap() }.getOrElse { emptyMap() }
        // catalogo-extra.json (android/imagenes-extra/): ejercicios del universo v12 que la carpeta de la 2.0 no trae.
        JsonObject(leer("imagenes/catalogo-extra.json") + leer("imagenes/catalogo.json"))
    }
    private val cache = HashMap<String, Bitmap?>()

    companion object {
        /** Enlace a la licencia para la ficha: solo si el catálogo trae `url_licencia` y es una dirección web. */
        fun enlaceLicencia(img: JsonObject): String? = (img["url_licencia"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    fun meta(id: String, base: BaseLocal): JsonObject? = (catalogo[id] as? JsonObject) ?: base.get("imagenes/$id")

    /** src: ruta del catálogo ("imagenes/x.svg") o data URI de la 1.0. */
    fun bitmap(src: String): Bitmap? = cache.getOrPut(src) {
        runCatching {
            when {
                src.startsWith("data:") -> Base64.decode(src.substringAfter(","), Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                src.endsWith(".svg", ignoreCase = true) -> {
                    val svg = context.assets.open(src).use { SVG.getFromInputStream(it) }
                    val vb = svg.documentViewBox
                    val w = 600
                    val h = if (vb != null && vb.width() > 0f) (w * vb.height() / vb.width()).toInt().coerceIn(100, 1200) else 600
                    svg.setDocumentWidth(w.toFloat()); svg.setDocumentHeight(h.toFloat())
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp); c.drawColor(Color.WHITE); svg.renderToCanvas(c)
                    }
                }
                else -> context.assets.open(src).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
}
