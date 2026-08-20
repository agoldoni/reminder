plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
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

application { mainClass.set("it.agoldoni.reminder.desktop.MainKt") }
