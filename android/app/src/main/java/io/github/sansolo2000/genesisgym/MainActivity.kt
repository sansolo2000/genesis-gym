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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
    private val gym by lazy { Gym(base, Imagenes(this)) {} }
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
    private val elegirPlanArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { gym.validarPlan(leerTexto(uri)) } catch (e: Exception) { gym.planMensaje = "No se pudo leer el archivo." }
    }

    /** Plan Génesis: guarda todas las partes nuevas en una sola transacción y recarga cada módulo. */
    private fun aplicarPlan(r: Plan.Revision, mostrar: Set<String>): String = try {
        val idAlimAntes = (com.estado.programa?.get("programa") as? kotlinx.serialization.json.JsonObject)?.get("id")
        val horaApoyoAntes = apo.horaReporteDe(apo.estado.programa)
        // Medicamentos: el catálogo va a un archivo aparte (no a la base ni al respaldo), antes de guardar el resto.
        r.nuevas.firstOrNull { it.clave == "medicamentos" }?.contenido?.let { AvisosMedicamentos.guardarCatalogo(this, it) }
        // Multiusuario: el perfil (id, módulos, señales y metadatos del plan) va en la misma transacción que las partes.
        val perfil = r.perfil?.let { listOf(Respaldo.Doc(Perfil.RUTA, Perfil.aplicar(gym.perfilDoc(), it, mostrar, Gimnasio.ahoraIso()))) } ?: emptyList()
        // 0.19.0: lo que el plan dice del vademécum (config/vademecum) y su configuración opcional, en la misma transacción.
        val vadem = mutableListOf<Respaldo.Doc>()
        var docV = base.get(Vademecum.RUTA); var cambiaV = false
        r.nuevas.firstOrNull { it.clave == "medicamentos" }?.docs?.firstOrNull()?.data?.let { m ->   // plan de antes, con el catálogo dentro
            docV = Vademecum.docGuardado(docV, (m["version_catalogo"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                (m["registros"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                (m["huella_sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "", "plan", Gimnasio.ahoraIso()); cambiaV = true
        }
        r.vademecum?.let { ref ->
            docV = Vademecum.conEsperado(docV, ref); cambiaV = true
            ref.config?.let { cfg ->
                val actual = r.nuevas.firstOrNull { it.clave == "medicamentos" }?.docs?.firstOrNull()?.data ?: base.get("config/medicamentos")
                vadem.add(Respaldo.Doc("config/medicamentos", kotlinx.serialization.json.JsonObject(LinkedHashMap(actual ?: kotlinx.serialization.json.JsonObject(emptyMap())).also { m ->
                    for (k in listOf("config_horarios", "config_dosis")) cfg[k]?.let { m[k] = it } })))
            }
        }
        if (cambiaV) docV?.let { vadem.add(Respaldo.Doc(Vademecum.RUTA, it)) }
        base.ponerVarios(r.nuevas.flatMap { it.docs } + perfil + vadem)
        val claves = r.nuevas.map { it.clave }.toSet()
        if ("alimentacion" in claves) com.trasImportarPlan(idAlimAntes)
        if ("apoyo" in claves) apo.trasImportarPlan(horaApoyoAntes)
        if ("medicamentos" in claves || r.vademecum != null) { med.olvidarCatalogo(); med.cargar() }
        runCatching { Programador.reprogramar(this) }
        runCatching { AvisosMedicamentos.reprogramar(this) }
        val omitidas = r.partes.filter { it.estado == "igual" }
        (if (r.nuevas.isEmpty()) (if (r.perfilCambia) "Perfil actualizado." else "Plan aplicado.") else "Plan importado: " + r.nuevas.joinToString(", ") { it.nombre.lowercase() } + ".") +
            (r.perfil?.let { " Este celular es de ${it.nombre}." } ?: "") +
            (r.vademecum?.let { " " + Vademecum.texto(AvisosMedicamentos.estadoVademecum(this, base)) } ?: "") +
            (if (omitidas.isNotEmpty()) " Ya estaban activas: " + omitidas.joinToString(", ") { it.nombre.lowercase() } + "." else "")
    } catch (e: Exception) { "No se pudo guardar el plan; no cambió nada. (${e.javaClass.simpleName})" }

    private val accionesGym = object : AccionesGym {
        override fun elegirPlan() = elegirPlanArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        override fun elegirRutina() = elegirRutinaArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        override fun guardarCsv() { csvPendiente = gym.textoCsv(); guardarCsvArchivo.launch("${Gimnasio.sello()}_genesis-fuerza.csv") }
        override fun guardarRespaldo() { runCatching { Programador.respaldarEn(this@MainActivity, base) }; val p = gym.textoRespaldo(); respaldoPendiente = p; guardarRespaldoArchivo.launch(p.first) }
        override fun elegirRespaldo() = elegirRespaldoArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }

    // E5: Comidas. Mismos documentos que la 2.0; cada cambio vuelve a calcular los avisos.
    private val com: Com by lazy { Com(base) { runCatching { Programador.reprogramar(this) }; runCatching { apo.sincronizarComidas() } } }

    // Módulo Apoyo psicológico (contrato v2): pestaña Reporte. Las comidas del reporte salen de Comidas.
    private val apo: Apo by lazy { Apo(base, { com.estado }) { runCatching { Programador.reprogramar(this) } } }
    private val elegirProgramaApoyo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { apo.validar(leerTexto(uri)) } catch (e: Exception) {
            apo.importacion = null to Apoyo.Resultado(false, listOf("No se pudo leer el archivo."), emptyList())
        }
    }
    private val accionesApoyo = object : AccionesApoyo {
        override fun elegirPrograma() = elegirProgramaApoyo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        override fun enviar() {
            apo.enviando = "Conectando con Google…"
            pedirToken({ token ->
                apo.enviando = ""
                if (token == null) { apo.mensaje = "err" to "Google no entregó un permiso de acceso."; return@pedirToken }
                lifecycleScope.launch { apo.subir(token) }
            }, { m -> apo.enviando = ""; apo.mensaje = "err" to "No se pudo enviar a Drive: $m" })
        }
    }

    // Módulo Medicamentos: tratamientos de Héctor y Majo, alertas e historial.
    private val med: Medi by lazy { Medi(this, base) {}.also { m -> m.elegirVademecum = { elegirVademecumArchivo.launch(arrayOf("application/gzip", "application/x-gzip", "application/json", "application/octet-stream", "*/*")) } } }
    /** 0.19.0: el vademécum se elige con el selector (CONTEXTO-MODULO-MEDICAMENTOS §10.12); se lee fuera del hilo de la pantalla. */
    private val elegirVademecumArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { med.importarVademecum { contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.FileNotFoundException(uri.toString()) } }
    }

    // E6: Health Connect para el Evaluador (pestaña Salud).
    private val hc by lazy { Hc(this) }
    private val pedirSaludE6 = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        lifecycleScope.launch { hc.refrescarPermisos() }
    }
    // M-07: cintura (sección de la pestaña Salud).
    private val cin by lazy { Cin(this, base) }
    private val accionesCintura = object : AccionesCintura {
        override fun enviar() {
            cin.enviando = "Conectando con Google…"
            pedirToken({ token ->
                cin.enviando = ""
                if (token == null) { cin.mensaje = "err" to "Google no entregó un permiso de acceso."; return@pedirToken }
                lifecycleScope.launch { cin.subir(token, gym.perfilId()) }
            }, { m -> cin.enviando = ""; cin.mensaje = "err" to "No se pudo enviar a Drive: $m" })
        }
    }
    // Datos diarios, entrega 1: "Subir ahora" en Salud (lee Health Connect con la app abierta).
    private val dd by lazy { Dd(this, base, hc) }
    private val accionesDd = object : AccionesDd {
        override fun subir() {
            dd.enviando = "Conectando con Google…"
            pedirToken({ token ->
                dd.enviando = ""
                if (token == null) { dd.mensaje = "err" to "Google no entregó un permiso de acceso."; return@pedirToken }
                lifecycleScope.launch { dd.subir(token, gym.perfilId()) }
            }, { m -> dd.enviando = ""; dd.mensaje = "err" to "No se pudo enviar a Drive: $m" })
        }
        /** Entrega 2: el ajuste de batería de Android (no pide ningún permiso nuevo). */
        override fun bateria() {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } }
        }
        /** Entrega 2: lectura de Health Connect en segundo plano (aprobada por Héctor, CONTEXTO-DATOS-DIARIOS R2). */
        override fun segundoPlano() = pedirSaludE6.launch(setOf(Hc.PERMISO_SEGUNDO_PLANO))
    }
    private val accionesHc = object : AccionesHc {
        override fun pedirPermisos() = pedirSaludE6.launch(hc.permisos)
        override fun leer() { lifecycleScope.launch { hc.refrescarPermisos(); hc.leer() } }
        override fun enviar() {
            hc.enviando = "Conectando con Google…"
            pedirToken({ token ->
                hc.enviando = ""
                if (token == null) { hc.mensaje = "err" to "Google no entregó un permiso de acceso."; return@pedirToken }
                lifecycleScope.launch { hc.subir(token, gym.perfilId()) }
            }, { m -> hc.enviando = ""; hc.mensaje = "err" to "No se pudo enviar a Drive: $m" })
        }
    }

    /** Como en Comidas: envía sin abrir ventanas si Google ya dio el permiso; si no, queda el botón Enviar. */
    private fun enviarAutomaticoApoyo() {
        if (apo.enviando.isNotEmpty() || apo.estado.pendientes().isEmpty()) return
        val pedido = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveTexto.ALCANCE))).build()
        Identity.getAuthorizationClient(this).authorize(pedido)
            .addOnSuccessListener { r ->
                val tok = r.accessToken
                if (r.hasResolution() || tok == null) { apo.faltaPermiso = true; return@addOnSuccessListener }
                lifecycleScope.launch { apo.subir(tok, automatico = true) }
            }
            .addOnFailureListener { apo.faltaPermiso = true }
    }
    private var fotoTipo: String = ""
    private var fotoCamara: Uri? = null

    private val elegirProgramaArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { com.validar(leerTexto(uri)) } catch (e: Exception) {
            com.importacion = null to Comidas.Resultado(false, listOf("No se pudo leer el archivo."), emptyList())
        }
    }
    private val elegirFotoGaleria = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) guardarFoto(uri) }
    private val tomarFotoCamara = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = fotoCamara; if (ok && u != null) guardarFoto(u) }

    /** Como la 2.0: la foto se reduce a 1280 px por lado y se guarda en JPEG al 75 %, para no llenar el celular ni el Drive. */
    private fun guardarFoto(uri: Uri) {
        val tipo = fotoTipo
        lifecycleScope.launch {
            val dataUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                        val w = info.size.width; val h = info.size.height
                        val k = minOf(1.0, 1280.0 / maxOf(w, h))
                        dec.setTargetSize(Math.round(w * k).toInt().coerceAtLeast(1), Math.round(h * k).toInt().coerceAtLeast(1))
                        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                }.getOrNull()
            }
            if (fotoCamara != null && uri == fotoCamara) runCatching { java.io.File(cacheDir, "fotos/camara.jpg").delete() }
            if (dataUrl == null) com.mensaje = "err" to "No se pudo guardar la foto: no se pudo leer la imagen."
            else com.ponerFoto(tipo, dataUrl)
        }
    }

    private val accionesComidas = object : AccionesComidas {
        override fun elegirPrograma() = elegirProgramaArchivo.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        override fun elegirFoto(tipo: String) { fotoTipo = tipo; elegirFotoGaleria.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        override fun tomarFoto(tipo: String) {
            fotoTipo = tipo
            val f = java.io.File(cacheDir, "fotos").apply { mkdirs() }.resolve("camara.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.archivos", f)
            fotoCamara = u
            try { tomarFotoCamara.launch(u) } catch (e: Exception) { com.mensaje = "err" to "No se encontró una app de cámara. Usa \"Elegir de la galería\"." }
        }
        override fun enviar() {
            com.enviando = "Conectando con Google…"
            pedirToken({ token ->
                if (token == null) { com.enviando = ""; com.mensaje = "err" to "Google no entregó un permiso de acceso."; return@pedirToken }
                lifecycleScope.launch { com.subir(token, "genesis-gym-n ${BuildConfig.VERSION_NAME}") }
            }, { m -> com.enviando = ""; com.mensaje = "err" to "No se pudo enviar a Drive: $m" })
        }
    }

    private var eleccionSonido by mutableIntStateOf(0)
    private var uriSonido by mutableStateOf<Uri?>(null)
    private val elegirSonidoAviso = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) { uriSonido = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java); eleccionSonido++ }
    }
    private val accionesAvisos = object : AccionesAvisos {
        override fun permitirNotificaciones() = pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        override fun abrirAlarmasExactas() = startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        override fun abrirBateria() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        override fun abrirSonidoCelular() = startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        override fun tieneNotificaciones() = this@MainActivity.tieneNotificaciones()
        override fun sinRestriccionBateria() = this@MainActivity.sinRestriccionBateria()
        override fun elegirSonido(actual: Uri?, alarma: Boolean) {
            elegirSonidoAviso.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido de los avisos")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Programador.uriSonido(Programador.Sonido(null, alarma, 0)))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, actual))
        }
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

    // Permiso de Drive (drive.file): quien lo pide deja aquí qué hacer con el token o con el error.
    private var trasToken: ((String?) -> Unit)? = null
    private var trasError: ((String) -> Unit)? = null
    private val autorizar = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            trasToken?.invoke(r.accessToken)
        } catch (e: Exception) {
            trasError?.invoke("Google no autorizó el acceso: " + describir(e))
        }
    }

    /** 0.8.0: envía solo lo que está listo, sin abrir ventanas. Si Google pide confirmar, queda el botón Enviar. */
    private fun enviarAutomatico() {
        if (com.enviando.isNotEmpty() || com.estado.listosParaEnviar() == 0) return
        val pedido = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveTexto.ALCANCE))).build()
        Identity.getAuthorizationClient(this).authorize(pedido)
            .addOnSuccessListener { r ->
                val tok = r.accessToken
                if (r.hasResolution() || tok == null) { com.faltaPermiso = true; return@addOnSuccessListener }
                lifecycleScope.launch { com.subir(tok, "genesis-gym-n ${BuildConfig.VERSION_NAME}", automatico = true) }
            }
            .addOnFailureListener { com.faltaPermiso = true }
    }

    private fun pedirToken(listo: (String?) -> Unit, error: (String) -> Unit) {
        trasToken = listo; trasError = error
        val pedido = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveTexto.ALCANCE))).build()
        Identity.getAuthorizationClient(this).authorize(pedido)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) autorizar.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else listo(r.accessToken)
            }
            .addOnFailureListener { e -> error("Google no autorizó el acceso: " + describir(e)) }
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
        pedirToken({ escribirPrueba(it) }, { driveEscritura = it })
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
        Programador.crearCanal(this)
        // Fondo oscuro: íconos claros en las barras de estado y de navegación.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#111315"))
        com.enviarAuto = { enviarAutomatico() }
        apo.enviarAuto = { enviarAutomaticoApoyo() }
        // O-33 (M-10): hacer en la app lo que pide una alerta la calla.
        com.alHacer = { e -> Alertas.tipoQueCalla(e)?.let { runCatching { AlertaServicio.callarTipo(this, it) } } }
        apo.alGuardarReporte = { runCatching { AlertaServicio.callarTipo(this, Recordatorios.REPORTE) } }
        gym.alAbrirSesion = { runCatching { AlertaServicio.callarTipo(this, Recordatorios.ENTRENAMIENTO) } }
        gym.alDescanso = { fin, texto -> runCatching { if (fin == null) Programador.cancelarDescanso(this) else Programador.programarDescanso(this, fin, texto) } }
        gym.alSerie = { fin, texto -> runCatching { if (fin == null) Programador.cancelarSerie(this) else Programador.programarSerie(this, fin, texto) } }
        gym.alRestaurar = {
            runCatching { Programador.restaurarDesde(this, base) }; runCatching { AvisosMedicamentos.reprogramar(this) }
            // O-57 (QA, 17-oct): los módulos se vuelven a leer de la base restaurada (las señales de un perfil de antes salen de Apoyo).
            runCatching { apo.cargar() }; runCatching { com.cargar() }; runCatching { med.cargar() }
            refresco++
        }
        gym.fraseHoy = { Frases.deHoy(apo.estado.programa) }
        gym.revisarPlan = { t -> Plan.revisar(t, gym.rutinaActiva(), com.estado.programa, apo.estado.programa, gym.perfil(), Gimnasio.ahoraIso(),
            runCatching { AvisosMedicamentos.huellaActiva(this, base) }.getOrNull(), gym.perfilDoc(),
            runCatching { AvisosMedicamentos.estadoVademecum(this, base) }.getOrNull()) }
        gym.aplicarPlan = { r, mostrar -> aplicarPlan(r, mostrar) }
        // Multiusuario: señales de alarma de esta persona y módulos visibles.
        gym.senalesHoy = { Perfil.senalesVigentes(gym.datosPerfil(), apo.estado) }
        gym.alCambiarModulos = { runCatching { Programador.reprogramar(this) }; runCatching { AvisosMedicamentos.reprogramar(this) }; refresco++ }
        apo.alActivar = { nueva, anterior -> runCatching { Programador.horaReporteDesdeApoyo(this, nueva, anterior) }; refresco++ }
        runCatching { Programador.reprogramar(this) }
        runCatching { AvisosMedicamentos.reprogramar(this) }
        runCatching { SubidaDiaria.programar(this) }   // entrega 2: subida automática de datos diarios (WorkManager, cada 6 h)
        intent?.let { abrirDesde(it) }
        if (intent?.getBooleanExtra(Programador.EXTRA_DETENER, false) == true) runCatching { AlertaServicio.detenerTodas(this) }   // O-33

        setContent {
            // 0.7.0: el tema oscuro del mockup en toda la app (pedido de Héctor, 11-oct)
            MaterialTheme(colorScheme = OSCURO) {
                Surface(modifier = Modifier.fillMaxSize(), color = FONDO, contentColor = TEXTO) {
                    var pestana by remember { mutableIntStateOf(0) }
                    BackHandler(enabled = gym.vista == "comidas" && (com.vista != "dia" || com.verImport)) {
                        when { com.verImport && com.estado.programa != null -> com.verImport = false; com.vista == "cambio" -> com.vista = "comida"; com.vista == "receta" -> com.vista = "dia"; else -> com.cerrarComida() }
                    }
                    BackHandler(enabled = gym.vista == "reporte" && (apo.vista == "resumen" || apo.verImport)) {
                        if (apo.vista == "resumen") apo.vista = "reporte" else { apo.verImport = false; apo.importacion = null }
                    }
                    BackHandler(enabled = gym.vista == "medicamentos" && med.vista != "lista") { med.vista = "lista"; med.errores = emptyList() }
                    BackHandler(enabled = gym.vista == "sesion") {
                        when {
                            gym.ficha != null -> gym.ficha = null
                            gym.sesVista == "descanso" -> gym.sesVista = "mapa"   // 0.11.2 (D-03): Atrás ya no corta el descanso
                            gym.sesVista != "mapa" -> gym.sesVista = "mapa"
                            else -> gym.ir("hoy")
                        }
                    }
                    Column(modifier = Modifier.safeDrawingPadding()) {
                        if (gym.vista != "sesion") Row(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Multiusuario: solo las pestañas de los módulos visibles; Hoy, Salud (datos para el Evaluador), Historial (respaldo), Importar y Avisos siempre.
                            @Suppress("UNUSED_VARIABLE") val tk = gym.tick   // se vuelve a dibujar al ocultar un módulo o importar un plan
                            val pf = gym.datosPerfil()
                            val modulo = mapOf("comidas" to Perfil.COMIDAS, "reporte" to Perfil.APOYO, "medicamentos" to Perfil.MEDICAMENTOS, "rutina" to Perfil.ENTRENAMIENTO)
                            listOf("hoy" to "Hoy", "comidas" to "Comidas", "reporte" to "Reporte", "salud" to Perfil.nombrePestanaSalud(pf), "medicamentos" to "Medicamentos", "rutina" to "Rutina", "historial" to "Historial", "importar" to "Importar", "avisos" to "Avisos", "pruebas" to "Pruebas")
                                .filter { (v, _) -> modulo[v]?.let { pf.visible(it) } ?: true }.forEach { (v, t) ->
                                val activa = gym.vista == v || (v == "hoy" && gym.vista == "sesion")
                                val ir = { if (v == "comidas" && gym.vista != "comidas") com.cargar(); if (v == "reporte" && gym.vista != "reporte") apo.refrescar(); if (v == "salud" && gym.vista != "salud") { dd.revisar(); if (hc.disponible()) lifecycleScope.launch { hc.refrescarPermisos() } }
                                    if (v == "medicamentos" && gym.vista != "medicamentos") med.entrar(); gym.ir(v) }
                                Pestana(t, activa, ir)
                            }
                        }
                        // Datos diarios: al abrir la app, Hoy avisa cuántos días faltan por subir.
                        // Entrega 2: si la tarea automática no pudo pedir el permiso de Google sin pantalla, se avisa (no falla en silencio).
                        if (gym.vista == "hoy" && dd.auto.faltaPermisoGoogle) Text(
                            "La subida automática necesita que vuelvas a dar permiso de Google: toca aquí y después \"Subir ahora\"",
                            color = androidx.compose.ui.graphics.Color(0xFFE57373), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(CAJA).clickable { dd.revisar(); gym.ir("salud") }.padding(12.dp))
                        if (gym.vista == "hoy" && dd.porSubir > 0) Text(
                            "${dd.porSubir} ${if (dd.porSubir == 1) "día" else "días"} por subir a Drive · toca para ir a ${Perfil.nombrePestanaSalud(gym.datosPerfil())}",
                            color = AMBAR, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(CAJA).clickable { dd.revisar(); if (hc.disponible()) lifecycleScope.launch { hc.refrescarPermisos() }; gym.ir("salud") }.padding(12.dp))
                        if (gym.vista == "comidas") PantallaComidas(com, accionesComidas)
                        else if (gym.vista == "reporte") PantallaApoyo(apo, accionesApoyo)
                        else if (gym.vista == "salud") PantallaSaludE6(hc, accionesHc, cin, accionesCintura, dd, accionesDd, gym.visible(Perfil.SALUD))
                        else if (gym.vista == "medicamentos") PantallaMedicamentos(med)
                        else if (gym.vista == "avisos") PantallaAvisos(this@MainActivity, accionesAvisos, refresco, eleccionSonido, uriSonido)
                        else if (gym.vista != "pruebas") PantallaGimnasio(gym, accionesGym)
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

    private fun abrirVista(v: String) {
        if (v == "comidas") com.cargar()
        if (v == "reporte") { apo.vista = "reporte"; apo.cargar() }
        if (v == "sesion" && gym.borrador == null) gym.ir("hoy") else gym.ir(v)
    }

    /** Desde una notificación: abre su vista; la de un medicamento abre la toma para responderla (Posponer…). */
    private fun abrirDesde(i: Intent) {
        val v = i.getStringExtra(Programador.EXTRA_VISTA) ?: return
        abrirVista(v)
        if (v == "medicamentos") {
            val tid = i.getStringExtra(AvisosMedicamentos.EXTRA_TRATAMIENTO); val local = i.getStringExtra(AvisosMedicamentos.EXTRA_LOCAL)
            // D-19: calla solo la alerta de esta toma; otra alerta que suene a la vez sigue (O-38).
            if (tid != null && local != null) runCatching { AvisosMedicamentos.callarNotificacion(tid, java.time.LocalDateTime.parse(local)) }
            med.abrirRespuesta(tid, local, i.getStringExtra(AvisosMedicamentos.EXTRA_EVENTO))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirDesde(intent)
        if (intent.getBooleanExtra(Programador.EXTRA_DETENER, false)) runCatching { AlertaServicio.detenerTodas(this) }   // O-33
    }

    override fun onPause() {
        super.onPause()
        runCatching { Programador.reprogramar(this) }   // al salir, los avisos quedan al día con lo marcado
        runCatching { AvisosMedicamentos.reprogramar(this) }
    }

    override fun onResume() {
        super.onResume()
        refresco++   // al volver de Ajustes o de una notificación, se vuelven a leer permisos y avisos
        runCatching { dd.revisar() }   // datos diarios: "N días por subir"
    }

    /** Pestaña con la forma del mockup: píldora ámbar si está activa, gris oscuro si no. */
    @Composable
    private fun Pestana(texto: String, activa: Boolean, alTocar: () -> Unit) {
        androidx.compose.foundation.layout.Box(
            Modifier.heightIn(min = 44.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
                .background(if (activa) AMBAR else CAJA)
                .clickable(onClick = alTocar)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(texto, color = if (activa) androidx.compose.ui.graphics.Color(0xFF1A1300) else GRIS,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
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
