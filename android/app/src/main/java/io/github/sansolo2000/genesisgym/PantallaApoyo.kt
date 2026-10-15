package io.github.sansolo2000.genesisgym

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sansolo2000.genesisgym.Apoyo.a
import io.github.sansolo2000.genesisgym.Apoyo.b
import io.github.sansolo2000.genesisgym.Apoyo.n
import io.github.sansolo2000.genesisgym.Apoyo.o
import io.github.sansolo2000.genesisgym.Apoyo.s
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime

/**
 * Pestaña Reporte — módulo Apoyo psicológico (contrato v2). Reporte diario, racha, alertas, señal de alarma,
 * resumen del viernes y envío a la carpeta "Genesis Gym - reportes apoyo" de Drive.
 * Todos los textos que ve Héctor salen del programa de Apoyo psicológico, tal cual.
 */
class Apo(val base: BaseLocal, private val comidas: () -> EstadoComidas?, private val alCambiar: () -> Unit) {
    /** Al activar un programa: (hora del reporte del programa nuevo, la del anterior). La actividad ajusta Avisos. */
    var alActivar: (String?, String?) -> Unit = { _, _ -> }
    var tick by mutableIntStateOf(0)
    var estado by mutableStateOf(EstadoApoyo(null)); private set
    var dia by mutableStateOf(Gimnasio.hoyIso())
    var vista by mutableStateOf("reporte")          // "reporte" | "resumen"
    var verImport by mutableStateOf(false)
    var importacion by mutableStateOf<Pair<JsonObject?, Apoyo.Resultado>?>(null)
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var resultado by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var errores by mutableStateOf<List<String>>(emptyList())
    var enviando by mutableStateOf("")
    var faltaPermiso by mutableStateOf(false)
    var aviso by mutableStateOf("")
    var enviarAuto: () -> Unit = {}
    /** O-33 (M-10): guardar el reporte calla la alerta del reporte que esté sonando. */
    var alGuardarReporte: () -> Unit = {}

    // Lo que se está editando del día elegido
    val resp = mutableStateMapOf<String, Any?>()
    val textos = mutableStateMapOf<String, String>()     // lo escrito en campos de texto y números
    val comidasManual = mutableStateMapOf<String, String?>()
    var hecho by mutableStateOf("nada")
    var minutos by mutableStateOf("")
    var rpe by mutableStateOf("")
    var entreno by mutableStateOf<EstadoApoyo.Entreno?>(null)

    // Resumen del viernes
    var cumplido by mutableStateOf<String?>(null)
    var barrera by mutableStateOf<String?>(null)
    var detalle by mutableStateOf("")
    var funciono by mutableStateOf("")
    var retoSiguiente by mutableStateOf("")

    fun ahora(): ZonedDateTime = ZonedDateTime.now(estado.zona)
    fun hoy(): String = estado.hoy(ahora())

    init { cargar() }

    fun cargar() {
        mensaje = try {
            val prog = base.get("programas_apoyo/activo")?.get("programa") as? JsonObject
            estado = EstadoApoyo(prog, base.coleccion("reportes_apoyo"), base.coleccion("resumenes_apoyo"))
            if (dia > hoy()) dia = hoy()
            null
        } catch (e: Exception) { "err" to "No se pudo leer la base de este celular: ${e.javaClass.simpleName}" }
        abrirDia(dia)
    }

    /** Al volver a la pestaña: relee la base sin borrar lo que se estaba escribiendo (salvo que cambió el día). */
    fun refrescar() {
        val cambioDia = dia > hoy() || (resp.isEmpty() && textos.isEmpty())
        try {
            val prog = base.get("programas_apoyo/activo")?.get("programa") as? JsonObject
            estado = EstadoApoyo(prog, base.coleccion("reportes_apoyo"), base.coleccion("resumenes_apoyo"))
        } catch (e: Exception) { mensaje = "err" to "No se pudo leer la base de este celular: ${e.javaClass.simpleName}" }
        if (cambioDia) abrirDia(if (dia > hoy()) hoy() else dia) else tick++
    }

