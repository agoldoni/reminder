package it.agoldoni.reminder.platform

/**
 * Se il sistema mostra già da sé una conferma quando qualcosa finisce negli appunti.
 *
 * Serve a non dire due volte la stessa cosa: da Android 13 il sistema apre una sua anteprima con
 * il testo copiato, e sovrapporle un messaggio dell'app è rumore. Sotto quella versione — dove
 * arriva ancora buona parte dei dispositivi, `minSdk` qui è 26 — non compare nulla, e senza un
 * messaggio nostro l'utente non ha modo di sapere se il tocco ha fatto qualcosa.
 */
expect fun sistemaConfermaLaCopia(): Boolean
