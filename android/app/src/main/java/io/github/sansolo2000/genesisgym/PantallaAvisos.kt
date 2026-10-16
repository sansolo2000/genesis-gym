package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/** Lo que solo la actividad puede hacer en la configuración de avisos. */
interface AccionesAvisos {
    fun permitirNotificaciones()
    fun abrirAlarmasExactas()
    fun abrirBateria()
    fun abrirSonidoCelular()
    fun elegirSonido(actual: android.net.Uri?, alarma: Boolean)
    fun tieneNotificaciones(): Boolean
    fun sinRestriccionBateria(): Boolean
}

@Composable
private fun Interruptor(texto: String, valor: Boolean, cambiar: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = valor, onCheckedChange = cambiar); Text(texto)
    }

@Composable
private fun Opciones(valores: List<Int>, actual: Int, texto: (Int) -> String, elegir: (Int) -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in valores) {
            if (actual == m) Button(onClick = {}) { Text(texto(m), style = MaterialTheme.typography.labelSmall) }
            else OutlinedButton(onClick = { elegir(m) }) { Text(texto(m), style = MaterialTheme.typography.labelSmall) }
        }
    }

/**
 * Etapa E5 — configuración de los avisos reales: qué avisar y a qué hora.
 * M-10 (14-oct): cada tipo de alerta con su tono, volumen, vibración, "sonar en silencio" y posponer.
 * [eleccion] sube cada vez que la actividad recibe un tono del selector de Android ([uriElegida]); va al tipo que lo pidió.
 */