    private fun sesiones(): List<JsonObject> = runCatching { base.coleccion("sesiones").map { it.data } }.getOrDefault(emptyList())

    /** Carga en el formulario el reporte guardado de f o, si no hay, la precarga del día. */
    fun abrirDia(f: String) {
        dia = f; resultado = emptyList(); errores = emptyList()
        resp.clear(); textos.clear(); comidasManual.clear()
        val r = estado.reportes[f]
        resp.putAll(estado.respuestasDe(r))
        for ((k, v) in resp) if (v is String || v is Double) textos[k] = if (v is Double) JsJson.numero(v.toString()).replace('.', ',') else v as String
        val det = r.o("comidas").o("detalle")
        for (t in Comidas.TIPOS) comidasManual[t] = det.s(t)
        val pre = estado.entrenoPrecargado(f, sesiones())
        val e = r.o("entrenamiento")
        entreno = if (e != null) EstadoApoyo.Entreno(e.s("planificado") ?: pre.planificado, e.s("hecho") ?: pre.hecho, e.n("minutos")?.toInt(), e.n("rpe")?.toInt(), e.s("sesion"), e.s("origen") ?: "manual") else pre
        hecho = entreno!!.hecho; minutos = entreno!!.minutos?.toString() ?: ""; rpe = entreno!!.rpe?.toString() ?: ""
        if (vista == "resumen" && !Apoyo.esViernes(f)) vista = "reporte"
        tick++
    }

    fun moverDia(n: Long) { val f = Apoyo.dia(dia, n); if (f <= hoy()) abrirDia(f) }

    /** Comidas del día: de la pestaña Comidas si hay programa de alimentación con ese día; si no, las marcadas aquí. */
    fun comidasDelDia(): EstadoApoyo.Comidas5 =
        estado.comidasDesdeApp(comidas(), dia) ?: EstadoApoyo.Comidas5(Comidas.TIPOS.associateWith { comidasManual[it] }, "manual")

