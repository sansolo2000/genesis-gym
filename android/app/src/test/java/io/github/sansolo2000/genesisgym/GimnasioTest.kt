package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.con
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resultados esperados generados con el código JS de la 2.0 (index.html) sobre datos inventados. */
class GimnasioTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test fun csv_identico_a_la_2_0() {
        val f = Json.parseToJsonElement(recurso("prueba-csv.json")).jsonObject
        val sesiones = f["sesiones"]!!.jsonArray.map { it.jsonObject }
        assertEquals(f["csv"]!!.jsonPrimitive.content, Gimnasio.csv(sesiones))
    }

    @Test fun borrador_identico_a_la_2_0() {
        val f = Json.parseToJsonElement(recurso("prueba-borrador.json")).jsonObject
        val rutina = Json.parseToJsonElement(f["rutina"]!!.jsonPrimitive.content).jsonObject
        for (c in f["casos"]!!.jsonArray) {
            val id = c.jsonObject["sesion_id"]!!.jsonPrimitive.content
            val ses = rutina.arr("sesiones").first { it.txt("id") == id }
            val b = Gimnasio.nuevoBorrador("2026-10-12", ses, rutina, "FIJO")
            assertEquals(id, c.jsonObject["borrador"]!!.jsonPrimitive.content, JsJson.stringify(b))
        }
    }

    @Test fun dia_de_la_semana() {
        assertEquals("lunes", Gimnasio.diaDe("2026-10-12"))
        assertEquals("domingo", Gimnasio.diaDe("2026-10-11"))
        assertEquals("sabado", Gimnasio.diaDe("2026-10-10"))
    }

    @Test fun anotar_y_copiar_indicado() {
        val rutina = Json.parseToJsonElement(Json.parseToJsonElement(recurso("prueba-borrador.json")).jsonObject["rutina"]!!.jsonPrimitive.content).jsonObject
        val b0 = Gimnasio.nuevoBorrador("2026-10-12", rutina.arr("sesiones")[0], rutina, "x")
        val b1 = Gimnasio.conValorSerie(b0, 0, 0, "carga_real_kg", 12.5)
        assertEquals("12.5", b1.arr("ejercicios")[0].arr("series")[0]["carga_real_kg"].toString())
        val b2 = Gimnasio.copiarIndicado(b1, 0, 1)
        val se = b2.arr("ejercicios")[0].arr("series")[1]
        assertEquals("20", se["carga_real_kg"].toString()); assertEquals("10", se["reps_reales"].toString())
        assertEquals(2, Gimnasio.seriesRegistradas(b2))
    }

    @Test fun numeros_escritos() {
        assertEquals(Gimnasio.Num.Valor(17.5), Gimnasio.parseNum("17,5", false))
        assertEquals(Gimnasio.Num.Valor(null), Gimnasio.parseNum("  ", true))
        assertEquals(Gimnasio.Num.Error, Gimnasio.parseNum("10,5", true))
        assertEquals(Gimnasio.Num.Error, Gimnasio.parseNum("-1", false))
        assertEquals(Gimnasio.Num.Error, Gimnasio.parseNum("abc", false))
    }

    @Test fun importar_reglas_de_la_2_0() {
        val texto = Json.parseToJsonElement(recurso("prueba-borrador.json")).jsonObject["rutina"]!!.jsonPrimitive.content
        val obj = Json.parseToJsonElement(texto).jsonObject
        val activa = Gimnasio.documentosActivacion(obj, "t")[0].data
        val igual = Gimnasio.revisarImportacion(texto, activa)
        assertTrue(igual.yaActiva); assertFalse(igual.resultado.ok)
        val misma = Gimnasio.revisarImportacion(texto.replace("\"nombre\":\"Ejemplo\"", "\"nombre\":\"Otro\""), activa)
        // id = ejemplo-formato (rutina de ejemplo): no se exige subir la versión
        assertTrue(misma.resultado.ok)
        val real = activa.con("rutina", obj.con("rutina", obj["rutina"]!!.jsonObject.con("id", JsonPrimitive("fuerza-genesis"))))
        val otra = Gimnasio.revisarImportacion(texto, real)
        assertTrue(otra.resultado.avisos[0].startsWith("Esta rutina (ejemplo-formato) es distinta de la activa (fuerza-genesis)."))
        val t2 = texto.replace("\"id\":\"ejemplo-formato\"", "\"id\":\"fuerza-genesis\"").replace("\"nombre\":\"Ejemplo\"", "\"nombre\":\"Otro\"")
        val vieja = Gimnasio.revisarImportacion(t2, real)
        assertEquals(listOf("rutina.version: debe ser mayor que la versión activa (1). Pide a Entrenamiento el archivo con la versión aumentada."), vieja.resultado.errores)
        assertEquals("rutinas/ejemplo-formato_v1", Gimnasio.documentosActivacion(obj, "t")[1].ruta)
    }

    @Test fun cambiar_fecha() {
        val rutina = Json.parseToJsonElement(Json.parseToJsonElement(recurso("prueba-borrador.json")).jsonObject["rutina"]!!.jsonPrimitive.content).jsonObject
        val b = Gimnasio.nuevoBorrador("2026-10-12", rutina.arr("sesiones")[0], rutina, "x")
        assertEquals("La fecha no puede ser futura.", Gimnasio.errorCambioFecha("2026-10-13", "2026-10-12", b, emptyList()))
        assertEquals("Elige una fecha válida.", Gimnasio.errorCambioFecha("2026-02-30", "2026-10-12", b, emptyList()))
        assertNull(Gimnasio.errorCambioFecha("2026-10-05", "2026-10-12", b, emptyList()))
        val c = Gimnasio.conFechaCambiada(b, "2026-10-05", "t")
        assertEquals("lunes", c.txt("dia_semana")); assertEquals(1, c.arr("cambios_fecha").size)
    }

    @Test fun textos() {
        assertEquals("lun, 12 oct", Gimnasio.fechaCorta("2026-10-12"))
        assertEquals("Lunes, 12 de octubre", Gimnasio.fechaLarga("2026-10-12"))
    }
}
