package io.github.sansolo2000.genesisgym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sansolo2000.genesisgym.Gimnasio.arr
import io.github.sansolo2000.genesisgym.Gimnasio.con
import io.github.sansolo2000.genesisgym.Gimnasio.esNulo
import io.github.sansolo2000.genesisgym.Gimnasio.num
import io.github.sansolo2000.genesisgym.Gimnasio.obj
import io.github.sansolo2000.genesisgym.Gimnasio.txt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Etapa E4 — pantallas del gimnasio (Hoy, Sesión, Rutina, Historial, Importar), equivalentes a las de la 1.0/2.0.
 * Los datos son los mismos documentos de la 2.0, guardados en BaseLocal.
 */
class Gym(val base: BaseLocal, val imagenes: Imagenes?, private val alCambiar: () -> Unit) {
    var vista by mutableStateOf("hoy")
    var borrador by mutableStateOf<JsonObject?>(null)
    var guardado by mutableStateOf("")
    var guardadoError by mutableStateOf(false)
    var inicioApertura: Long? = null
    var ficha by mutableStateOf<String?>(null)
    var errorCierre by mutableStateOf("")
    var confirmarBorrado by mutableStateOf(false)
    var editarFecha by mutableStateOf(false)
    var errorFecha by mutableStateOf("")
    var importacion by mutableStateOf<Gimnasio.Importacion?>(null)
    var importTexto by mutableStateOf("")
    var importMensaje by mutableStateOf("")
    var csvMensaje by mutableStateOf("")
    var respaldoMensaje by mutableStateOf("")
    var respaldadoAhora by mutableStateOf(false)
    var restaurar by mutableStateOf<Respaldo.Leido?>(null)
    var restaurarConfirmar by mutableStateOf(false)
    var tick by mutableIntStateOf(0)
    var editarPerfil by mutableStateOf(false)

    // 0.6.0: sesión por mapa → hoja por ejercicio → descanso → cierre
    var sesVista by mutableStateOf("mapa")
    var idxEj by mutableIntStateOf(0)
    var verFicha by mutableStateOf(false)
    var descansoFin by mutableStateOf<Long?>(null)
    var descansoTotal by mutableIntStateOf(0)
    var descansoDe by mutableStateOf<Pair<Int, Int>?>(null)
    var aviso by mutableStateOf("")
    /** La actividad programa (fin en ms, texto) o cancela (null) el aviso de fin de descanso. */
    var alDescanso: (Long?, String) -> Unit = { _, _ -> }

    fun refrescar() { tick++; alCambiar() }

    // ---------- datos ----------
    fun rutinaActiva(): JsonObject? = base.get("config/rutina_activa")
    fun rutina(): JsonObject? = rutinaActiva()?.obj("rutina")
    fun sesiones(): List<JsonObject> = Gimnasio.ordenarSesiones(base.coleccion("sesiones").map { it.data })
    fun perfil(): String? = base.get("config/perfil")?.txt("nombre")
    fun ultimoRespaldo(): String? = base.get("config/respaldo")?.txt("ultimo")

    fun guardarPerfil(nombre: String) {
        val n = nombre.trim(); if (n.isEmpty()) return
        base.put("config/perfil", buildJsonObject { put("nombre", n); put("creado", Gimnasio.ahoraIso()) }); editarPerfil = false; refrescar()
    }

    fun abrirSesion(fecha: String, sesionId: String) {
        val existente = sesiones().firstOrNull { it.txt("fecha") == fecha && it.txt("sesion_id") == sesionId }
        borrador = existente ?: run {
            val r = rutina() ?: return
            val ses = r.arr("sesiones").firstOrNull { it.txt("id") == sesionId } ?: return
            Gimnasio.nuevoBorrador(fecha, ses, r, Gimnasio.ahoraIso())
        }
        inicioApertura = if (existente == null) System.currentTimeMillis() else null
        guardado = if (existente != null) "Guardado ✓" else "Sin guardar: se guarda al anotar la primera serie"
        guardadoError = false; errorCierre = ""; confirmarBorrado = false; editarFecha = false; errorFecha = ""
        sesVista = "mapa"; verFicha = false; aviso = ""; cancelarDescanso()
        idxEj = Gimnasio.primerPendiente(borrador!!).coerceAtLeast(0)
        vista = "sesion"
    }

    fun guardar(nuevo: JsonObject) {
        val b = nuevo.con("actualizado", JsonPrimitive(Gimnasio.ahoraIso()))
        borrador = b
        try {
            base.put("sesiones/" + Gimnasio.docIdSesion(b.txt("fecha")!!, b.txt("sesion_id")!!), b)
            guardado = "Guardado ✓"; guardadoError = false
        } catch (e: Exception) { guardado = "No se pudo guardar. Vuelve a intentarlo."; guardadoError = true }
        refrescar()
    }

