package io.github.sansolo2000.genesisgym

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Datos diarios, entrega 1 (CONTEXTO-DATOS-DIARIOS v3 §11.8). Solo datos inventados. */
class DatosDiariosTest {
    private val z = DatosDiarios.ZONA
    private val d = LocalDate.parse("2030-10-16")
    private val BOM = "﻿"
    private fun t(f: String, h: Int, m: Int = 0): Instant = ZonedDateTime.of(LocalDate.parse(f), LocalTime.of(h, m), z).toInstant()
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun lineas(texto: String) = texto.removePrefix(BOM).removeSuffix("\r\n").split("\r\n")

    private fun sesion(fecha: String, id: String, series: Int) = json("""{"fecha":"$fecha","dia_semana":"${Gimnasio.diaDe(fecha)}","sesion_id":"$id",
        "sesion_nombre":"Sesión $id","rutina_id":"rutina-prueba","rutina_version":1,"estado":"cerrada","rpe_sesion":7,"duracion_min":50,"nota":"",
        "ejercicios":[{"ejercicio_id":"ejercicio-x","nombre":"Ejercicio X","series":[${(1..series).joinToString(",") { """{"n":$it,"tipo":"efectiva","carga_prescrita_kg":10,"prescrito_reps":"10","carga_real_kg":10,"reps_reales":10}""" }}]}]}""")

    @Test fun nombresDeLosArchivos() {
        assertEquals("fuerza-hector-2030-10-16.csv", DatosDiarios.nombre("fuerza", "hector", d, 1))
        assertEquals("hc-hector-diario-2030-10-16.csv", DatosDiarios.nombre("hc-diario", "hector", d, 1))
        assertEquals("hc-hector-actividades-2030-10-16-v2.csv", DatosDiarios.nombre("hc-actividades", "hector", d, 2))
        assertEquals("cintura-hector-2030-10-16.csv", DatosDiarios.nombre("cintura", "hector", d, 1))
        assertEquals("comidas-hector-2030-10-16-v3.csv", DatosDiarios.nombre("comidas", "hector", d, 3))
        assertEquals("manifest-hector-2030-10-16.json", DatosDiarios.nombreManifest("hector", d, 1))
        assertEquals("manifest-hector-2030-10-16-v2.json", DatosDiarios.nombreManifest("hector", d, 2))
    }

    @Test fun ceroFilasDejaSoloLaCabecera() {
        val f = DatosDiarios.fuerza(emptyList(), d)
        assertEquals(BOM + Gimnasio.COLUMNAS.joinToString(";") + "\r\n", f.texto); assertEquals(0, f.filas)
        assertEquals(BOM + Cintura.COLUMNAS.joinToString(";") + "\r\n", DatosDiarios.cintura(emptyList(), d).texto)
        val a = DatosDiarios.hcActividades(d, emptyList())
        assertEquals(BOM + SaludCsv.COLUMNAS_ACTIVIDADES.joinToString(";") + "\r\n", a.texto); assertEquals(0, a.filas)
    }

    @Test fun fuerzaSoloConLasSesionesDeEseDia() {
        val f = DatosDiarios.fuerza(listOf(sesion("2030-10-15", "A", 3), sesion("2030-10-16", "B", 2)), d)
        val l = lineas(f.texto)
        assertEquals(Gimnasio.COLUMNAS.joinToString(";"), l[0])
        assertEquals(3, l.size); assertEquals(2, f.filas)
        assertTrue(l.drop(1).all { it.startsWith("2030-10-16;miercoles;B;") })
        assertEquals(27, l[1].split(";").size)   // 20 + 7 del cronómetro (M-09)
    }

    @Test fun hcDiarioUnaFilaSinSuenoNiFcEnReposo() {
        val pe = listOf(SaludCsv.Peso(t("2030-10-16", 6, 30), 55.5, "com.moving.movinglife"), SaludCsv.Peso(t("2030-10-15", 6, 30), 56.0, "com.moving.movinglife"))
        val a = DatosDiarios.hcDiario(d, emptyList(), emptyList(), pe)
        assertEquals(1, a.filas)
        assertEquals(listOf(SaludCsv.COLUMNAS_DIARIO.joinToString(";"), "2030-10-16;;;;;;;55,5;06:30;movinglife"), lineas(a.texto))
        assertEquals(listOf(SaludCsv.COLUMNAS_DIARIO.joinToString(";"), "2030-10-16;;;;;;;;;"), lineas(DatosDiarios.hcDiario(d, emptyList(), emptyList(), emptyList()).texto))
    }

