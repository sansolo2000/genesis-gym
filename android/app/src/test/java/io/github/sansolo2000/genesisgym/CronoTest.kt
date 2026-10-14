package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Gimnasio.arr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** M-09: cronómetro de la sesión (CONTEXTO-DATOS-DIARIOS v3.4 §1.3.1). Solo datos inventados. */
class CronoTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun serie(modo: String, tipo: String = "efectiva") =
        if (modo == "tiempo") """{"n":1,"tipo":"$tipo","modo":"tiempo","prescrito_seg":300,"seg_reales":null}"""
        else """{"n":1,"tipo":"$tipo","modo":"reps","prescrito_reps":"10","carga_real_kg":null,"reps_reales":null}"""
    private fun ej(id: String, vararg series: String) = """{"ejercicio_id":"$id","nombre":"$id","series":[${series.joinToString(",")}]}"""

    // Elíptica (tiempo) · 2 de fuerza (con una serie de calentamiento) · 2 elongaciones (tiempo).
    private val sesion = json("""{"fecha":"2030-10-16","dia_semana":"miercoles","sesion_id":"b","sesion_nombre":"Sesión B","rutina_id":"r","rutina_version":1,
        "estado":"en_curso","rpe_sesion":null,"duracion_min":null,"nota":"","ejercicios":[
        ${ej("eliptica", serie("tiempo", "calentamiento"))},
        ${ej("press", serie("reps", "calentamiento"), serie("reps"))},
        ${ej("remo", serie("reps"))},
        ${ej("elong-1", serie("tiempo"))},
        ${ej("elong-2", serie("tiempo"))}]}""")

    // 2030-10-16 en Santiago es horario de verano (-03:00): 10:00 local = 13:00Z.
    private fun t(hhmmss: String): Instant = Instant.parse("2030-10-16T${hhmmss}Z")
    private fun anotar(b: JsonObject, i: Int, k: Int): JsonObject {
        val se = b.arr("ejercicios")[i].arr("series")[k]
        val campo = if (se["modo"].toString().contains("tiempo")) "seg_reales" else "reps_reales"
        return Gimnasio.conValorSerie(b, i, k, campo, 10.0)
    }
    /** Anota (i, k) a la hora dada, como lo hace Gym.guardar. */
    private fun paso(b: JsonObject, i: Int, k: Int, h: String) = Crono.alGuardar(b, anotar(b, i, k), t(h))

    @Test fun bloquesPorPosicion() {
        assertEquals(listOf("cal", "fuerza", "fuerza", "elong", "elong"), Gimnasio.bloques(sesion.arr("ejercicios")))
        // Un ejercicio por tiempo entre dos de fuerza queda en fuerza.
        val medio = json("""{"ejercicios":[${ej("a", serie("reps"))},${ej("plancha", serie("tiempo"))},${ej("c", serie("reps"))}]}""")
        assertEquals(listOf("fuerza", "fuerza", "fuerza"), Gimnasio.bloques(medio.arr("ejercicios")))
        // Sin ningún ejercicio por repeticiones: todo fuerza.
        assertEquals(listOf("fuerza"), Gimnasio.bloques(json("""{"ejercicios":[${ej("x", serie("tiempo"))}]}""").arr("ejercicios")))
    }

    @Test fun sinIniciarLasSieteColumnasVanVacias() {
        assertEquals(List(7) { "" }, Crono.campos(sesion))
        assertNull(Crono.resumen(sesion))
        val filas = Gimnasio.csv(listOf(sesion)).split("\r\n")
        assertEquals(Gimnasio.COLUMNAS.joinToString(";"), filas[0])
        assertEquals(27, Gimnasio.COLUMNAS.size)
        assertTrue(filas.drop(1).all { it.endsWith(";;;;;;;") && it.split(";").size == 27 })
    }

    @Test fun finAutomaticoConBloquesContiguos() {
        var b = Crono.iniciar(sesion, t("13:00:00"))
        assertTrue(Crono.corriendo(b))
        assertEquals(b, Crono.iniciar(b, t("13:05:00")))                // iniciar dos veces no cambia nada
        b = paso(b, 0, 0, "13:09:30")                                     // elíptica: fin del calentamiento
        b = paso(b, 1, 0, "13:15:00"); b = paso(b, 1, 1, "13:30:00"); b = paso(b, 2, 0, "13:54:00")   // fuerza
        b = paso(b, 3, 0, "13:58:00")
        assertTrue(Crono.corriendo(b))
        b = paso(b, 4, 0, "14:02:24")                                     // última serie de la sesión: se detiene sola
        assertFalse(Crono.corriendo(b))
        assertEquals(listOf("10:00:00", "11:02:24", "auto", "62,4", "9,5", "44,5", "8,4"), Crono.campos(b))
        assertEquals("Sesión B · 62 min" to "calentamiento 10 min · fuerza 45 min · elongación 8 min", Crono.resumen(b))
        // Una vez detenido no se reabre ni se recalcula, aunque se edite una serie.
        assertEquals(Crono.campos(b), Crono.campos(Crono.alGuardar(b, Gimnasio.conValorSerie(b, 1, 1, "reps_reales", 8.0), t("14:30:00"))))
        // En el CSV, las 7 columnas se repiten en cada fila de la sesión.
        val filas = Gimnasio.csv(listOf(b)).split("\r\n").drop(1)
        assertEquals(6, filas.size)
        assertTrue(filas.all { it.endsWith(";10:00:00;11:02:24;auto;62,4;9,5;44,5;8,4") })
    }

    @Test fun finManualElBloqueEnCursoTerminaAhiYLosQueNoEmpezaronVanVacios() {
        var b = Crono.iniciar(sesion, t("13:00:00"))
        b = paso(b, 0, 0, "13:10:00"); b = paso(b, 1, 0, "13:20:00")
        b = Crono.terminar(b, t("13:40:00"))
        assertEquals(listOf("10:00:00", "10:40:00", "manual", "40,0", "10,0", "30,0", ""), Crono.campos(b))
        assertEquals(b, Crono.terminar(b, t("13:50:00")))               // terminar otra vez no cambia nada
        // Terminar antes de anotar nada: todo el tiempo es del bloque en curso (la elíptica, calentamiento).
        val c = Crono.terminar(Crono.iniciar(sesion, t("13:00:00")), t("13:03:00"))
        assertEquals(listOf("10:00:00", "10:03:00", "manual", "3,0", "3,0", "", ""), Crono.campos(c))
    }

    @Test fun abiertoSoloLlevaElInicio() {
        val b = paso(Crono.iniciar(sesion, t("13:00:00")), 0, 0, "13:10:00")
        assertEquals(listOf("10:00:00", "", "abierto", "", "", "", ""), Crono.campos(b))
        assertNull(Crono.resumen(b))
        assertEquals("18:42", Crono.transcurrido(b, t("13:18:42")))
        assertEquals("1:02:05", Crono.transcurrido(b, t("14:02:05")))
        assertEquals("fuerza", Crono.bloqueActual(b))
    }

    @Test fun reiniciarSoloMientrasNoHayaSeriesAnotadas() {   // Coordinador, 14-oct (U-57)
        val b = Crono.iniciar(sesion, t("13:00:00"))
        assertTrue(Crono.puedeReiniciar(b))
        assertFalse(Crono.puedeReiniciar(sesion))                      // sin iniciar: se ofrece "Iniciar rutina"
        val r = Crono.reiniciar(b, t("13:20:00"))
        assertEquals("10:20:00", Crono.campos(r)[0])
        assertTrue(Crono.corriendo(r))
        // Con la primera serie anotada ya no se puede reiniciar.
        val conSerie = paso(r, 0, 0, "13:25:00")
        assertFalse(Crono.puedeReiniciar(conSerie))
        assertEquals(conSerie, Crono.reiniciar(conSerie, t("13:30:00")))
    }

    @Test fun cerrarLaSesionDetieneElCronometroComoManual() {   // O-26 (U-58)
        val b = paso(Crono.iniciar(sesion, t("13:00:00")), 0, 0, "13:10:00")
        val c = Gimnasio.cerrada(b, t("13:30:00"))
        assertEquals("cerrada", (c["estado"] as kotlinx.serialization.json.JsonPrimitive).content)
        // El calentamiento terminó a las 10:10; el resto es de fuerza, el bloque en curso al cerrar.
        assertEquals(listOf("10:00:00", "10:30:00", "manual", "30,0", "10,0", "20,0", ""), Crono.campos(c))
        assertFalse(Crono.corriendo(c))
        assertFalse(Crono.puedeReiniciar(c))
        // Sin cronómetro, cerrar no inventa nada.
        assertEquals(List(7) { "" }, Crono.campos(Gimnasio.cerrada(sesion, t("13:30:00"))))
    }

    @Test fun conCronometroYSinSeriesNoCuentaComoRegistrada() {   // O-27
        val soloCrono = Crono.iniciar(sesion, t("13:00:00"))
        assertEquals(emptyList<JsonObject>(), Gimnasio.sesionesDeSemana(listOf(soloCrono), "2030-10-16"))
        assertEquals(emptySet<String>(), Gimnasio.clavesRegistradas(listOf(soloCrono)))
        val rutina = listOf(json("""{"id":"b","dia_semana":"miercoles"}"""))
        assertEquals(listOf("b"), Gimnasio.pendientesDeSemana(rutina, listOf(soloCrono), "2030-10-16", null).map { it["id"].toString().trim('"') })
        // Con la primera serie anotada empieza a contar.
        val conSerie = paso(soloCrono, 1, 0, "13:15:00")
        assertEquals(1, Gimnasio.sesionesDeSemana(listOf(conSerie), "2030-10-16").size)
        assertEquals(setOf("2030-10-16_b"), Gimnasio.clavesRegistradas(listOf(conSerie)))
        // Una sesión sin cronómetro sigue contando como antes.
        assertEquals(setOf("2030-10-16_b"), Gimnasio.clavesRegistradas(listOf(sesion)))
    }

    @Test fun elCronometroEntraAlRespaldoComoParteDeLaSesion() {
        val b = paso(Crono.iniciar(sesion, t("13:00:00")), 0, 0, "13:10:00")
        val vuelta = json(JsJson.stringify(b))
        assertEquals(Crono.campos(b), Crono.campos(vuelta))
    }
}
