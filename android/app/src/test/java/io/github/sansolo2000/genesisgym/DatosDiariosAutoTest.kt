package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** 0.19.0, entrega 2 de datos diarios (CONTEXTO-DATOS-DIARIOS §11.1). Fechas inventadas de 2030. */
class DatosDiariosAutoTest {
    private val z = DatosDiarios.ZONA
    private fun en(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), z)
    private fun d(s: String) = LocalDate.parse(s)
    private val subido = DatosDiarios.EstadoDia(1, emptyMap(), false)

    @Test fun losCambiosEnSieteDiasYLoNuncaSubidoHastaVeintiuno() {
        val ahora = en("2030-10-25T13:00")
        // Sin datos más viejos: solo los 7 días de siempre (18 al 24).
        assertEquals((18..24).map { d("2030-10-%02d".format(it)) }, DatosDiarios.diasARevisar(ahora, { null }, null))
        // Con datos desde el 10: los nunca subidos del 10 al 17 entran; los ya subidos de esos días no.
        val r = DatosDiarios.diasARevisar(ahora, { if (it == d("2030-10-12")) subido else null }, d("2030-10-10"))
        assertEquals(d("2030-10-10"), r.first()); assertFalse(d("2030-10-12") in r); assertTrue(d("2030-10-11") in r)
        assertEquals(d("2030-10-24"), r.last())
        // Nunca más de 21 días atrás, aunque haya datos desde antes.
        assertEquals(d("2030-10-04"), DatosDiarios.diasARevisar(ahora, { null }, d("2030-09-01")).first())
        // Un día dentro de los 7 se revisa aunque ya esté subido (cambios tardíos).
        assertTrue(d("2030-10-20") in DatosDiarios.diasARevisar(ahora, { subido }, d("2030-10-01")))
    }

    @Test fun laAutomaticaEsperaQuinceMinutosDespuesDelCierre() {
        // A las 12:10 ayer ya cerró (12:00) para "Subir ahora", pero la automática espera hasta las 12:15.
        val ahora = en("2030-10-25T12:10")
        assertTrue(d("2030-10-24") in DatosDiarios.diasARevisar(ahora, { null }, null))
        assertFalse(d("2030-10-24") in DatosDiarios.diasARevisar(ahora, { null }, null, DatosDiarios.MARGEN_AUTOMATICA_MIN))
        assertTrue(d("2030-10-24") in DatosDiarios.diasARevisar(en("2030-10-25T12:15"), { null }, null, DatosDiarios.MARGEN_AUTOMATICA_MIN))
        // Antes de las 12:00 ayer no cierra para ninguna.
        assertFalse(d("2030-10-24") in DatosDiarios.diasARevisar(en("2030-10-25T11:59"), { null }, null))
    }

    /** 0.19.0 (Héctor): "Exportar CSV" va a la carpeta propia "exportacion", con la misma estrategia de carpeta que datos-diarios. */
    @Test fun exportarCsvADrive() {
        assertEquals("exportacion", Gimnasio.CARPETA_EXPORTACION)
        assertEquals("20301025_130000_genesis-fuerza.csv", Gimnasio.nombreCsv("20301025_130000"))
        assertTrue(Gimnasio.textoExportado("x.csv", true).contains("muévela una vez a Genesis Gym"))
        assertFalse(Gimnasio.textoExportado("x.csv", false).contains("raíz"))
        // Las dos carpetas de la app con nombres distintos (la búsqueda es por nombre, dondequiera que estén).
        assertFalse(DatosDiarios.CARPETA == Gimnasio.CARPETA_EXPORTACION)
        assertTrue(DriveTexto.consultaCarpeta(Gimnasio.CARPETA_EXPORTACION).contains("name='exportacion'"))
    }

    @Test fun motivoYPrimerDiaConDatos() {
        assertEquals("automatico", DatosDiarios.motivo(null, automatico = true))
        assertEquals("automatico", DatosDiarios.motivo(subido, automatico = true))
        assertEquals("manual", DatosDiarios.motivo(null)); assertEquals("tardio", DatosDiarios.motivo(subido))
        assertEquals(d("2030-10-05"), DatosDiarios.inicioDatos(listOf("2030-10-12", "2030-10-05_almuerzo", "basura", "2030-10-09")))
        assertNull(DatosDiarios.inicioDatos(emptyList()))
    }
}
