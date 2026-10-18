package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** 0.19.0: el vademécum aparte del plan (CONTEXTO-MODULO-MEDICAMENTOS §10). Solo un vademécum inventado. */
class VademecumTest {
    private val ahora = "2030-10-18T12:00:00.000Z"
    private fun med(nombre: String, id: String, frac: String? = "Sí") = buildJsonObject {
        put("nombre", nombre); put("ids", buildJsonArray { add(JsonPrimitive(id)) }); put("tag", "COMPRIMIDOS"); put("unidad_dosis", "comprimido")
        frac?.let { put("fraccionable", it) }
    }
    private fun archivo(version: String = "p1", meds: List<JsonObject> = listOf(med("FICTICINA 10 mg", "9000001"), med("INVENTOL 5 mg", "9000002", null))) =
        JsJson.stringify(buildJsonObject {
            put("tipo", Vademecum.TIPO); put("version_formato", "1.0"); put("version", version); put("fuente", "prueba"); put("registros", meds.size)
            put("medicamentos", JsonArray(meds))
        }).toByteArray(Charsets.UTF_8)
    private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun ref(bytes: ByteArray, version: String = "p1") = Vademecum.Ref(version, Vademecum.huella(bytes), 2, "vademecum.json.gz", null)

    @Test fun elArchivoSeAceptaComprimidoOSinComprimir() {
        val b = archivo()
        val (r, err) = Vademecum.revisarArchivo(gz(b), ref(b))
        assertTrue(err.toString(), err.isEmpty())
        assertEquals("p1", r!!.version); assertEquals(2, r.registros); assertEquals(Vademecum.huella(b), r.huella)   // huella del JSON descomprimido
        assertTrue(r.avisos.any { it.contains("sin unidad") })
        assertEquals(2, Medicamentos.catalogoDesdeJson(r.catalogo).lista.size)   // se guarda con el mismo formato que el catálogo de la 0.18.0
        assertNotNull(Vademecum.revisarArchivo(b, ref(b)).first)
    }

    /** QA §32.2: la huella del plan manda; un archivo distinto (una copia vieja, otro vademécum) no reemplaza al que está. */
    @Test fun laHuellaDelPlanManda() {
        val viejo = archivo("p1"); val nuevo = archivo("p2")
        val (r, err) = Vademecum.revisarArchivo(gz(viejo), ref(nuevo, "p2"))
        assertNull(r); assertTrue(err.single().contains("no es el vademécum que pide tu plan (p2)"))
        // Sin referencia (un celular sin plan), se acepta y avisa.
        val (r2, _) = Vademecum.revisarArchivo(viejo, null)
        assertTrue(r2!!.avisos.any { it.contains("todavía no dice") })
    }

