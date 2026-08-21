plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

/**
 * La versione finisce in una risorsa perché la finestra Info la mostri: il modulo desktop non ha
 * un `BuildConfig`, e leggerla dal manifest del jar non funzionerebbe con `:desktopApp:run`, dove
 * le classi stanno su filesystem.
 */
val generaVersione by tasks.registering {
    val versione = project.property("promemoriaVersion") as String
    val destinazione = layout.buildDirectory.dir("generated/versione")
    inputs.property("versione", versione)
    outputs.dir(destinazione)
    doLast {
        destinazione.get().file("versione.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("versione=$versione\n")
        }
    }
}

sourceSets.main { resources.srcDir(generaVersione) }

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
            packageVersion = project.property("promemoriaVersion") as String
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
