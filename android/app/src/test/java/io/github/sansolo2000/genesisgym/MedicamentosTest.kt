package io.github.sansolo2000.genesisgym

import io.github.sansolo2000.genesisgym.Medicamentos as M
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Módulo Medicamentos (claude/Vademecum.md §12). Catálogo INVENTADO de pocos registros: el real no va al repositorio.
 * Fechas de 2030; las de horario de verano se calculan con las reglas de la zona.
 */
class MedicamentosTest {
    private val SCL = ZoneId.of("America/Santiago")
    private val MAD = ZoneId.of("Europe/Madrid")

    // Corren desde android/app; el archivo de configuración es el que va en el APK.
    private val archivoConfig = listOf(File("src/main/assets/medicamentos/config-medicamentos.json"), File("android/app/src/main/assets/medicamentos/config-medicamentos.json")).first { it.isFile }
    private val cfg: Medicamentos.Config = M.configDeArchivo(Json.parseToJsonElement(archivoConfig.readText()).jsonObject).let { (c, e) -> assertTrue(e.toString(), e.isEmpty()); c!! }

    private fun med(nombre: String, ids: List<String>, unidad: String? = "comprimido", fracc: String? = "Sí") = M.Med(nombre, ids, "COMPRIMIDOS", unidad, null, fracc)
    private val ficticina1 = med("FICTICINA 10 mg comprimidos", listOf("9000001"))
    private val ficticina2 = med("FICTICINA 10 mg comprimidos", listOf("9000002", "9000003"))
    private val catalogo = M.Catalogo(listOf(
        med("ÁCIDO INVENTÓLICO 5 mg", listOf("9000010")),
        ficticina1, ficticina2,
        med("JARABE PRUEBOL", listOf("9000020"), "mL", "Sí (volumen)"),
        med("Ñandú gotas ficticias", listOf("9000030"), "gota por ojo", "Sí (volumen)"),
        med("CÁPSULA IMAGINARIA", listOf("9000040"), "cápsula", "No"),
        M.Med("POLVO SIN DATOS", listOf("9000050"), "No Identificado"),
        med("COMPUESTO ÁCIDO", listOf("9000060"))
    ))

    private fun trat(f: Medicamentos.Frecuencia, inicio: String = "2030-03-04", dur: Medicamentos.Duracion = Medicamentos.Duracion(M.DUR_INDEFINIDA),
                     persona: String = M.HECTOR, alertas: Boolean = true, vigente: String = "2030-01-01T00:00:00.000Z") =
        M.Tratamiento("tq1", persona, ficticina1, 1.0, "comprimido", f, LocalDate.parse(inicio), dur, alertas, 1, vigente, vigente)

    private fun veces(vararg h: String) = M.Frecuencia(M.VECES_DIA, veces = h.size, horarios = h.toList())
    private fun en(fechaHora: String, z: ZoneId = SCL) = ZonedDateTime.of(LocalDateTime.parse(fechaHora), z)

    // ---------- configuración y sugerencias ----------
    @Test fun configuracionDeLaAppTraeVersionYLasSugerenciasDelRequerimiento() {
        assertTrue(cfg.versionHorarios >= 1 && cfg.versionDosis >= 1)
        assertEquals(listOf("09:00", "21:00"), M.sugerir(cfg, M.VECES_DIA, 2))
        assertEquals(listOf("07:00", "15:00", "23:00"), M.sugerir(cfg, M.VECES_DIA, 3))
        assertEquals(listOf("07:00", "15:00", "23:00"), M.sugerir(cfg, M.INTERVALO_HORAS, intervaloHoras = 8))
        // Sin sugerencia en la configuración: repartidas en el día.
        assertEquals(listOf("07:00", "11:48", "16:36", "21:24", "02:12"), M.sugerir(cfg, M.VECES_DIA, 5))
        assertEquals(listOf("09:00"), M.sugerir(cfg, M.INTERVALO_DIAS))
        assertTrue(M.AVISO_SUGERENCIA.contains("No es una recomendación médica") && M.AVISO_SUGERENCIA.contains("prevalece"))
    }

    @Test fun losHorariosSugeridosSePuedenEditar() {
        val sug = M.sugerir(cfg, M.VECES_DIA, 2)
        val editado = M.Frecuencia(M.VECES_DIA, veces = 2, horarios = listOf("08:30", "20:15"))
        assertTrue(M.validarFrecuencia(editado, cfg).isEmpty())
        val t = trat(editado)
        val oc = M.ocurrencias(t, en("2030-03-04T00:00"), en("2030-03-04T23:59"), SCL)
        assertEquals(listOf("08:30", "20:15"), oc.map { it.local.toLocalTime().toString() })
        assertFalse(oc.map { it.local.toLocalTime().toString() } == sug)
    }