@Composable
fun PantallaAvisos(c: Context, a: AccionesAvisos, tick: Int, eleccion: Int, uriElegida: android.net.Uri?) {
    val inicial = remember { Programador.config(c) }
    var comidas by remember { mutableStateOf(inicial.comidas) }
    var minutos by remember { mutableStateOf(inicial.minutosAntes) }
    var tareas by remember { mutableStateOf(inicial.tareas) }
    var entrenamiento by remember { mutableStateOf(inicial.entrenamiento) }
    var horaEnt by remember { mutableStateOf(inicial.horaEntrenamiento) }
    var descanso by remember { mutableStateOf(inicial.descanso) }
    var reporte by remember { mutableStateOf(inicial.reporte) }
    var horaRep by remember { mutableStateOf(inicial.horaReporte) }
    val tipos = remember { mutableStateMapOf<String, Alertas.Tipo>().apply { for (t in Alertas.TODOS) put(t, Programador.tipo(c, t)) } }
    var eligiendo by remember { mutableStateOf<String?>(null) }
    val probar = remember { mutableStateMapOf<String, String>() }
    androidx.compose.runtime.LaunchedEffect(eleccion) {
        val t = eligiendo
        if (eleccion > 0 && t != null) { tipos[t] = tipos.getValue(t).copy(sonido = uriElegida?.toString()); eligiendo = null }
    }
    var mensaje by remember { mutableStateOf("") }
    var lista by remember { mutableStateOf(Programador.proximos(c)) }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val estado = remember(tick) { Programador.estado(c) }
    val (va, vm) = remember(tick) { Programador.volumenAlarmas(c) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Avisos", style = MaterialTheme.typography.headlineSmall)
        Text("Se calculan con el programa de alimentación y la rutina activos en esta app. Una comida ya marcada o un entrenamiento ya registrado no se avisa.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(); Text("Permisos y volumen", style = MaterialTheme.typography.titleMedium)
        Text("Notificaciones: " + if (a.tieneNotificaciones()) "permitidas" else "NO permitidas")
        if (!a.tieneNotificaciones()) Button(onClick = { a.permitirNotificaciones() }) { Text("Permitir notificaciones") }
        val exactas = remember(tick) { c.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
        Text("Alarmas a la hora exacta: " + if (exactas) "permitidas" else "NO permitidas (los avisos pueden atrasarse hasta 10 min y solo suenan unos segundos)")
        if (!exactas) OutlinedButton(onClick = { a.abrirAlarmasExactas() }) { Text("Abrir ajuste de alarmas exactas") }
        Text("Batería: " + if (a.sinRestriccionBateria()) "sin restricción" else "optimizada (Android puede retrasar avisos)")
        if (!a.sinRestriccionBateria()) OutlinedButton(onClick = { a.abrirBateria() }) { Text("Abrir ajuste de batería") }
        Text("Volumen de alarmas del celular: $va de $vm. El volumen de cada alerta es un porcentaje de este; la app no lo cambia." +
            if (va == 0) " Con el celular en 0 no se escucha nada." else "", style = MaterialTheme.typography.bodySmall)
        Alertas.avisoNoMolestar(estado.filtro, estado.alarmasPermitidas)?.let {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text(it, Modifier.padding(12.dp)) }
        }
        OutlinedButton(onClick = { a.abrirSonidoCelular() }) { Text("Abrir volumen del celular") }

        for (tp in Alertas.TODOS) {
            HorizontalDivider(); Text(Alertas.NOMBRE[tp] ?: tp, style = MaterialTheme.typography.titleMedium)
            when (tp) {
                Recordatorios.COMIDA -> {
                    Interruptor("Activa (las 5 comidas del programa)", comidas) { comidas = it }
                    if (comidas) { Text("Avisar cada comida:"); Opciones(listOf(0, 10, 15, 30, 60), minutos, { m -> if (m == 0) "A la hora" else "$m min antes" }) { minutos = it } }
                }
                Recordatorios.TAREA -> Interruptor("Activa (las del programa, a su hora)", tareas) { tareas = it }
                Recordatorios.ENTRENAMIENTO -> {
                    Interruptor("Activa (los días con sesión en la rutina)", entrenamiento) { entrenamiento = it }
                    if (entrenamiento) OutlinedTextField(value = horaEnt, onValueChange = { horaEnt = it.take(5) }, singleLine = true,
                        label = { Text("Hora del aviso de entrenamiento (HH:MM)") }, isError = !Recordatorios.horaValida(horaEnt), modifier = Modifier.fillMaxWidth())
                }
                Alertas.DESCANSO -> Interruptor("Activa (fin del descanso y de las series por tiempo)", descanso) { descanso = it }
                Recordatorios.REPORTE -> {
                    Interruptor("Activa", reporte) { reporte = it }
                    if (reporte) OutlinedTextField(value = horaRep, onValueChange = { horaRep = it.take(5) }, singleLine = true,
                        label = { Text("Hora del reporte (HH:MM)") }, isError = !Recordatorios.horaValida(horaRep), modifier = Modifier.fillMaxWidth())
                }
                Alertas.MEDICAMENTO -> Text("Se activan en cada tratamiento, en la pestaña Medicamentos.", style = MaterialTheme.typography.bodySmall)
            }
            val v = tipos.getValue(tp)
            Text("Tono: " + Programador.nombreTono(c, v), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { eligiendo = tp; a.elegirSonido(v.sonido?.let(android.net.Uri::parse), true) }) { Text("Elegir tono") }
            Text("Volumen: ${v.volumen} % del volumen de alarmas")
            Slider(value = v.volumen.toFloat(), onValueChange = { tipos[tp] = v.copy(volumen = (it / 10).toInt() * 10) }, valueRange = 0f..100f, steps = 9)
            Interruptor("Vibrar", v.vibrar) { tipos[tp] = v.copy(vibrar = it) }
            Interruptor("Sonar aunque esté en silencio / No molestar", v.enSilencio) { tipos[tp] = v.copy(enSilencio = it) }
            if (!v.enSilencio) Text("Apagado: no suena si el celular está en silencio, en vibración o en No molestar.", style = MaterialTheme.typography.bodySmall)
            if (Alertas.esperaRespuesta(tp)) Text("Cada aviso suena hasta que respondas (como máximo ${Alertas.TOPE_MS / 60000} minutos). Sin respuesta se repite cada " +
                "${Medicamentos.REPETIR_MIN} minutos durante ${Medicamentos.VENTANA_MIN / 60} horas. Posponer (15, 30, 60 o 90 min) se elige en cada aviso.")
            else if (Alertas.insiste(tp)) {
                Text("Suena hasta que toques Detener o Posponer (como máximo ${Alertas.TOPE_MS / 60000} minutos). Posponer:")
                Opciones(Alertas.POSPONER, v.posponerMin, { m -> "$m min" }) { tipos[tp] = v.copy(posponerMin = it) }
            } else Text("Suena una vez, al llegar a 0.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    // Riesgo de QA: si no va a sonar, se dice por qué (silencio, vibración, No molestar o volumen en 0).
                    val e = Programador.estado(c)
                    probar[tp] = Alertas.porQueNoSuena(v, e.timbre, e.filtro, e.alarmasPermitidas) ?: ""
                    Programador.tocar(c, tp, v)
                }) { Text("Probar") }
                OutlinedButton(onClick = { Programador.detener(); Programador.dejarDeVibrar(c); probar[tp] = "" }) { Text("Detener") }
            }
            probar[tp]?.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        HorizontalDivider()
        Button(onClick = {
            if (entrenamiento && !Recordatorios.horaValida(horaEnt)) { mensaje = "Escribe la hora del entrenamiento como HH:MM, por ejemplo 18:30."; return@Button }
            if (reporte && !Recordatorios.horaValida(horaRep)) { mensaje = "Escribe la hora del reporte como HH:MM, por ejemplo 21:00."; return@Button }
            Programador.guardarConfig(c, Recordatorios.Config(comidas, minutos, tareas, entrenamiento, horaEnt, reporte, horaRep, descanso))
            for ((k, v) in tipos) Programador.guardarTipo(c, k, v)
            val n = Programador.reprogramar(c)
            lista = Programador.proximos(c)
            mensaje = "Guardado. $n aviso${if (n == 1) "" else "s"} programado${if (n == 1) "" else "s"} para los próximos 7 días. Puedes cerrar la app."
        }, modifier = Modifier.fillMaxWidth()) { Text("Guardar y programar") }
        if (mensaje.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(mensaje, Modifier.padding(12.dp)) }

        HorizontalDivider(); Text("Próximos avisos", style = MaterialTheme.typography.titleMedium)
        if (lista.isEmpty()) Text("No hay avisos programados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (x in lista.take(15)) Text(Recordatorios.etiqueta(x, ZoneId.systemDefault()) + " · " + x.texto.lineSequence().first(), style = MaterialTheme.typography.bodyMedium)
        if (lista.size > 15) Text("… y ${lista.size - 15} más.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
    }
}
