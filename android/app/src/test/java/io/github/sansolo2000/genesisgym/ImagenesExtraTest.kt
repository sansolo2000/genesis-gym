package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Universo v13: las 2 elongaciones usan las imágenes libres de Wikimedia Commons (libres/), sin modificar y con crédito. */
class ImagenesExtraTest {
    // Las pruebas corren desde android/app; el catálogo vive en android/imagenes-extra/ y en el APK queda bajo imagenes/.
    private val carpeta = listOf(File("../imagenes-extra"), File("android/imagenes-extra")).first { it.isDirectory }
    private val imagenes = Json.parseToJsonElement(File(carpeta, "catalogo-extra.json").readText()).jsonObject["imagenes"]!!.jsonObject
    private fun o(id: String) = imagenes[id] as JsonObject
    private fun t(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content ?: ""
    private fun srcs(o: JsonObject) = (o["srcs"] as JsonArray).map { (it as JsonPrimitive).content }
    private fun md5(f: File) = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    // md5 de las imágenes aprobadas por Entrenamiento (13-oct).
    private val aprobadas = mapOf(
        "libres/estiramiento-pecho-codos-atras.png" to "7511ba172d2ac63874449c77050dd923",
        "libres/estiramiento-espalda-alta-brazos-adelante-inicio.png" to "b91a19b2ef64d90e05ebdf6254d90926",
        "libres/estiramiento-espalda-alta-brazos-adelante-final.png" to "963f84a945fd1f5fad56c87ffc69c025")

    @Test fun todasLasRutasExistenEnLaCarpeta() {
        imagenes.forEach { (id, v) ->
            val s = srcs(v as JsonObject)
            assertTrue("$id sin imágenes", s.isNotEmpty())
            s.forEach { assertTrue("$id: falta $it", it.startsWith("imagenes/") && File(carpeta, it.removePrefix("imagenes/")).isFile) }
        }
    }

    @Test fun lasDosElongacionesUsanLasImagenesLibresSinModificar() {
        assertEquals(listOf("imagenes/libres/estiramiento-pecho-codos-atras.png"), srcs(o("estiramiento-pecho-codos-atras")))
        assertEquals(listOf("imagenes/libres/estiramiento-espalda-alta-brazos-adelante-inicio.png", "imagenes/libres/estiramiento-espalda-alta-brazos-adelante-final.png"),
            srcs(o("estiramiento-espalda-alta-brazos-adelante")))
        aprobadas.forEach { (ruta, esperado) -> assertEquals(ruta, esperado, md5(File(carpeta, ruta))) }
        assertFalse(imagenes.values.flatMap { srcs(it as JsonObject) }.any { it.contains("free-exercise-db") })
    }

    @Test fun cadaImagenTraeCreditoYEtiquetas() {
        imagenes.forEach { (id, v) ->
            v as JsonObject
            assertTrue("$id sin crédito", t(v, "credito").isNotBlank())
            assertEquals("$id: una etiqueta por imagen", srcs(v).size, (v["etiquetas"] as JsonArray).size)
            assertEquals("$id validada", "true", t(v, "validada_entrenamiento"))
        }
        val pecho = o("estiramiento-pecho-codos-atras")
        assertTrue(t(pecho, "credito").contains("CC BY-SA 4.0") && t(pecho, "credito").contains("Cancer Research UK"))
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", t(pecho, "url_licencia"))
        assertTrue(t(o("estiramiento-espalda-alta-brazos-adelante"), "credito").contains("dominio público"))
    }

    @Test fun laFichaEnlazaLaLicenciaSoloSiEsUnaDireccionWeb() {
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", Imagenes.enlaceLicencia(o("estiramiento-pecho-codos-atras")))
        assertNull(Imagenes.enlaceLicencia(o("estiramiento-espalda-alta-brazos-adelante")))   // dominio público: sin url_licencia
        fun con(v: String) = JsonObject(mapOf("url_licencia" to JsonPrimitive(v)))
        assertEquals("http://ejemplo.org/l", Imagenes.enlaceLicencia(con(" http://ejemplo.org/l ")))
        assertNull(Imagenes.enlaceLicencia(con("")))
        assertNull(Imagenes.enlaceLicencia(con("javascript:alert(1)")))
        assertNull(Imagenes.enlaceLicencia(JsonObject(mapOf("url_licencia" to JsonPrimitive(4)))))
    }
}
