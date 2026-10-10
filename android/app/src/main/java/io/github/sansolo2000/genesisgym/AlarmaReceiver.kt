package io.github.sansolo2000.genesisgym

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.time.ZoneId

/** Llega cuando suena un aviso de prueba: anota la hora real de llegada y muestra la notificación. */
class AlarmaReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(Alarmas.EXTRA_ID, -1)
        val a = Alarmas.lista(c).firstOrNull { it.id == id } ?: return
        val llego = a.copy(llegoMs = System.currentTimeMillis())
        Alarmas.guardar(c, llego)   // se anota aunque no haya permiso de notificaciones

        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Alarmas.crearCanal(c)
        val texto = Avisos.estado(llego, ZoneId.systemDefault())
        val n = Notification.Builder(c, Alarmas.CANAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Génesis Gym N · aviso de prueba ${a.id}")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(100 + a.id, n)
    }
}
