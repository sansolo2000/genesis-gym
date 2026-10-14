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
    private val d = LocalDate.parse("2026-10-14")
    private val BOM = "﻿"
    private fun t(f: String, h: Int, m: Int = 0): Instant = ZonedDateTime.of(LocalDate.parse(f), LocalTime.of(h, m), z).toInstant()
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun lineas(texto: String) = texto.removePrefix(BOM).removeSuffix("\r\n").split("\r\n")

    private fun sesion(fecha: String, id: String, series: Int) = json("""{"fecha":"$fecha","dia_semana":"${Gimnasio.diaDe(fecha)}","sesion_id":"$id",
        "sesion_nombre":"Sesión $id","rutina_id":"rutina-prueba","rutina_version":1,"estado":"cerrada","rpe_sesion":7,"duracion_min":50,"nota":"",
        "ejercicios":[{"ejercicio_id":"ejercicio-x","nombre":"Ejercicio X","series":[${(1..series).joinToString(",") { """{"n":$it,"tipo":"efectiva","carga_prescrita_kg":10,"prescrito_reps":"10","carga_real_kg":10,"reps_reales":10}""" }}]}]}""")

    @Test fun nombresDeLosArchivos() {
        assertEquals("fuerza-hector-2026-10-14.csv", DatosDiarios.nombre("fuerza", "hector", d, 1))
        assertEquals("hc-hector-diario-2026-10-14.csv", DatosDiarios.nombre("hc-diario", "hector", d, 1))
        assertEquals("hc-hector-actividades-2026-10-14-v2.csv", DatosDiarios.nombre("hc-actividades", "hector", d, 2))
        assertEquals("cintura-hector-2026-10-14.csv", DatosDiarios.nombre("cintura", "hector", d, 1))
        assertEquals("comidas-hector-2026-10-14-v3.csv", DatosDiarios.nombre("comidas", "hector", d, 3))
        assertEquals("manifest-hector-2026-10-14.json", DatosDiarios.nombreManifest("hector", d, 1))
        assertEquals("manifest-hector-2026-10-14-v2.json", DatosDiarios.nombreManifest("hector", d, 2))
    }

    @Test fun ceroFilasDejaSoloLaCabecera() {
        val f = DatosDiarios.fuerza(emptyList(), d)
        assertEquals(BOM + Gimnasio.COLUMNAS.joinToString(";") + "\r\n", f.texto); assertEquals(0, f.filas)
        assertEquals(BOM + Cintura.COLUMNAS.joinToString(";") + "\r\n", DatosDiarios.cintura(emptyList(), d).texto)
        val a = DatosDiarios.hcActividades(d, emptyList())
        assertEquals(BOM + SaludCsv.COLUMNAS_ACTIVIDADES.joinToString(";") + "\r\n", a.texto); assertEquals(0, a.filas)
    }

    @Test fun fuerzaSoloConLasSesionesDeEseDia() {
        val f = DatosDiarios.fuerza(listOf(sesion("2026-10-13", "A", 3), sesion("2026-10-14", "B", 2)), d)
        val l = lineas(f.texto)
        assertEquals(Gimnasio.COLUMNAS.joinToString(";"), l[0])
        assertEquals(3, l.size); assertEquals(2, f.filas)
        assertTrue(l.drop(1).all { it.startsWith("2026-10-14;miercoles;B;") })
        assertEquals(20, l[1].split(";").size)
    }

    @Test fun hcDiarioUnaFilaSinSuenoNiFcEnReposo() {
        val pe = listOf(SaludCsv.Peso(t("2026-10-14", 6, 30), 80.25, "com.moving.movinglife"), SaludCsv.Peso(t("2026-10-13", 6, 30), 81.0, "com.moving.movinglife"))
        val a = DatosDiarios.hcDiario(d, emptyList(), emptyList(), pe)
        assertEquals(1, a.filas)
        assertEquals(listOf(SaludCsv.COLUMNAS_DIARIO.joinToString(";"), "2026-10-14;;;;;;;80,25;06:30;movinglife"), lineas(a.texto))
        assertEquals(listOf(SaludCsv.COLUMNAS_DIARIO.joinToString(";"), "2026-10-14;;;;;;;;;"), lineas(DatosDiarios.hcDiario(d, emptyList(), emptyList(), emptyList()).texto))
    }

    @Test fun cinturaDelDia() {
        val r = listOf(Cintura.Registro("2026-10-14", "07:10", 93.25, 2, listOf(93.2, 93.3), true, null),
            Cintura.Registro("2026-10-16", "07:00", 93.0, 2, listOf(93.0, 93.0), true, null))
        val c = DatosDiarios.cintura(r, d)
        assertEquals(1, c.filas)
        assertEquals("2026-10-14;07:10;93,25;2;93,2;93,3;;si;", lineas(c.texto)[1])
    }

    private val programa = json("""{"programa":{"id":"prog-prueba","nombre":"Prueba","desde":"2026-10-12","hasta":"2026-10-18"},
        "perfiles":[{"id":"persona-prueba"}],"preparaciones":{"avena":{"nombre":"Avena"},"pollo":{"nombre":"Pollo"}},
        "dias":[{"fecha":"2026-10-14","comidas":[{"tipo":"desayuno","hora":"08:00","preparacion":"avena"},{"tipo":"almuerzo","hora":"13:30","preparacion":"pollo"}]}]}""")
    private fun reg(tipo: String, campos: String) = Respaldo.Doc("comidas/2026-10-14_$tipo", json("""{"fecha":"2026-10-14","tipo":"$tipo",$campos}"""))

    @Test fun comidasCincoFilasConYSinMarca() {
        val e = EstadoComidas(programa, null, listOf(
            reg("desayuno", """"estado":"indicado","version":2,"actualizado":"2026-10-14T11:05:00.000Z""""),
            reg("almuerzo", """"estado":"no-comi","version":1,"actualizado":"2026-10-14T17:00:00.000Z""""),
            reg("colacion_am", """"nota":"solo una nota","version":1,"actualizado":"2026-10-14T13:00:00.000Z""""),
            reg("cena", """"estado":"otro","version":1,"actualizado":"2026-10-15T00:30:00.000Z"""")))
        val a = DatosDiarios.comidas(e, d)
        assertEquals(5, a.filas)
        assertEquals(listOf(
            DatosDiarios.COLUMNAS_COMIDAS.joinToString(";"),
            "2026-10-14;desayuno;08:00;avena;indicado;si;no;2;2026-10-14T11:05:00.000Z",
            "2026-10-14;colacion_am;;;;no;;;",
            "2026-10-14;almuerzo;13:30;pollo;no-comi;no;no;1;2026-10-14T17:00:00.000Z",
            "2026-10-14;colacion_pm;;;;no;;;",
            "2026-10-14;cena;;;otro;si;no;1;2026-10-15T00:30:00.000Z"), lineas(a.texto))
        // Sin programa: las 5 filas igual, sin hora ni preparación.
        assertEquals("2026-10-14;desayuno;;;;no;;;", lineas(DatosDiarios.comidas(EstadoComidas(null), d).texto)[1])
    }

    @Test fun comidasConIntercambio() {
        val e = EstadoComidas(programa, null, listOf(reg("desayuno", """"estado":"indicado","version":1,"actualizado":"2026-10-14T11:05:00.000Z"""")))
        e.intercambiar("2026-10-14_desayuno", "2026-10-14_almuerzo", "2026-10-14T12:00:00.000Z")
        val l = lineas(DatosDiarios.comidas(e, d).texto)
        assertEquals("2026-10-14;desayuno;08:00;pollo;indicado;si;si;2;2026-10-14T12:00:00.000Z", l[1])
        assertEquals("2026-10-14;almuerzo;13:30;avena;;no;;;", l[3])   // sin marca: sin intercambio, versión ni momento
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
        val b = archivos(listOf(sesion("2026-10-14", "B", 1)))              // una sesión cargada al día siguiente
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
        val a = archivos(listOf(sesion("2026-10-14", "B", 2)))
        val e = subido(a)
        val gen = ZonedDateTime.of(2026, 10, 15, 13, 40, 0, 0, z)
        val m = json(DatosDiarios.manifest("hector", d, 1, gen, "0.14.0", 25, "manual", e.tipos, emptyList(),
            DatosDiarios.LecturaHc(gen, false, listOf("fc_reposo")), null))
        assertEquals("datos-diarios-manifest", (m["formato"] as JsonPrimitive).content)
        assertEquals("1.0", (m["version_formato"] as JsonPrimitive).content)
        assertEquals("2026-10-14", (m["fecha"] as JsonPrimitive).content)
        assertEquals("America/Santiago", (m["zona_horaria"] as JsonPrimitive).content)
        assertEquals("2026-10-15T13:40:00-03:00", (m["generado_en"] as JsonPrimitive).content)
        val vig = (m["vigentes"] as JsonArray).map { it as JsonObject }
        assertEquals(DatosDiarios.TIPOS, vig.map { (it["tipo"] as JsonPrimitive).content })
        val fz = vig[0]
        assertEquals("fuerza-hector-2026-10-14.csv", (fz["nombre"] as JsonPrimitive).content)
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

    @Test fun reporteDeApoyoEnElManifest() {
        val r = json("""{"fecha":"2026-10-14","version":2,"tardio":false,"enviado":{"version":2,"id":"x","en":"2026-10-14T23:00:00.000Z"}}""")
        val o = DatosDiarios.reporteApoyo(r)
        assertEquals("guardado", (o["estado"] as JsonPrimitive).content)
        assertEquals("2", (o["version"] as JsonPrimitive).content)
        assertEquals("true", (o["enviado_drive"] as JsonPrimitive).content)
        val sinEnviar = DatosDiarios.reporteApoyo(json("""{"fecha":"2026-10-14","version":3,"tardio":true,"enviado":{"version":2}}"""))
        assertEquals("false", (sinEnviar["enviado_drive"] as JsonPrimitive).content)
        assertEquals("true", (sinEnviar["tardio"] as JsonPrimitive).content)
    }

    @Test fun diaSinCerrarNoSeSube() {
        val antes = ZonedDateTime.of(2026, 10, 15, 11, 59, 0, 0, z)
        assertEquals((8..13).map { LocalDate.of(2026, 10, it) }, DatosDiarios.ventana(antes))
        assertTrue(DatosDiarios.ayerAbierto(antes))
        val despues = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, z)
        assertEquals((8..14).map { LocalDate.of(2026, 10, it) }, DatosDiarios.ventana(despues))
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
        val dias = listOf(LocalDate.parse("2026-10-13"), d)
        val a = archivos()
        val locales: (LocalDate) -> List<DatosDiarios.Archivo> = { listOf(a[0], a[3], a[4]) }
        assertEquals(2, DatosDiarios.porSubir(dias, locales) { null })
        assertEquals(1, DatosDiarios.porSubir(dias, locales) { if (it == d) subido(a) else null })
        assertEquals(0, DatosDiarios.porSubir(dias, locales) { subido(a) })
    }

    @Test fun carpetaDelDiaDentroDeDatosDiarios() {
        assertEquals("name='2026-10-14' and mimeType='application/vnd.google-apps.folder' and trashed=false and 'abc123' in parents",
            DriveTexto.consultaCarpetaEn("2026-10-14", "abc123"))
        assertEquals(DriveTexto.consultaCarpeta("datos-diarios"), DriveTexto.consultaCarpetaEn("datos-diarios", null))
    }
}
