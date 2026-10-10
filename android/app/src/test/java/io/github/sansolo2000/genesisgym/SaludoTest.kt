package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Test

class SaludoTest {
    @Test fun version_muestra_nombre_codigo_y_commit() =
        assertEquals("Versión 0.0.1 (código 1) · compilación abc1234", Saludo.version("0.0.1", 1, "abc1234"))

    @Test fun primera_apertura() =
        assertEquals("Primera vez que se abre en este celular.", Saludo.aperturas(1))

    @Test fun varias_aperturas() =
        assertEquals("Se ha abierto 3 veces en este celular.", Saludo.aperturas(3))
}
