package io.github.sansolo2000.genesisgym.qa

import io.github.sansolo2000.genesisgym.Respaldo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA-T01 a QA-T03 (claude/PLAN-PRUEBAS-3.0.md §6). Lo mantiene QA. Solo datos inventados. */
class QaRespaldoTest {
    private fun recurso(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun d(ruta: String, vararg p: Pair<String, String>) = Respaldo.Doc(ruta, buildJsonObject { for ((k, v) in p) put(k, v) })

    /** Un documento inventado por cada colección del respaldo, incluidas las marcas de compras y tareas. */
    private val todas = listOf(
        d("config/perfil", "nombre" to "Persona QA"),
        d("rutinas/qa-forma-v3_v3", "id" to "qa-forma-v3"),
        d("sesiones/2026-10-14_qa-a", "fecha" to "2026-10-14", "sesion_id" to "qa-a"),
        d("imagenes/qa-fuerza", "archivo" to "qa.svg"),
        d("alimentacion/compras_hechas", "programa_id" to "qa-prog"),
        d("alimentacion/tareas_hechas", "programa_id" to "qa-prog"),
        d("programas_alimentacion/qa-prog", "id" to "qa-prog"),
        d("comidas/2026-10-14_almuerzo", "estado" to "indicado"),
        d("fotos/2026-10-14_almuerzo", "jpg" to "AAAA"),
        // 0.11.0 (módulo Apoyo): editado por Desarrollador 3 con autorización de Héctor (12-oct); QA lo revisa.
        d("programas_apoyo/activo", "importado_en" to "2026-10-14T12:00:00.000Z"),
        d("reportes_apoyo/2026-10-14", "formato" to "reporte-diario"),
        d("resumenes_apoyo/2026-10-16", "formato" to "resumen-semanal"),
        // 0.13.0 (M-07, cintura): editado por Desarrollador 3 con autorización de Héctor (13-oct); QA lo revisa.
        d("cintura/2026-10-16_0705", "fecha" to "2026-10-16")
    )

    @Test fun qa_t01_ida_y_vuelta_con_todas_las_colecciones() {
        assertEquals(Respaldo.COLECCIONES.toSet(), todas.map { it.ruta.substringBefore('/') }.toSet())
        val texto = Respaldo.crear(todas.shuffled(kotlin.random.Random(7)), "Persona QA", "qa", "2026-10-14T12:00:00.000Z")
        val r = Respaldo.leer(texto)
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
        val l = (r as Respaldo.Resultado.Ok).leido
        assertEquals(Respaldo.huella(todas), Respaldo.huella(l.docs))
        assertEquals(todas.map { it.ruta }.sorted(), l.docs.map { it.ruta })
        assertEquals(2, l.conteos["alimentacion"])
        assertEquals(1, l.conteos["sesiones"])
        assertEquals(1, l.conteos["comidas"])
        assertEquals("Persona QA", l.perfil)
        assertEquals("2026-10-14" to "2026-10-14", l.rango)
    }

    /** Archivo generado una vez con herramientas/migrar.js sobre pruebas/datos-ejemplo/base-1.0 (inventada). */
    @Test fun qa_t02_respaldo_de_migrar_js_se_puede_restaurar() {
        val r = Respaldo.leer(recurso("qa/qa-ejemplo-desde-1.0.json"))
        assertTrue(r.toString(), r is Respaldo.Resultado.Ok)
        val l = (r as Respaldo.Resultado.Ok).leido
        assertEquals(mapOf("config" to 1, "rutinas" to 1, "sesiones" to 2, "imagenes" to 2), l.conteos)
        assertEquals("2026-10-05" to "2026-10-12", l.rango)
        assertNull(l.perfil)   // por eso Hoy vuelve a pedir el nombre después de restaurar (prueba U-02)
        assertEquals("aef6bf0b62c27f146271bac8be9d824071fd11de9e09f97d1952e7ebdf2c3344", Respaldo.huella(l.docs))
    }

    @Test fun qa_t03_archivos_malos_nunca_se_aceptan() {
        assertTrue(Respaldo.leer("") is Respaldo.Resultado.Error)
        val bueno = recurso("qa/qa-ejemplo-desde-1.0.json")
        val alterado = bueno.replace("aef6bf0b62c27f146271bac8be9d824071fd11de9e09f97d1952e7ebdf2c3344", "0".repeat(64))
        assertTrue("la huella debía estar en el archivo", alterado != bueno)
        assertEquals(Respaldo.Resultado.Error("La huella del respaldo no coincide: el archivo está incompleto o fue modificado. No se restauró nada."), Respaldo.leer(alterado))
        val mezcla = "{\"formato\":\"genesis-respaldo\",\"version\":1,\"documentos\":[{\"ruta\":\"config/x\",\"data\":{}},{\"ruta\":\"apoyo/x\",\"data\":{}}]}"
        assertEquals(Respaldo.Resultado.Error("El respaldo trae una colección desconocida: apoyo."), Respaldo.leer(mezcla))
    }
}