    fun ir(v: String) { if (vista == "sesion" && v != "sesion") { borrador = null; cancelarDescanso() }; vista = v; confirmarBorrado = false; errorCierre = ""; editarFecha = false }

    // ---------- 0.6.0: hoja y descanso ----------
    fun abrirEjercicio(i: Int) { idxEj = i; verFicha = false; sesVista = "hoja" }

    /** ✓ de una serie: si no tiene valores, copia lo indicado (como "= Indicado"); después parte el descanso. */
    fun marcarSerie(i: Int, k: Int) {
        val b = borrador ?: return
        val se = b.arr("ejercicios")[i].arr("series")[k]
        if (!Gimnasio.registrada(se)) {
            val copia = Gimnasio.copiarIndicado(b, i, k)
            if (!Gimnasio.registrada(copia.arr("ejercicios")[i].arr("series")[k])) {
                aviso = "Escribe las repeticiones (y la carga) de la serie ${k + 1} antes de marcarla."; return
            }
            guardar(copia)
        }
        aviso = ""
        iniciarDescanso(i, k)
    }

    fun siguienteDe(i: Int, k: Int): Pair<Int, Int>? {
        val ejs = borrador?.arr("ejercicios") ?: return null
        if (k + 1 < ejs[i].arr("series").size) return i to k + 1
        return if (i + 1 < ejs.size) i + 1 to 0 else null
    }

    fun iniciarDescanso(i: Int, k: Int) {
        val ej = borrador?.arr("ejercicios")?.getOrNull(i) ?: return
        val seg = ej.num("descanso_seg")?.toInt() ?: 0
        if (seg <= 0) { if (k == ej.arr("series").size - 1) aviso = "Ejercicio listo. Toca \"Siguiente\" cuando quieras."; return }
        descansoTotal = seg; descansoDe = i to k
        descansoFin = System.currentTimeMillis() + seg * 1000L
        sesVista = "descanso"
        alDescanso(descansoFin, textoSiguiente())
    }

    fun textoSiguiente(): String {
        val (i, k) = descansoDe ?: return ""
        val ejs = borrador?.arr("ejercicios") ?: return ""
        val sig = siguienteDe(i, k) ?: return "Pasa al cierre de la sesión."
        val se = ejs[sig.first].arr("series")[sig.second]
        return if (sig.first == i) "Serie ${sig.second + 1}: ${Gimnasio.prescritoTexto(se)}" else "Siguiente: ${ejs[sig.first].txt("nombre")}"
    }

    fun masTreinta() { val f = descansoFin ?: return; descansoFin = f + 30_000; descansoTotal += 30; alDescanso(descansoFin, textoSiguiente()) }

    fun cancelarDescanso() { if (descansoFin != null) alDescanso(null, ""); descansoFin = null }

    /** Fin del descanso (por tiempo o tocando "Terminar"): vuelve a la hoja, en el ejercicio que sigue. */
    fun terminarDescanso(porTiempo: Boolean) {
        val de = descansoDe
        if (!porTiempo) alDescanso(null, "")
        descansoFin = null
        val sig = de?.let { siguienteDe(it.first, it.second) }
        if (de != null && sig == null) { sesVista = "cierre"; return }
        if (sig != null && sig.first != idxEj) { idxEj = sig.first; verFicha = false }
        sesVista = "hoja"
        if (porTiempo) aviso = "Descanso terminado."
    }

    fun cerrarSesion() {
        val b = borrador ?: return
        if (b.esNulo("rpe_sesion") || b.esNulo("duracion_min")) { errorCierre = "Para cerrar, indica el RPE de la sesión y la duración en minutos."; return }
        errorCierre = ""; guardar(b.con("estado", JsonPrimitive("cerrada")))
    }

    fun borrarSesion() {
        val b = borrador ?: return
        base.borrar("sesiones/" + Gimnasio.docIdSesion(b.txt("fecha")!!, b.txt("sesion_id")!!)); refrescar(); ir("historial")
    }

    fun cambiarFecha(nueva: String) {
        val b = borrador ?: return
        val todas = sesiones()
        if (nueva == b.txt("fecha")) { editarFecha = false; return }
        Gimnasio.errorCambioFecha(nueva, Gimnasio.hoyIso(), b, todas)?.let { errorFecha = it; return }
        val vieja = b.txt("fecha")!!
        val guardada = todas.any { it.txt("fecha") == vieja && it.txt("sesion_id") == b.txt("sesion_id") }
        if (!guardada) { borrador = b.con("fecha", JsonPrimitive(nueva)).con("dia_semana", JsonPrimitive(Gimnasio.diaDe(nueva))); editarFecha = false; return }
        val copia = Gimnasio.conFechaCambiada(b, nueva, Gimnasio.ahoraIso())
        base.ponerVarios(listOf(Respaldo.Doc("sesiones/" + Gimnasio.docIdSesion(nueva, b.txt("sesion_id")!!), copia)))
        base.borrar("sesiones/" + Gimnasio.docIdSesion(vieja, b.txt("sesion_id")!!))
        borrador = copia; guardado = "Fecha cambiada ✓"; editarFecha = false; errorFecha = ""; refrescar()
    }

