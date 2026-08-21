import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Room genera l'actual object del costruttore del database: expect/actual object sono in Beta
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    // Gruppo intermedio dei due target JVM: qui java.* è disponibile (OdsExporter, date)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(libs.material.icons.core)
                api(libs.room.runtime)
                api(libs.lifecycle.viewmodel.compose)
                api(libs.navigation.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.junit)
            }
        }
        val desktopMain by getting {
            // Gli asset della web app (HTML, CSS, JS, icone) servono al codice di `jvmSharedMain`,
            // che è un source set intermedio: le sue `resources/` non vengono raccolte da AGP —
            // verificato con `:shared:sourceSets` — e finirebbero nel jar desktop ma non nell'APK.
            // Perciò stanno in una cartella neutra dichiarata a **entrambi** i target: qui e in
            // `android.sourceSets["main"]` più sotto. Metterle in un solo posto darebbe una pagina
            // bianca su una delle due piattaforme, e solo a runtime.
            resources.srcDir("src/webAssets")
            dependencies {
                // Su desktop SQLite non è garantito dal sistema: driver bundled
                implementation(libs.sqlite.bundled)
                // Su Android mDNS lo fa NsdManager, qui serve una libreria: jmdns
                implementation(libs.jmdns)
            }
        }
    }
}

android {
    namespace = "it.agoldoni.reminder.shared"
    // L'altra metà della dichiarazione degli asset: vedi il commento in `desktopMain`.
    sourceSets["main"].resources.srcDir("src/webAssets")
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}
