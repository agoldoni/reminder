package it.agoldoni.reminder.platform

/**
 * Identità usate dalla sincronizzazione. L'`actual` vive in `jvmSharedMain`: Android e desktop
 * sono entrambi JVM e condividono `java.util.UUID`.
 */
expect fun newUuid(): String