    fun validarImport(texto: String) {
        importTexto = texto; importMensaje = ""
        if (texto.isBlank()) { importacion = null; importMensaje = "No hay nada que validar: elige el archivo o pega el contenido completo."; return }
        importacion = Gimnasio.revisarImportacion(texto, rutinaActiva())
    }

    fun activar() {
        val im = importacion ?: return
        val obj = im.rutina ?: return
        if (!im.resultado.ok) return
        try { base.ponerVarios(Gimnasio.documentosActivacion(obj, Gimnasio.ahoraIso())); importMensaje = "Rutina activada."; refrescar() }
        catch (e: Exception) { importMensaje = "No se pudo guardar la rutina. Vuelve a intentarlo." }
    }

    fun textoCsv(): String = "﻿" + Gimnasio.csv(sesiones())

    fun textoRespaldo(): Pair<String, String> {
        val docs = base.todos()
        return Respaldo.nombreArchivo(perfil(), Gimnasio.hoyIso()) to Respaldo.crear(docs, perfil(), BuildConfig.VERSION_NAME, Gimnasio.ahoraIso())
    }

    fun respaldoGuardado(nombre: String) {
        val ahora = Gimnasio.ahoraIso()
        base.put("config/respaldo", buildJsonObject { put("ultimo", ahora); put("archivo", nombre); put("documentos", base.todos().size) })
        respaldadoAhora = true
        respaldoMensaje = "Respaldo creado: $nombre. Guárdalo fuera del celular (por ejemplo, en Drive)."
        refrescar()
    }

    fun leerRespaldo(texto: String) {
        restaurar = null; restaurarConfirmar = false
        if (texto.isBlank()) { respaldoMensaje = "El archivo llegó vacío al celular."; return }
        when (val r = Respaldo.leer(texto)) {
            is Respaldo.Resultado.Error -> respaldoMensaje = r.mensaje
            is Respaldo.Resultado.Ok -> { restaurar = r.leido; respaldoMensaje = "" }
        }
    }

    fun aplicarRestauracion() {
        val r = restaurar ?: return
        try {
            base.reemplazarTodo(r.docs)
            if (Respaldo.huella(base.todos()) != Respaldo.huella(r.docs)) throw IllegalStateException("verificación")
            respaldoMensaje = "Restauración completa: ${r.docs.size} documentos, huella verificada."
            restaurar = null; restaurarConfirmar = false; refrescar()
        } catch (e: Exception) {
            respaldoMensaje = "No se pudo restaurar. Si dice \"verificación\", vuelve a restaurar desde el mismo archivo. (${e.message})"
        }
    }
}

// ---------- piezas comunes ----------
@Composable private fun Titulo(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
@Composable private fun Sub(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
@Composable private fun Gris(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun Nota(t: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) =
    Card(modifier = Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = color)) { Text(t, modifier = Modifier.padding(12.dp)) }
@Composable private fun Tarjeta(contenido: @Composable () -> Unit) =
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenido() } }

internal const val ALARMA = "Detén el entrenamiento y consulta si tienes náuseas, vómitos, dolor abdominal, cansancio extremo o dolor muscular inusual. Esta app no da consejos médicos."
private const val PRUEBA = "Versión de prueba: tus entrenamientos reales se siguen registrando en Génesis Gym 1.0."

@Composable
fun PantallaGimnasio(g: Gym, acciones: AccionesGym) {
    @Suppress("UNUSED_VARIABLE") val t = g.tick   // se vuelve a dibujar cuando cambian los datos
    if (g.vista == "sesion" && g.borrador != null) { SesionNueva(g); g.ficha?.let { Ficha(g, it) }; return }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Nota(PRUEBA, MaterialTheme.colorScheme.tertiaryContainer)
        when (g.vista) {
            "sesion" -> Hoy(g)   // sin registro abierto
            "rutina" -> RutinaV(g)
            "historial" -> Historial(g, acciones)
            "importar" -> Importar(g, acciones)
            else -> Hoy(g)
        }
        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
    }
    g.ficha?.let { Ficha(g, it) }
}

/** Lo que solo la actividad puede hacer (abrir el selector de archivos, guardar un archivo). */
interface AccionesGym {
    fun elegirRutina()
    fun guardarCsv()
    fun guardarRespaldo()
    fun elegirRespaldo()
}

