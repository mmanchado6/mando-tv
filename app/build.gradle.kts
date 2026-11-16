import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Clave de firma propia, creada por docker/dentro.sh la primera vez (carpeta firma/).
val firma = Properties().apply {
    val f = rootProject.file("firma/firma.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Runtime de Android que usa Robolectric en los tests. Lo descarga Gradle (queda en la caché) y
// Robolectric funciona sin conexión, en vez de descargarlo por su cuenta en cada ejecución.
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
val copyRobolectricRuntime = tasks.register<Copy>("copyRobolectricRuntime") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

android {
    namespace = "local.mandotv"
    compileSdk = 37

    defaultConfig {
        applicationId = "local.mandotv"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (firma.isNotEmpty()) {
            create("release") {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Privacidad: no incrustar en el APK el bloque cifrado de metadatos de dependencias
    // (solo sirve para Google Play).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
            all {
                it.systemProperty("capturas.dir", rootProject.file("salida/capturas").absolutePath)
                // ProjectGuardTest: revisa el manifiesto que acaba en el APK de release y el código fuente.
                it.dependsOn("processReleaseManifest")
                it.systemProperty(
                    "manifest.release",
                    layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
                        .get().asFile.absolutePath,
                )
                it.systemProperty("main.sources", file("src/main/java").absolutePath)
                it.maxHeapSize = "2g"
                it.dependsOn(copyRobolectricRuntime)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
                // Robolectric necesita acceder a clases internas del JDK para simular Android 17.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    robolectricRuntime("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