    fun guardar() {
        val pre = entreno ?: estado.entrenoPrecargado(dia, sesiones())
        val desdeApp = pre.origen == "app" && hecho == pre.hecho
        val ent = EstadoApoyo.Entreno(pre.planificado, hecho, minutos.trim().toIntOrNull(), rpe.trim().toIntOrNull(),
            if (desdeApp) pre.sesion else null, if (desdeApp) "app" else "manual")
        val respuestas = HashMap<String, Any?>(resp)
        for (q in estado.preguntas()) {
            val id = q.s("id") ?: continue
            when (q.s("tipo")) {
                "numero" -> respuestas[id] = textos[id]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.replace(',', '.').toDoubleOrNull() ?: it }
                "texto" -> respuestas[id] = textos[id]?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        val (doc, err) = estado.armarReporte(dia, respuestas, comidasDelDia(), ent, ahora())
        errores = err
        if (doc == null) { tick++; return }
        try {
            val d = estado.guardar(dia, doc)
            base.put(d.ruta, d.data)
            resultado = estado.mensajesAlGuardar(doc)
            alCambiar()
            alGuardarReporte()
            enviarAuto()
        } catch (e: Exception) { errores = listOf("No se pudo guardar: ${e.javaClass.simpleName}") }
        tick++
    }

    /** Ajuste 3: si cambian las comidas de un día ya reportado (dentro del plazo), el reporte se actualiza solo. */
    fun sincronizarComidas() {
        if (estado.programa == null) return
        val h = hoy()
        for (f in listOf(Apoyo.dia(h, -1), h)) {
            val d = runCatching { estado.sincronizarComidas(f, comidas(), ahora()) }.getOrNull() ?: continue
            runCatching { base.put(d.ruta, d.data) }
        }
        tick++
        enviarAuto()
    }

    // ---------- resumen del viernes ----------
    fun abrirResumen() {
        val r = estado.resumenes[dia]
        cumplido = r.o("reto").s("cumplido")?.takeIf { it != "sin-reto" }
        barrera = r.o("barrera").s("id"); detalle = r.o("barrera").s("detalle") ?: ""
        funciono = r.s("lo_que_funciono") ?: ""; retoSiguiente = r.s("reto_siguiente") ?: ""
        errores = emptyList(); resultado = emptyList(); vista = "resumen"
    }
    fun vistaPrevia(): JsonObject? = estado.armarResumen(dia, EstadoApoyo.Cierre("si", estado.barreras().firstOrNull()?.first, null, null, "-"), ahora()).first
    fun guardarResumen() {
        val (doc, err) = estado.armarResumen(dia, EstadoApoyo.Cierre(cumplido, barrera, detalle, funciono, retoSiguiente), ahora())
        errores = err
        if (doc == null) { tick++; return }
        try {
            val d = estado.guardarResumen(dia, doc)
            base.put(d.ruta, d.data)
            resultado = listOf("ok" to "Resumen guardado. Reto de la próxima semana: ${retoSiguiente.trim()}")
            enviarAuto()
        } catch (e: Exception) { errores = listOf("No se pudo guardar: ${e.javaClass.simpleName}") }
        tick++
    }

    // ---------- programa ----------
    fun horaReporteDe(p: JsonObject?): String? = ((p?.get("horario") as? JsonObject)?.get("reporte") as? kotlinx.serialization.json.JsonPrimitive)?.content

    /** Plan Génesis: el programa ya se guardó junto con las otras partes; aquí se recarga y se avisa como en activar(). */
    fun trasImportarPlan(horaAntes: String?) {
        cargar()
        importacion = null; verImport = false
        alActivar(horaReporteDe(estado.programa), horaAntes)
        alCambiar()
    }
    fun validar(texto: String) { importacion = Apoyo.revisarTexto(texto, estado.programa) }
    fun activar() {
        val (obj, r) = importacion ?: return
        if (obj == null || !r.ok) return
        try {
            val horaAntes = horaReporteDe(estado.programa)
            base.ponerVarios(Apoyo.documentosActivacion(obj, Gimnasio.ahoraIso()))
            importacion = null; verImport = false
            cargar()
            alActivar(horaReporteDe(obj), horaAntes)
            mensaje = "ok" to "Programa de apoyo activado: ${obj.o("programa").s("id")} (versión ${obj.o("programa").n("version")?.toInt()})."
            alCambiar()
        } catch (e: Exception) { mensaje = "err" to "No se pudo guardar el programa: ${e.javaClass.simpleName}" }
    }

    // ---------- Drive ----------
    suspend fun subir(token: String, automatico: Boolean = false) {
        if (enviando.isNotEmpty()) return
        faltaPermiso = false
        val lista = estado.pendientes()
        if (lista.isEmpty()) { if (!automatico) mensaje = "info" to "No hay nada nuevo que enviar."; return }
        try {
            enviando = "Preparando carpeta…"
            val carpeta = DriveApi.carpetaPropia(token, Apoyo.CARPETA)
            lista.forEachIndexed { i, (tipo, f) ->
                enviando = "Subiendo ${i + 1} de ${lista.size}…"
                val id = DriveApi.subirArchivo(token, carpeta, estado.nombreArchivo(tipo, f), "application/json", estado.textoEnvio(tipo, f).toByteArray(Charsets.UTF_8))
                val d = estado.marcarEnviado(tipo, f, id, Gimnasio.ahoraIso())
                base.put(d.ruta, d.data)
            }
            val m = "Enviado a Apoyo psicológico: ${lista.size} archivo${if (lista.size == 1) "" else "s"} en la carpeta \"${Apoyo.CARPETA}\" de tu Drive."
            if (automatico) aviso = "Enviado a Apoyo psicológico ✓" else mensaje = "ok" to m
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to (if (automatico) "No se pudo enviar solo ($m). Queda guardado: toca Enviar cuando tengas internet."
                else "No se pudo enviar a Drive: $m. El reporte queda guardado en el celular; vuelve a intentarlo con internet.")
        } finally { enviando = ""; tick++ }
    }
}

interface AccionesApoyo {
    fun elegirPrograma()
    fun enviar()
}

// ---------- piezas de pantalla ----------
@Composable private fun Tit(t: String) = Text(t, fontSize = 32.sp, fontWeight = FontWeight.Bold)
@Composable private fun Sub(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
@Composable private fun Gr(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = GRIS)
@Composable private fun Etq(t: String, color: Color = GRIS) = Text(t, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
@Composable private fun Caja(contenido: @Composable () -> Unit) =
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CAJA)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenido() } }
@Composable private fun Nota(t: String, tipo: String) {
    val (fondo, borde) = when (tipo) {
        "alarma", "err" -> Color(0xFF3A1D1D) to Color(0xFFE57373)
        "alerta" -> Color(0xFF3A2E1A) to AMBAR
        "ok" -> Color(0xFF1E3A2F) to VERDE
        else -> CAJA2 to LINEA
    }
    Text(t, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fondo)
        .border(1.dp, borde, RoundedCornerShape(12.dp)).padding(12.dp))
}
@Composable private fun Opcion(sel: Boolean, texto: String, mod: Modifier = Modifier, alTocar: () -> Unit) =
    if (sel) Button(onClick = alTocar, modifier = mod.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = AMBAR, contentColor = Color(0xFF231A0B))) { Text(texto, textAlign = TextAlign.Center) }
    else OutlinedButton(onClick = alTocar, modifier = mod.heightIn(min = 48.dp)) { Text(texto, textAlign = TextAlign.Center, color = TEXTO) }

