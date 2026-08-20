plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // Dispatchers.Main sulla JVM desktop: senza questo viewModelScope non parte
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "it.agoldoni.reminder.desktop.MainKt"

        nativeDistributions {
            packageName = "Promemoria"
            packageVersion = "1.0.0"
            description = "Promemoria e scadenze"
            vendor = "Alberto Goldoni"
            // Moduli JDK non deducibili dal bytecode: JDBC per SQLite bundled, prefs per Java
            modules("java.sql", "java.prefs", "java.naming")

            linux {
                packageName = "promemoria"
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "Utility"
                appCategory = "Utility"
            }
        }
    }
}