    @Test fun cinturaDelDia() {
        val r = listOf(Cintura.Registro("2030-10-16", "07:10", 70.25, 2, listOf(70.2, 70.3), true, null),
            Cintura.Registro("2030-10-18", "07:00", 71.5, 2, listOf(71.5, 71.5), true, null))
        val c = DatosDiarios.cintura(r, d)
        assertEquals(1, c.filas)
        assertEquals("2030-10-16;07:10;70,25;2;70,2;70,3;;si;", lineas(c.texto)[1])
    }

    private val programa = json("""{"programa":{"id":"prog-prueba","nombre":"Prueba","desde":"2030-10-14","hasta":"2030-10-20"},
        "perfiles":[{"id":"persona-prueba"}],"preparaciones":{"avena":{"nombre":"Avena"},"pollo":{"nombre":"Pollo"}},
        "dias":[{"fecha":"2030-10-16","comidas":[{"tipo":"desayuno","hora":"08:00","preparacion":"avena"},{"tipo":"almuerzo","hora":"13:30","preparacion":"pollo"}]}]}""")
    private fun reg(tipo: String, campos: String) = Respaldo.Doc("comidas/2030-10-16_$tipo", json("""{"fecha":"2030-10-16","tipo":"$tipo",$campos}"""))

    @Test fun comidasCincoFilasConYSinMarca() {
        val e = EstadoComidas(programa, null, listOf(
            reg("desayuno", """"estado":"indicado","version":2,"actualizado":"2030-10-16T11:05:00.000Z""""),
            reg("almuerzo", """"estado":"no-comi","version":1,"actualizado":"2030-10-16T17:00:00.000Z""""),
            reg("colacion_am", """"nota":"solo una nota","version":1,"actualizado":"2030-10-16T13:00:00.000Z""""),
            reg("cena", """"estado":"otro","version":1,"actualizado":"2030-10-17T00:30:00.000Z"""")))
        val a = DatosDiarios.comidas(e, d)
        assertEquals(5, a.filas)
        assertEquals(listOf(
            DatosDiarios.COLUMNAS_COMIDAS.joinToString(";"),
            "2030-10-16;desayuno;08:00;avena;indicado;si;no;2;2030-10-16T11:05:00.000Z",
            "2030-10-16;colacion_am;;;;no;;;",
            "2030-10-16;almuerzo;13:30;pollo;no-comi;no;no;1;2030-10-16T17:00:00.000Z",
            "2030-10-16;colacion_pm;;;;no;;;",
            "2030-10-16;cena;;;otro;si;no;1;2030-10-17T00:30:00.000Z"), lineas(a.texto))
        // Sin programa: las 5 filas igual, sin hora ni preparación.
        assertEquals("2030-10-16;desayuno;;;;no;;;", lineas(DatosDiarios.comidas(EstadoComidas(null), d).texto)[1])
    }

    @Test fun comidasConIntercambio() {
        val e = EstadoComidas(programa, null, listOf(reg("desayuno", """"estado":"indicado","version":1,"actualizado":"2030-10-16T11:05:00.000Z"""")))
        e.intercambiar("2030-10-16_desayuno", "2030-10-16_almuerzo", "2030-10-16T12:00:00.000Z")
        val l = lineas(DatosDiarios.comidas(e, d).texto)
        assertEquals("2030-10-16;desayuno;08:00;pollo;indicado;si;si;2;2030-10-16T12:00:00.000Z", l[1])
        assertEquals("2030-10-16;almuerzo;13:30;avena;;no;;;", l[3])   // sin marca: sin intercambio, versión ni momento
    }

    private fun prog(id: String, desde: String, hasta: String, fecha: String) = json("""{"programa":{"id":"$id","nombre":"$id","desde":"$desde","hasta":"$hasta"},
        "perfiles":[{"id":"persona-prueba"}],"preparaciones":{"avena":{"nombre":"Avena"},"pollo":{"nombre":"Pollo"}},
        "dias":[{"fecha":"$fecha","comidas":[{"tipo":"desayuno","hora":"08:00","preparacion":"avena"},{"tipo":"almuerzo","hora":"13:30","preparacion":"pollo"}]}]}""")

    @Test fun programaQueCubreElDia() {   // D-09
        val viejo = prog("p1", "2030-10-14", "2030-10-20", "2030-10-16"); val nuevo = prog("p1b", "2030-10-14", "2030-10-20", "2030-10-16")
        assertEquals(nuevo, DatosDiarios.programaDe(d, null, listOf(viejo to "2030-10-10T10:00:00.000Z", nuevo to "2030-10-12T10:00:00.000Z")))
        assertEquals(viejo, DatosDiarios.programaDe(d, viejo, listOf(nuevo to "2030-10-12T10:00:00.000Z")))   // si el activo cubre el día, manda
        assertNull(DatosDiarios.programaDe(LocalDate.parse("2030-10-30"), viejo, listOf(nuevo to "2030-10-12T10:00:00.000Z")))
    }

    @Test fun importarOtroProgramaNoSubeComidasConMenosDatos() {   // D-09 (QA): importar P1, subir, importar P2 y subir de nuevo
        val p1 = prog("p1", "2030-10-14", "2030-10-20", "2030-10-16"); val p2 = prog("p2", "2030-10-21", "2030-10-27", "2030-10-23")
        val e1 = EstadoComidas(p1, null, listOf(reg("desayuno", """"estado":"indicado","version":1,"actualizado":"2030-10-16T11:05:00.000Z"""")))
        val inter1 = e1.intercambiar("2030-10-16_desayuno", "2030-10-16_almuerzo", "2030-10-16T12:00:00.000Z").first { it.ruta == "alimentacion/intercambios" }.data
        val regs = e1.registros.map { (k, v) -> Respaldo.Doc("comidas/$k", v) }
        // 1.ª subida, con P1 activo.
        val a1 = DatosDiarios.comidasDelDia(d, p1, listOf(p1 to "2030-10-13T10:00:00.000Z"), inter1, regs, false)!!
        assertEquals("2030-10-16;desayuno;08:00;pollo;indicado;si;si;2;2030-10-16T12:00:00.000Z", lineas(a1.texto)[1])
        val est = DatosDiarios.EstadoDia(1, mapOf("comidas" to DatosDiarios.Subido(1, DatosDiarios.nombre("comidas", "hector", d, 1), a1.sha256, a1.filas)), false)
        // Se importa P2: el día sigue usando P1 (guardado) y sus intercambios, así que no cambia y no hay -v2.
        val guardados = listOf(p1 to "2030-10-13T10:00:00.000Z", p2 to "2030-10-20T10:00:00.000Z")
        val a2 = DatosDiarios.comidasDelDia(d, p2, guardados, inter1, regs, true)!!
        assertEquals(a1.texto, a2.texto)
        assertTrue(DatosDiarios.cambios(listOf(a2), est).isEmpty())
        // Un intercambio en P2 reemplaza los de P1: el día ya subido no se recalcula (queda vigente lo subido).
        assertNull(DatosDiarios.comidasDelDia(d, p2, guardados, json("""{"programa_id":"p2","cambios":{}}"""), regs, true))
        assertEquals(0, DatosDiarios.porSubir(listOf(d), { dd -> DatosDiarios.locales(emptyList(), emptyList(), { null }, dd) },
            { DatosDiarios.EstadoDia(1, mapOf("fuerza" to DatosDiarios.Subido(1, "f", DatosDiarios.fuerza(emptyList(), d).sha256, 0),
                "cintura" to DatosDiarios.Subido(1, "c", DatosDiarios.cintura(emptyList(), d).sha256, 0)), false) }))
        // Ningún programa guardado cubre el día: ya subido, no se recalcula; sin subir, van las 5 filas sin hora ni preparación.
        assertNull(DatosDiarios.comidasDelDia(d, p2, listOf(p2 to "2030-10-20T10:00:00.000Z"), null, regs, true))
        assertEquals("2030-10-16;desayuno;;;indicado;si;no;2;2030-10-16T12:00:00.000Z",
            lineas(DatosDiarios.comidasDelDia(d, p2, listOf(p2 to "2030-10-20T10:00:00.000Z"), null, regs, false)!!.texto)[1])
    }

    @Test fun programaNuevoSinIntercambiosResubeUnaMarcaTardia() {   // D-10 (QA)
        val p1 = prog("p1", "2030-10-07", "2030-10-13", "2030-10-09"); val p2 = prog("p2", "2030-10-14", "2030-10-20", "2030-10-16")
        val inter1 = json("""{"programa_id":"p1","cambios":{}}""")   // de un intercambio hecho en P1: sigue guardado tras importar P2
        val guardados = listOf(p1 to "2030-10-06T10:00:00.000Z", p2 to "2030-10-13T10:00:00.000Z")
        val antes = listOf(reg("desayuno", """"estado":"indicado","version":1,"actualizado":"2030-10-16T11:05:00.000Z""""))
        val a1 = DatosDiarios.comidasDelDia(d, p2, guardados, inter1, antes, false)!!
        val est = DatosDiarios.EstadoDia(1, mapOf("comidas" to DatosDiarios.Subido(1, DatosDiarios.nombre("comidas", "hector", d, 1), a1.sha256, a1.filas)), false)
        // Marca tardía del almuerzo de ese día, después de la 1.ª subida.
        val despues = antes + reg("almuerzo", """"estado":"otro","version":1,"actualizado":"2030-10-17T13:00:00.000Z"""")
        val a2 = DatosDiarios.comidasDelDia(d, p2, guardados, inter1, despues, true)!!
        assertEquals("2030-10-16;almuerzo;13:30;pollo;otro;si;no;1;2030-10-17T13:00:00.000Z", lineas(a2.texto)[3])
        assertEquals(listOf("comidas" to 2), DatosDiarios.cambios(listOf(a2), est).map { it.first.tipo to it.second })
    }

    private fun archivos(fuerza: List<JsonObject> = emptyList()) = listOf(DatosDiarios.fuerza(fuerza, d), DatosDiarios.hcDiario(d, emptyList(), emptyList(), emptyList()),
        DatosDiarios.hcActividades(d, emptyList()), DatosDiarios.cintura(emptyList(), d), DatosDiarios.comidas(EstadoComidas(null), d))
    private fun subido(a: List<DatosDiarios.Archivo>, v: Int = 1) = DatosDiarios.EstadoDia(1, a.associate { it.tipo to DatosDiarios.Subido(v, DatosDiarios.nombre(it.tipo, "hector", d, v), it.sha256, it.filas) }, false)

    @Test fun versionesSoloDeLoQueCambio() {
        val a = archivos()
        assertEquals(DatosDiarios.TIPOS, DatosDiarios.cambios(a, null).map { it.first.tipo })
        assertTrue(DatosDiarios.cambios(a, null).all { it.second == 1 })
        val e = subido(a)
        assertFalse(DatosDiarios.hayQueSubir(a, e))                          // nada cambió: no se sube nada
        val b = archivos(listOf(sesion("2030-10-16", "B", 1)))              // una sesión cargada al día siguiente
        val c = DatosDiarios.cambios(b, e)
        assertEquals(listOf("fuerza" to 2), c.map { it.first.tipo to it.second })
        assertTrue(DatosDiarios.hayQueSubir(a, e.copy(manifestPendiente = true)))   // se cortó antes del manifest
        assertEquals("manual", DatosDiarios.motivo(null)); assertEquals("tardio", DatosDiarios.motivo(e))
        assertEquals("manual", DatosDiarios.motivo(e.copy(version = 0, manifestPendiente = true)))
    }

    @Test fun estadoSeGuardaYSeLee() {
        val e = subido(archivos(), 2).copy(manifestPendiente = true)
        assertEquals(e, DatosDiarios.estadoDesdeJson(DatosDiarios.estadoAJson(e)))
        assertNull(DatosDiarios.estadoDesdeJson(null)); assertNull(DatosDiarios.estadoDesdeJson("{no es json"))
    }

    @Test fun manifestConFilasYSha256() {
        val a = archivos(listOf(sesion("2030-10-16", "B", 2)))
        val e = subido(a)
        val gen = ZonedDateTime.of(2030, 10, 17, 13, 40, 0, 0, z)
        val m = json(DatosDiarios.manifest("hector", d, 1, gen, "0.14.0", 25, "manual", e.tipos, emptyList(),
            DatosDiarios.LecturaHc(gen, false, listOf("fc_reposo")), null))
        assertEquals("datos-diarios-manifest", (m["formato"] as JsonPrimitive).content)
        assertEquals("1.0", (m["version_formato"] as JsonPrimitive).content)
        assertEquals("2030-10-16", (m["fecha"] as JsonPrimitive).content)
        assertEquals("America/Santiago", (m["zona_horaria"] as JsonPrimitive).content)
        assertEquals("2030-10-17T13:40:00-03:00", (m["generado_en"] as JsonPrimitive).content)
        val vig = (m["vigentes"] as JsonArray).map { it as JsonObject }
        assertEquals(DatosDiarios.TIPOS, vig.map { (it["tipo"] as JsonPrimitive).content })
        val fz = vig[0]
        assertEquals("fuerza-hector-2030-10-16.csv", (fz["nombre"] as JsonPrimitive).content)
        assertEquals("2", (fz["filas"] as JsonPrimitive).content)
        assertEquals(JsJson.sha256(a[0].texto), (fz["sha256"] as JsonPrimitive).content)
        assertEquals("5", (vig[4]["filas"] as JsonPrimitive).content)
        assertEquals("true", (m["completo"] as JsonPrimitive).content)
        assertEquals("false", ((m["health_connect"] as JsonObject)["segundo_plano"] as JsonPrimitive).content)
        assertEquals("no-guardado", ((m["reporte_apoyo"] as JsonObject)["estado"] as JsonPrimitive).content)
        // Con un faltante, el manifest no está completo.
        val f = DatosDiarios.faltantesHc(false, null, emptySet())
        val m2 = json(DatosDiarios.manifest("hector", d, 2, gen, "0.14.0", 25, "tardio", e.tipos - "hc-diario" - "hc-actividades", f,
            DatosDiarios.LecturaHc(null, false, DatosDiarios.PERMISOS), null))
        assertEquals("false", (m2["completo"] as JsonPrimitive).content)
        assertEquals(3, (m2["vigentes"] as JsonArray).size)
        assertEquals("health-connect-no-disponible", (((m2["faltantes"] as JsonArray)[0] as JsonObject)["motivo"] as JsonPrimitive).content)
    }

    @Test fun cadaTipoEstaEnVigentesOEnFaltantes() {
        val e = subido(archivos())
        val gen = ZonedDateTime.of(2030, 10, 17, 13, 40, 0, 0, z)
        fun tipos(m: JsonObject, k: String) = (m[k] as JsonArray).map { ((it as JsonObject)["tipo"] as JsonPrimitive).content }
        // hc-actividades nunca se exportó y no vino como faltante: igual queda en faltantes y el manifest no está completo.
        val m = json(DatosDiarios.manifest("hector", d, 1, gen, "0.14.0", 25, "manual", e.tipos - "hc-actividades", emptyList(),
            DatosDiarios.LecturaHc(gen, false, emptyList()), null))
        assertEquals(listOf("hc-actividades"), tipos(m, "faltantes"))
        assertEquals("false", (m["completo"] as JsonPrimitive).content)
        // Con Health Connect no disponible: los 2 hc- en faltantes, una sola vez cada uno.
        val m2 = json(DatosDiarios.manifest("hector", d, 1, gen, "0.14.0", 25, "manual", e.tipos - "hc-diario" - "hc-actividades",
            DatosDiarios.faltantesHc(false, null, emptySet()), DatosDiarios.LecturaHc(null, false, DatosDiarios.PERMISOS), null))
        assertEquals(listOf("hc-diario", "hc-actividades"), tipos(m2, "faltantes"))
        for (mm in listOf(m, m2)) assertTrue(DatosDiarios.TIPOS.all { it in tipos(mm, "vigentes") || it in tipos(mm, "faltantes") })
    }

    @Test fun numeracionSigueDesdeDrive() {
        val n = listOf("fuerza-hector-2030-10-16.csv", "fuerza-hector-2030-10-16-v3.csv", "comidas-hector-2030-10-16.csv",
            "manifest-hector-2030-10-16-v2.json", "manifest-hector-2030-10-16.json", "fuerza-otra-2030-10-16-v9.csv", "fuerza-hector-2030-10-15-v7.csv", "nota.txt")
        assertEquals(mapOf("fuerza" to 3, "comidas" to 1, "manifest" to 2), DatosDiarios.versionesEnDrive(n, "hector", d))
        assertEquals(emptyMap<String, Int>(), DatosDiarios.versionesEnDrive(emptyList(), "hector", d))
        // hc-diario no se confunde con hc-actividades ni con fuerza.
        assertEquals(mapOf("hc-diario" to 2), DatosDiarios.versionesEnDrive(listOf("hc-hector-diario-2030-10-16-v2.csv"), "hector", d))
    }

    @Test fun menosPermisosNoSubeUnVnDelHc() {   // §1.5.8 (v3.3)
        val a = archivos()
        val todos = DatosDiarios.PERMISOS.toSet()
        val e = subido(a).let { s -> s.copy(tipos = s.tipos.mapValues { (t, v) -> if (t in DatosDiarios.PERMISOS_DE) v.copy(permisos = DatosDiarios.permisosDe(t, todos)) else v }) }
        val hc = listOf(a[1], a[2])
        // Sin FC en reposo: hc-diario no se sube y va a faltantes; hc-actividades (sus permisos siguen) sí.
        val (ok, falt) = DatosDiarios.filtrarPermisos(hc, todos - "fc_reposo", e)
        assertEquals(listOf("hc-actividades"), ok.map { it.tipo })
        assertEquals(listOf("hc-diario" to "permisos-reducidos"), falt.map { it.tipo to it.motivo })
        assertTrue(falt[0].detalle.contains("fc_reposo"))
        // Con los mismos permisos o con más, se aplica la regla general.
        assertEquals(2, DatosDiarios.filtrarPermisos(hc, todos, e).first.size)
        val pocos = e.copy(tipos = e.tipos + ("hc-diario" to e.tipos["hc-diario"]!!.copy(permisos = setOf("peso"))))
        assertEquals(2, DatosDiarios.filtrarPermisos(hc, setOf("peso", "sueno", "ejercicio", "distancia", "desnivel", "fc"), pocos).first.size)
        // Sin subida anterior, o sin permisos guardados (estado de antes de la v3.3), no se compara.
        assertEquals(2, DatosDiarios.filtrarPermisos(hc, emptySet(), null).first.size)
        assertEquals(2, DatosDiarios.filtrarPermisos(hc, emptySet(), subido(a)).first.size)
        // En el manifest: el hc-diario anterior sigue vigente y además está en faltantes; completo = false.
        val gen = ZonedDateTime.of(2030, 10, 17, 13, 40, 0, 0, z)
        val m = json(DatosDiarios.manifest("hector", d, 2, gen, "0.14.0", 25, "tardio", e.tipos, falt,
            DatosDiarios.LecturaHc(gen, false, listOf("fc_reposo")), null))
        assertEquals(5, (m["vigentes"] as JsonArray).size)
        assertEquals("permisos-reducidos", (((m["faltantes"] as JsonArray)[0] as JsonObject)["motivo"] as JsonPrimitive).content)
        assertEquals("false", (m["completo"] as JsonPrimitive).content)
        // Los permisos guardados sobreviven al estado en preferencias.
        assertEquals(e, DatosDiarios.estadoDesdeJson(DatosDiarios.estadoAJson(e)))
    }

    @Test fun reporteDeApoyoEnElManifest() {
        val r = json("""{"fecha":"2030-10-16","version":2,"tardio":false,"enviado":{"version":2,"id":"x","en":"2030-10-16T23:00:00.000Z"}}""")
        val o = DatosDiarios.reporteApoyo(r)
        assertEquals("guardado", (o["estado"] as JsonPrimitive).content)
        assertEquals("2", (o["version"] as JsonPrimitive).content)
        assertEquals("true", (o["enviado_drive"] as JsonPrimitive).content)
        val sinEnviar = DatosDiarios.reporteApoyo(json("""{"fecha":"2030-10-16","version":3,"tardio":true,"enviado":{"version":2}}"""))
        assertEquals("false", (sinEnviar["enviado_drive"] as JsonPrimitive).content)
        assertEquals("true", (sinEnviar["tardio"] as JsonPrimitive).content)
    }

    @Test fun diaSinCerrarNoSeSube() {
        val antes = ZonedDateTime.of(2030, 10, 17, 11, 59, 0, 0, z)
        assertEquals((10..15).map { LocalDate.of(2030, 10, it) }, DatosDiarios.ventana(antes))
        assertTrue(DatosDiarios.ayerAbierto(antes))
        val despues = ZonedDateTime.of(2030, 10, 17, 12, 0, 0, 0, z)
        assertEquals((10..16).map { LocalDate.of(2030, 10, it) }, DatosDiarios.ventana(despues))
        assertFalse(DatosDiarios.ayerAbierto(despues))
    }

    @Test fun corteDelDiaEnLosCambiosDeHorarioDeChile() {
        // Septiembre: el 6-sep-2026 a las 00:00 (-04) el reloj salta a 01:00 (-03).
        val sep1 = Instant.parse("2026-09-06T03:30:00Z"); val sep2 = Instant.parse("2026-09-06T04:30:00Z")
        assertEquals(ZoneOffset.ofHours(-4), z.rules.getOffset(sep1)); assertEquals(ZoneOffset.ofHours(-3), z.rules.getOffset(sep2))
        fun act(i: Instant) = SaludCsv.Actividad(i, i.plusSeconds(3600), SaludCsv.BICICLETA, null, 20000.0, 300.0, 140.0, "com.garmin.android.apps.connectmobile")
        val lista = listOf(act(sep1), act(sep2))
        assertEquals(listOf("2026-09-05;23:30;MTB"), lineas(DatosDiarios.hcActividades(LocalDate.parse("2026-09-05"), lista).texto).drop(1).map { it.split(";").take(3).joinToString(";") })
        assertEquals(listOf("2026-09-06;01:30;MTB"), lineas(DatosDiarios.hcActividades(LocalDate.parse("2026-09-06"), lista).texto).drop(1).map { it.split(";").take(3).joinToString(";") })
        assertEquals(ZonedDateTime.of(2026, 9, 6, 12, 0, 0, 0, z), DatosDiarios.cierre(LocalDate.parse("2026-09-05")))
        // Abril: el 5-abr-2026 a las 00:00 (-03) el reloj vuelve a las 23:00 (-04) del sábado 4.
        val abr1 = Instant.parse("2026-04-05T03:30:00Z"); val abr2 = Instant.parse("2026-04-05T04:30:00Z")
        assertEquals(ZoneOffset.ofHours(-4), z.rules.getOffset(abr1))
        val la = listOf(act(abr1), act(abr2))
        assertEquals(listOf("2026-04-04;23:30;MTB"), lineas(DatosDiarios.hcActividades(LocalDate.parse("2026-04-04"), la).texto).drop(1).map { it.split(";").take(3).joinToString(";") })
        assertEquals(listOf("2026-04-05;00:30;MTB"), lineas(DatosDiarios.hcActividades(LocalDate.parse("2026-04-05"), la).texto).drop(1).map { it.split(";").take(3).joinToString(";") })
        // El sueño va al día en que termina, también el domingo del cambio.
        val su = listOf(SaludCsv.Sueno(Instant.parse("2026-09-06T02:00:00Z"), t("2026-09-06", 7), emptyList(), "com.garmin.android.apps.connectmobile"))
        assertEquals("2026-09-06;;480;;;;;;;Garmin Connect", lineas(DatosDiarios.hcDiario(LocalDate.parse("2026-09-06"), emptyList(), su, emptyList()).texto)[1])
    }

    @Test fun faltantesDeHealthConnect() {
        assertEquals(listOf("hc-diario", "hc-actividades"), DatosDiarios.faltantesHc(false, null, emptySet()).map { it.tipo })
        assertEquals("error", DatosDiarios.faltantesHc(true, "falló", emptySet())[0].motivo)
        assertEquals(listOf("hc-diario", "hc-actividades"), DatosDiarios.faltantesHc(true, null, setOf("fc", "distancia")).map { it.tipo })
        assertEquals(listOf("hc-actividades"), DatosDiarios.faltantesHc(true, null, setOf("peso")).map { it.tipo })
        assertTrue(DatosDiarios.faltantesHc(true, null, setOf("sueno", "ejercicio")).isEmpty())   // permisos parciales: se escribe con columnas vacías
    }

    @Test fun diasPorSubir() {
        val dias = listOf(LocalDate.parse("2030-10-15"), d)
        val a = archivos()
        val locales: (LocalDate) -> List<DatosDiarios.Archivo> = { listOf(a[0], a[3], a[4]) }
        assertEquals(2, DatosDiarios.porSubir(dias, locales) { null })
        assertEquals(1, DatosDiarios.porSubir(dias, locales) { if (it == d) subido(a) else null })
        assertEquals(0, DatosDiarios.porSubir(dias, locales) { subido(a) })
    }

    @Test fun carpetaDelDiaDentroDeDatosDiarios() {
        assertEquals("name='2030-10-16' and mimeType='application/vnd.google-apps.folder' and trashed=false and 'abc123' in parents",
            DriveTexto.consultaCarpetaEn("2030-10-16", "abc123"))
        assertEquals(DriveTexto.consultaCarpeta("datos-diarios"), DriveTexto.consultaCarpetaEn("datos-diarios", null))
    }
}
