package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los resultados esperados los produjo `alimentacion.js` de la 2.0 (con un programa inventado, un reloj fijo y una base
 * y un Drive falsos). La nativa debe escribir los mismos documentos y los mismos registro-comida, byte a byte.
 */
class ComidasTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun lista(e: JsonElement?) = (e as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    @Test fun validador_igual_a_la_2_0() {
        val casos = Json.parseToJsonElement(recurso("prueba-programa-casos.json")).jsonArray
        assertTrue(casos.size >= 20)
        for (c in casos) {
            c as JsonObject
            val nombre = c["nombre"]!!.jsonPrimitive.content
            val r = Comidas.validarPrograma(Json.parseToJsonElement(c["json"]!!.jsonPrimitive.content))
            assertEquals(nombre, lista(c["errores"]), r.errores)
            assertEquals(nombre, lista(c["avisos"]), r.avisos)
            assertEquals(nombre, c["ok"]!!.jsonPrimitive.content.toBoolean(), r.ok)
        }
    }

    @Test fun fechas_como_javascript() {
        assertTrue(Comidas.fechaOk(JsonPrimitive("2026-02-30")))        // JavaScript lo pasa a marzo
        assertTrue(!Comidas.fechaOk(JsonPrimitive("2026-13-01")))
        assertTrue(!Comidas.fechaOk(JsonPrimitive("2026-01-32")))
        assertEquals("lunes 2-mar", Comidas.fechaLarga("2026-02-30"))
        assertEquals("martes 6-oct", Comidas.fechaLarga("2026-10-06"))
        assertEquals("2026-11-01", Comidas.sumarDias("2026-10-31", 1))
    }

    @Test fun mismo_programa_ya_activo() {
        val prog = recurso("prueba-comidas-escenario.json").let { Json.parseToJsonElement(it).jsonObject["programa"]!!.jsonObject }
        val (_, r) = Comidas.revisarTexto(JsJson.stringify(prog), prog)
        assertTrue(r.yaActivo && !r.ok)
        assertEquals(listOf("El archivo llegó vacío al celular."), Comidas.revisarTexto("  ", null).second.errores)
    }

    @Test fun escenario_igual_a_la_2_0() {
        val raiz = Json.parseToJsonElement(recurso("prueba-comidas-escenario.json")).jsonObject
        val prog = raiz["programa"]!!.jsonObject
        val ahora = "2026-10-06T15:00:00.000Z"
        val app = "genesis-gym-2 2.0-prueba"
        var est = EstadoComidas(null)
        var dia = "2026-10-06"
        for (p in raiz["pasos"]!!.jsonArray) {
            p as JsonObject
            val nombre = p["nombre"]!!.jsonPrimitive.content
            var archivos: List<EstadoComidas.Archivo>? = null
            est.mensaje = null
            val docs: List<Respaldo.Doc> = when (nombre) {
                "activar" -> EstadoComidas.documentosActivacion(prog, ahora).also { est = EstadoComidas(prog); dia = est.elegirDia("2026-10-06") }
                "desayuno indicado", "desayuno indicado otra vez" -> est.marcar(dia, "desayuno", "indicado", ahora)
                "almuerzo otro" -> est.marcar(dia, "almuerzo", "otro", ahora)
                "almuerzo nota", "almuerzo nota igual" -> est.anotar(dia, "almuerzo", "media porción y \"pan\"", ahora)
                "colacion_am otro sin foto" -> est.marcar(dia, "colacion_am", "otro", ahora)
                "almuerzo foto" -> est.ponerFoto(dia, "almuerzo", "data:image/jpeg;base64,QUJD", ahora)
                "colacion_am indicado" -> est.marcar(dia, "colacion_am", "indicado", ahora)
                "intercambiar cena 06 con almuerzo 07" -> est.intercambiar("2026-10-06_cena", "2026-10-07_almuerzo", ahora)
                "cena indicado" -> est.marcar(dia, "cena", "indicado", ahora)
                "desayuno no comi" -> est.marcar(dia, "desayuno", "no-comi", ahora)
                "deshacer intercambio" -> est.deshacer("2026-10-06_cena", ahora)
                "intercambiar mismo dia" -> est.intercambiar("2026-10-06_colacion_am", "2026-10-06_colacion_pm", ahora)
                "intercambiar cadena" -> est.intercambiar("2026-10-06_colacion_am", "2026-10-05_cena", ahora)
                "enviar falta foto", "enviar 1", "enviar sin pendientes", "enviar 2" -> {
                    archivos = est.preparaEnvio(app)
                    val a = archivos
                    if (a == null) emptyList() else {
                        val hechos = a.mapNotNull { est.alListo(it.clave, "id-" + it.nombre, ahora) }
                        est.mensajeEnviado(a.count { it.mime == "application/json" }, a.size); hechos
                    }
                }
                else -> error("paso desconocido: $nombre")
            }
            // documentos escritos, en el mismo orden y con el mismo texto
            val esperadas = p["escrituras"]!!.jsonArray.map { it.jsonObject }
            assertEquals(nombre, esperadas.map { it["ruta"]!!.jsonPrimitive.content }, docs.map { it.ruta })
            esperadas.forEachIndexed { i, e -> assertEquals("$nombre ${e["ruta"]}", JsJson.stringify(e["data"]!!), JsJson.stringify(docs[i].data)) }
            // archivos que se suben a Drive
            val ea = p["archivos"]
            if (ea is JsonArray) {
                val a = archivos!!
                assertEquals(nombre, ea.size, a.size)
                ea.forEachIndexed { i, x ->
                    x as JsonObject
                    assertEquals(nombre, x["nombre"]!!.jsonPrimitive.content, a[i].nombre)
                    assertEquals(nombre, x["mime"]!!.jsonPrimitive.content, a[i].mime)
                    assertEquals(nombre, x["clave"]!!.jsonPrimitive.content, a[i].clave)
                    assertEquals(nombre, (x["texto"] as? JsonPrimitive)?.takeIf { it.isString }?.content, a[i].texto)
                }
            } else assertTrue(nombre, archivos == null)
            // mensaje, intercambios y registros en memoria
            if (nombre != "activar") {
                val m = p["mensaje"]
                if (m is JsonObject) assertEquals(nombre, m["tipo"]!!.jsonPrimitive.content to m["texto"]!!.jsonPrimitive.content, est.mensaje)
                else assertEquals(nombre, null, est.mensaje)
            }
            assertEquals(nombre, JsJson.stringify(p["cambios"]!!), JsJson.stringify(JsonObject(est.cambios)))
            assertEquals(nombre, JsJson.stringify(p["registros"]!!), JsJson.stringify(JsonObject(est.registros)))
            assertEquals(nombre, p["dia"]!!.jsonPrimitive.content, dia)
        }
    }

    @Test fun indentado_como_javascript() {
        val o = Json.parseToJsonElement("""{"a":[],"b":{},"c":[1,{"d":null}],"e":"x\"y"}""")
        assertEquals("{\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    1,\n    {\n      \"d\": null\n    }\n  ],\n  \"e\": \"x\\\"y\"\n}", JsJson.indentado(o))
        assertEquals("null", JsJson.indentado(JsonNull))
    }
}
