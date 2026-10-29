plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.javier.reservas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.javier.reservas"
        // Android 7.0 o superior (los equipos Sunmi V2 / P2 traen Android 7.1 u 11)
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
        // Solo equipos reales (ARM de 64 y 32 bits, como los Sunmi): el APK pesa menos sin las librerías del emulador
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Los modelos de inteligencia artificial (.onnx) se guardan tal cual, sin comprimir
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.print:print:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    // Lector de QR (funciona sin internet y sin Google Play Services)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Impresora interna Sunmi (V2, P2, T2, D2…)
    implementation("com.sunmi:printerlibrary:1.0.18")
    // Cámara para leer placas
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // OCR de Google (incluido en la app: funciona sin internet y sin Google Play)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // IA para leer placas (detector + lector), corre en el equipo sin internet. ONNX Runtime de Microsoft (MIT)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Envío y descarga en segundo plano (solo con internet, cuida la batería)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Saber cuándo la app pasa a segundo plano (para pedir la clave del equipo)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
}