    // ---------- buscador ----------
    @Test fun buscaSinTildesNiMayusculasYPorCoincidenciaParcial() {
        assertEquals(listOf("ÁCIDO INVENTÓLICO 5 mg", "COMPUESTO ÁCIDO"), catalogo.buscar("acido").first.map { it.nombre })
        assertEquals("ÁCIDO INVENTÓLICO 5 mg", catalogo.buscar("Inventolico").first.single().nombre)
        assertEquals("ÁCIDO INVENTÓLICO 5 mg", catalogo.buscar("ventó").first.single().nombre)
        assertEquals("ÁCIDO INVENTÓLICO 5 mg", catalogo.buscar("  inv   ÁCI ").first.single().nombre)   // palabras en otro orden
        assertEquals("Ñandú gotas ficticias", catalogo.buscar("nandu").first.single().nombre)
        assertEquals("JARABE PRUEBOL", catalogo.buscar("9000020").first.single().nombre)                // por identificador
        assertTrue(catalogo.buscar("zzz").first.isEmpty())
        assertTrue(catalogo.buscar("   ").first.isEmpty())
    }

    @Test fun primeroLosQueEmpiezanPorLoEscritoYSinMostrarTodos() {
        val (res, total) = catalogo.buscar("a", max = 3)
        assertEquals(3, res.size)
        assertTrue(total > 3)
        assertEquals("ÁCIDO INVENTÓLICO 5 mg", res.first().nombre)
    }

    @Test fun mismoNombreSeDistingueYGuardaTodosLosIdentificadores() {
        val r = catalogo.buscar("ficticina").first
        assertEquals(2, r.size)
        assertEquals(setOf(listOf("9000001"), listOf("9000002", "9000003")), r.map { it.ids }.toSet())
        val t = trat(veces("09:00")).copy(med = ficticina2)
        val j = M.tratamientoAJson(t)
        assertEquals(listOf("9000002", "9000003"), j["medicamento"]!!.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(t, M.tratamientoDesdeJson(Json.parseToJsonElement(JsJson.stringify(j)).jsonObject))
    }

    // ---------- dosis ----------
    @Test fun laDosisRechazaCantidadesInvalidas() {
        assertNotNull(M.validarDosis(cfg, null, "comprimido", "Sí"))
        assertNotNull(M.validarDosis(cfg, 0.0, "comprimido", "Sí"))
        assertNotNull(M.validarDosis(cfg, -1.0, "comprimido", "Sí"))
        assertNotNull(M.validarDosis(cfg, 1.0, null, "Sí"))
        assertNotNull(M.validarDosis(cfg, 1.0, "inventada", "Sí"))
        assertNull(M.leerCantidad("-1")); assertNull(M.leerCantidad("abc")); assertNull(M.leerCantidad(""))
        assertEquals(0.5, M.leerCantidad("0,5")!!, 0.0); assertEquals(1.25, M.leerCantidad("1.25")!!, 0.0)
    }

    @Test fun fraccionableSoloAceptaCuartosYMitades() {
        for (ok in listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0)) assertNull("$ok", M.validarDosis(cfg, ok, "comprimido", "Sí"))
        for (malo in listOf(0.1, 0.3, 0.2, 1.1, 0.125)) assertTrue("$malo", M.validarDosis(cfg, malo, "comprimido", "Sí")!!.contains("0,25 o 0,5"))
    }

    @Test fun noFraccionableSoloAceptaEnteros() {
        assertNull(M.validarDosis(cfg, 1.0, "cápsula", "No")); assertNull(M.validarDosis(cfg, 2.0, "cápsula", "No"))
        assertTrue(M.validarDosis(cfg, 0.5, "cápsula", "No")!!.contains("no es fraccionable"))
        assertNotNull(M.validarDosis(cfg, 0.25, "mL", "No"))   // tampoco al cambiar la unidad
    }

