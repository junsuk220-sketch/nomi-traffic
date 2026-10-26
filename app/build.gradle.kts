import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "nomi.traffic"
    compileSdk = 36

    defaultConfig {
        applicationId = "nomi.traffic"
        minSdk = 26
        targetSdk = 36
        versionCode = 182
        versionName = "1.0.1-next-train"
        buildConfigField("String", "SEOUL_SUBWAY_API_KEY", quote(seoulSubwayApiKey()))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}

fun seoulSubwayApiKey(): String {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props.getProperty("SEOUL_SUBWAY_API_KEY", "").trim()
}

fun quote(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
