plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // org.json reale nei test locali (quello di Android sul JVM è solo uno stub)
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket verso il dashboard di Hermes (Apache-2.0)
    implementation("com.google.zxing:core:3.5.3") // solo per il QR del login (Apache-2.0)
}
android {
    namespace = "dev.r1agents"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.r1agents"
        minSdk = 29          // Android 10+: copre ogni R1 con Android (rabbitOS e ROM di terzi)
        targetSdk = 34
        versionCode = 44
        versionName = "0.5.37"
        testInstrumentationRunner = "dev.r1agents.RuntimeInstrumentation"
        manifestPlaceholders["appLabel"] = "R1 Agents"
    }
    testBuildType = "qa"
    buildTypes {
        // La build debug non deve poter sostituire l'app dell'utente (stesso id = aggiornamento silenzioso con una build debuggabile).
        getByName("debug") {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "R1 Agents debug"
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            manifestPlaceholders["appLabel"] = "R1 Agents QA"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // installabile via adb senza keystore
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
