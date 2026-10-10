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
import kotlinx.serialization.json.jsonObject

/**
 * Imágenes de los ejercicios: las MISMAS de la 2.0 (carpeta `imagenes/` del repositorio, copiadas al APK al compilar,
 * sin modificar) y su catálogo con crédito y validación de Entrenamiento. Como en la 2.0, para los ejercicios del
 * catálogo manda el catálogo; si no está, se usa la imagen que haya en la base (por ejemplo, migrada desde la 1.0).
 */
class Imagenes(private val context: Context) {
    private val catalogo: JsonObject by lazy {
        runCatching { Json.parseToJsonElement(context.assets.open("imagenes/catalogo.json").bufferedReader().readText()).jsonObject["imagenes"]!!.jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }
    private val cache = HashMap<String, Bitmap?>()

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
