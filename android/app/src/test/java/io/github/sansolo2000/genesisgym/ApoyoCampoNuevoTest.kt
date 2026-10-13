package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apoyo #8: un programa con un campo nuevo (frases_diarias) se importa y el campo se guarda tal cual. Solo datos inventados. */
class ApoyoCampoNuevoTest {
    private val base = Json.parseToJsonElement(javaClass.classLoader!!.getResource("prueba-apoyo-programa.json")!!.readText()).jsonObject
    private val frases = JsonObject(mapOf(
        "rotacion" to JsonPrimitive("por_tipo_dia"), "desde" to JsonPrimitive("2026-10-14"),
        "fuerza" to JsonArray(listOf(JsonPrimitive("Frase de prueba A"))),
        "mtb" to JsonArray(listOf(JsonPrimitive("Frase de prueba B"))),
        "descanso" to JsonArray(listOf(JsonPrimitive("Frase de prueba C")))))

    @Test fun campoDesconocidoNoSeRechazaYSeConserva() {
        val conFrases = JsonObject(base + ("frases_diarias" to frases))
        val (obj, r) = Apoyo.revisarTexto(JsJson.stringify(conFrases), null)
        assertTrue(r.errores.toString(), r.ok)
        val docs = Apoyo.documentosActivacion(obj!!, "2026-10-13T12:00:00.000Z")
        assertEquals(frases, (docs.first().data["programa"] as JsonObject)["frases_diarias"])
    }
}