    @Test fun losOtrosValoresDeFraccionableSalenDeLaConfiguracion() {
        assertNull(M.validarDosis(cfg, 2.5, "mL", "Sí (volumen)"))
        assertNotNull(M.validarDosis(cfg, 2.55, "mL", "Sí (volumen)"))
        assertNotNull(M.validarDosis(cfg, 1.5, "gota", "Sí (volumen)"))   // la gota es entera
        assertNull(M.validarDosis(cfg, 1.0, "aplicación", "No aplica"))
        assertNull(M.validarDosis(cfg, 0.5, "cm", "Sí (longitud)"))
        assertNull(M.validarDosis(cfg, 0.5, "sobre", "Depende"))
        assertNotNull(M.validarDosis(cfg, 0.25, "sobre", "Depende"))
        assertNull(M.validarDosis(cfg, 0.25, "comprimido", null))         // sin dato en el catálogo: paso "sin_dato"
        assertEquals(cfg.pasoPorFraccionable.getValue("sin_dato"), M.paso(cfg, null, "comprimido"), 0.0)
    }

    @Test fun laUnidadPredeterminadaSaleDelCatalogoNuncaDelNombre() {
        assertEquals("comprimido", M.unidadPorDefecto(cfg, ficticina1))
        assertEquals(listOf("gota"), M.unidadesDe(cfg, catalogo.buscar("nandu").first.single()))
        assertEquals(listOf("mL", "UI", "mg"), M.unidadesDe(cfg, med("X", listOf("1"), "mL, UI (insulina) o mg")))
        // "JARABE" en el nombre no define nada: sin unidad en el catálogo no hay predeterminada ni fraccionable.
        val sinDatos = M.Med("JARABE CAPSULA COMPRIMIDO", listOf("1"))
        assertNull(M.unidadPorDefecto(cfg, sinDatos))
        assertEquals(cfg.pasoPorFraccionable.getValue("sin_dato"), M.paso(cfg, sinDatos.fraccionable, "comprimido"), 0.0)
    }

    // ---------- frecuencia ----------
    @Test fun vecesAlDiaEIntervaloDeHorasSonExcluyentes() {
        val ambas = M.Frecuencia(M.VECES_DIA, veces = 2, intervaloHoras = 8, horarios = listOf("09:00", "21:00"))
        assertTrue(M.validarFrecuencia(ambas, cfg).any { it.contains("Solo se puede usar una modalidad") })
        val alReves = M.Frecuencia(M.INTERVALO_HORAS, veces = 2, intervaloHoras = 8, horarios = listOf("07:00"))
        assertTrue(M.validarFrecuencia(alReves, cfg).any { it.contains("Solo se puede usar una modalidad") })
        val conEventos = M.Frecuencia(M.DIAS_SEMANA, dias = listOf(1), horarios = listOf("09:00"), eventos = listOf(M.Toma("desayuno", "07:00")))
        assertFalse(M.validarFrecuencia(conEventos, cfg).isEmpty())
        // Cada una sola es válida.
        assertTrue(M.validarFrecuencia(M.Frecuencia(M.VECES_DIA, veces = 2, horarios = listOf("09:00", "21:00")), cfg).isEmpty())
        assertTrue(M.validarFrecuencia(M.Frecuencia(M.INTERVALO_HORAS, intervaloHoras = 8, horarios = listOf("07:00")), cfg).isEmpty())
    }

