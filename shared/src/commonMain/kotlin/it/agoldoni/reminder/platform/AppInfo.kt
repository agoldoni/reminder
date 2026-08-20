package it.agoldoni.reminder.platform

/** Dati di build mostrati nella finestra Info; ogni piattaforma li fornisce a modo suo. */
data class AppInfo(
    val author: String,
    val version: String,
    val build: String,
    val buildDate: String
)
