package io.github.sansolo2000.genesisgym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTextoTest {
    @Test fun consulta_escapa_comillas() =
        assertEquals("name='a\\'b' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            DriveTexto.consultaCarpeta("a'b"))

    @Test fun multipart_tiene_las_dos_partes_y_el_cierre() {
        val t = String(DriveTexto.multipart("L", "{\"name\":\"x\"}", "hola", "text/plain"), Charsets.UTF_8)
        assertTrue(t.startsWith("--L\r\nContent-Type: application/json"))
        assertTrue(t.contains("{\"name\":\"x\"}\r\n--L\r\nContent-Type: text/plain"))
        assertTrue(t.endsWith("hola\r\n--L--\r\n"))
    }

    @Test fun formato_de_una_rutina() =
        assertEquals("genesis-rutina", DriveTexto.formatoDe("{ \"formato\": \"genesis-rutina\", \"version_formato\": \"1.0\" }"))

    @Test fun formato_ausente() = assertNull(DriveTexto.formatoDe("{\"otra\":1}"))

    @Test fun json_escapa_caracteres() = assertEquals("a\\\"b\\\\c\\n", DriveTexto.json("a\"b\\c\n"))
}