private val PLAN_ETQ = mapOf("fuerza" to "Fuerza", "mtb" to "MTB", "descanso" to "Descanso", "otro" to "Otro", "nada" to "Nada")
private val COMIDA_ETQ = mapOf("indicado" to "Lo indicado", "otro" to "Otra cosa", "no-comi" to "No comí")

@Composable
fun PantallaApoyo(a: Apo, acc: AccionesApoyo) {
    @Suppress("UNUSED_VARIABLE") val t = a.tick
    Box(Modifier.fillMaxSize()) {
        when {
            a.estado.programa == null || a.verImport -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tit("Reporte")
                a.mensaje?.let { (tipo, texto) -> Nota(texto, tipo) }
                if (a.estado.programa == null) Caja { Sub("Aún no hay programa de apoyo"); Gr("Las preguntas, las reglas de la racha y los textos los define el chat Apoyo psicológico en un archivo programa-apoyo-….json. Impórtalo para empezar.") }
                Importar(a, acc)
            }
            a.vista == "resumen" -> Resumen(a)
            else -> Reporte(a, acc)
        }
        if (a.aviso.isNotEmpty()) {
            LaunchedEffect(a.aviso) { kotlinx.coroutines.delay(2600); a.aviso = "" }
            Text(a.aviso, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 56.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E3A2F)).border(1.dp, VERDE, RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }
}

@Composable
private fun Importar(a: Apo, acc: AccionesApoyo) = Caja {
    Sub("Importar programa de apoyo")
    Gr("Lo entrega el chat Apoyo psicológico. Si tiene un solo error, se rechaza completo. En el selector puedes ir a Drive (Genesis Gym › programas-apoyo).")
    Button(onClick = { acc.elegirPrograma() }) { Text("Elegir archivo .json") }
    a.importacion?.let { (obj, r) ->
        if (r.yaActivo) Nota("Este programa ya está activo. No hay nada que importar.", "info")
        else if (!r.ok) {
            Nota("Programa rechazado. ${r.errores.size} error${if (r.errores.size == 1) "" else "es"}. Devuelve esta lista a Apoyo psicológico.", "err")
            for (x in r.errores) Text("• $x")
        } else if (obj != null) {
            Nota("Programa válido: ${obj.o("programa").s("id")}, versión ${obj.o("programa").n("version")?.toInt()}, vigente desde ${obj.o("programa").s("vigente_desde")}.", "ok")
            if (r.avisos.isNotEmpty()) { Gr("Avisos (no bloquean):"); for (x in r.avisos) Text("• $x") }
            Button(onClick = { a.activar() }) { Text("Activar este programa") }
        }
    }
    if (a.estado.programa != null) OutlinedButton(onClick = { a.verImport = false; a.importacion = null }) { Text("Cerrar") }
}