// ---------- Hoy ----------
@Composable
private fun Hoy(g: Gym) {
    val hoy = Gimnasio.hoyIso(); val dia = Gimnasio.diaDe(hoy)
    val rutina = g.rutina()
    Gris("${Gimnasio.DIA_LABEL[dia]} · hora de Chile")
    Titulo(Gimnasio.fechaLarga(hoy))
    rutina?.obj("rutina")?.let { Gris("${it.txt("nombre")} · versión ${JsJson.numero(it["version"].toString())}") }

    val perfil = g.perfil()
    if (perfil != null && !g.editarPerfil) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Gris("Este celular es de: $perfil")
        TextButton(onClick = { g.editarPerfil = true }) { Text("Cambiar") }
    }
    if (perfil == null || g.editarPerfil) Tarjeta {
        var nombre by remember { mutableStateOf(perfil ?: "") }
        Sub("¿De quién es este celular?")
        Gris("Cada celular guarda los datos de una sola persona. El nombre aparece en los respaldos.")
        OutlinedTextField(value = nombre, onValueChange = { nombre = it.take(40) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { g.guardarPerfil(nombre) }) { Text("Guardar") }
            if (perfil != null) OutlinedButton(onClick = { g.editarPerfil = false }) { Text("Cancelar") }
        }
    }
    if (perfil != null) {
        val ultimo = g.ultimoRespaldo()
        val dias = ultimo?.let { runCatching { java.time.Duration.between(java.time.Instant.parse(it), java.time.Instant.now()).toDays() }.getOrNull() }
        if (ultimo == null) Nota("Aún no tienes respaldo. Tus datos están solo en este celular. Crea uno en Historial → Respaldo.", MaterialTheme.colorScheme.errorContainer)
        else if (dias != null && dias > 7) Nota("Tu último respaldo tiene $dias días. Crea uno nuevo en Historial → Respaldo.", MaterialTheme.colorScheme.errorContainer)
    }
    if (rutina?.obj("rutina")?.txt("id") == Gimnasio.RUTINA_EJEMPLO) Nota("Rutina de ejemplo: los ejercicios y cargas son de relleno. Importa la rutina de Entrenamiento.")
    if (rutina == null) { Tarjeta { Sub("Aún no hay rutina"); Gris("La rutina la entrega el chat Entrenamiento en un archivo JSON. Impórtala para ver aquí la sesión de cada día."); Button(onClick = { g.ir("importar") }) { Text("Importar rutina") } }; return }

    val sesiones = g.sesiones()
    val ses = rutina.arr("sesiones").firstOrNull { it.txt("dia_semana") == dia }
    Tarjeta {
        if (ses != null) {
            val doc = sesiones.firstOrNull { it.txt("fecha") == hoy && it.txt("sesion_id") == ses.txt("id") }
            Gris("Sesión de hoy"); Sub(ses.txt("nombre") ?: "")
            Gris("~${JsJson.numero(ses["duracion_estimada_min"].toString())} min · ${ses.arr("ejercicios").size} ejercicios" + (doc?.let { " · " + if (it.txt("estado") == "cerrada") "cerrada" else "en curso" } ?: ""))
            ses.txt("notas")?.takeIf { it.isNotEmpty() }?.let { Text(it) }
            Button(onClick = { g.abrirSesion(hoy, ses.txt("id")!!) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (doc == null) "Empezar sesión" else if (doc.txt("estado") == "cerrada") "Ver sesión" else "Continuar sesión")
            }
        } else { Gris("Hoy"); Sub("Sin fuerza hoy"); Gris("La rutina no tiene sesión para el ${Gimnasio.DIA_LABEL[dia]?.lowercase()}.") }
    }
    sesiones.firstOrNull { !(ses != null && it.txt("fecha") == hoy && it.txt("sesion_id") == ses.txt("id")) }?.let { u ->
        Sub("Última sesión registrada")
        FilaSesion(g, u)
    }
    val otras = rutina.arr("sesiones").filter { ses == null || it.txt("id") != ses.txt("id") }
    if (otras.isNotEmpty()) {
        Sub("Registrar otra sesión hoy")
        for (s in otras) OutlinedButton(onClick = { g.abrirSesion(hoy, s.txt("id")!!) }, modifier = Modifier.fillMaxWidth()) {
            Text("${s.txt("nombre")} (${Gimnasio.DIA_LABEL[s.txt("dia_semana")]} en la rutina)")
        }
    }
}

