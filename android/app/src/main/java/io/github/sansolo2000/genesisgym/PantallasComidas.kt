package io.github.sansolo2000.genesisgym

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Etapa E5 — pantalla Comidas, equivalente a la de la 2.0 (alimentacion.js): programa, el día con sus 5 comidas,
 * marcar, foto, nota, intercambios, tareas y "Enviar a Alimentación". Usa los mismos documentos que la 2.0.
 */
class Com(val base: BaseLocal, private val alCambiar: () -> Unit) {
    var tick by mutableIntStateOf(0)
    var estado by mutableStateOf(EstadoComidas(null))
        private set
    var dia by mutableStateOf(Gimnasio.hoyIso())
    var verImport by mutableStateOf(false)
    var importacion by mutableStateOf<Pair<JsonObject?, Comidas.Resultado>?>(null)
    var eligiendo by mutableStateOf<String?>(null)
    var mensaje by mutableStateOf<Pair<String, String>?>(null)
    var enviando by mutableStateOf("")
    // 0.8.0: navegación nueva (día → hoja de una comida → cambiar) y envío automático
    var vista by mutableStateOf("dia")
    var sel by mutableStateOf<String?>(null)
    var aviso by mutableStateOf("")
    var faltaPermiso by mutableStateOf(false)
    /** La actividad intenta enviar lo pendiente sin preguntar (si Google ya dio el permiso). */
    var enviarAuto: () -> Unit = {}

    init { cargar() }

    fun cargar() {
        mensaje = try {
            val prog = base.get("alimentacion/programa_activo")?.obj("programa")
            val fotos = base.rutas("fotos").map { it.removePrefix("fotos/") }.toSet()
            estado = EstadoComidas(prog, base.get("alimentacion/intercambios"), base.coleccion("comidas"), fotos)
            dia = estado.elegirDia(Gimnasio.hoyIso())
            null
        } catch (e: Exception) { "err" to "No se pudo leer la base de este celular: ${e.javaClass.simpleName}" }
        tick++
    }

    private fun escribir(docs: List<Respaldo.Doc>) {
        try { if (docs.isNotEmpty()) base.ponerVarios(docs) } catch (e: Exception) { estado.mensaje = "err" to "No se pudo guardar: ${e.javaClass.simpleName}" }
        mensaje = estado.mensaje
        tick++
        if (docs.isNotEmpty()) alCambiar()
    }

    fun marcar(t: String, e: String) = escribir(estado.marcar(dia, t, e, Gimnasio.ahoraIso()))
    fun abrir(k: String) { sel = k; vista = "comida"; mensaje = null }
    /** Cierra la hoja de una comida y, si quedó algo listo para enviar, lo envía solo. */
    fun cerrarComida() { vista = "dia"; sel = null; enviarAuto() }
    fun marcarRapido(t: String) { marcar(t, "indicado"); aviso = "${Comidas.ETQ[t]} marcado ✓"; enviarAuto() }
    fun anotar(t: String, nota: String) { val d = estado.anotar(dia, t, nota, Gimnasio.ahoraIso()); estado.mensaje = mensaje; escribir(d) }
    fun ponerFoto(t: String, dataUrl: String) { val d = estado.ponerFoto(dia, t, dataUrl, Gimnasio.ahoraIso()); estado.mensaje = mensaje; escribir(d) }
    fun intercambiar(a: String, b: String) { eligiendo = null; escribir(estado.intercambiar(a, b, Gimnasio.ahoraIso())) }
    fun deshacer(k: String) = escribir(estado.deshacer(k, Gimnasio.ahoraIso()))
    fun moverDia(n: Long) { dia = Comidas.sumarDias(dia, n); mensaje = null; eligiendo = null }

