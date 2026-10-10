package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AvisosTest {
    private val zona = ZoneId.of("America/Santiago")
    private val ahora = ZonedDateTime.of(2026, 10, 9, 23, 10, 30, 0, zona)

    @Test fun hora_futura_es_hoy() =
        assertEquals(ZonedDateTime.of(2026, 10, 9, 23, 30, 0, 0, zona), Avisos.proximaOcurrencia("23:30", ahora))

    @Test fun hora_pasada_es_manana() =
        assertEquals(ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, zona), Avisos.proximaOcurrencia("07:00", ahora))

    @Test fun acepta_una_cifra_en_la_hora() =
        assertEquals(ZonedDateTime.of(2026, 10, 10, 7, 5, 0, 0, zona), Avisos.proximaOcurrencia("7:05", ahora))

    @Test fun rechaza_horas_invalidas() {
        assertNull(Avisos.proximaOcurrencia("24:00", ahora))
        assertNull(Avisos.proximaOcurrencia("7.30", ahora))
        assertNull(Avisos.proximaOcurrencia("", ahora))
    }

    @Test fun atraso_en_palabras() {
        assertEquals("a la hora", Avisos.atraso(0, 3_000))
        assertEquals("+42 s", Avisos.atraso(0, 42_000))
        assertEquals("+2 min 5 s", Avisos.atraso(0, 125_000))
        assertEquals("−10 s", Avisos.atraso(10_000, 0))
    }

    @Test fun aviso_ida_y_vuelta_como_texto() {
        val a = Aviso(2, 123L, Avisos.MODO_EXACTO, 456L)
        assertEquals(a, Aviso.deTexto(a.aTexto()))
        val b = Aviso(3, 789L, Avisos.MODO_INEXACTO)
        assertEquals(b, Aviso.deTexto(b.aTexto()))
        assertNull(Aviso.deTexto("basura"))
    }
}
