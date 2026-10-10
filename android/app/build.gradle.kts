import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versión: android/version.properties (una sola fuente).
val versiones = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

// Firma: la llave llega SOLO desde los secretos de GitHub, como variables de entorno del flujo de compilación.
// Si no están, no se puede generar el APK de publicación (no hay plan B con otra llave).
val llave: String? = System.getenv("GG_KEYSTORE_PATH")

android {
    namespace = "io.github.sansolo2000.genesisgym"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sansolo2000.genesisgym"
        minSdk = 34          // Android 14: Health Connect viene en el sistema (PROPUESTA-APP-NATIVA §3)
        targetSdk = 35
        versionCode = versiones.getProperty("versionCode").toInt()
        versionName = versiones.getProperty("versionName")
        buildConfigField("String", "COMMIT", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
    }

    signingConfigs {
        if (llave != null) {
            create("publicacion") {
                storeFile = file(llave)
                storePassword = System.getenv("GG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GG_KEY_ALIAS")
                keyPassword = System.getenv("GG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (llave != null) signingConfig = signingConfigs.getByName("publicacion")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.health.connect:connect-client:1.1.0")   // E2: Health Connect, solo lectura
    testImplementation("junit:junit:4.13.2")
}
