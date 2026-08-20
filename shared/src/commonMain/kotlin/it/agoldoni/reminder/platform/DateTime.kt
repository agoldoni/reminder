package it.agoldoni.reminder.platform

/**
 * Data e ora. Gli `actual` vivono in `jvmSharedMain`: Android e desktop sono entrambi JVM,
 * quindi condividono una sola implementazione basata su `java.util.Calendar`.
 */
expect fun nowMillis(): Long

/** `dd/MM/yyyy HH:mm` */
expect fun formatDateTime(millis: Long): String

/** `dd/MM/yyyy` */
expect fun formatDate(millis: Long): String

/** `HH:mm` */
expect fun formatTime(millis: Long): String

/** `yyyyMMdd`, per i nomi dei file esportati. */
expect fun formatFileDate(millis: Long): String

/** Mezzanotte del giorno corrente. */
expect fun startOfToday(): Long

/** Prende anno/mese/giorno da [dateMillis] e li applica a [baseMillis], conservandone l'ora. */
expect fun withDateFrom(baseMillis: Long, dateMillis: Long): Long

/** Applica ora e minuti a [baseMillis], azzerando secondi e millisecondi. */
expect fun withTime(baseMillis: Long, hour: Int, minute: Int): Long

expect fun hourOf(millis: Long): Int

expect fun minuteOf(millis: Long): Int
