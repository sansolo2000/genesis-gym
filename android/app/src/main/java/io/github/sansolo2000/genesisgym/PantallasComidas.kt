package io.github.sansolo2000.genesisgym

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
    suspend fun subir(token: String, app: String) {
        val archivos = estado.preparaEnvio(app)
        if (archivos == null) { mensaje = estado.mensaje; tick++; return }
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
            mensaje = estado.mensaje
        } catch (e: Exception) {
            val m = if (e is DriveApi.ErrorDrive) "Drive respondió ${e.codigo}" else (e.message ?: e.javaClass.simpleName)
            mensaje = "err" to "No se pudo enviar a Drive: $m. Lo marcado queda guardado en el celular; vuelve a intentarlo con internet."
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
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        N("Versión de prueba: tus comidas reales se siguen marcando en Génesis Gym 2.0.", MaterialTheme.colorScheme.tertiaryContainer)
        G("Alimentación"); T("Comidas")
        c.mensaje?.let { (tipo, texto) -> N(texto, when (tipo) { "err" -> MaterialTheme.colorScheme.errorContainer; "ok" -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.secondaryContainer }) }
        if (e.programa == null) {
            Caja { S("Aún no hay programa"); G("El menú lo entrega el chat Alimentación en un archivo JSON. Impórtalo para ver aquí tus 5 comidas de cada día.") }
            ImportarPrograma(c, a)
        } else if (c.verImport) ImportarPrograma(c, a)
        else Dia(c, a)
        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun Dia(c: Com, a: AccionesComidas) {
    val e = c.estado; val f = c.dia; val d = e.diaDe(f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { c.moverDia(-1) }, enabled = f > e.desde()) { Text("‹") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Comidas.fechaLarga(f) + if (f == Gimnasio.hoyIso()) " · hoy" else "", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            G(if (d != null) d.s("etiqueta") ?: "" else "Sin menú para este día")
        }
        OutlinedButton(onClick = { c.moverDia(1) }, enabled = f < e.hasta()) { Text("›") }
    }
    val restr = (e.programa?.get("restricciones") as? JsonArray)?.map { Comidas.js(it) } ?: emptyList()
    if (restr.isNotEmpty()) N("Restricciones: " + restr.joinToString(" · "), MaterialTheme.colorScheme.secondaryContainer)
    if (d == null) { Caja { G("El programa no trae menú para este día.") } }
    else {
        val comidas = Comidas.TIPOS.mapNotNull { e.comidaDe(f, it) }
        val estados = comidas.map { e.estadoDe(Comidas.clave(f, Comidas.js(it["tipo"]))) }
        val kcalInd = comidas.indices.sumOf { if (estados[it] == "indicado") kcalDe(e, comidas[it]) else 0.0 }
        val conCambios = comidas.any { it["intercambio"] != null }
        val otras = estados.count { it == "otro" }
        val tot = if (conCambios) num(comidas.sumOf { kcalDe(e, it) }) else d.o("total_estimado_kcal")?.get(e.pid)?.let { Comidas.js(it) } ?: "—"
        val meta = e.perfil?.get("meta_kcal")?.takeIf { Comidas.verdadero(it) }?.let { Comidas.js(it) } ?: "—"
        Caja {
            Text("${estados.count { it != null }} de ${comidas.size} comidas marcadas · plan del día ≈ $tot kcal${if (conCambios) " con intercambios" else ""} (meta $meta)", fontWeight = FontWeight.SemiBold)
            G("Lo indicado que comiste suma ≈ ${num(kcalInd)} kcal${if (otras > 0) " · $otras comida${if (otras == 1) "" else "s"} por revisar en Alimentación" else ""}. Son estimaciones del programa.")
        }
        for (comida in comidas) key(f + Comidas.js(comida["tipo"])) { Comida(c, a, f, comida) }
        val tareas = (d["tareas"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        if (tareas.isNotEmpty()) Caja { S("Tareas del día"); for (x in tareas) Text("${x.s("hora")} · ${x.s("texto") ?: ""}") }
    }
    val n = e.pendientes().size
    Caja {
        S("Enviar a Alimentación")
        G((if (n > 0) "$n comida${if (n == 1) "" else "s"} por enviar." else "Todo lo marcado ya fue enviado.") + " Se sube a tu Drive, carpeta \"${Comidas.CARPETA}\". Necesita internet.")
        Button(onClick = { a.enviar() }, enabled = n > 0 && c.enviando.isEmpty()) { Text(c.enviando.ifEmpty { "Enviar a Alimentación" + if (n > 0) " ($n)" else "" }) }
    }
    val p = e.programa.o("programa")
    G("Programa: ${p.s("nombre")} · ${Comidas.fechaLarga(e.desde())} a ${Comidas.fechaLarga(e.hasta())}")
    OutlinedButton(onClick = { c.verImport = true; c.importacion = null; c.mensaje = null }) { Text("Importar otro programa") }
}

@Composable
private fun Comida(c: Com, a: AccionesComidas, f: String, comida: JsonObject) {
    val e = c.estado
    val tipo = Comidas.js(comida["tipo"]); val k = Comidas.clave(f, tipo)
    val pr = e.preparaciones()[Comidas.js(comida["preparacion"])] as? JsonObject
    val porc = (pr.o("porciones")?.get(e.pid) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    val ap = pr.o("aporte_estimado").o(e.pid)
    val est = e.estadoDe(k)
    val x = comida["intercambio"] as? JsonObject
    Caja {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            G("${comida.s("hora")} · ${Comidas.ETQ[tipo] ?: tipo}")
            if (est != null) Text(if (e.enviado(k)) "Enviado" else "Por enviar", style = MaterialTheme.typography.labelMedium,
                color = if (e.enviado(k)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        }
        S(pr.s("nombre") ?: Comidas.js(comida["preparacion"]))
        if (porc.isEmpty()) G("Sin porción para este perfil.")
        for (p in porc) Text("• ${Comidas.js(p["alimento"])}: ${Comidas.js(p["gramos"])} g" + (p.s("medida")?.let { " ($it)" } ?: ""))
        val linea = listOfNotNull(
            ap?.let { "≈ ${Comidas.js(it["kcal"])} kcal · ${Comidas.js(it["proteina_g"])} g proteína" },
            comida.s("conservacion")?.takeIf { it.isNotEmpty() }?.let { "desde el $it" },
            pr.s("notas")?.takeIf { it.isNotEmpty() })
        if (linea.isNotEmpty()) G(linea.joinToString(" · "))
        if (x != null) {
            val desde = Comidas.js(x["desde"])
            val original = e.comidaPlan(f, tipo)?.let { (e.preparaciones()[Comidas.js(it["preparacion"])] as? JsonObject).s("nombre") } ?: ""
            N("Intercambiada con: ${e.etiquetaLugar(desde)}. En el plan original aquí iba $original.", MaterialTheme.colorScheme.secondaryContainer)
            if (Comidas.js(e.cambios[desde]?.get("desde")) == k) OutlinedButton(onClick = { c.deshacer(k) }) { Text("Deshacer intercambio") }
        }
        for ((v, texto) in Comidas.ESTADOS) Elegido(est == v, texto) { c.marcar(tipo, v) }
        if (c.eligiendo == k) Elegir(c, k) else OutlinedButton(onClick = { c.eligiendo = k; c.mensaje = null }, modifier = Modifier.fillMaxWidth()) { Text("Intercambiar con otra comida…") }
        if (est == "otro") {
            val tiene = k in e.fotos
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { a.tomarFoto(tipo) }) { Text(if (tiene) "Otra foto" else "Tomar foto") }
                OutlinedButton(onClick = { a.elegirFoto(tipo) }) { Text("Elegir de la galería") }
            }
            if (tiene) {
                val bmp = remember(k, e.registros[k]?.get("version")) { c.fotoBytes(k)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                bmp?.let { Image(it.asImageBitmap(), contentDescription = "Foto de ${Comidas.ETQ[tipo]}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
            } else N("Falta la foto: Alimentación la necesita para estimar lo que comiste.", MaterialTheme.colorScheme.errorContainer)
            val guardada = e.registros[k].s("nota") ?: ""
            var nota by remember(k) { mutableStateOf(guardada) }
            OutlinedTextField(value = nota, onValueChange = { nota = it }, label = { Text("Nota (opcional): qué y cuánto comiste") }, minLines = 2,
                modifier = Modifier.fillMaxWidth().onFocusChanged { fs -> if (!fs.isFocused && nota != (e.registros[k].s("nota") ?: "") && (nota.isNotEmpty() || e.registros[k]?.get("nota") != null)) c.anotar(tipo, nota) })
            if (nota != guardada) OutlinedButton(onClick = { c.anotar(tipo, nota) }) { Text("Guardar nota") }
        }
    }
}

@Composable
private fun Elegir(c: Com, k: String) {
    val e = c.estado
    // Primero este día, después los siguientes y al final los anteriores (lo más probable es intercambiar hacia adelante).
    fun orden(f: String) = if (f == c.dia) "0" else (if (f > c.dia) "1" else "2") + f
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("¿Con qué comida la intercambias?", fontWeight = FontWeight.SemiBold)
            G("Ambas quedan cambiadas: aquí comerás lo de la otra, y allá lo de esta. La hora de cada comida no cambia.")
            for (d in e.dias().sortedBy { orden(Comidas.js(it["fecha"])) }) {
                val f = Comidas.js(d["fecha"])
                val op = Comidas.TIPOS.mapNotNull { e.comidaDe(f, it) }.filter { Comidas.clave(f, Comidas.js(it["tipo"])) != k }
                if (op.isEmpty()) continue
                G(Comidas.fechaLarga(f) + when { f == c.dia -> " · este día"; f < c.dia -> " · día anterior"; else -> "" })
                for (o in op) {
                    val t = Comidas.js(o["tipo"])
                    OutlinedButton(onClick = { c.intercambiar(k, Comidas.clave(f, t)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${Comidas.ETQ[t]} · ${(e.preparaciones()[Comidas.js(o["preparacion"])] as? JsonObject).s("nombre") ?: Comidas.js(o["preparacion"])}", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            OutlinedButton(onClick = { c.eligiendo = null }) { Text("Cancelar") }
        }
    }
}
