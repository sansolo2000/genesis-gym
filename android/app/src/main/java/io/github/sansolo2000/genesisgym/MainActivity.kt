package io.github.sansolo2000.genesisgym

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Etapa E1: prueba de avisos. Muestra el estado de los permisos, permite programar 3 avisos de prueba
 * y registra a qué hora llegó cada uno. No guarda ni lee datos de salud.
 */
class MainActivity : ComponentActivity() {
    private var refresco by mutableIntStateOf(0)

    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresco++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("e0", Context.MODE_PRIVATE)
        val aperturas = prefs.getInt("aperturas", 0) + if (savedInstanceState == null) 1 else 0
        if (savedInstanceState == null) prefs.edit().putInt("aperturas", aperturas).apply()
        Alarmas.crearCanal(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Pantalla(aperturas, refresco)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresco++   // al volver de Ajustes o de una notificación, se vuelven a leer permisos y avisos
    }

    private fun tieneNotificaciones() =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun sinRestriccionBateria() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Composable
    private fun Pantalla(aperturas: Int, @Suppress("UNUSED_PARAMETER") tick: Int) {
        val zona = ZoneId.systemDefault()
        val ahora = ZonedDateTime.now(zona)
        val horas = remember { mutableStateListOf(Avisos.sugerida(ahora, 2), Avisos.sugerida(ahora, 10), "07:00") }
        var mensaje by remember { mutableStateOf("") }
        val avisos = Alarmas.lista(this)

        Column(
            modifier = Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Prueba de avisos", style = MaterialTheme.typography.headlineMedium)
            Text("Versión de prueba (etapa E1). Tus entrenamientos se siguen registrando en Génesis Gym 1.0.")

            HorizontalDivider()
            Text("1. Permisos", style = MaterialTheme.typography.titleMedium)
            Text("Notificaciones: " + if (tieneNotificaciones()) "permitidas" else "NO permitidas")
            if (!tieneNotificaciones()) Button(onClick = { pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Permitir notificaciones")
            }
            Text("Alarmas a la hora exacta: " + if (Alarmas.puedeExactas(this@MainActivity)) "permitidas" else "NO permitidas (los avisos pueden atrasarse)")
            OutlinedButton(onClick = {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }) { Text("Abrir ajuste de alarmas exactas") }
            Text("Batería: " + if (sinRestriccionBateria()) "sin restricción" else "optimizada (Android puede retrasar avisos)")
            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Abrir ajuste de batería")
            }

            HorizontalDivider()
            Text("2. Programar 3 avisos (hora HH:MM)", style = MaterialTheme.typography.titleMedium)
            for (i in 0..2) {
                OutlinedTextField(
                    value = horas[i], onValueChange = { horas[i] = it },
                    label = { Text("Aviso ${i + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val ya = ZonedDateTime.now(zona)
                    val tiempos = horas.map { Avisos.proximaOcurrencia(it, ya) }
                    if (tiempos.any { it == null }) {
                        mensaje = "Revisa las horas: deben tener el formato HH:MM, por ejemplo 07:30."
                    } else {
                        Alarmas.borrarTodo(this@MainActivity)
                        val hechos = tiempos.mapIndexed { i, t -> Alarmas.programar(this@MainActivity, i + 1, t!!.toInstant().toEpochMilli()) }
                        mensaje = "Programados ${hechos.size} avisos (modo ${hechos.first().modo}). Puedes cerrar la app."
                    }
                    refresco++
                }) { Text("Programar") }
                OutlinedButton(onClick = { Alarmas.borrarTodo(this@MainActivity); mensaje = "Avisos borrados."; refresco++ }) {
                    Text("Borrar")
                }
            }
            if (mensaje.isNotEmpty()) Text(mensaje)

            HorizontalDivider()
            Text("3. Resultado", style = MaterialTheme.typography.titleMedium)
            if (avisos.isEmpty()) Text("Todavía no hay avisos programados.")
            for (a in avisos) Text(Avisos.estado(a, zona))

            HorizontalDivider()
            Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
            Text(Saludo.aperturas(aperturas), style = MaterialTheme.typography.bodySmall)
        }
    }
}
