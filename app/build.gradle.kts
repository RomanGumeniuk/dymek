import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Podpis wydania: lokalnie z keystore.properties, w CI ze zmiennych srodowiskowych.
// Bez nich wydanie podpisuje sie kluczem debug (dziala, ale aktualizacja wymaga odinstalowania).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    System.getenv("DYMEK_$name") ?: keystoreProps.getProperty(name)

android {
    namespace = "io.github.romangumeniuk.dymek"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.romangumeniuk.dymek"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"
    }

    signingConfigs {
        val storePath = signingValue("STORE_FILE")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("STORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // SDK korzysta z refleksji (Jackson), wiec bez minifikacji
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.34.0")
}
