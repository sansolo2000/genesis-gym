package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importador del plan Génesis: sobre con las tres partes, todo o nada y omitir lo idéntico. Solo datos inventados. */
class PlanTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private val ahora = "2026-10-12T22:00:00.000Z"

    /** Rutina válida de validador-casos.json, con un id propio (el de ejemplo tiene reglas especiales). */
    private val rutina: JsonObject = Json.parseToJsonElement(recurso("validador-casos.json")).jsonArray
        .map { it.jsonObject }.first { it["nombre"]!!.jsonPrimitive.content == "valida" }["json"]!!.jsonPrimitive.content
        .replace("\"ejemplo-formato\"", "\"fuerza-prueba\"").let { Json.parseToJsonElement(it).jsonObject }
    private val alimentacion: JsonObject = Json.parseToJsonElement(recurso("prueba-programa-con-compras.json")).jsonObject
    private val apoyo: JsonObject = Json.parseToJsonElement(recurso("prueba-apoyo-programa.json")).jsonObject

    private fun sobre(tipo: String = Plan.TIPO, perfil: String = "prueba", ru: JsonObject? = rutina, al: JsonObject? = alimentacion, ap: JsonObject? = apoyo) =
        JsJson.stringify(buildJsonObject {
            put("tipo", tipo); put("version_formato", "1.0"); put("perfil", perfil); put("generado_en", "2026-10-12T10:31:02-03:00")
            if (ru != null) put("rutina", ru)
            if (al != null) put("alimentacion", al)
            if (ap != null) put("apoyo", ap)
        })

    @Test fun sobreValidoSinNadaActivoImportaLasTres() {
        val r = Plan.revisar(sobre(), null, null, null, "Prueba", ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertEquals(listOf("rutina", "alimentacion", "apoyo"), r.nuevas.map { it.clave })
        assertTrue(r.nuevas.all { it.docs.isNotEmpty() })
        assertTrue(r.avisos.isEmpty())
    }

    @Test fun tipoIncorrectoSeRechaza() {
        val r = Plan.revisar(sobre(tipo = "otra-cosa"), null, null, null, null, ahora)
        assertFalse(r.ok); assertTrue(r.partes.isEmpty())
    }

    @Test fun faltaUnaParteSeRechaza() {
        val r = Plan.revisar(sobre(ap = null), null, null, null, null, ahora)
        assertFalse(r.ok); assertTrue(r.errores.single().contains("apoyo"))
    }

    @Test fun textoQueNoEsJsonSeRechaza() = assertFalse(Plan.revisar("{no es json", null, null, null, null, ahora).ok)

    @Test fun unaParteConErroresRechazaTodo() {
        val apoyoMalo = JsonObject(apoyo.filterKeys { it != "horario" })
        val r = Plan.revisar(sobre(ap = apoyoMalo), null, null, null, null, ahora)
        assertFalse(r.ok)
        assertEquals("error", r.partes.first { it.clave == "apoyo" }.estado)
        assertEquals("nueva", r.partes.first { it.clave == "rutina" }.estado)   // válida, pero no se aplica: todo o nada
    }

    @Test fun parteIdenticaALaActivaSeOmite() {
        val rutinaActiva = buildJsonObject { put("rutina", rutina); put("importada_en", ahora) }
        val r = Plan.revisar(sobre(), rutinaActiva, alimentacion, null, null, ahora)
        assertTrue(r.errores.toString(), r.ok)
        assertEquals("igual", r.partes.first { it.clave == "rutina" }.estado)
        assertEquals("igual", r.partes.first { it.clave == "alimentacion" }.estado)
        assertEquals(listOf("apoyo"), r.nuevas.map { it.clave })
    }

    @Test fun perfilDistintoSoloAvisa() {
        val r = Plan.revisar(sobre(perfil = "otra-persona"), null, null, null, "Héctor", ahora)
        assertTrue(r.ok)
        assertEquals(1, r.avisos.size)
    }

    @Test fun perfilConTildesCoincide() {
        val r = Plan.revisar(sobre(perfil = "hector"), null, null, null, "Héctor", ahora)
        assertTrue(r.avisos.isEmpty())
    }

    @Test fun rutinaConVersionMenorEsError() {
        val activaMayor = Json.parseToJsonElement(JsJson.stringify(rutina).replace("\"version\":1,", "\"version\":9,")).jsonObject
        val r = Plan.revisar(sobre(), buildJsonObject { put("rutina", activaMayor); put("importada_en", JsonPrimitive(ahora)) }, null, null, null, ahora)
        assertFalse(r.ok)
        assertEquals("error", r.partes.first { it.clave == "rutina" }.estado)
    }
}
