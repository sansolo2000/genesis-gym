package io.github.sansolo2000.genesisgym

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * Etapa E5 — configuración de los avisos reales: qué avisar, a qué hora, y cómo suena (sonido, modo y volumen).
 * [eleccion] sube cada vez que la actividad recibe un sonido del selector de Android ([uriElegida]).
 */
@Composable
fun PantallaAvisos(c: Context, a: AccionesAvisos, tick: Int, eleccion: Int, uriElegida: android.net.Uri?) {
    val inicial = remember { Programador.config(c) }
    var comidas by remember { mutableStateOf(inicial.comidas) }
    var minutos by remember { mutableStateOf(inicial.minutosAntes) }
    var tareas by remember { mutableStateOf(inicial.tareas) }
    var entrenamiento by remember { mutableStateOf(inicial.entrenamiento) }
    var horaEnt by remember { mutableStateOf(inicial.horaEntrenamiento) }
    var reporte by remember { mutableStateOf(inicial.reporte) }
    var horaRep by remember { mutableStateOf(inicial.horaReporte) }
    var sonido by remember { mutableStateOf(Programador.sonido(c)) }
    androidx.compose.runtime.LaunchedEffect(eleccion) { if (eleccion > 0) sonido = sonido.copy(uri = uriElegida) }
    var mensaje by remember { mutableStateOf("") }
    var lista by remember { mutableStateOf(Programador.proximos(c)) }
    @Suppress("UNUSED_VARIABLE") val t = tick

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Avisos", style = MaterialTheme.typography.headlineSmall)
        Text("Se calculan con el programa de alimentación y la rutina activos en esta app. Una comida ya marcada o un entrenamiento ya registrado no se avisa.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(); Text("Permisos", style = MaterialTheme.typography.titleMedium)
        Text("Notificaciones: " + if (a.tieneNotificaciones()) "permitidas" else "NO permitidas")
        if (!a.tieneNotificaciones()) Button(onClick = { a.permitirNotificaciones() }) { Text("Permitir notificaciones") }
        val exactas = remember(tick) { c.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
        Text("Alarmas a la hora exacta: " + if (exactas) "permitidas" else "NO permitidas (los avisos pueden atrasarse hasta 10 min)")
        if (!exactas) OutlinedButton(onClick = { a.abrirAlarmasExactas() }) { Text("Abrir ajuste de alarmas exactas") }
        Text("Batería: " + if (a.sinRestriccionBateria()) "sin restricción" else "optimizada (Android puede retrasar avisos)")
        if (!a.sinRestriccionBateria()) OutlinedButton(onClick = { a.abrirBateria() }) { Text("Abrir ajuste de batería") }

        HorizontalDivider(); Text("Qué avisar", style = MaterialTheme.typography.titleMedium)
        Interruptor("Comidas (las 5 del programa)", comidas) { comidas = it }
        if (comidas) {
            Text("Avisar cada comida:")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf(0, 10, 15, 30, 60)) {
                    val txt = if (m == 0) "A la hora" else "$m min antes"
                    if (minutos == m) Button(onClick = {}) { Text(txt, style = MaterialTheme.typography.labelSmall) }
                    else OutlinedButton(onClick = { minutos = m }) { Text(txt, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Interruptor("Tareas de cocina (las del programa, a su hora)", tareas) { tareas = it }
        Interruptor("Entrenamiento (los días con sesión en la rutina)", entrenamiento) { entrenamiento = it }
        if (entrenamiento) OutlinedTextField(value = horaEnt, onValueChange = { horaEnt = it.take(5) }, singleLine = true,
            label = { Text("Hora del aviso de entrenamiento (HH:MM)") }, isError = !Recordatorios.horaValida(horaEnt), modifier = Modifier.fillMaxWidth())
        Interruptor("Reporte del día", reporte) { reporte = it }
        if (reporte) OutlinedTextField(value = horaRep, onValueChange = { horaRep = it.take(5) }, singleLine = true,
            label = { Text("Hora del reporte (HH:MM)") }, isError = !Recordatorios.horaValida(horaRep), modifier = Modifier.fillMaxWidth())

        HorizontalDivider(); Text("Sonido", style = MaterialTheme.typography.titleMedium)
        Text("Suena: " + Programador.nombreSonido(c, sonido))
        OutlinedButton(onClick = { a.elegirSonido(sonido.uri, sonido.alarma) }) { Text("Elegir sonido") }
        Interruptor("Sonar como alarma (suena aunque el celular esté en silencio)", sonido.alarma) { sonido = sonido.copy(alarma = it) }
        Text("Volumen de los avisos: ${sonido.volumen} %")
        Slider(value = sonido.volumen.toFloat(), onValueChange = { sonido = sonido.copy(volumen = (it / 10).toInt() * 10) }, valueRange = 0f..100f, steps = 9)
        val (va, vm) = remember(tick, sonido.alarma) { Programador.volumenSistema(c, sonido.alarma) }
        Text("Es un porcentaje del volumen de ${if (sonido.alarma) "alarmas" else "notificaciones"} del celular, que hoy está en $va de $vm." +
            if (va == 0) " Con el celular en 0 no se escucha nada." else "", style = MaterialTheme.typography.bodySmall)
        if (!sonido.alarma) Text("Como notificación no suena si el celular está en silencio o vibración.", style = MaterialTheme.typography.bodySmall)
        Text("Cada aviso suena hasta ${Programador.DURACION_MS / 1000} segundos.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Programador.tocar(c, sonido) }) { Text("Probar sonido") }
            OutlinedButton(onClick = { Programador.detener() }) { Text("Detener") }
        }
        OutlinedButton(onClick = { a.abrirSonidoCelular() }) { Text("Abrir volumen del celular") }

        HorizontalDivider()
        Button(onClick = {
            if (entrenamiento && !Recordatorios.horaValida(horaEnt)) { mensaje = "Escribe la hora del entrenamiento como HH:MM, por ejemplo 18:30."; return@Button }
            if (reporte && !Recordatorios.horaValida(horaRep)) { mensaje = "Escribe la hora del reporte como HH:MM, por ejemplo 21:00."; return@Button }
            Programador.guardarConfig(c, Recordatorios.Config(comidas, minutos, tareas, entrenamiento, horaEnt, reporte, horaRep))
            Programador.guardarSonido(c, sonido)
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
