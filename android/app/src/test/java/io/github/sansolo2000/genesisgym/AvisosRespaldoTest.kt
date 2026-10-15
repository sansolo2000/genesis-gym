package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M-04: Avisos y sonido en el respaldo; hora del reporte tomada del programa de apoyo. */
class AvisosRespaldoTest {
    @Test fun idaYVueltaConservaTodo() {
        val r = Recordatorios.Respaldados(
            Recordatorios.Config(comidas = true, minutosAntes = 10, tareas = true, entrenamiento = true, horaEntrenamiento = "07:30", reporte = true, horaReporte = "21:15"),
            "content://media/internal/audio/media/42", true, 85)
        assertEquals(r, Recordatorios.avisosDesdeJson(Recordatorios.avisosAJson(r)))
    }

    @Test fun idaYVueltaConLosTiposDeAlerta() {   // M-10
        val tipos = mapOf(
            Recordatorios.COMIDA to Alertas.Tipo("content://media/internal/audio/media/7", 60, true, true, 5),
            Alertas.DESCANSO to Alertas.Tipo(null, 100, false, false, 10))
        val r = Recordatorios.Respaldados(Recordatorios.Config(comidas = true, descanso = false), null, false, 70, tipos)
        assertEquals(r, Recordatorios.avisosDesdeJson(Recordatorios.avisosAJson(r)))
    }

    @Test fun sinSonidoElegidoQuedaNulo() {
        val r = Recordatorios.Respaldados(Recordatorios.Config(), null, false, 70)
        assertEquals(r, Recordatorios.avisosDesdeJson(Recordatorios.avisosAJson(r)))
    }

    @Test fun camposMalosUsanLosValoresPorDefecto() {
        val o = JsonObject(mapOf("comidas" to JsonPrimitive("si"), "hora_reporte" to JsonPrimitive("25:00"), "volumen" to JsonPrimitive(500)))
        val r = Recordatorios.avisosDesdeJson(o)
        assertEquals(false, r.config.comidas)
        assertEquals("21:00", r.config.horaReporte)
        assertEquals(100, r.volumen)
    }

    @Test fun horaDelReporteSeTomaSoloSiCambia() {
        assertEquals("21:30", Recordatorios.horaReporteAAplicar("21:30", null))     // primer programa
        assertEquals("21:30", Recordatorios.horaReporteAAplicar("21:30", "21:00"))  // el programa cambió la hora
        assertNull(Recordatorios.horaReporteAAplicar("21:00", "21:00"))            // misma hora: respeta lo que Héctor puso en Avisos
        assertNull(Recordatorios.horaReporteAAplicar("9pm", null))                 // hora inválida
        assertNull(Recordatorios.horaReporteAAplicar(null, "21:00"))
    }
}
