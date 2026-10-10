package io.github.sansolo2000.genesisgym

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.time.format.DateTimeFormatter
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
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

    private val elegirSonido = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val uri = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            Alarmas.cambiarSonido(this, uri, Alarmas.comoAlarma(this))
        }
        refresco++
    }

    private fun abrirSelectorSonido() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido de los avisos")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarmas.sonido(this))
        elegirSonido.launch(i)
    }

    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresco++ }

    // E2: tipos de Health Connect que se prueban (solo lectura). Mismo orden que Salud.TIPOS.
    private val tiposSalud: List<KClass<out Record>> = listOf(
        WeightRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class, RestingHeartRateRecord::class
    )
    private val permisosSalud: Set<String> = tiposSalud.map { HealthPermission.getReadPermission(it) }.toSet()
    private var permisosConcedidos by mutableStateOf<Set<String>>(emptySet())

    // E4: gimnasio. La base local tiene los mismos documentos que la 2.0.
    private val base by lazy { BaseLocal(this) }
    private val gym by lazy { Gym(base) {} }
    private var csvPendiente: String = ""
    private var respaldoPendiente: Pair<String, String>? = null

    private fun leerTexto(uri: Uri): String = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    private fun escribirTexto(uri: Uri, texto: String) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(texto.toByteArray(Charsets.UTF_8)) } }
    private fun nombreDe(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null } ?: "archivo"

    private val elegirRutinaArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { gym.validarImport(leerTexto(uri).removePrefix("\uFEFF")) } catch (e: Exception) { gym.importMensaje = "No se pudo leer el archivo." }
    }
    private val guardarCsvArchivo = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) { gym.csvMensaje = "Exportación cancelada."; return@registerForActivityResult }
        gym.csvMensaje = try { escribirTexto(uri, csvPendiente); "CSV guardado: ${nombreDe(uri)}. Súbelo al chat Evaluador." } catch (e: Exception) { "No se pudo guardar el CSV." }
    }
    private val guardarRespaldoArchivo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = respaldoPendiente
        if (uri == null || p == null) { gym.respaldoMensaje = "Respaldo cancelado: no se guardó ningún archivo."; return@registerForActivityResult }
        try { escribirTexto(uri, p.second); gym.respaldoGuardado(nombreDe(uri)) } catch (e: Exception) { gym.respaldoMensaje = "No se pudo crear el respaldo. Vuelve a intentarlo." }
    }
    private val elegirRespaldoArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { gym.leerRespaldo(leerTexto(uri).removePrefix("\uFEFF")) } catch (e: Exception) { gym.respaldoMensaje = "No se pudo leer el archivo." }
    }
    private val accionesGym = object : AccionesGym {
        override fun elegirRutina() = elegirRutinaArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        override fun guardarCsv() { csvPendiente = gym.textoCsv(); guardarCsvArchivo.launch("genesis-fuerza-${Gimnasio.hoyIso()}.csv") }
        override fun guardarRespaldo() { val p = gym.textoRespaldo(); respaldoPendiente = p; guardarRespaldoArchivo.launch(p.first) }
        override fun elegirRespaldo() = elegirRespaldoArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }

    // E3: Drive. El token queda solo en memoria; los textos de resultado no incluyen el contenido de los archivos.
    private var driveLectura by mutableStateOf("")
    private var driveEscritura by mutableStateOf("")

    private val abrirArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { driveLectura = "No elegiste ningún archivo."; return@registerForActivityResult }
        driveLectura = try {
            var nombre = "(sin nombre)"; var tamano = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { nombre = c.getString(0) ?: nombre; if (!c.isNull(1)) tamano = c.getLong(1) }
            }
            val texto = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val formato = DriveTexto.formatoDe(texto) ?: "no es un archivo de Génesis"
            "Leído: $nombre · ${if (tamano >= 0) "$tamano bytes" else "${texto.length} caracteres"} · formato: $formato"
        } catch (e: Exception) {
            "No se pudo leer el archivo (" + e.javaClass.simpleName + ")"
        }
    }

    private val autorizar = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            escribirPrueba(r.accessToken)
        } catch (e: Exception) {
            driveEscritura = "Google no autorizó el acceso: " + describir(e)
        }
    }

    private fun describir(e: Exception): String = when {
        e is ApiException && e.statusCode == 10 -> "código 10 (en Google Cloud falta el cliente OAuth de tipo Android, o el paquete o la huella SHA-1 no coinciden)"
        e is ApiException && e.statusCode == 16 -> "código 16 (cancelaste la ventana de Google)"
        e is ApiException -> "código ${e.statusCode}"
        e is DriveApi.ErrorDrive && e.codigo == 403 -> "Drive respondió 403 (¿está activada la Google Drive API en el proyecto?)"
        e is DriveApi.ErrorDrive -> e.message ?: "error de Drive"
        else -> e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
    }

    private fun conectarYEscribir() {
        driveEscritura = "Conectando con Google…"
        val pedido = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveTexto.ALCANCE))).build()
        Identity.getAuthorizationClient(this).authorize(pedido)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) autorizar.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else escribirPrueba(r.accessToken)
            }
            .addOnFailureListener { e -> driveEscritura = "Google no autorizó el acceso: " + describir(e) }
    }

    private fun escribirPrueba(token: String?) {
        if (token == null) { driveEscritura = "Google no entregó un permiso de acceso."; return }
        driveEscritura = "Creando el archivo de prueba…"
        lifecycleScope.launch {
            driveEscritura = try {
                val carpeta = DriveApi.carpetaPropia(token, DriveTexto.CARPETA_PRUEBA)
                val sello = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val nombre = DriveApi.subirTexto(token, carpeta, "prueba-e3-$sello.txt",
                    "Archivo de prueba de Génesis Gym N (etapa E3). No contiene datos personales. Se puede borrar.")
                "Listo: creé \"$nombre\" en la carpeta \"${DriveTexto.CARPETA_PRUEBA}\" de tu Drive."
            } catch (e: Exception) {
                "No se pudo escribir en Drive: " + describir(e)
            }
        }
    }

    private val pedirSalud =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { concedidos ->
            permisosConcedidos = concedidos
            refresco++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("e0", Context.MODE_PRIVATE)
        val aperturas = prefs.getInt("aperturas", 0) + if (savedInstanceState == null) 1 else 0
        if (savedInstanceState == null) prefs.edit().putInt("aperturas", aperturas).apply()
        Alarmas.crearCanal(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var pestana by remember { mutableIntStateOf(0) }
                    BackHandler(enabled = gym.vista == "sesion") { gym.ir("hoy") }
                    Column(modifier = Modifier.safeDrawingPadding()) {
                        Row(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("hoy" to "Hoy", "rutina" to "Rutina", "historial" to "Historial", "importar" to "Importar", "pruebas" to "Pruebas").forEach { (v, t) ->
                                val activa = gym.vista == v || (v == "hoy" && gym.vista == "sesion")
                                if (activa) Button(onClick = { gym.ir(v) }) { Text(t) } else OutlinedButton(onClick = { gym.ir(v) }) { Text(t) }
                            }
                        }
                        if (gym.vista != "pruebas") PantallaGimnasio(gym, accionesGym)
                        else {
                            Row(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Avisos", "Salud", "Drive").forEachIndexed { i, t ->
                                    if (pestana == i) Button(onClick = {}) { Text(t) } else OutlinedButton(onClick = { pestana = i }) { Text(t) }
                                }
                            }
                            when (pestana) {
                                0 -> Pantalla(aperturas, refresco)
                                1 -> PantallaSalud(refresco)
                                else -> PantallaDrive()
                            }
                        }
                    }
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
        val horas = remember { mutableStateListOf(Avisos.sugerida(ahora, 2), Avisos.sugerida(ahora, 10), "09:00") }
        var mensaje by remember { mutableStateOf("") }
        val avisos = Alarmas.lista(this)

        Column(
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
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
            Text("Sonido", style = MaterialTheme.typography.titleMedium)
            Text("Suena: " + Alarmas.nombreSonido(this@MainActivity))
            OutlinedButton(onClick = { abrirSelectorSonido() }) { Text("Elegir sonido") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = Alarmas.comoAlarma(this@MainActivity),
                    onCheckedChange = { Alarmas.cambiarSonido(this@MainActivity, Alarmas.sonido(this@MainActivity), it); refresco++ }
                )
                Text("Sonar como alarma (suena aunque el celular esté en silencio)")
            }
            OutlinedButton(onClick = {
                AlarmaReceiver.mostrar(this@MainActivity, "Génesis Gym N · prueba de sonido", "Así va a sonar cada aviso.", 199)
            }) { Text("Probar sonido ahora") }

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

    @Composable
    private fun PantallaSalud(@Suppress("UNUSED_PARAMETER") tick: Int) {
        val estado = HealthConnectClient.getSdkStatus(this)
        val alcance = rememberCoroutineScope()
        var resultado by remember { mutableStateOf<List<String>>(emptyList()) }
        var mensaje by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Prueba de Health Connect", style = MaterialTheme.typography.headlineMedium)
            Text("Etapa E2. Solo cuenta cuántos registros hay en los últimos 30 días y de qué app vienen. No muestra ni guarda valores, y nada sale del celular.")

            HorizontalDivider()
            Text("1. Health Connect", style = MaterialTheme.typography.titleMedium)
            Text("Estado: " + when (estado) {
                HealthConnectClient.SDK_AVAILABLE -> "disponible"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "requiere actualizar Health Connect"
                else -> "no disponible en este celular"
            })
            if (estado != HealthConnectClient.SDK_AVAILABLE) return@Column

            val cliente = HealthConnectClient.getOrCreate(this@MainActivity)
            androidx.compose.runtime.LaunchedEffect(tick) {
                permisosConcedidos = try { cliente.permissionController.getGrantedPermissions() } catch (e: Exception) { emptySet() }
            }

            HorizontalDivider()
            Text("2. Permisos de lectura", style = MaterialTheme.typography.titleMedium)
            Salud.TIPOS.forEachIndexed { i, nombre ->
                val ok = HealthPermission.getReadPermission(tiposSalud[i]) in permisosConcedidos
                Text("$nombre: " + if (ok) "permitido" else "sin permiso")
            }
            Button(onClick = { pedirSalud.launch(permisosSalud) }) { Text("Pedir permisos de lectura") }
            Text("En la ventana de Health Connect puedes marcar solo los que quieras.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("3. Contar registros (30 días)", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                mensaje = "Contando…"
                alcance.launch {
                    val fin = Instant.now()
                    val filtro = TimeRangeFilter.between(fin.minus(30, ChronoUnit.DAYS), fin)
                    resultado = Salud.TIPOS.indices.map { i ->
                        val tipo = tiposSalud[i]
                        val linea = if (HealthPermission.getReadPermission(tipo) !in permisosConcedidos) "sin permiso"
                        else try {
                            val origenes = mutableListOf<String>()
                            var pagina: String? = null
                            do {
                                @Suppress("UNCHECKED_CAST")
                                val clase = tipo as KClass<Record>
                                val r = cliente.readRecords(ReadRecordsRequest(recordType = clase, timeRangeFilter = filtro, pageToken = pagina))
                                r.records.forEach { origenes.add(it.metadata.dataOrigin.packageName) }
                                pagina = r.pageToken
                            } while (pagina != null)
                            Salud.resumen(origenes)
                        } catch (e: Exception) {
                            "error al leer (" + e.javaClass.simpleName + ")"
                        }
                        "${Salud.TIPOS[i]}: $linea"
                    }
                    mensaje = ""
                }
            }) { Text("Contar") }
            if (mensaje.isNotEmpty()) Text(mensaje)
            for (l in resultado) Text(l)

            HorizontalDivider()
            Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun PantallaDrive() {
        Column(
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Prueba de Drive", style = MaterialTheme.typography.headlineMedium)
            Text("Etapa E3. No muestra el contenido de tus archivos: solo el nombre, el tamaño y el formato.")

            HorizontalDivider()
            Text("1. Leer un archivo (sin iniciar sesión)", style = MaterialTheme.typography.titleMedium)
            Text("Se abre el selector de Android. Toca el menú y elige Drive → Genesis Gym → rutinas, por ejemplo.")
            Button(onClick = { abrirArchivo.launch(arrayOf("*/*")) }) { Text("Elegir archivo") }
            if (driveLectura.isNotEmpty()) Text(driveLectura)

            HorizontalDivider()
            Text("2. Escribir en tu Drive (con Google)", style = MaterialTheme.typography.titleMedium)
            Text("Pide el permiso mínimo (drive.file): la app solo ve lo que ella misma crea. Crea un archivo de texto de prueba, sin datos personales, en la carpeta \"${DriveTexto.CARPETA_PRUEBA}\". Nunca sobrescribe.")
            Button(onClick = { conectarYEscribir() }) { Text("Conectar y crear archivo de prueba") }
            if (driveEscritura.isNotEmpty()) Text(driveEscritura)

            HorizontalDivider()
            Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT), style = MaterialTheme.typography.bodySmall)
        }
    }
}