    @Test fun validacionesDeCadaModalidad() {
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.VECES_DIA, veces = 3, horarios = listOf("09:00", "21:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.VECES_DIA, veces = 2, horarios = listOf("09:00", "9:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.VECES_DIA, veces = 2, horarios = listOf("09:00", "09:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.INTERVALO_DIAS, intervaloDias = 1, horarios = listOf("09:00")), cfg).isEmpty())
        assertTrue(M.validarFrecuencia(M.Frecuencia(M.INTERVALO_DIAS, intervaloDias = 2, horarios = listOf("09:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.DIAS_SEMANA, dias = listOf(8), horarios = listOf("09:00")), cfg).isEmpty())
        assertTrue(M.validarFrecuencia(M.Frecuencia(M.CICLOS, diasToma = 21, diasDescanso = 7, horarios = listOf("09:00")), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.CICLOS, diasToma = 21, diasDescanso = 0, horarios = listOf("09:00")), cfg).isEmpty())
        assertTrue(M.validarFrecuencia(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma("desayuno", "07:00"), M.Toma("antes_dormir", "22:30"))), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma(M.SEGUN_NECESIDAD, null), M.Toma("desayuno", "07:00"))), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma("inventado", "07:00"))), cfg).isEmpty())
        assertFalse(M.validarFrecuencia(M.Frecuencia("otra"), cfg).isEmpty())
    }

    @Test fun tratamientosDistintosParaHectorYMajo() {
        val h = trat(veces("09:00")); val m = trat(veces("10:00"), persona = M.MAJO).copy(id = "tq2")
        assertTrue(M.validar(h, cfg).isEmpty()); assertTrue(M.validar(m, cfg).isEmpty())
        assertFalse(M.validar(h.copy(persona = "otra"), cfg).isEmpty())
        assertEquals(M.MAJO, M.tratamientoDesdeJson(M.tratamientoAJson(m))!!.persona)
    }

    // ---------- primera administración ----------
    @Test fun laPrimeraEsElProximoHorarioValido() {
        val t = trat(veces("07:00", "15:00", "23:00"))
        assertEquals(LocalDateTime.parse("2030-03-05T15:00"), M.proxima(t, en("2030-03-05T14:00"))!!.local)
        assertEquals(LocalDateTime.parse("2030-03-06T07:00"), M.proxima(t, en("2030-03-05T23:30"))!!.local)   // ya pasaron todas: el día siguiente
        assertEquals(LocalDateTime.parse("2030-03-05T23:00"), M.proxima(t, en("2030-03-05T15:00"))!!.local)   // posterior, no igual
        // Antes del inicio: la primera del día de inicio.
        assertEquals(LocalDateTime.parse("2030-03-04T07:00"), M.proxima(t, en("2030-03-01T12:00"))!!.local)
    }

    @Test fun laPrimeraEnLaProximaFechaQueCorresponde() {
        // Lunes, miércoles y viernes a las 09:00; el lunes 4 a las 10:00 → el miércoles 6.
        val sem = trat(M.Frecuencia(M.DIAS_SEMANA, dias = listOf(1, 3, 5), horarios = listOf("09:00")))
        assertEquals(LocalDateTime.parse("2030-03-06T09:00"), M.proxima(sem, en("2030-03-04T10:00"))!!.local)
        // Cada 3 días desde el 4: 4, 7, 10…
        val cada3 = trat(M.Frecuencia(M.INTERVALO_DIAS, intervaloDias = 3, horarios = listOf("08:00")))
        assertEquals(LocalDateTime.parse("2030-03-07T08:00"), M.proxima(cada3, en("2030-03-04T09:00"))!!.local)
        // Ciclo de 21 de toma y 3 de descanso: el día 22 (descanso) → el día 25, inicio del segundo ciclo.
        val ciclo = trat(M.Frecuencia(M.CICLOS, diasToma = 21, diasDescanso = 3, horarios = listOf("09:00")))
        assertEquals(LocalDateTime.parse("2030-03-28T09:00"), M.proxima(ciclo, en("2030-03-25T10:00"))!!.local)
        // Con un máximo de 1 ciclo, después del primero no hay más.
        assertNull(M.proxima(trat(M.Frecuencia(M.CICLOS, diasToma = 21, diasDescanso = 3, ciclosMax = 1, horarios = listOf("09:00"))), en("2030-03-25T10:00")))
    }

    @Test fun cadaNHorasCruzaLaMedianoche() {
        val t = trat(M.Frecuencia(M.INTERVALO_HORAS, intervaloHoras = 8, horarios = listOf("22:00")))
        val oc = M.ocurrencias(t, en("2030-03-04T00:00"), en("2030-03-06T00:00"), SCL)
        assertEquals(listOf("2030-03-04T22:00", "2030-03-05T06:00", "2030-03-05T14:00", "2030-03-05T22:00"), oc.map { it.local.toString() })
        assertEquals(LocalDateTime.parse("2030-03-05T14:00"), M.proxima(t, en("2030-03-05T07:00"))!!.local)
        assertEquals(listOf("06:00", "12:00", "18:00", "00:00"), M.horasDelIntervalo("06:00", 6))
    }

    @Test fun segunNecesidadNoGeneraTomasNiAlertas() {
        val t = trat(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma(M.SEGUN_NECESIDAD, null))))
        assertTrue(M.validarFrecuencia(t.frecuencia, cfg).isEmpty())
        assertTrue(M.ocurrencias(t, en("2030-03-04T00:00"), en("2030-03-10T00:00"), SCL).isEmpty())
        assertNull(M.proxima(t, en("2030-03-04T00:00")))
        assertTrue(M.cadenasAbiertas(t, en("2030-03-05T09:30"), emptyMap()).isEmpty())
    }

    @Test fun eventosLlevanSuEventoEnCadaToma() {
        val t = trat(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma("desayuno", "07:00"), M.Toma("antes_dormir", "22:30"))))
        val oc = M.ocurrencias(t, en("2030-03-04T00:00"), en("2030-03-04T23:59"), SCL)
        assertEquals(listOf("desayuno", "antes_dormir"), oc.map { it.evento })
    }

    // ---------- duración y vencidos ----------
    @Test fun duracionEnDiasCalculaLaFechaDeTermino() {
        assertEquals(LocalDate.parse("2030-03-10"), M.fechaFin(LocalDate.parse("2030-03-04"), M.Duracion(M.DUR_DIAS, dias = 7)))
        assertEquals(LocalDate.parse("2030-03-04"), M.fechaFin(LocalDate.parse("2030-03-04"), M.Duracion(M.DUR_DIAS, dias = 1)))
        assertNull(M.fechaFin(LocalDate.parse("2030-03-04"), M.Duracion(M.DUR_INDEFINIDA)))
        assertFalse(M.validarDuracion(LocalDate.parse("2030-03-04"), M.Duracion(M.DUR_FECHA, fin = LocalDate.parse("2030-03-03"))).isEmpty())
        assertFalse(M.validarDuracion(LocalDate.parse("2030-03-04"), M.Duracion(M.DUR_DIAS, dias = 0)).isEmpty())
        val t = trat(veces("09:00"), dur = M.Duracion(M.DUR_DIAS, dias = 3))
        assertEquals(3, M.ocurrencias(t, en("2030-03-01T00:00"), en("2030-03-31T00:00"), SCL).size)
    }

    @Test fun losTratamientosVencidosNoGeneranAlertas() {
        val t = trat(veces("09:00", "21:00"), dur = M.Duracion(M.DUR_FECHA, fin = LocalDate.parse("2030-03-05")))
        val ahora = en("2030-03-07T08:00")
        assertTrue(t.vencido(ahora.toLocalDate()))
        assertTrue(M.ocurrencias(t, ahora, ahora.plusDays(30), SCL).isEmpty())
        assertNull(M.proxima(t, ahora))
        assertTrue(M.cadenasAbiertas(t, ahora, emptyMap()).isEmpty())
        // Sin alertas tampoco abre cadenas ni cierra tomas como "sin respuesta".
        val apagado = trat(veces("09:00"), alertas = false)
        assertTrue(M.cadenasAbiertas(apagado, en("2030-03-05T09:30"), emptyMap()).isEmpty())
        assertTrue(M.cerradasSinRespuesta(apagado, en("2030-03-05T15:00"), emptySet()).isEmpty())
    }

    // ---------- alertas: cada 10 minutos por 2 horas ----------
    @Test fun sinRespuestaSeRepiteCada10MinutosPorDosHoras() {
        val t0 = Instant.parse("2030-03-04T12:00:00Z").toEpochMilli()
        val avisos = M.avisosDeCadena(t0)
        assertEquals(12, avisos.size)
        assertEquals((0 until 12).map { t0 + it * 600_000L }, avisos)
        val alFinal = M.paso(t0, t0 + 120 * 60_000L)
        assertTrue(alFinal.cerrar); assertFalse(alFinal.sonar)
        val atrasada = M.paso(t0, t0 + 3 * 60_000L)       // la alarma llegó 3 min tarde: suena y vuelve a los 10
        assertTrue(atrasada.sonar); assertEquals(t0 + 600_000L, atrasada.proximoMs)
        val ultima = M.paso(t0, t0 + 115 * 60_000L)        // la última: el próximo paso es el cierre a las 2 h
        assertTrue(ultima.sonar); assertEquals(t0 + 120 * 60_000L, ultima.proximoMs)
        val antes = M.paso(t0, t0 - 1000)
        assertFalse(antes.sonar); assertEquals(t0, antes.proximoMs)
    }

    @Test fun confirmarPosponerYOmitirDecidenLaCadena() {
        val t = trat(veces("09:00"), inicio = "2030-03-05")
        val oc = M.ocurrencias(t, en("2030-03-05T00:00"), en("2030-03-05T23:59"), SCL).single()
        val t0 = oc.cuando.toInstant()
        fun reg(estado: String, minutosDespues: Long, pos: Int? = null): Medicamentos.Registro {
            val r = t0.plusSeconds(minutosDespues * 60)
            return M.registroDesde(M.rutaRegistro(t.id, oc.local, estado, r.toEpochMilli()), M.registro(t, oc, estado, r, pos, "alerta"))!!
        }
        assertEquals(t0.toEpochMilli(), M.inicioCadena(t0.toEpochMilli(), emptyList()))
        assertNull(M.inicioCadena(t0.toEpochMilli(), listOf(reg(M.CONFIRMADA, 5))))
        assertNull(M.inicioCadena(t0.toEpochMilli(), listOf(reg(M.OMITIDA, 5))))
        val pos = reg(M.POSTERGADA, 20, 30)
        assertEquals(t0.plusSeconds(50 * 60).toEpochMilli(), M.inicioCadena(t0.toEpochMilli(), listOf(pos)))
        assertNull(M.inicioCadena(t0.toEpochMilli(), listOf(pos, reg(M.CONFIRMADA, 55))))
        // Cadenas abiertas: sin respuesta dentro de las 2 h, o la postergación (vuelve a sonar a los 30 min).
        val clave = M.clave(t.id, oc.local)
        assertEquals(t0.toEpochMilli(), M.cadenasAbiertas(t, en("2030-03-05T09:30"), emptyMap()).single().second)
        assertEquals(t0.plusSeconds(50 * 60).toEpochMilli(), M.cadenasAbiertas(t, en("2030-03-05T09:30"), mapOf(clave to listOf(pos))).single().second)
        assertTrue(M.cadenasAbiertas(t, en("2030-03-05T09:30"), mapOf(clave to listOf(reg(M.CONFIRMADA, 5)))).isEmpty())
        // Pasadas las 2 h sin nada: se cierra como "sin respuesta" (ruta fija, sin duplicar).
        assertEquals(listOf(oc.local), M.cerradasSinRespuesta(t, en("2030-03-05T11:01"), emptySet()).map { it.local })
        assertTrue(M.cerradasSinRespuesta(t, en("2030-03-05T11:01"), setOf(clave)).isEmpty())
        assertEquals(M.rutaRegistro(t.id, oc.local, M.SIN_RESPUESTA, 1), M.rutaRegistro(t.id, oc.local, M.SIN_RESPUESTA, 2))
        assertEquals(listOf(15, 30, 60, 90), M.POSTERGAR)
    }

    // ---------- historial ----------
    @Test fun editarNoCambiaElHistorial() {
        val v1 = M.guardar(trat(veces("09:00")), null, Instant.parse("2030-03-01T12:00:00Z"))
        assertEquals(1, v1.version)
        val oc = M.ocurrencias(v1, en("2030-03-05T00:00"), en("2030-03-05T23:59"), SCL).single()
        val r = M.registro(v1, oc, M.CONFIRMADA, oc.cuando.toInstant().plusSeconds(120), null, "alerta")
        val antes = JsJson.stringify(r)
        val v2 = M.guardar(v1.copy(cantidad = 0.5, frecuencia = veces("08:00", "20:00")), v1, Instant.parse("2030-03-06T12:00:00Z"))
        assertEquals(2, v2.version); assertEquals(v1.id, v2.id); assertEquals(v1.creadoEn, v2.creadoEn)
        assertEquals(antes, JsJson.stringify(r))
        val snap = r["tratamiento"]!!.jsonObject
        assertEquals(1, snap["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("1", snap["dosis"]!!.jsonObject["cantidad"]!!.jsonPrimitive.content)
        assertEquals(listOf("09:00"), snap["frecuencia"]!!.jsonObject["horarios"]!!.jsonArray.map { it.jsonPrimitive.content })
        // La versión nueva rige solo hacia adelante: no genera tomas antes de su vigencia.
        assertTrue(M.cadenasAbiertas(v2, en("2030-03-06T09:05"), emptyMap()).isEmpty())
        assertTrue(M.cerradasSinRespuesta(v2, en("2030-03-06T12:30"), emptySet()).isEmpty())
        // Cada respuesta es un documento distinto.
        assertFalse(M.rutaRegistro(v1.id, oc.local, M.POSTERGADA, 1) == M.rutaRegistro(v1.id, oc.local, M.CONFIRMADA, 2))
        assertTrue(Regex("^[A-Za-z0-9_\\-.~:@+]{1,200}/[A-Za-z0-9_\\-.~:@+]{1,200}$").matches(M.rutaRegistro(v1.id, oc.local, M.POSTERGADA, 1)))
    }

    @Test fun elRegistroGuardaLoQuePideElRequerimiento() {
        val t = trat(M.Frecuencia(M.EVENTOS, eventos = listOf(M.Toma("desayuno", "07:00"))))
        val oc = M.ocurrencias(t, en("2030-03-05T00:00"), en("2030-03-05T23:59"), SCL).single()
        val r = M.registro(t, oc, M.POSTERGADA, Instant.parse("2030-03-05T10:05:00Z"), 15, "alerta")
        assertEquals("2030-03-05T07:00", r["programada"]!!.jsonPrimitive.content)
        assertEquals("2030-03-05T10:05:00.000Z", r["respondida_en"]!!.jsonPrimitive.content)
        assertEquals("postergada", r["estado"]!!.jsonPrimitive.content)
        assertEquals(15, r["postergacion_min"]!!.jsonPrimitive.content.toInt())
        assertEquals("2030-03-05T10:20:00.000Z", r["postergada_hasta"]!!.jsonPrimitive.content)
        assertEquals("desayuno", r["evento"]!!.jsonPrimitive.content)
        assertEquals(M.HECTOR, r["persona"]!!.jsonPrimitive.content)
        assertEquals("FICTICINA 10 mg comprimidos", r["tratamiento"]!!.jsonObject["medicamento"]!!.jsonObject["nombre"]!!.jsonPrimitive.content)
    }

    // ---------- zona horaria y horario de verano ----------
    @Test fun alCambiarDeZonaSeConservaLaHoraLocal() {
        val t = trat(veces("09:00"))
        val scl = M.ocurrencias(t, en("2030-03-05T00:00"), en("2030-03-05T23:59"), SCL).single()
        val mad = M.ocurrencias(t, en("2030-03-05T00:00", MAD), en("2030-03-05T23:59", MAD), MAD).single()
        assertEquals(LocalTime.of(9, 0), scl.cuando.toLocalTime()); assertEquals(LocalTime.of(9, 0), mad.cuando.toLocalTime())
        assertFalse(scl.cuando.toInstant() == mad.cuando.toInstant())
        assertEquals(scl.local, mad.local)   // misma toma (misma clave del historial)
    }

    @Test fun losCambiosOficialesDeHoraConservanLaHoraLocal() {
        for (desde in listOf("2030-08-01T00:00:00Z", "2030-03-01T00:00:00Z")) {   // inicio y fin del horario de verano en Chile
            val tr = SCL.rules.nextTransition(Instant.parse(desde))
            assertNotNull(tr)
            val dia = tr.dateTimeAfter.toLocalDate()
            val t = trat(veces("09:00"), inicio = dia.minusDays(2).toString())
            val oc = M.ocurrencias(t, ZonedDateTime.of(dia.minusDays(2).atStartOfDay(), SCL), ZonedDateTime.of(dia.plusDays(3).atStartOfDay(), SCL), SCL)
            assertEquals(5, oc.size)
            assertTrue(oc.all { it.cuando.toLocalTime() == LocalTime.of(9, 0) })
            val horas = oc.zipWithNext { a, b -> Duration.between(a.cuando, b.cuando).toHours() }
            assertEquals(if (tr.isGap) 23L else 25L, horas.first { it != 24L })
        }
    }

    @Test fun unaHoraQueNoExisteSeCorreAlaSiguienteSinPerderLaToma() {
        val tr = SCL.rules.nextTransition(Instant.parse("2030-08-01T00:00:00Z"))
        assertTrue(tr.isGap)
        val hueco = tr.dateTimeBefore.plusMinutes(30)            // no existe ese día
        val t = trat(veces(hueco.toLocalTime().toString()), inicio = hueco.toLocalDate().toString())
        val oc = M.ocurrencias(t, ZonedDateTime.of(hueco.toLocalDate().atStartOfDay(), SCL).minusHours(2), ZonedDateTime.of(hueco.toLocalDate().plusDays(1).atStartOfDay(), SCL), SCL).first()
        assertEquals(hueco, oc.local)
        assertEquals(hueco.plusSeconds(tr.duration.seconds).toLocalTime(), oc.cuando.toLocalTime())
    }

    // ---------- sección del plan (contrato con el Consolidador) ----------
    private fun seccion(medicamentos: JsonArray = JsonArray(listOf(M.medAJson(ficticina1), M.medAJson(ficticina2))), tipo: String = M.SECCION_TIPO) = buildJsonObject {
        put("tipo", tipo); put("version_formato", "1.0")
        put("catalogo", buildJsonObject { put("fuente", "catálogo inventado de prueba"); put("version", "prueba-1"); put("medicamentos", medicamentos) })
        put("config_horarios", Json.parseToJsonElement(archivoConfig.readText()).jsonObject["config_horarios"]!!)
    }

    @Test fun seccionMedicamentosValidaYErrores() {
        val (s, e, _) = M.revisarSeccion(seccion())
        assertTrue(e.toString(), e.isEmpty())
        assertEquals(2, s!!.registros); assertEquals("prueba-1", s.versionCatalogo)
        assertEquals(2, M.catalogoDesdeJson(seccion()["catalogo"]!!.jsonObject).tamano)
        assertFalse(M.revisarSeccion(seccion(tipo = "otro")).second.isEmpty())
        val sinIds = JsonArray(listOf(buildJsonObject { put("nombre", "SIN IDS") }))
        assertTrue(M.revisarSeccion(seccion(sinIds)).second.any { it.contains("sin nombre o sin ids") })
        assertFalse(M.revisarSeccion(seccion(JsonArray(emptyList()))).second.isEmpty())
        val malaCfg = JsonObject(seccion() + ("config_dosis" to buildJsonObject { put("version", 0) }))
        assertFalse(M.revisarSeccion(malaCfg).second.isEmpty())
    }

    @Test fun elPlanConMedicamentosAgregaLaParteYSinEllaNoCambia() {
        fun recurso(n: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource(n)!!.readText()).jsonObject
        val rutina = Json.parseToJsonElement(javaClass.classLoader!!.getResource("validador-casos.json")!!.readText()).jsonArray
            .map { it.jsonObject }.first { it["nombre"]!!.jsonPrimitive.content == "valida" }["json"]!!.jsonPrimitive.content
            .replace("\"ejemplo-formato\"", "\"fuerza-prueba\"").let { Json.parseToJsonElement(it).jsonObject }
        fun sobre(med: JsonObject?) = JsJson.stringify(buildJsonObject {
            put("tipo", Plan.TIPO); put("version_formato", "1.0"); put("perfil", "prueba"); put("generado_en", "2030-03-01T10:00:00-03:00")
            put("rutina", rutina); put("alimentacion", recurso("prueba-programa-con-compras.json")); put("apoyo", recurso("prueba-apoyo-programa.json"))
            if (med != null) put("medicamentos", med)
        })
        val ahora = "2030-03-01T13:00:00.000Z"
        val sin = Plan.revisar(sobre(null), null, null, null, "Prueba", ahora)
        assertEquals(listOf("rutina", "alimentacion", "apoyo"), sin.partes.map { it.clave })
        val con = Plan.revisar(sobre(seccion()), null, null, null, "Prueba", ahora)
        assertTrue(con.errores.toString(), con.ok)
        val p = con.partes.single { it.clave == "medicamentos" }
        assertEquals("nueva", p.estado)
        assertEquals(listOf("config/medicamentos"), p.docs.map { it.ruta })
        assertNull(p.docs.single().data["catalogo"])   // el catálogo no va a la base ni al respaldo
        val huella = p.docs.single().data["huella_sha256"]!!.jsonPrimitive.content
        assertEquals("igual", Plan.revisar(sobre(seccion()), null, null, null, "Prueba", ahora, huella).partes.single { it.clave == "medicamentos" }.estado)
        val mala = Plan.revisar(sobre(seccion(tipo = "otro")), null, null, null, "Prueba", ahora)
        assertFalse(mala.ok)   // todo o nada
        assertEquals("error", mala.partes.single { it.clave == "medicamentos" }.estado)
        // La configuración que trae el plan reemplaza la de la app.
        val cfgPlan = M.config(p.docs.single().data["config_horarios"] as JsonObject, null, cfg).first!!
        assertEquals(cfg.sugerencias, cfgPlan.sugerencias)
        assertEquals(cfg.unidades, cfgPlan.unidades)
    }

    @Test fun elTipoMedicamentosSeSumaALasAlertasSinCambiarLasDeM10() {
        assertEquals(5, Alertas.TIPOS.size)
        assertEquals(Alertas.TIPOS + Alertas.MEDICAMENTO, Alertas.TODOS)
        assertTrue(Alertas.insiste(Alertas.MEDICAMENTO)); assertTrue(Alertas.esperaRespuesta(Alertas.MEDICAMENTO))
        assertFalse(Alertas.TIPOS.any { Alertas.esperaRespuesta(it) })
        // Propuesta pendiente de Héctor: Medicamentos suena por sobre los demás.
        assertEquals(Alertas.MEDICAMENTO, Alertas.ganador(listOf(Recordatorios.ENTRENAMIENTO, Alertas.MEDICAMENTO)))
        val base = Alertas.inicial(null, 70, false)
        val m = Alertas.desdeJson(Alertas.aJson(mapOf(Alertas.MEDICAMENTO to base.copy(volumen = 40))), base)
        assertEquals(40, m.getValue(Alertas.MEDICAMENTO).volumen)
        assertTrue(Medicamentos.COLECCION in Respaldo.COLECCIONES)
    }
}