    fun validar(texto: String) {
        importacion = Comidas.revisarTexto(texto.removePrefix("﻿"), estado.programa)
    }
    fun activar() {
        val (obj, r) = importacion ?: return
        if (obj == null || !r.ok) return
        try {
            base.ponerVarios(EstadoComidas.documentosActivacion(obj, Gimnasio.ahoraIso()))
            val mismo = (estado.programa?.get("programa") as? JsonObject)?.get("id") == (obj["programa"] as? JsonObject)?.get("id")
            cargar()
            // La 2.0 olvida los intercambios en memoria al cambiar de programa; al leerlos se filtran por programa_id.
            if (!mismo) estado.cambios.clear()
            importacion = null; verImport = false
            mensaje = "ok" to "Programa activado: ${Comidas.js((obj["programa"] as? JsonObject)?.get("nombre"))}."
            alCambiar()
        } catch (e: Exception) { mensaje = "err" to "No se pudo guardar el programa: ${e.javaClass.simpleName}" }
    }

    fun fotoBytes(k: String): ByteArray? = base.get("fotos/$k")?.txt("dataUrl")?.let { u -> runCatching { Base64.decode(u.substringAfter(','), Base64.DEFAULT) }.getOrNull() }

    /** Sube lo pendiente a Drive. [token] viene de AuthorizationClient (permiso drive.file). */
    suspend fun subir(token: String, app: String, automatico: Boolean = false) {
        if (enviando.isNotEmpty()) return
        faltaPermiso = false
        val archivos = estado.preparaEnvio(app, omitirSinFoto = automatico)
        if (archivos == null) { if (!automatico) mensaje = estado.mensaje; estado.mensaje = null; tick++; return }
        val comidas = archivos.count { it.mime == "application/json" }
        try {
            enviando = "Preparando carpeta…"
            val carpeta = DriveApi.carpetaPropia(token, Comidas.CARPETA)
            archivos.forEachIndexed { i, a ->
                enviando = "Subiendo ${i + 1} de ${archivos.size}…"
                val bytes = a.texto?.toByteArray(Charsets.UTF_8) ?: fotoBytes(a.clave.substringBefore('|')) ?: throw IllegalStateException("no se encontró la foto")
                val id = DriveApi.subirArchivo(token, carpeta, a.nombre, a.mime, bytes)
                estado.alListo(a.clave, id, Gimnasio.ahoraIso())?.let { base.put(it.ruta, it.data) }
            }
            estado.mensajeEnviado(comidas, archivos.size)
            if (automatico) { aviso = "Enviado a Alimentación ✓"; estado.mensaje = null } else mensaje = estado.mensaje
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to (if (automatico) "No se pudo enviar solo ($m). Queda pendiente: toca Enviar cuando tengas internet."
                else "No se pudo enviar a Drive: $m. Lo marcado queda guardado en el celular; vuelve a intentarlo con internet.")
        } finally { enviando = ""; tick++ }
    }
}

/** Lo que solo la actividad puede hacer: elegir archivos y fotos, y pedir el permiso de Drive. */
interface AccionesComidas {
    fun elegirPrograma()
    fun tomarFoto(tipo: String)
    fun elegirFoto(tipo: String)
    fun enviar()
}

@Composable private fun T(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
@Composable private fun S(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
@Composable private fun G(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun N(t: String, color: Color) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) { Text(t, modifier = Modifier.padding(12.dp)) }
@Composable private fun Caja(contenido: @Composable () -> Unit) =
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenido() } }
@Composable private fun Elegido(sel: Boolean, texto: String, onClick: () -> Unit) =
    if (sel) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(texto) } else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(texto) }

private fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
private fun JsonObject?.s(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

@Composable
fun PantallaComidas(c: Com, a: AccionesComidas) {
    @Suppress("UNUSED_VARIABLE") val t = c.tick
    val e = c.estado
    Box(Modifier.fillMaxSize()) {
        when {
            e.programa == null || c.verImport -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Comidas", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                c.mensaje?.let { (tipo, texto) -> N(texto, if (tipo == "err") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) }
                if (e.programa == null) Caja { S("Aún no hay programa"); G("El menú lo entrega el chat Alimentación en un archivo JSON. Impórtalo para ver aquí tus 5 comidas de cada día.") }
                ImportarPrograma(c, a)
                Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
            }
            c.vista == "comida" -> HojaComida(c, a)
            c.vista == "cambio" -> Cambiar(c)
            else -> Dia(c, a)
        }
        if (c.aviso.isNotEmpty()) {
            LaunchedEffect(c.aviso) { kotlinx.coroutines.delay(2600); c.aviso = "" }
            Text(c.aviso, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 56.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E3A2F)).border(1.dp, VERDE, RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }
}

@Composable
private fun ImportarPrograma(c: Com, a: AccionesComidas) = Caja {
    S("Importar programa de alimentación")
    G("Lo entrega el chat Alimentación (archivo programa-alimentacion-…json). Si tiene un solo error, se rechaza completo. En el selector puedes ir a Drive.")
    Button(onClick = { a.elegirPrograma() }) { Text("Elegir archivo .json") }
    c.importacion?.let { (obj, r) ->
        if (r.yaActivo) N("Este programa ya está activo. No hay nada que importar.", MaterialTheme.colorScheme.secondaryContainer)
        else if (!r.ok) {
            N("Programa rechazado. ${r.errores.size} error${if (r.errores.size == 1) "" else "es"}. Devuelve esta lista a Alimentación.", MaterialTheme.colorScheme.errorContainer)
            for (x in r.errores) Text("• $x")
        } else if (obj != null) {
            val p = obj.o("programa"); val p0 = (obj["perfiles"] as? JsonArray)?.firstOrNull() as? JsonObject
            N("Programa válido.", MaterialTheme.colorScheme.primaryContainer)
            Text("${p.s("nombre")}", fontWeight = FontWeight.SemiBold)
            G("${Comidas.fechaLarga(p.s("desde") ?: "")} a ${Comidas.fechaLarga(p.s("hasta") ?: "")} · ${(obj["dias"] as? JsonArray)?.size ?: 0} días · perfil ${p0.s("nombre")?.takeIf { it.isNotEmpty() } ?: Comidas.js(p0?.get("id"))}")
            if (r.avisos.isNotEmpty()) { N("Avisos (no bloquean):", MaterialTheme.colorScheme.secondaryContainer); for (x in r.avisos) Text("• $x") }
            Button(onClick = { c.activar() }) { Text("Activar este programa") }
        }
    }
    if (c.estado.programa != null) OutlinedButton(onClick = { c.verImport = false; c.importacion = null }) { Text("Cerrar") }
}

private fun kcalDe(e: EstadoComidas, comida: JsonObject): Double =
    ((e.preparaciones()[Comidas.js(comida["preparacion"])] as? JsonObject).o("aporte_estimado").o(e.pid)?.get("kcal") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else JsJson.numero(d.toString())


// ---------- 0.8.0: Comidas simplificada (mockup aprobado por Héctor el 11-oct) ----------
private val ESTILO_ESTADO = mapOf(
    "indicado" to Triple("✓ Listo", Color(0xFF0E1F18), VERDE),
    "otro" to Triple("◐ Otra cosa", Color(0xFF1A1300), AMBAR),
    "no-comi" to Triple("✕ No comí", TEXTO, Color(0xFF3A4046))
)
private val DIA_CORTO = listOf("dom", "lun", "mar", "mié", "jue", "vie", "sáb")

@Composable private fun EtiC(t: String, color: Color = GRIS) = Text(t, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
@Composable private fun ChipC(t: String) = Text(t, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(CAJA2).padding(horizontal = 10.dp, vertical = 5.dp))

@Composable
private fun Dia(c: Com, a: AccionesComidas) {
    val e = c.estado; val f = c.dia; val d = e.diaDe(f); val hoy = Gimnasio.hoyIso()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("Comidas", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(e.programa.o("programa").s("nombre") ?: "", color = GRIS, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            // días del programa
            val fechas = e.dias().mapNotNull { it.s("fecha") }.filter { Comidas.fechaOk(JsonPrimitive(it)) }.sorted()
            val estadoLista = rememberLazyListState(initialFirstVisibleItemIndex = (fechas.indexOf(f) - 1).coerceAtLeast(0))
            LazyRow(state = estadoLista, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(fechas) { x ->
                    val sel = x == f
                    val ld = runCatching { LocalDate.parse(x) }.getOrNull()
                    val n = Comidas.TIPOS.count { e.comidaDe(x, it) != null && e.estadoDe(Comidas.clave(x, it)) != null }
                    val total = Comidas.TIPOS.count { e.comidaDe(x, it) != null }
                    Column(Modifier.width(64.dp).heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) AMBAR else CAJA)
                        .border(1.dp, if (x == hoy && !sel) AMBAR else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { c.dia = x; c.mensaje = null }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val tinta = if (sel) Color(0xFF1A1300) else TEXTO
                        Text((ld?.let { DIA_CORTO[it.dayOfWeek.value % 7] } ?: "") + if (x == hoy) " · hoy" else "", fontSize = 11.sp, color = tinta)
                        Text("${ld?.dayOfMonth ?: ""}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tinta)
                        Text("●".repeat(n) + "○".repeat((total - n).coerceAtLeast(0)), fontSize = 9.sp, color = if (n == total && total > 0) VERDE else if (sel) tinta else GRIS)
                    }
                }
            }
            c.mensaje?.let { (tipo, texto) -> N(texto, when (tipo) { "err" -> MaterialTheme.colorScheme.errorContainer; "ok" -> MaterialTheme.colorScheme.secondaryContainer; else -> CAJA2 }) }
            if (d == null) { Caja { G("El programa no trae menú para este día.") } }
            else {
                val comidas = Comidas.TIPOS.mapNotNull { e.comidaDe(f, it) }
                val proxima = comidas.firstOrNull { e.estadoDe(Comidas.clave(f, Comidas.js(it["tipo"]))) == null }
                proxima?.let { p ->
                    val t = Comidas.js(p["tipo"]); val pr = e.preparaciones()[Comidas.js(p["preparacion"])] as? JsonObject
                    val porc = (pr.o("porciones")?.get(e.pid) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF22262A)).border(1.dp, AMBAR, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EtiC((if (f == hoy) "PRÓXIMA · " else "") + "${Comidas.ETQ[t]?.uppercase()} · ${p.s("hora")}", AMBAR)
                        Text(pr.s("nombre") ?: Comidas.js(p["preparacion"]), fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                        Text(porc.joinToString(" · ") { "${Comidas.js(it["alimento"])} ${Comidas.js(it["gramos"])} g" } +
                            (p.s("conservacion")?.takeIf { it.isNotEmpty() }?.let { " · desde el $it" } ?: ""), color = GRIS, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(onClick = { c.marcarRapido(t) }, modifier = Modifier.weight(3f).heightIn(min = 52.dp)) { Text("✓ Comí lo indicado", fontWeight = FontWeight.SemiBold) }
                            OutlinedButton(onClick = { c.abrir(Comidas.clave(f, t)) }, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) { Text("Otra cosa…", color = TEXTO) }
                        }
                    }
                }
                val marcadas = comidas.count { e.estadoDe(Comidas.clave(f, Comidas.js(it["tipo"]))) != null }
                val kcalInd = comidas.sumOf { if (e.estadoDe(Comidas.clave(f, Comidas.js(it["tipo"]))) == "indicado") kcalDe(e, it) else 0.0 }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("$marcadas de ${comidas.size} comidas marcadas", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("≈ ${num(kcalInd)} kcal indicadas", color = GRIS, fontSize = 13.sp)
                }
                // la lista del día: comidas y tareas por hora
                val filas = comidas.map { (it.s("hora") ?: "") to it } + ((d["tareas"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { (it.s("hora") ?: "") to it } ?: emptyList())
                for ((hora, x) in filas.sortedBy { it.first }) {
                    if (x.containsKey("texto") && !x.containsKey("tipo")) FilaTarea(hora, x.s("texto") ?: "")
                    else FilaComida(c, f, x, x === proxima)
                }
                val restr = (e.programa?.get("restricciones") as? JsonArray)?.map { Comidas.js(it) } ?: emptyList()
                if (restr.isNotEmpty()) Text("Restricciones: " + restr.joinToString(" · "), color = GRIS, fontSize = 12.sp)
                Text("Las kcal son estimaciones del programa.", color = GRIS, fontSize = 12.sp)
            }
            TextButton(onClick = { c.verImport = true; c.importacion = null; c.mensaje = null }) { Text("Importar otro programa") }
        }
        BarraEnvio(c, a)
    }
}

@Composable
private fun FilaComida(c: Com, f: String, comida: JsonObject, esProxima: Boolean) {
    val e = c.estado; val t = Comidas.js(comida["tipo"]); val k = Comidas.clave(f, t)
    val est = e.estadoDe(k); val estilo = est?.let { ESTILO_ESTADO[it] }
    val pr = e.preparaciones()[Comidas.js(comida["preparacion"])] as? JsonObject
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(CAJA)
        .border(1.dp, if (esProxima) AMBAR else Color.Transparent, RoundedCornerShape(12.dp)).clickable { c.abrir(k) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(comida.s("hora") ?: "", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (est != null) GRIS else TEXTO, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text((Comidas.ETQ[t] ?: t) + if (comida["intercambio"] != null) " · ⇄ cambiada" else "", color = GRIS, fontSize = 12.sp)
            Text(pr.s("nombre") ?: Comidas.js(comida["preparacion"]), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (estilo != null) Text(estilo.first, color = estilo.second, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(estilo.third).padding(horizontal = 8.dp, vertical = 4.dp))
        else if (esProxima) Text("Ahora", color = AMBAR, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilaTarea(hora: String, texto: String) =
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15181A)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(hora, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AZUL, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) { Text("Tarea de cocina", color = GRIS, fontSize = 12.sp); Text(texto, fontSize = 15.sp) }
    }

@Composable
private fun BarraEnvio(c: Com, a: AccionesComidas) {
    val e = c.estado
    val n = e.pendientes().size
    if (n == 0 && c.enviando.isEmpty()) return
    val sinFoto = n - e.listosParaEnviar()
    Row(Modifier.fillMaxWidth().background(FONDO).border(1.dp, CAJA2).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (c.enviando.isNotEmpty()) c.enviando else "$n comida${if (n == 1) "" else "s"} por enviar", fontSize = 14.sp)
            Text(when {
                c.faltaPermiso -> "Toca Enviar una vez para dar permiso a Google; después se envía solo."
                sinFoto > 0 -> "$sinFoto sin foto: se envía${if (sinFoto == 1) "" else "n"} cuando la agregues."
                else -> "Se envía solo al marcar; si no hay internet, queda aquí."
            }, color = GRIS, fontSize = 12.sp)
        }
        Button(onClick = { a.enviar() }, enabled = c.enviando.isEmpty() && n > 0, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VERDE, contentColor = Color(0xFF0E1F18))) { Text("Enviar", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun HojaComida(c: Com, a: AccionesComidas) {
    val e = c.estado; val k = c.sel ?: run { c.vista = "dia"; return }
    val (f, t) = Comidas.partir(k)
    val comida = e.comidaDe(f, t) ?: run { c.vista = "dia"; return }
    val pr = e.preparaciones()[Comidas.js(comida["preparacion"])] as? JsonObject
    val porc = (pr.o("porciones")?.get(e.pid) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val ap = pr.o("aporte_estimado").o(e.pid)
    val est = e.estadoDe(k)
    val x = comida["intercambio"] as? JsonObject
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c.cerrarComida() }) { Text("‹ ${Comidas.fechaLarga(f)}", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            if (est != null) Text(if (e.enviado(k)) "Enviada" else "Por enviar", color = if (e.enviado(k)) VERDE else AMBAR, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EtiC("${Comidas.ETQ[t]?.uppercase()} · ${comida.s("hora")}", AMBAR)
            Text(pr.s("nombre") ?: Comidas.js(comida["preparacion"]), fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ap?.let { ChipC("≈ ${Comidas.js(it["kcal"])} kcal"); ChipC("${Comidas.js(it["proteina_g"])} g proteína") }
                comida.s("conservacion")?.takeIf { it.isNotEmpty() }?.let { ChipC("desde el $it") }
            }
            pr.s("notas")?.takeIf { it.isNotEmpty() }?.let { Text(it, color = GRIS, fontSize = 14.sp) }
            if (x != null) {
                val desde = Comidas.js(x["desde"])
                val original = e.comidaPlan(f, t)?.let { (e.preparaciones()[Comidas.js(it["preparacion"])] as? JsonObject).s("nombre") } ?: ""
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2733)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("⇄ Cambiada con ${e.etiquetaLugar(desde)}. Aquí iba $original.", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (Comidas.js(e.cambios[desde]?.get("desde")) == k) TextButton(onClick = { c.deshacer(k) }) { Text("Deshacer") }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CAJA).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EtiC("PORCIÓN")
                if (porc.isEmpty()) G("Sin porción para este perfil.")
                for (p in porc) Row(Modifier.fillMaxWidth()) {
                    Text(Comidas.js(p["alimento"]) + (p.s("medida")?.let { " ($it)" } ?: ""), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${Comidas.js(p["gramos"])} g", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            EtiC("¿CÓMO TE FUE?")
            val opciones = listOf(Triple("indicado", "Comí lo indicado", "✓"), Triple("otro", "Comí otra cosa o una parte", "◐"), Triple("no-comi", "No comí", "✕"))
            for ((v, texto, icono) in opciones) {
                val sel = est == v; val col = ESTILO_ESTADO[v]!!.third
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) Color(0xFF22262A) else CAJA)
                    .border(2.dp, if (sel) col else CAJA2, RoundedCornerShape(12.dp)).clickable { c.marcar(t, v) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (v == "no-comi") GRIS else col), contentAlignment = Alignment.Center) {
                        Text(icono, color = FONDO, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (est == "otro") {
                val tiene = k in e.fotos
                Column(Modifier.fillMaxWidth().border(1.dp, AMBAR, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Alimentación necesita una foto para estimar lo que comiste.", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { a.tomarFoto(t) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (tiene) "Otra foto" else "Tomar foto", color = TEXTO) }
                        OutlinedButton(onClick = { a.elegirFoto(t) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Galería", color = TEXTO) }
                    }
                    if (tiene) {
                        val bmp = remember(k, e.registros[k]?.get("version")) { c.fotoBytes(k)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Foto de ${Comidas.ETQ[t]}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
                    }
                    val guardada = e.registros[k].s("nota") ?: ""
                    var nota by remember(k) { mutableStateOf(guardada) }
                    OutlinedTextField(value = nota, onValueChange = { nota = it }, label = { Text("Nota (opcional): qué y cuánto comiste") }, minLines = 2,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { fs -> if (!fs.isFocused && nota != (e.registros[k].s("nota") ?: "") && (nota.isNotEmpty() || e.registros[k]?.get("nota") != null)) c.anotar(t, nota) })
                    if (nota != guardada) OutlinedButton(onClick = { c.anotar(t, nota) }) { Text("Guardar nota") }
                }
            }
            OutlinedButton(onClick = { c.vista = "cambio" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("⇄ Cambiar por otra comida del programa", color = TEXTO) }
        }
        Button(onClick = { c.cerrarComida() }, modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (est == "otro" && k !in e.fotos) "Listo (la foto puede ir después)" else "Listo", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Cambiar(c: Com) {
    val e = c.estado; val k = c.sel ?: run { c.vista = "dia"; return }
    val (fk, tk) = Comidas.partir(k)
    fun orden(f: String) = if (f == fk) "0" else (if (f > fk) "1" else "2") + f
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { c.vista = "comida" }, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) { Text("‹ Volver", fontWeight = FontWeight.SemiBold) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cambiar ${Comidas.ETQ[tk]?.lowercase()} del ${Comidas.fechaLarga(fk)}", fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            G("Elige con cuál. Las dos quedan cambiadas; la hora de cada una no cambia.")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (d in e.dias().sortedBy { orden(Comidas.js(it["fecha"])) }) {
                val f = Comidas.js(d["fecha"])
                val op = Comidas.TIPOS.mapNotNull { e.comidaDe(f, it) }.filter { Comidas.clave(f, Comidas.js(it["tipo"])) != k }
                if (op.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EtiC(Comidas.fechaLarga(f).uppercase() + when { f == fk -> " · MISMO DÍA"; f < fk -> " · DÍA ANTERIOR"; else -> "" })
                    for (o in op) {
                        val t = Comidas.js(o["tipo"])
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(CAJA)
                            .border(1.dp, if (t == tk) LINEA else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { c.intercambiar(k, Comidas.clave(f, t)); c.vista = "comida"; c.aviso = "Cambiadas ✓" }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Comidas.ETQ[t] ?: t, color = GRIS, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                            Text((e.preparaciones()[Comidas.js(o["preparacion"])] as? JsonObject).s("nombre") ?: Comidas.js(o["preparacion"]), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
