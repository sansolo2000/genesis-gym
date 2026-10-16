package io.github.sansolo2000.genesisgym

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * M-10 — hace sonar una alerta hasta "Detener" o "Posponer", con el tope de 2 minutos (después se calla y la
 * notificación queda). Si llegan varias, se ve una notificación por tipo y suena la de mayor prioridad
 * (Entrenamiento > Comidas > Tareas > Reporte). Al detener una se detienen todas.
 */
class AlertaServicio : Service() {
    companion object {
        private const val SONAR = "sonar"
        /** El servicio en curso (mismo proceso), para que Detener y Posponer lo callen. */
        var actual: AlertaServicio? = null
            private set

        fun sonar(c: Context, datos: Intent, id: Int): Intent = Intent(datos).setClass(c, AlertaServicio::class.java).setAction(SONAR).putExtra(Programador.EXTRA_ID, id)

        private fun canalesQueInsisten() = Alertas.TIPOS.filter { Alertas.insiste(it) }.map { Programador.canal(it) }.toSet()

        /** "Detener" (o tocar la notificación, O-33): calla y quita todas las alertas, también las que quedaron después del tope. */
        fun detenerTodas(c: Context) {
            actual?.detenerTodo()
            val nm = c.getSystemService(NotificationManager::class.java)
            val canales = canalesQueInsisten()
            for (n in nm.activeNotifications) if (n.notification.channelId in canales) nm.cancel(n.id)
            Programador.detener(); Programador.dejarDeVibrar(c)
        }

        /** O-33: marcar la comida o la tarea, abrir la sesión o guardar el reporte calla la alerta de ese tipo. */
        fun callarTipo(c: Context, tipo: String) {
            actual?.quitarTipo(tipo)
            val nm = c.getSystemService(NotificationManager::class.java)
            for (n in nm.activeNotifications) if (n.notification.channelId == Programador.canal(tipo) && n.id != actual?.idEnPrimerPlano()) nm.cancel(n.id)
        }
    }

    fun idEnPrimerPlano(): Int? = idPrimerPlano

    /** Quita las alertas de un tipo. Si era la que sonaba, se calla y se cierra el servicio; si sonaba otra, sigue. */
    fun quitarTipo(t: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val ids = activas.filterValues { it == t }.keys.toList()
        if (ids.isEmpty() && tipoSonando != t) return
        for (id in ids) if (id != idPrimerPlano) { nm.cancel(id); activas.remove(id) }
        if (tipoSonando == t) {
            callar()
            for (id in ids) activas.remove(id)
            detenerServicio(quitarNotificacion = ids.contains(idPrimerPlano))
        }
    }

    private val activas = LinkedHashMap<Int, String>()   // id de la notificación → tipo
    private var idPrimerPlano: Int? = null
    private var notifPrimerPlano: android.app.Notification? = null
    private var tipoSonando: String? = null
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private val tope = Runnable { callar(); detenerServicio(quitarNotificacion = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); actual = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != SONAR) { if (activas.isEmpty()) stopSelf(); return START_NOT_STICKY }
        val t = intent.getStringExtra(Programador.EXTRA_TIPO) ?: Recordatorios.COMIDA
        val id = intent.getIntExtra(Programador.EXTRA_ID, 300)
        val n = Programador.construir(this, intent, id)
        // Cada startForegroundService pide su startForeground: se repite con la notificación que ya estaba en primer plano.
        val fg = idPrimerPlano; val fgN = notifPrimerPlano
        if (fg == null || fgN == null) { startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); idPrimerPlano = id; notifPrimerPlano = n }
        else { startForeground(fg, fgN, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); getSystemService(NotificationManager::class.java).notify(id, n) }
        activas[id] = t
        if (Alertas.reemplaza(tipoSonando, t)) sonarTipo(t)
        return START_NOT_STICKY
    }

    private fun sonarTipo(t: String) {
        callar()
        val v = Programador.tipo(this, t)
        val e = Programador.estado(this)
        if (Alertas.suena(v, e.timbre, e.filtro, e.alarmasPermitidas)) mp = Programador.preparar(this, v, true)?.also { it.start() }
        if (Alertas.vibra(v, e.timbre)) Programador.vibrar(this, true)
        tipoSonando = t
        h.removeCallbacks(tope); h.postDelayed(tope, Alertas.TOPE_MS)
    }

    /** Calla el sonido y la vibración; las notificaciones quedan. */
    fun callar() {
        mp?.let { runCatching { it.stop() }; it.release() }; mp = null
        Programador.dejarDeVibrar(this)
        tipoSonando = null
        h.removeCallbacks(tope)
    }

    /** "Detener": calla y quita todas las notificaciones de alertas, salvo las de Medicamentos (esperan Confirmar, Posponer u Omitir). */
    fun detenerTodo() {
        callar()
        val nm = getSystemService(NotificationManager::class.java)
        for ((id, t) in activas) if (!Alertas.esperaRespuesta(t)) nm.cancel(id)
        val quedaPrimerPlano = idPrimerPlano?.let { activas[it] }?.let { Alertas.esperaRespuesta(it) } == true
        activas.clear()
        detenerServicio(quitarNotificacion = !quedaPrimerPlano)
    }

    /** "Posponer" de una notificación: calla todo y quita solo esa notificación. */
    fun posponer(id: Int) {
        callar()
        getSystemService(NotificationManager::class.java).cancel(id)
        activas.remove(id)
        detenerServicio(quitarNotificacion = id == idPrimerPlano)
    }

    private fun detenerServicio(quitarNotificacion: Boolean) {
        stopForeground(if (quitarNotificacion) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        idPrimerPlano = null; notifPrimerPlano = null
        stopSelf()
    }

    override fun onDestroy() { callar(); if (actual === this) actual = null; super.onDestroy() }
}

/** Botones "Detener" y "Posponer" de una alerta, y la notificación descartada (cuenta como Detener). */
class AccionAlerta : BroadcastReceiver() {
    companion object { const val DETENER = "detener"; const val POSPONER = "posponer"; const val BORRADA = "borrada" }

    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(Programador.EXTRA_ID, -1)
        val nm = c.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            POSPONER -> {
                Programador.posponer(c, intent)
                AlertaServicio.actual?.posponer(id) ?: nm.cancel(id)
            }
            else -> AlertaServicio.detenerTodas(c)   // al detener una se detienen todas
        }
        Programador.detener(); Programador.dejarDeVibrar(c)
    }
}
