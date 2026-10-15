package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-10: alertas configurables por tipo (decisiones de Héctor y del Coordinador, 14-oct). */
class AlertasTest {
    private val base = Alertas.Tipo(null, 70, true, false, 10)

    @Test fun losCincoTiposYCualesInsisten() {
        assertEquals(5, Alertas.TIPOS.size)
        assertEquals(listOf("Comidas", "Tareas de cocina", "Entrenamientos", "Descanso en entrenamiento", "Reporte diario"), Alertas.TIPOS.map { Alertas.NOMBRE[it] })
        assertTrue(Alertas.TIPOS.filter { it != Alertas.DESCANSO }.all { Alertas.insiste(it) })
        assertFalse(Alertas.insiste(Alertas.DESCANSO))                     // el descanso suena una vez, al llegar a 0
        assertEquals(120_000L, Alertas.TOPE_MS)                              // tope de 2 minutos (Héctor)
    }

    @Test fun prioridadCuandoCoinciden() {
        assertEquals(Recordatorios.ENTRENAMIENTO, Alertas.ganador(listOf(Recordatorios.REPORTE, Recordatorios.COMIDA, Recordatorios.ENTRENAMIENTO)))
        assertEquals(Recordatorios.COMIDA, Alertas.ganador(listOf(Recordatorios.TAREA, Recordatorios.COMIDA)))
        assertEquals(Recordatorios.TAREA, Alertas.ganador(listOf(Recordatorios.REPORTE, Recordatorios.TAREA)))
        assertNull(Alertas.ganador(emptyList()))
        assertTrue(Alertas.reemplaza(null, Recordatorios.REPORTE))             // no sonaba nada
        assertTrue(Alertas.reemplaza(Recordatorios.COMIDA, Recordatorios.ENTRENAMIENTO))
        assertFalse(Alertas.reemplaza(Recordatorios.ENTRENAMIENTO, Recordatorios.COMIDA))
        assertFalse(Alertas.reemplaza(Recordatorios.COMIDA, Recordatorios.COMIDA))
    }

    @Test fun silencioYNoMolestar() {
        val conSilencio = base.copy(enSilencio = true)
        // Con "sonar en silencio": suena con el celular en silencio y en No molestar si este deja pasar las alarmas.
        assertTrue(Alertas.suena(conSilencio, Alertas.TIMBRE_SILENCIO, Alertas.FILTRO_TODO, null))
        assertTrue(Alertas.suena(conSilencio, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_ALARMAS, null))
        assertTrue(Alertas.suena(conSilencio, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_PRIORIDAD, true))
        assertFalse(Alertas.suena(conSilencio, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_PRIORIDAD, false))   // "Permitir alarmas" apagado
        assertFalse(Alertas.suena(conSilencio, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_NADA, null))
        // Sin el interruptor: respeta silencio, vibración y No molestar.
        assertTrue(Alertas.suena(base, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_TODO, null))
        assertFalse(Alertas.suena(base, Alertas.TIMBRE_VIBRAR, Alertas.FILTRO_TODO, null))
        assertFalse(Alertas.suena(base, Alertas.TIMBRE_NORMAL, Alertas.FILTRO_PRIORIDAD, true))
        // Vibración: en silencio solo vibra con "sonar en silencio"; sin "Vibrar" nunca.
        assertTrue(Alertas.vibra(base, Alertas.TIMBRE_VIBRAR))
        assertFalse(Alertas.vibra(base, Alertas.TIMBRE_SILENCIO))
        assertTrue(Alertas.vibra(conSilencio, Alertas.TIMBRE_SILENCIO))
        assertFalse(Alertas.vibra(base.copy(vibrar = false), Alertas.TIMBRE_NORMAL))
        // La pantalla avisa cuando "sonar en silencio" no alcanza.
        assertNotNull(Alertas.avisoNoMolestar(Alertas.FILTRO_PRIORIDAD, false))
        assertNotNull(Alertas.avisoNoMolestar(Alertas.FILTRO_NADA, null))
        assertNull(Alertas.avisoNoMolestar(Alertas.FILTRO_TODO, null))
        assertNull(Alertas.avisoNoMolestar(Alertas.FILTRO_PRIORIDAD, true))
    }

    @Test fun valorInicialEsElSonidoUnicoDeAntes() {
        assertEquals(Alertas.Tipo("content://x/1", 85, true, true, 10), Alertas.inicial("content://x/1", 85, true))
        assertEquals(100, Alertas.inicial(null, 500, false).volumen)
    }

    @Test fun respaldoDeLosTipos() {
        val m = mapOf(Recordatorios.REPORTE to Alertas.Tipo("content://x/2", 40, false, true, 15), Alertas.DESCANSO to base)
        assertEquals(m, Alertas.desdeJson(Alertas.aJson(m), base))
        // Campos malos: valores por defecto; un posponer que no está en 5/10/15 vuelve a 10; un tipo desconocido se ignora.
        val malo = JsonObject(mapOf(Recordatorios.COMIDA to JsonObject(mapOf("volumen" to JsonPrimitive(300), "vibrar" to JsonPrimitive("si"), "posponer_min" to JsonPrimitive(7))),
            "otro" to JsonObject(emptyMap())))
        assertEquals(mapOf(Recordatorios.COMIDA to Alertas.Tipo(null, 100, true, false, 10)), Alertas.desdeJson(malo, base))
        assertEquals(emptyMap<String, Alertas.Tipo>(), Alertas.desdeJson(null, base))
    }

    @Test fun posponerYVigencia() {
        assertEquals(1_000L + 10 * 60_000L, Alertas.posponerHasta(1_000L, 10))
        val marcadas = setOf("2030-10-16_desayuno")
        fun v(tipo: String, claves: List<String>) = Alertas.vigente(tipo, claves, { it in marcadas }, { it == "2030-10-16" }, { it == "2030-10-15" })
        assertFalse(v(Recordatorios.COMIDA, listOf("2030-10-16_desayuno")))              // ya se marcó: no vuelve a sonar
        assertTrue(v(Recordatorios.COMIDA, listOf("2030-10-16_desayuno", "2030-10-16_colacion_am")))   // falta una
        assertFalse(v(Recordatorios.ENTRENAMIENTO, listOf("2030-10-16")))
        assertTrue(v(Recordatorios.REPORTE, listOf("2030-10-16")))
        assertTrue(v(Recordatorios.TAREA, emptyList()))
    }
}