@Composable
private fun FilaSesion(g: Gym, s: JsonObject) {
    val rpe = s["rpe_sesion"]?.takeIf { it !is JsonNull }?.toString() ?: "—"
    val min = s["duracion_min"]?.takeIf { it !is JsonNull }?.let { "$it min" } ?: "—"
    OutlinedButton(onClick = { g.abrirSesion(s.txt("fecha")!!, s.txt("sesion_id")!!) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text("${Gimnasio.fechaCorta(s.txt("fecha")!!)} · ${s.txt("sesion_nombre")}", fontWeight = FontWeight.SemiBold)
            Text("${Gimnasio.seriesRegistradas(s)}/${Gimnasio.seriesTotales(s)} series · RPE $rpe · $min · " + if (s.txt("estado") == "cerrada") "Cerrada" else "En curso",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- Sesión ----------
@Composable
internal fun CierreSesion(g: Gym) {
    val b = g.borrador ?: return
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    TextButton(onClick = { g.sesVista = "mapa" }) { Text("‹ Mapa") }
    Gris("${Gimnasio.fechaLarga(b.txt("fecha")!!)} · v${b["rutina_version"]}")
    Titulo(b.txt("sesion_nombre") ?: "")
    Text((if (b.txt("estado") == "cerrada") "Cerrada" else "En curso") + " · " + g.guardado,
        color = if (g.guardadoError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Gris("${Gimnasio.seriesRegistradas(b)} de ${Gimnasio.seriesTotales(b)} series anotadas.")
    (b["cambios_fecha"] as? kotlinx.serialization.json.JsonArray)?.let { c ->
        if (c.isNotEmpty()) Gris("Fecha corregida. Antes: " + c.joinToString(", ") { Gimnasio.fechaCorta((it as JsonObject).txt("de") ?: "") } + ".")
    }
    if (!g.editarFecha) TextButton(onClick = { g.editarFecha = true; g.errorFecha = "" }) { Text("Cambiar fecha") }
    else Tarjeta {
        var f by remember { mutableStateOf(b.txt("fecha")!!) }
        OutlinedTextField(value = f, onValueChange = { f = it.take(10) }, label = { Text("Fecha correcta (AAAA-MM-DD)") }, singleLine = true)
        Gris("Se conservan las series, el RPE, la duración y la nota. No puede ser una fecha futura ni repetir una sesión ya registrada ese día.")
        if (g.errorFecha.isNotEmpty()) Text(g.errorFecha, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { g.cambiarFecha(f.trim()) }) { Text("Guardar fecha") }
            OutlinedButton(onClick = { g.editarFecha = false }) { Text("Cancelar") }
        }
    }

    Tarjeta {
        Sub("Cierre de la sesión")
        Gris("RPE de la sesión (1 = muy fácil, 10 = máximo)")
        for (fila in listOf(1..5, 6..10)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in fila) {
                val sel = b.num("rpe_sesion") == n.toDouble()
                Button(onClick = { g.errorCierre = ""; g.guardar(b.con("rpe_sesion", JsonPrimitive(n))) }, modifier = Modifier.width(60.dp),
                    colors = if (sel) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()) { Text("$n") }
            }
        }
        key(b.txt("fecha"), b.txt("sesion_id")) {
            var dur by remember { mutableStateOf(b["duracion_min"]?.takeIf { it !is JsonNull }?.toString() ?: "") }
            OutlinedTextField(value = dur, onValueChange = { v ->
                dur = v
                when (val p = Gimnasio.parseNum(v, true)) {
                    is Gimnasio.Num.Error -> { g.guardado = "Duración no válida: minutos entre 1 y 300."; g.guardadoError = true }
                    is Gimnasio.Num.Valor -> if (p.v != null && (p.v < 1 || p.v > 300)) { g.guardado = "Duración no válida: minutos entre 1 y 300."; g.guardadoError = true }
                        else g.guardar(g.borrador!!.con("duracion_min", p.v?.let { JsJson.primitivo(it) } ?: JsonNull))
                }
            }, label = { Text("Duración (min)") }, placeholder = { Text("${b["duracion_estimada_min"]}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            val sugerido = g.inicioApertura?.let { maxOf(1L, Math.round((System.currentTimeMillis() - it) / 60000.0)) }
            if (sugerido != null && b.txt("estado") != "cerrada") TextButton(onClick = { dur = "$sugerido"; g.guardar(g.borrador!!.con("duracion_min", JsonPrimitive(sugerido))) }) {
                Text("Llevas $sugerido min con la sesión abierta. Usar $sugerido min")
            }
            var nota by remember { mutableStateOf(b.txt("nota") ?: "") }
            OutlinedTextField(value = nota, onValueChange = { nota = it; g.guardar(g.borrador!!.con("nota", JsonPrimitive(it))) }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        }
        if (g.errorCierre.isNotEmpty()) Text(g.errorCierre, color = MaterialTheme.colorScheme.error)
        if (b.txt("estado") == "cerrada") Nota("Sesión cerrada. Puedes corregir valores y se guardan solos.")
        else Button(onClick = { g.cerrarSesion() }, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
        if (!g.confirmarBorrado) TextButton(onClick = { g.confirmarBorrado = true }) { Text("Eliminar este registro") }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("¿Eliminar este registro? No se puede deshacer.", modifier = Modifier.weight(1f))
            Button(onClick = { g.borrarSesion() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Eliminar") }
            OutlinedButton(onClick = { g.confirmarBorrado = false }) { Text("Cancelar") }
        }
    }
}
}

@Composable
private fun SerieV(g: Gym, b: JsonObject, i: Int, k: Int, ejId: String, se: JsonObject, sesiones: List<JsonObject>) {
    val modo = se.txt("modo")
    val repKey = if (modo == "tiempo") "seg_reales" else "reps_reales"
    Text("Serie ${se["n"]} · ${if (se.txt("tipo") == "calentamiento") "calentamiento" else "efectiva"}", fontWeight = FontWeight.SemiBold)
    Text("Indicado: ${Gimnasio.prescritoTexto(se)}" + (se["rpe_objetivo"]?.takeIf { it !is JsonNull }?.let { " · RPE obj. ${Gimnasio.kg(it)}" } ?: ""))
    if (se.esNulo("carga_prescrita_kg")) se.txt("carga_indicacion")?.let { Gris("Carga: $it") }
    val prev = Gimnasio.anterior(sesiones, ejId, se.num("n") ?: 0.0, b.txt("fecha")!!)
    Gris(prev?.let { (s, p) ->
        val carga = if (p.esNulo("carga_real_kg")) "—" else Gimnasio.kg(p["carga_real_kg"]) + " kg"
        val rep = if (modo == "tiempo") (if (p.esNulo("seg_reales")) "—" else "${p["seg_reales"]} s") else (if (p.esNulo("reps_reales")) "—" else "${p["reps_reales"]}")
        "Anterior (${Gimnasio.fechaCorta(s.txt("fecha")!!)}): $carga × $rep"
    } ?: "Sin registro anterior")
    key(b.txt("fecha"), b.txt("sesion_id"), i, k, se["carga_real_kg"].toString(), se[repKey].toString()) {
        var carga by remember { mutableStateOf(Gimnasio.kg(se["carga_real_kg"])) }
        var reps by remember { mutableStateOf(se[repKey]?.takeIf { it !is JsonNull }?.toString() ?: "") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = carga, onValueChange = { v -> carga = v; anotar(g, i, k, "carga_real_kg", v, false) },
                label = { Text("Carga kg") }, placeholder = { Text(if (se.esNulo("carga_prescrita_kg")) "—" else Gimnasio.kg(se["carga_prescrita_kg"])) },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = reps, onValueChange = { v -> reps = v; anotar(g, i, k, repKey, v, true) },
                label = { Text(if (modo == "tiempo") "Segundos" else "Reps") },
                placeholder = { Text(if (modo == "tiempo") "${se["prescrito_seg"]}" else se.txt("prescrito_reps") ?: "") },
                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
    if (!se.esNulo("carga_prescrita_kg") || modo != "rango") TextButton(onClick = { g.guardar(Gimnasio.copiarIndicado(g.borrador!!, i, k)) }) { Text("= Indicado") }
}

internal fun anotar(g: Gym, i: Int, k: Int, clave: String, texto: String, entero: Boolean) {
    when (val p = Gimnasio.parseNum(texto, entero)) {
        is Gimnasio.Num.Error -> { g.guardado = "Valor no válido: usa solo números (decimales con coma o punto)."; g.guardadoError = true }
        is Gimnasio.Num.Valor -> g.guardar(Gimnasio.conValorSerie(g.borrador!!, i, k, clave, p.v))
    }
}

// ---------- Ficha ----------
@Composable
private fun Ficha(g: Gym, id: String) {
    val e = g.rutina()?.arr("ejercicios")?.firstOrNull { it.txt("id") == id }
    fun lista(k: String): List<String> = (e?.get(k) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    AlertDialog(onDismissRequest = { g.ficha = null }, confirmButton = { TextButton(onClick = { g.ficha = null }) { Text("Cerrar") } },
        title = { Text(e?.txt("nombre") ?: id) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FichaImagenes(g, id, e?.txt("nombre") ?: id)
                e?.txt("equipo")?.let { Text("Equipo: $it") }
                val m = lista("musculos_principales"); val s = lista("musculos_secundarios")
                if (m.isNotEmpty()) Text("Músculos: " + m.joinToString(", ") + if (s.isNotEmpty()) " (también: ${s.joinToString(", ")})" else "")
                e?.txt("descripcion")?.let { Text(it) }
                Sub("Pasos"); lista("pasos").forEachIndexed { n, p -> Text("${n + 1}. $p") }
                Sub("Errores comunes"); lista("errores_comunes").forEach { Text("• $it") }
                lista("precauciones").takeIf { it.isNotEmpty() }?.let { Sub("Precauciones"); it.forEach { p -> Text("• $p") } }
                Nota(ALARMA, MaterialTheme.colorScheme.errorContainer)
            }
        })
}

// ---------- Rutina ----------
@Composable
private fun RutinaV(g: Gym) {
    val r = g.rutina()
    Gris("Rutina activa")
    if (r == null) { Titulo("Rutina"); Gris("Aún no hay rutina."); Button(onClick = { g.ir("importar") }) { Text("Importar rutina") }; return }
    val ru = r.obj("rutina")!!
    Titulo(ru.txt("nombre") ?: "")
    Gris("Versión ${ru["version"]} · vigente desde ${Gimnasio.fechaCorta(ru.txt("vigente_desde") ?: "")} · autor: ${ru.txt("autor")}")
    ru.txt("notas")?.takeIf { it.isNotEmpty() }?.let { Text(it) }
    for (s in r.arr("sesiones").sortedBy { Gimnasio.ORDEN_SEMANA.indexOf(it.txt("dia_semana")) }) Tarjeta {
        Gris("${Gimnasio.DIA_LABEL[s.txt("dia_semana")]} · ~${s["duracion_estimada_min"]} min")
        Sub(s.txt("nombre") ?: "")
        for (x in s.arr("ejercicios").sortedBy { it.num("orden") }) {
            val e = r.arr("ejercicios").firstOrNull { it.txt("id") == x.txt("ejercicio_id") }
            val ef = x.arr("series").count { it.txt("tipo") == "efectiva" }
            OutlinedButton(onClick = { g.ficha = x.txt("ejercicio_id") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${x["orden"]}. ${e?.txt("nombre") ?: x.txt("ejercicio_id")}", fontWeight = FontWeight.SemiBold)
                    Text("${x.arr("series").size} series ($ef efectivas) · descanso ${x["descanso_seg"]} s", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------- Historial ----------
@Composable
private fun Historial(g: Gym, a: AccionesGym) {
    val sesiones = g.sesiones()
    Gris("Registro"); Titulo("Historial")
    Tarjeta {
        Sub("Exportar para el Evaluador")
        Gris("Archivo CSV con una fila por serie (separador ; y decimales con coma), igual que la 2.0.")
        Button(onClick = { a.guardarCsv() }, enabled = sesiones.isNotEmpty()) { Text("Exportar CSV") }
        if (g.csvMensaje.isNotEmpty()) Text(g.csvMensaje)
    }
    Tarjeta {
        Sub("Respaldo de todos tus datos")
        Gris("Tus datos viven solo en este celular. Crea un respaldo cada semana y guárdalo en Drive. Es el mismo formato de la 2.0.")
        Text((g.ultimoRespaldo()?.let { "Último respaldo: ${it.take(16).replace('T', ' ')} UTC" } ?: "Todavía no hay respaldos.") + (g.perfil()?.let { " · perfil: $it" } ?: ""))
        Button(onClick = { a.guardarRespaldo() }) { Text("Crear respaldo") }
        HorizontalDivider()
        Sub("Restaurar desde un respaldo")
        Gris("Reemplaza TODOS los datos de este celular por los del archivo (sirve para traer los datos de la 2.0).")
        OutlinedButton(onClick = { a.elegirRespaldo() }) { Text("Elegir archivo de respaldo") }
        if (g.respaldoMensaje.isNotEmpty()) Text(g.respaldoMensaje)
        g.restaurar?.let { r ->
            val c = r.conteos
            Nota("Respaldo válido (huella verificada).\n" + (r.perfil?.let { "Perfil: $it · " } ?: "") + "creado el ${r.exportadoEn ?: "?"}" + (r.app?.let { " · origen: $it" } ?: "") +
                "\nSesiones: ${c["sesiones"]}" + (r.rango?.let { " (del ${Gimnasio.fechaCorta(it.first)} al ${Gimnasio.fechaCorta(it.second)})" } ?: "") +
                " · rutinas: ${c["rutinas"]} · imágenes: ${c["imagenes"]} · configuración: ${c["config"]}" +
                (if ((c["comidas"] ?: 0) + (c["fotos"] ?: 0) > 0) " · comidas: ${c["comidas"] ?: 0} · fotos de comidas: ${c["fotos"] ?: 0}" else ""))
            val actuales = sesiones.size; val rutinas = g.rutina()?.let { 1 } ?: 0
            Text("Hoy este celular tiene $actuales sesiones. Se reemplazarán por los datos del respaldo.")
            if ((actuales > 0 || rutinas > 0) && !g.respaldadoAhora) Nota("Antes de reemplazar, crea un respaldo de lo que hay ahora (botón \"Crear respaldo\"). Así no pierdes nada si te equivocas de archivo.", MaterialTheme.colorScheme.errorContainer)
            else if (!g.restaurarConfirmar) Button(onClick = { g.restaurarConfirmar = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reemplazar todo con este respaldo") }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("¿Seguro? Esto no se puede deshacer, salvo restaurando otro respaldo.", modifier = Modifier.weight(1f))
                Button(onClick = { g.aplicarRestauracion() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Sí, reemplazar") }
                OutlinedButton(onClick = { g.restaurarConfirmar = false }) { Text("Cancelar") }
            }
        }
    }
    Gris("${sesiones.size} ${if (sesiones.size == 1) "sesión" else "sesiones"} en la base")
    if (sesiones.isEmpty()) Gris("Todavía no hay sesiones registradas. Aparecen aquí cuando registras la primera serie.")
    for (s in sesiones) FilaSesion(g, s)
}

// ---------- Importar ----------
@Composable
private fun Importar(g: Gym, a: AccionesGym) {
    Gris("Rutina de Entrenamiento"); Titulo("Importar rutina")
    Gris("Elige el archivo o pega el JSON que entregó Entrenamiento. Si tiene un solo error, la rutina se rechaza completa y no se corrige nada.")
    Tarjeta {
        Button(onClick = { a.elegirRutina() }) { Text("Elegir archivo (.json)") }
        Gris("En el selector puedes ir a Drive → Genesis Gym → rutinas.")
        var texto by remember { mutableStateOf(g.importTexto) }
        OutlinedTextField(value = texto, onValueChange = { texto = it }, label = { Text("O pega el contenido") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
        Button(onClick = { g.validarImport(texto) }) { Text("Validar") }
    }
    if (g.importMensaje.isNotEmpty()) Text(g.importMensaje)
    val im = g.importacion ?: return
    val r = im.resultado
    Tarjeta {
        when {
            im.yaActiva -> Nota("Esta rutina ya está activa. No hay nada que importar: es la misma versión y el mismo contenido.")
            !r.ok -> {
                Nota("Rutina rechazada. ${r.errores.size} ${if (r.errores.size == 1) "error" else "errores"}. Devuelve esta lista a Entrenamiento.", MaterialTheme.colorScheme.errorContainer)
                r.errores.forEach { Text("• $it") }
            }
            else -> {
                val ru = im.rutina!!.obj("rutina")!!
                Nota("Rutina válida.")
                Text("${ru.txt("nombre")} · versión ${ru["version"]} · desde ${Gimnasio.fechaCorta(ru.txt("vigente_desde") ?: "")}", fontWeight = FontWeight.SemiBold)
                Gris(im.rutina.arr("sesiones").joinToString(", ") { Gimnasio.DIA_LABEL[it.txt("dia_semana")] ?: "" } + " · ${im.rutina.arr("ejercicios").size} ejercicios en el catálogo")
                if (r.avisos.isNotEmpty()) { Sub("Avisos (no bloquean)"); r.avisos.forEach { Text("• $it") } }
                if (g.importMensaje != "Rutina activada.") Button(onClick = { g.activar() }) { Text("Activar esta rutina") }
            }
        }
    }
}

@Composable
internal fun FichaImagenes(g: Gym, id: String, nombre: String) {
    val img = g.imagenes?.meta(id, g.base)
    val srcs = (img?.get("srcs") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    if (img == null || srcs.isEmpty()) { Gris("Sin imagen todavía. Solo se agregan imágenes con licencia abierta."); return }
    val etiquetas = (img["etiquetas"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    srcs.forEachIndexed { k, src ->
        val bmp = remember(src) { g.imagenes!!.bitmap(src) }
        if (bmp != null) androidx.compose.foundation.Image(bitmap = bmp.asImageBitmap(), contentDescription = "$nombre: ${etiquetas.getOrNull(k) ?: ""}",
            modifier = Modifier.fillMaxWidth())
        else Gris("(no se pudo mostrar la imagen)")
        etiquetas.getOrNull(k)?.let { Gris(it) }
    }
    val nota = img.txt("nota")?.takeIf { it.isNotEmpty() }
    val validada = (img["validada_entrenamiento"] as? JsonPrimitive)?.content == "true"
    if (validada) nota?.let { Nota("Nota de Entrenamiento: $it") }
    else Nota("Imagen referencial, pendiente de validar por Entrenamiento." + (nota?.let { " $it" } ?: "") + " Sigue siempre los pasos escritos.", MaterialTheme.colorScheme.errorContainer)
    Text(img.txt("credito")?.takeIf { it.isNotEmpty() }
        ?: "Ilustración: ${img.txt("autor") ?: ""} · ${img.txt("fuente") ?: ""} · licencia ${img.txt("licencia") ?: ""} (${img.txt("url_licencia") ?: ""}). Sin modificaciones.",
        style = MaterialTheme.typography.bodySmall)
}
