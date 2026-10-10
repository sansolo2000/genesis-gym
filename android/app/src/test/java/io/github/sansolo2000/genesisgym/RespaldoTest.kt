package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los respaldos de prueba los generó el código JavaScript de la 2.0 (herramientas/migrar.js y la misma huella). */
class RespaldoTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test fun lee_un_respaldo_creado_por_la_2_0() {
        val r = Respaldo.leer(recurso("respaldo-ejemplo-1.0.json"))
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
        val l = (r as Respaldo.Resultado.Ok).leido
        assertEquals(6, l.docs.size)
        assertEquals(2, l.conteos["sesiones"])
        assertEquals("2026-10-05" to "2026-10-12", l.rango)
    }

    @Test fun huella_identica_con_textos_y_numeros_dificiles() {
        val r = Respaldo.leer(recurso("respaldo-rarezas.json"))
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
    }

    @Test fun cada_documento_se_serializa_igual_que_javascript() {
        val esperado = Json.parseToJsonElement(recurso("respaldo-rarezas-serializado.json")).jsonArray
        val docs = (Respaldo.leer(recurso("respaldo-rarezas.json")) as Respaldo.Resultado.Ok).leido.docs
        for ((i, e) in esperado.withIndex()) {
            e as JsonObject
            assertEquals(e["ruta"]!!.jsonPrimitive.content, docs[i].ruta)
            assertEquals(e["json"]!!.jsonPrimitive.content, JsJson.stringify(docs[i].data))
        }
    }

    @Test fun ida_y_vuelta_crear_y_leer() {
        val docs = (Respaldo.leer(recurso("respaldo-rarezas.json")) as Respaldo.Resultado.Ok).leido.docs
        val texto = Respaldo.crear(docs.reversed(), "Persona de prueba", "0.4.0", "2026-10-10T05:00:00.000Z")
        val r = Respaldo.leer(texto)
        assertTrue(r is Respaldo.Resultado.Ok)
        assertEquals(Respaldo.huella(docs), Respaldo.huella((r as Respaldo.Resultado.Ok).leido.docs))
    }

    @Test fun archivo_modificado_se_rechaza() {
        val t = recurso("respaldo-ejemplo-1.0.json").replaceFirst("\"rpe_sesion\": 5", "\"rpe_sesion\": 6")
        assertEquals(Respaldo.Resultado.Error("La huella del respaldo no coincide: el archivo está incompleto o fue modificado. No se restauró nada."), Respaldo.leer(t))
    }

    @Test fun mensajes_de_error_iguales_a_la_2_0() {
        assertEquals(Respaldo.Resultado.Error("El archivo no es un respaldo válido (no es JSON)."), Respaldo.leer("{no"))
        assertEquals(Respaldo.Resultado.Error("El archivo no es un respaldo de Génesis Gym (falta \"formato\": \"genesis-respaldo\"). ¿Elegiste una rutina en vez de un respaldo?"), Respaldo.leer("{\"formato\":\"genesis-rutina\"}"))
        assertEquals(Respaldo.Resultado.Error("Versión de respaldo no soportada: 2."), Respaldo.leer("{\"formato\":\"genesis-respaldo\",\"version\":2}"))
        assertEquals(Respaldo.Resultado.Error("El respaldo no trae la lista de documentos."), Respaldo.leer("{\"formato\":\"genesis-respaldo\",\"version\":1}"))
        assertEquals(Respaldo.Resultado.Error("El respaldo trae una colección desconocida: otra."), Respaldo.leer("{\"formato\":\"genesis-respaldo\",\"version\":1,\"documentos\":[{\"ruta\":\"otra/x\",\"data\":{}}]}"))
        assertEquals(Respaldo.Resultado.Error("El respaldo tiene un documento con formato inválido."), Respaldo.leer("{\"formato\":\"genesis-respaldo\",\"version\":1,\"documentos\":[{\"ruta\":\"config/x\",\"data\":[]}]}"))
        assertEquals(Respaldo.Resultado.Error("El respaldo tiene documentos repetidos."), Respaldo.leer("{\"formato\":\"genesis-respaldo\",\"version\":1,\"documentos\":[{\"ruta\":\"config/x\",\"data\":{}},{\"ruta\":\"config/x\",\"data\":{}}]}"))
    }

    @Test fun numeros_como_javascript() {
        val casos = mapOf("0" to "0", "-0" to "0", "20" to "20", "20.0" to "20", "22.5" to "22.5", "0.1" to "0.1", "1e21" to "1e+21",
            "1e+21" to "1e+21", "1e-7" to "1e-7", "0.000001" to "0.000001", "123456789012" to "123456789012", "-4.50" to "-4.5", "1e20" to "100000000000000000000", "1.5e-10" to "1.5e-10")
        for ((lit, js) in casos) assertEquals(lit, js, JsJson.numero(lit))
    }

    @Test fun numeros_creados_en_la_app() {
        assertEquals("20", JsJson.stringify(JsJson.primitivo(20.0)))
        assertEquals("22.5", JsJson.stringify(JsJson.primitivo(22.5)))
        assertEquals("17.25", JsJson.stringify(JsJson.primitivo(17.25)))
    }

    @Test fun nombre_de_archivo() = assertEquals("genesis-respaldo-hector-2026-10-10.json", Respaldo.nombreArchivo("Héctor", "2026-10-10"))
}
