package io.github.sansolo2000.genesisgym

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Etapa E0: una sola pantalla. Sirve para comprobar que el APK firmado se instala en el S25
 * y que una versión nueva se instala encima sin perder datos (el contador de aperturas).
 * No guarda ni lee datos de salud.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("e0", Context.MODE_PRIVATE)
        val aperturas = prefs.getInt("aperturas", 0) + if (savedInstanceState == null) 1 else 0
        if (savedInstanceState == null) prefs.edit().putInt("aperturas", aperturas).apply()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Android 15+ dibuja la app de borde a borde: safeDrawingPadding deja libre la barra de estado
                    // (hora, batería) y la de navegación. En la 0.0.1 el título quedaba debajo de la barra de estado.
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(Saludo.titulo(), style = MaterialTheme.typography.headlineMedium)
                        Text("Versión de prueba (etapa E0). Tus entrenamientos se siguen registrando en Génesis Gym 1.0.")
                        Text(Saludo.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.COMMIT))
                        Text(Saludo.aperturas(aperturas))
                    }
                }
            }
        }
    }
}