    @Test fun archivosMalosSeRechazanEnteros() {
        assertNull(Vademecum.revisarArchivo("no es json".toByteArray(), null).first)
        assertNull(Vademecum.revisarArchivo(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3), null).first)   // gzip dañado
        val trunco = gz(archivo()).let { it.copyOf(it.size / 2) }
        assertNull(Vademecum.revisarArchivo(trunco, null).first)
        val sinNombre = archivo(meds = listOf(buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive("1")) }) }))
        assertTrue(Vademecum.revisarArchivo(sinNombre, null).second.single().contains("sin nombre o sin ids"))
        val otroTipo = JsJson.stringify(buildJsonObject { put("tipo", "plan-genesis") }).toByteArray()
        assertTrue(Vademecum.revisarArchivo(otroTipo, null).second.any { it.startsWith("tipo") })
    }

    @Test fun estadoYTextos() {
        val b = archivo(); val r = ref(b)
        // Sin archivo en el celular: falta, aunque la base diga otra cosa.
        val doc = Vademecum.conEsperado(null, r)
        val falta = Vademecum.estado(doc, null, hayArchivo = false)
        assertEquals(Vademecum.FALTA, Vademecum.situacion(falta)); assertTrue(Vademecum.texto(falta).contains("Actualizar vademécum"))
        // Guardado y coincide con el plan.
        val rev = Vademecum.revisarArchivo(b, r).first!!
        val ok = Vademecum.estado(Vademecum.docGuardado(doc, rev, "archivo", ahora), null, true)
        assertEquals(Vademecum.OK, Vademecum.situacion(ok)); assertEquals(r, ok.esperado!!.copy(config = null))
        // Un plan nuevo pide otra versión: desactualizado, hasta elegir el archivo nuevo.
        val r2 = ref(archivo("p2"), "p2")
        val des = Vademecum.estado(Vademecum.conEsperado(Vademecum.docGuardado(doc, rev, "archivo", ahora), r2), null, true)
        assertEquals(Vademecum.DESACTUALIZADO, Vademecum.situacion(des)); assertTrue(Vademecum.texto(des).contains("pide el vademécum p2"))
        // Catálogo que llegó dentro de un plan de antes (0.17/0.18): misma versión y registros = al día.
        val legado = buildJsonObject { put("version_catalogo", "p1"); put("registros", 2); put("huella_sha256", "a".repeat(64)) }
        val antes = Vademecum.estado(Vademecum.conEsperado(null, r), legado, true)
        assertEquals("p1", antes.version); assertEquals(Vademecum.OK, Vademecum.situacion(antes))
    }

    // ---------- la referencia en el plan ----------
    private val rutina: JsonObject = Json.parseToJsonElement(javaClass.classLoader!!.getResource("validador-casos.json")!!.readText()).jsonArray
        .map { it.jsonObject }.first { it["nombre"]!!.jsonPrimitive.content == "valida" }["json"]!!.jsonPrimitive.content
        .replace("\"ejemplo-formato\"", "\"fuerza-prueba\"").let { Json.parseToJsonElement(it).jsonObject }
    private val alimentacion = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-programa-con-compras.json")!!.readText()).jsonObject
    private val apoyo = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-apoyo-programa.json")!!.readText()).jsonObject
    private fun plan(refJson: JsonObject?) = JsJson.stringify(buildJsonObject {
        put("tipo", Plan.TIPO); put("version_formato", "1.0"); put("perfil", "prueba"); put("generado_en", ahora)
        put("rutina", rutina); put("alimentacion", alimentacion); put("apoyo", apoyo)
        refJson?.let { put(Vademecum.CLAVE_PLAN, it) }
    })
    private val refJson = buildJsonObject { put("version", "p1"); put("registros", 2); put("huella_sha256", Vademecum.huella(archivo())); put("archivo", "vademecum.json.gz") }

    @Test fun elPlanConLaReferenciaSeImportaAunqueFalteElVademecum() {
        val r = Plan.revisar(plan(refJson), null, null, null, "Prueba", ahora, null, null, Vademecum.Estado(null, null, null, null, null))
        assertTrue(r.errores.toString(), r.ok)
        assertEquals("p1", r.vademecum!!.version); assertTrue(r.vademecumCambia)
        assertTrue(r.avisos.toString(), r.avisos.any { it.contains("falta en este celular") && it.contains("Actualizar vademécum") })
        assertTrue(r.partes.none { it.clave == "medicamentos" })   // el plan ya no trae el catálogo
        // Si ya lo pedía y está, no hay nada nuevo por esto.
        val listo = Vademecum.estado(Vademecum.docGuardado(Vademecum.conEsperado(null, r.vademecum!!), Vademecum.revisarArchivo(archivo(), r.vademecum).first!!, "archivo", ahora), null, true)
        val r2 = Plan.revisar(plan(refJson), null, null, null, "Prueba", ahora, null, null, listo)
        assertFalse(r2.vademecumCambia); assertTrue(r2.avisos.any { it.contains("ya está en este celular") })
    }

    @Test fun unaReferenciaMalaRechazaElPlan() {
        val mala = buildJsonObject { put("version", "p1"); put("huella_sha256", "NO-ES-HEX") }
        assertTrue(Plan.revisar(plan(mala), null, null, null, "Prueba", ahora).errores.any { it.startsWith("catalogo_medicamentos.huella_sha256") })
        val configNull = JsonObject(refJson + ("config" to buildJsonObject { put("config_dosis", kotlinx.serialization.json.JsonNull) }))
        assertTrue(Plan.revisar(plan(configNull), null, null, null, "Prueba", ahora).errores.any { it.contains("config_dosis") })
        // Un plan sin la referencia sigue como antes (compatibilidad con la 0.18.0).
        val sin = Plan.revisar(plan(null), null, null, null, "Prueba", ahora)
        assertTrue(sin.ok); assertNull(sin.vademecum); assertFalse(sin.vademecumCambia)
    }

    /** La 0.18.0 ignora las claves que no conoce: un plan con solo la referencia se importa igual (lo comprueba el mismo Plan.revisar sin estado). */
    @Test fun laReferenciaNoTraeElCatalogo() {
        val texto = plan(refJson)
        assertTrue(texto.length < 200_000)
        assertNull(Json.parseToJsonElement(texto).jsonObject["medicamentos"])
    }
}
