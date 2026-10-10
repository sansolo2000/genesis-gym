package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Test

class SaludTest {
    @Test fun sin_registros() = assertEquals("0 registros", Salud.resumen(emptyList()))

    @Test fun agrupa_por_app_de_mayor_a_menor_con_nombres_conocidos() =
        assertEquals(
            "5 registros: movinglife 3 · Samsung Health 2",
            Salud.resumen(listOf(
                "com.sec.android.app.shealth", "com.moving.movinglife", "com.moving.movinglife",
                "com.sec.android.app.shealth", "com.moving.movinglife"
            ))
        )

    @Test fun paquete_desconocido_se_muestra_tal_cual() =
        assertEquals("1 registro: com.ejemplo.app 1", Salud.resumen(listOf("com.ejemplo.app")))
}