@Composable
private fun Reporte(a: Apo, acc: AccionesApoyo) {
    val e = a.estado
    val ahora = a.ahora(); val hoy = e.hoy(ahora); val f = a.dia
    val guardado = e.reportes[f]
    val bloqueado = guardado != null && !e.dentroDelPlazo(f, ahora)
    val racha = e.rachaActual(ahora); val mejor = e.mejorRacha()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { a.moverDia(-1) }) { Text("‹", fontSize = 24.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (f == hoy) "Hoy" else Comidas.fechaLarga(f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(when {
                        guardado == null && !e.dentroDelPlazo(f, ahora) -> "Fuera de plazo: se guarda, pero no suma a la racha"
                        guardado == null -> "Sin reporte · plazo: ${e.plazo(f).toLocalTime()} del día siguiente"
                        bloqueado -> "Guardado · el plazo venció"
                        else -> "Guardado (versión ${e.version(guardado)}) · se puede corregir hasta las ${e.plazo(f).toLocalTime()} de mañana"
                    }, color = GRIS, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                TextButton(onClick = { a.moverDia(1) }, enabled = f < hoy) { Text("›", fontSize = 24.sp) }
            }
            a.mensaje?.let { (tipo, texto) -> Nota(texto, tipo) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Etq("RACHA"); Text("$racha", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AMBAR) }
                Column(Modifier.weight(1f)) { Etq("MEJOR RACHA"); Text("$mejor", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            }
            if (f == hoy && guardado == null) {
                if (e.rachaReinicio(f, ahora)) e.texto("racha_reinicio")?.let { Nota(Apoyo.rellenar(it, racha, mejor), "info") }
                else if (e.ayerFuera(f, ahora)) e.texto("ayer_fuera")?.let { Nota(Apoyo.rellenar(it, racha, mejor), "info") }
            }
            e.retoVigente(f)?.let { Caja { Etq("RETO DE LA SEMANA", VERDE); Text(it, fontSize = 16.sp) } }
            for ((tipo, texto) in a.resultado) Nota(texto, tipo)

            for (q in e.preguntas()) Pregunta(a, q, bloqueado)
            ComidasV(a, bloqueado)
            EntrenoV(a, bloqueado)

            if (a.errores.isNotEmpty()) Nota(a.errores.joinToString("\n") { "• $it" }, "err")
            if (!bloqueado) Button(onClick = { a.guardar() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AMBAR, contentColor = Color(0xFF231A0B))) {
                Text(if (guardado == null) "Guardar reporte" else "Guardar cambios", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (Apoyo.esViernes(f) && e.puedeResumir(f)) OutlinedButton(onClick = { a.abrirResumen() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (e.resumenes[f] == null) "Cerrar la semana (resumen del viernes)" else "Ver o corregir el resumen de la semana")
            }
            TextButton(onClick = { a.verImport = true }) { Text("Importar otro programa de apoyo") }
            Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall, color = GRIS)
        }
        BarraEnvio(a, acc)
    }
}

@Composable
private fun Pregunta(a: Apo, q: JsonObject, bloqueado: Boolean) {
    val id = q.s("id") ?: return
    val titulo = (q.s("texto") ?: id) + if (q.b("obligatoria") == true) "" else ""
    Caja {
        Text(titulo, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        when (q.s("tipo")) {
            "escala" -> {
                val mn = q.n("min")?.toInt() ?: 1; val mx = q.n("max")?.toInt() ?: 5
                val sel = a.resp[id] as? Int
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (v in mn..mx) Opcion(sel == v, "$v", Modifier.weight(1f)) { if (!bloqueado) { a.resp[id] = v; a.tick++ } }
                }
                sel?.let { q.o("etiquetas").s("$it")?.let { e -> Text(e, color = AMBAR, fontSize = 14.sp) } }
                    ?: Text("${q.o("etiquetas").s("$mn") ?: mn} … ${q.o("etiquetas").s("$mx") ?: mx}", color = GRIS, fontSize = 13.sp)
            }
            "si_no" -> {
                val sel = a.resp[id] as? Boolean
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Opcion(sel == true, "Sí", Modifier.weight(1f)) { if (!bloqueado) { a.resp[id] = true; a.tick++ } }
                    Opcion(sel == false, "No", Modifier.weight(1f)) { if (!bloqueado) { a.resp[id] = false; a.tick++ } }
                }
                // Señal de alarma: el texto se muestra en el momento, sin esperar a guardar.
                if (sel == true && a.estado.programa.o("alarma_inmediata").s("campo") == id) a.estado.textoAlarma()?.let { Nota(it, "alarma") }
            }
            "numero" -> OutlinedTextField(value = a.textos[id] ?: "", onValueChange = { if (!bloqueado) a.textos[id] = it.take(6) }, singleLine = true, readOnly = bloqueado,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { q.n("paso")?.let { p -> Text("De ${JsJson.numero(p.toString()).replace('.', ',')} en ${JsJson.numero(p.toString()).replace('.', ',')}") } })
            else -> {
                val max = q.n("max_caracteres")?.toInt()
                OutlinedTextField(value = a.textos[id] ?: "", onValueChange = { if (!bloqueado) a.textos[id] = if (max != null) it.take(max) else it }, readOnly = bloqueado,
                    modifier = Modifier.fillMaxWidth(), singleLine = (max ?: 999) <= 40,
                    supportingText = { if (max != null) Text("${(a.textos[id] ?: "").length}/$max") })
            }
        }
    }
}

@Composable
private fun ComidasV(a: Apo, bloqueado: Boolean) {
    val c = a.comidasDelDia()
    val cch = a.estado.cuentaComoHecha()
    Caja {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comidas", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${Comidas.TIPOS.count { c.detalle[it] in cch }} de 5", color = AMBAR, fontWeight = FontWeight.SemiBold)
        }
        if (c.origen == "app") {
            Gr("Salen de la pestaña Comidas. Si marcas una comida después de guardar, el reporte se actualiza solo hasta el plazo.")
            for (t in Comidas.TIPOS) Row {
                Text(Comidas.ETQ[t] ?: t, modifier = Modifier.weight(1f))
                Text(c.detalle[t]?.let { COMIDA_ETQ[it] } ?: "Sin marcar", color = if (c.detalle[t] in cch) VERDE else GRIS)
            }
        } else {
            Gr("No hay programa de alimentación para este día: márcalas aquí.")
            for (t in Comidas.TIPOS) {
                Text(Comidas.ETQ[t] ?: t, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((k, etq) in COMIDA_ETQ) Opcion(a.comidasManual[t] == k, etq, Modifier.weight(1f)) {
                        if (!bloqueado) { a.comidasManual[t] = if (a.comidasManual[t] == k) null else k; a.tick++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntrenoV(a: Apo, bloqueado: Boolean) {
    val pre = a.entreno ?: return
    Caja {
        Text("Entrenamiento", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Gr("Planificado: ${PLAN_ETQ[pre.planificado] ?: pre.planificado}" + (if (pre.origen == "app" && pre.sesion != null) " · Registrado en Gimnasio: ${pre.sesion}" else ""))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (h in Apoyo.HECHO) Opcion(a.hecho == h, PLAN_ETQ[h] ?: h, Modifier.weight(1f)) { if (!bloqueado) { a.hecho = h; a.tick++ } }
        }
        if (a.hecho != "nada" && a.hecho != "descanso") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = a.minutos, onValueChange = { if (!bloqueado) a.minutos = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Minutos") },
                singleLine = true, readOnly = bloqueado, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(value = a.rpe, onValueChange = { if (!bloqueado) a.rpe = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("RPE 1–10") },
                singleLine = true, readOnly = bloqueado, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Resumen(a: Apo) {
    val e = a.estado; val f = a.dia
    val previa = a.vistaPrevia()
    val reto = e.retoVigente(Apoyo.sabadoDe(f))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { a.vista = "reporte"; a.errores = emptyList(); a.resultado = emptyList() }) { Text("‹ Reporte del ${Comidas.fechaLarga(f)}", fontWeight = FontWeight.SemiBold) }
        Tit("Cierre de la semana")
        Gr("${Comidas.fechaLarga(Apoyo.sabadoDe(f))} a ${Comidas.fechaLarga(f)}")
        e.texto(if (reto == null) "resumen_sin_reto" else "resumen_listo")?.let { Nota(it, "info") }
        if (previa?.b("parcial") == true) e.texto("resumen_parcial")?.let { Nota(it, "info") }
        if (previa?.b("tardio") == true) Nota("Se guardará como tardío (después del sábado a las ${e.plazo(f).toLocalTime()}).", "info")
        if (previa != null) Caja {
            Fila("Días reportados", "${previa.n("dias_reportados")?.toInt()} de 7")
            Fila("Días mínimos", "${previa.n("dias_minimos")?.toInt()}")
            Fila("Racha", "${previa.o("racha").n("actual")?.toInt()} (mejor ${previa.o("racha").n("mejor")?.toInt()})")
            Fila("Comidas hechas", "${previa.o("comidas").n("hechas")?.toInt()} de 35")
            Fila("Días con agua", "${previa.n("agua_dias")?.toInt()}")
            val p = previa.o("promedios")
            fun pr(k: String) = p.n(k)?.let { JsJson.numero(it.toString()).replace('.', ',') } ?: "—"
            Fila("Energía · hambre · ánimo", "${pr("energia")} · ${pr("hambre")} · ${pr("animo")}")
        }
        if (reto != null) Caja {
            Etq("RETO DE ESTA SEMANA", VERDE); Text(reto, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((k, etq) in listOf("si" to "Cumplido", "parcial" to "A medias", "no" to "No")) Opcion(a.cumplido == k, etq, Modifier.weight(1f)) { a.cumplido = k }
            }
        }
        Caja {
            Text("¿Qué te costó más esta semana?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            for ((id, etq) in e.barreras()) Opcion(a.barrera == id, etq, Modifier.fillMaxWidth()) { a.barrera = id }
            OutlinedTextField(value = a.detalle, onValueChange = { a.detalle = it.take(280) }, label = { Text("Detalle (opcional)") }, modifier = Modifier.fillMaxWidth())
        }
        Caja {
            OutlinedTextField(value = a.funciono, onValueChange = { a.funciono = it.take(280) }, label = { Text("Lo que funcionó (opcional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = a.retoSiguiente, onValueChange = { a.retoSiguiente = it.take(140) }, label = { Text("Reto de la próxima semana") }, modifier = Modifier.fillMaxWidth())
        }
        for ((tipo, texto) in a.resultado) Nota(texto, tipo)
        if (a.errores.isNotEmpty()) Nota(a.errores.joinToString("\n") { "• $it" }, "err")
        Button(onClick = { a.guardarResumen() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AMBAR, contentColor = Color(0xFF231A0B))) { Text("Guardar resumen", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

@Composable private fun Fila(k: String, v: String) = Row { Text(k, color = GRIS, modifier = Modifier.weight(1f)); Text(v, fontWeight = FontWeight.SemiBold) }

@Composable
private fun BarraEnvio(a: Apo, acc: AccionesApoyo) {
    val n = a.estado.pendientes().size
    if (n == 0 && a.enviando.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(FONDO).border(1.dp, CAJA2).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (a.enviando.isNotEmpty()) a.enviando else "$n reporte${if (n == 1) "" else "s"} por enviar", fontSize = 14.sp)
            Text(if (a.faltaPermiso) "Toca Enviar una vez para dar permiso a Google; después se envía solo." else "Se envía solo al guardar; si no hay internet, queda aquí.", color = GRIS, fontSize = 12.sp)
        }
        Button(onClick = { acc.enviar() }, enabled = a.enviando.isEmpty() && n > 0, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VERDE, contentColor = Color(0xFF0E1F18))) { Text("Enviar", fontWeight = FontWeight.Bold) }
    }
}
