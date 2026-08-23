'use strict';

/*
  La pagina fa tre cose separate, e tenerle separate è il punto:

  - **resta in attesa** che il telefono le dica che qualcosa è cambiato, e aggiorna la sola scheda
    che è cambiata;
  - ricalcola da sola la fascia cromatica al passare dell'ora, senza chiedere niente;
  - manda le modifiche, quando l'indirizzo con cui è stata aperta lo permette.

  La prima era, fino alla feature 005, «chiede ogni trenta secondi e se qualcosa è cambiato
  ridisegna tutto». Erano due difetti che si tenevano per mano, e sono stati tolti insieme perché
  toglierne uno solo avrebbe peggiorato la pagina: un elenco che si ricostruisce per intero due
  volte al minuto è un difetto che si nota a fatica, ma lo stesso elenco che si ricostruisce per
  intero **appena qualcuno tocca qualcosa** sarebbe un lampeggio addosso a chi sta leggendo.

  La seconda esiste perché un promemoria che scade alle 18:00 deve diventare "scaduto" alle 18:00
  anche se nessun dato è cambiato. È la controparte della scelta di formattare le date qui invece
  che sul telefono: gli orari seguono il fuso di questo browser, ma in cambio il colore resta
  giusto senza traffico.

  La terza porta con sé il problema che governa metà di questo file: **mentre un modulo è aperto il
  ridisegno non deve girare.** La ragione non è più che l'elenco venga cancellato — non lo è — ma
  che la riga in modifica è una scheda dell'elenco *sotto* il dialogo: aggiornare le altre può
  spostarla, perché l'ordine dipende dalla data, e chi chiude il modulo si ritroverebbe il contesto
  cambiato sotto. Il modulo sta fuori da `#elenco` (prima difesa, in index.html) e il ridisegno si
  sospende (seconda, qui sotto).
*/

/*
  Quanto aspetta il **server** prima di rispondere `304` a una richiesta in attesa. Qui serve solo
  per sapere quando smettere di crederci: se le due costanti divergono, quella di qui dev'essere la
  più grande — un client che si arrende prima che il telefono risponda butterebbe via la risposta
  proprio mentre stava arrivando.
*/
var ATTESA_SERVER = 25000;
var MARGINE_GUARDIA = 10000;

/*
  **Il pavimento fra due richieste, e non è una precauzione teorica.**

  Il ciclo si riprogramma appena una risposta arriva: è ciò che rende immediato l'aggiornamento
  quando il telefono aspetta davvero. Ma il telefono può rispondere *subito* per due ragioni
  legittime — è una versione precedente che il parametro `attendi` non lo conosce, oppure ci sono
  già troppe attese aperte e questa è stata respinta — e in entrambi i casi «riparti appena arriva»
  diventa un ciclo stretto che martella la porta.

  Con il pavimento, quei due casi degradano in un polling ogni cinque secondi, che è esattamente
  ciò che si vuole: più lento del push, più vivo del ritmo di prima, e mai una raffica.
*/
var INTERVALLO_MINIMO = 5000;

/* Il rientro dopo un guasto raddoppia fino a questo tetto: un telefono spento non va martellato. */
var RITARDO_MINIMO = 1000;
var RITARDO_MASSIMO = 30000;

var INTERVALLO_COLORI = 60000;
var GIORNO = 24 * 60 * 60 * 1000;
var VERSIONE_ATTESA = 2;

/* Quanto resta accesa l'evidenziazione di una scheda cambiata, prima di iniziare a svanire. */
var DURATA_EVIDENZA = 2000;

var token = new URLSearchParams(location.search).get('t') || '';
var etag = null;
var eventi = [];
var primaRisposta = false;
var inCorso = false;

/* Il giro in corso, per poterlo interrompere; il prossimo, per poterlo disdire; la guardia. */
var controllore = null;
var timerGiro = null;
var timerGuardia = null;
var ritardo = RITARDO_MINIMO;

/* La scheda è nascosta: nessun giro nuovo. [interrotta] distingue *chi* ha chiuso la richiesta. */
var fermato = false;
var interrotta = false;

/* Che cosa permette l'indirizzo con cui questa pagina è stata aperta. */
var puoScrivere = false;

/* Mentre è aperto, il ridisegno non gira: dentro c'è quello che l'utente sta scrivendo. */
var moduloAperto = false;
/* L'evento in modifica, o null se si sta creando. */
var inModifica = null;
/* L'ultima completazione, per poterla annullare: {id, dati, updatedAt}. */
var daAnnullare = null;

/*
  I tre pezzi di stato che l'aggiornamento mirato porta con sé.

  [schede] sono i nodi vivi, per `id`; [disegnati] è la lista **come è stata disegnata**, che non è
  sempre `eventi` — a modulo aperto il ridisegno si ferma e `eventi` va avanti da solo, e la
  differenza fra le due è esattamente ciò che si applica alla chiusura. [permessiDisegnati] ricorda
  con quali permessi le schede sono state costruite: i comandi ci sono o non ci sono, quindi se
  cambiano non basta aggiornarle, vanno rifatte.
*/
var schede = {};
var disegnati = [];
var permessiDisegnati = null;

var elenco = document.getElementById('elenco');
var vuoto = document.getElementById('vuoto');
var avviso = document.getElementById('avviso');
var bottoneNuovo = document.getElementById('nuovo');
var barraAnnulla = document.getElementById('annulla-barra');
var testoAnnulla = document.getElementById('annulla-testo');
var bottoneAnnulla = document.getElementById('annulla');

var modulo = document.getElementById('modulo');
var moduloTitolo = document.getElementById('modulo-titolo');
var moduloErrore = document.getElementById('modulo-errore');
var campoTitolo = document.getElementById('campo-titolo');
var campoDescrizione = document.getElementById('campo-descrizione');
var campoQuando = document.getElementById('campo-quando');
var campoAnticipo = document.getElementById('campo-anticipo');

/*
  `dd/MM/yyyy HH:mm`, lo stesso formato di formatDateTime nell'app. Scritto a mano e non con
  Intl.DateTimeFormat perché quello, in italiano, infila una virgola fra data e ora: sarebbe una
  differenza visibile fra le due viste, ed è proprio ciò che si vuole evitare.
*/
function dueCifre(n) { return n < 10 ? '0' + n : '' + n; }

function formatta(ms) {
  var d = new Date(ms);
  return dueCifre(d.getDate()) + '/' + dueCifre(d.getMonth() + 1) + '/' + d.getFullYear() +
    ' ' + dueCifre(d.getHours()) + ':' + dueCifre(d.getMinutes());
}

/*
  Il valore che vuole `<input type="datetime-local">`: `yyyy-MM-ddTHH:mm`, sempre nell'ora locale
  di questo browser. `toISOString()` non va bene — converte in UTC, e l'orario mostrato slitterebbe.
*/
function perCampo(ms) {
  var d = new Date(ms);
  return d.getFullYear() + '-' + dueCifre(d.getMonth() + 1) + '-' + dueCifre(d.getDate()) +
    'T' + dueCifre(d.getHours()) + ':' + dueCifre(d.getMinutes());
}

function daCampo(valore) {
  /* `new Date('2026-08-23T15:30')` senza fuso è interpretato come ora locale: è ciò che serve. */
  var t = new Date(valore).getTime();
  return isNaN(t) ? null : t;
}

function inizioDiOggi() {
  var d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/* Le stesse quattro fasce di EventCard, nello stesso ordine di precedenza. */
function fascia(evento) {
  var ora = Date.now();
  if (evento.dateTimeMillis < ora) return 'scaduto';
  var oggi = inizioDiOggi();
  if (evento.dateTimeMillis < oggi + GIORNO) return 'oggi';
  if (evento.dateTimeMillis < oggi + 2 * GIORNO) return 'domani';
  return 'futuro';
}

function bottone(etichetta, principale, onClick) {
  var b = document.createElement('button');
  b.type = 'button';
  b.className = principale ? 'azione principale' : 'azione';
  b.textContent = etichetta;
  b.addEventListener('click', onClick);
  return b;
}

/* --- Le schede ------------------------------------------------------------------------------ */

/*
  **I dati vivono sul nodo, non nella closure dei gestori**, ed è la differenza fra questa versione
  e quella precedente. Prima i pulsanti catturavano `evento` in una closure, e funzionava perché una
  scheda viveva meno di un aggiornamento: al giro dopo il nodo non c'era più, e con lui la closure.

  Da quando le schede si riusano, la closure sopravvivrebbe ai dati: un tocco su «Fatto» manderebbe
  l'`updatedAt` che quella scheda aveva **quando è stata creata**, e chi guarda vedrebbe «modificato
  sul telefono nel frattempo» dopo aver toccato un pulsante su una scheda che mostra il valore
  giusto. I gestori leggono `card._evento` al momento del click, che è sempre quello vero.
*/
function creaScheda(evento) {
  var card = document.createElement('article');
  card.className = 'evento';

  var titolo = document.createElement('div');
  titolo.className = 'titolo';
  card.appendChild(titolo);

  /*
    La descrizione c'è **sempre**, anche quando è vuota, e allora si nasconde con `hidden`.
    Crearla e distruggerla a seconda del contenuto vorrebbe dire che l'ordine dei figli di una
    scheda dipende dai dati, e l'aggiornamento in loco dovrebbe cercarli invece di conoscerli.
    Un nodo vuoto e nascosto costa niente; `[hidden]` in app.css garantisce che sparisca davvero.
  */
  var descrizione = document.createElement('div');
  descrizione.className = 'descrizione';
  card.appendChild(descrizione);

  var quando = document.createElement('div');
  quando.className = 'quando';
  card.appendChild(quando);

  var notifica = document.createElement('div');
  notifica.className = 'notifica';
  card.appendChild(notifica);

  card._parti = { titolo: titolo, descrizione: descrizione, quando: quando, notifica: notifica };

  if (puoScrivere) {
    var comandi = document.createElement('div');
    comandi.className = 'comandi';
    // `card` è il nodo, che non cambia; `card._evento` sono i dati, che cambiano. Leggerli qui
    // dentro invece di catturarli è tutta la differenza.
    comandi.appendChild(bottone('Fatto', false, function () { completa(card._evento); }));
    comandi.appendChild(bottone('Modifica', true, function () { apriModulo(card._evento); }));
    card.appendChild(comandi);
  }

  aggiornaScheda(card, evento);
  return card;
}

/* Scrive i dati in una scheda che esiste già. textContent, per la ragione di sempre. */
function aggiornaScheda(card, evento) {
  var p = card._parti;
  p.titolo.textContent = evento.titolo;
  p.descrizione.textContent = evento.descrizione || '';
  p.descrizione.hidden = !evento.descrizione;
  p.quando.textContent = formatta(evento.dateTimeMillis);
  p.notifica.textContent = 'Notifica: ' + formatta(evento.notificationMillis);
}

/*
  **La fascia si scrive con `classList`, mai con `className`.**

  `card.className = 'evento ' + fascia` — come faceva la versione precedente — sovrascrive l'intera
  lista di classi, evidenziazione compresa. Il difetto sarebbe intermittente per costruzione:
  l'evidenziazione sparisce solo quando il ricalcolo dei colori capita a passare nei due secondi in
  cui è accesa, cioè raramente, e mai mentre la si sta cercando. Vale in entrambe le direzioni —
  nemmeno l'evidenziazione deve poter cancellare la fascia.
*/
function impostaFascia(card, nuova) {
  if (card._fascia === nuova) return;
  if (card._fascia) card.classList.remove(card._fascia);
  card.classList.add(nuova);
  card._fascia = nuova;
}

/*
  L'evidenziazione della scheda appena cambiata: il rovescio dell'aggiornamento mirato.

  Togliere il ridisegno totale toglie il fastidio del lampeggio e, nello stesso gesto, toglie anche
  il **segnale**: una riga su quaranta che cambia senza che nulla lo dica si può semplicemente non
  vedere. Compare di scatto e svanisce piano — l'apparizione deve prendere l'occhio, la sparizione
  no. Le due velocità stanno in app.css e non qui, dove ci sono solo i millisecondi.
*/
function evidenzia(card) {
  card.classList.add('cambiata');
  if (card._timerEvidenza) clearTimeout(card._timerEvidenza);
  card._timerEvidenza = setTimeout(function () {
    card._timerEvidenza = null;
    card.classList.remove('cambiata');
  }, DURATA_EVIDENZA);
}

/* Una scheda che se ne va porta con sé il suo timer: altrimenti scatterebbe su un nodo staccato. */
function scarta(card) {
  if (card._timerEvidenza) {
    clearTimeout(card._timerEvidenza);
    card._timerEvidenza = null;
  }
}

/* --- La riconciliazione ---------------------------------------------------------------------- */

/*
  Che cosa conta come «cambiata»: solo i campi **visibili**.

  Non `updatedAt`, che è il numero di versione della riga e può muoversi senza che sullo schermo
  cambi niente — una riscrittura identica arrivata dalla sincronizzazione, per esempio. Evidenziare
  quella vorrebbe dire annunciare un cambiamento che non c'è.
*/
function differisce(a, b) {
  return a.titolo !== b.titolo ||
    (a.descrizione || '') !== (b.descrizione || '') ||
    a.dateTimeMillis !== b.dateTimeMillis ||
    a.notificationMillis !== b.notificationMillis;
}

/*
  **Funzione pura**: due liste entrano, le operazioni che portano dalla prima alla seconda escono.
  Non tocca il DOM, non legge variabili globali, non guarda l'ora.

  Tenerla separata dall'applicazione non è ordine per il gusto dell'ordine. È l'unico pezzo di
  questo file che si potrebbe provare da solo il giorno in cui il progetto avesse una ragione
  indipendente per avere un banco di prova JavaScript; renderla impura chiuderebbe quella porta per
  sempre. E serve già adesso a una seconda cosa: **quali schede evidenziare è scritto qui dentro**,
  nel tipo dell'operazione, e non va ricavato una seconda volta da un secondo confronto — due
  confronti prima o poi divergono.

  Chiave: `id`. È nel payload dalla feature 002, messo lì per una ragione diversa, e il browser
  parla con un solo dispositivo, quindi basta come identità.

  I tre tipi, e uno di essi merita una nota: **una scheda che si sposta senza cambiare è
  `invariata`.** Il riordino nasce dal fatto che *un'altra* ha cambiato data, e chi non è cambiato
  non deve essere annunciato come se lo fosse. La posizione la sistema chi applica.
*/
function riconcilia(precedenti, nuovi) {
  var prima = {};
  var i;
  for (i = 0; i < precedenti.length; i++) prima[precedenti[i].id] = precedenti[i];

  var operazioni = [];
  var visti = {};
  for (i = 0; i < nuovi.length; i++) {
    var evento = nuovi[i];
    var vecchio = prima[evento.id];
    visti[evento.id] = true;
    operazioni.push({
      tipo: !vecchio ? 'inserita' : (differisce(vecchio, evento) ? 'aggiornata' : 'invariata'),
      evento: evento
    });
  }

  var rimossi = [];
  for (i = 0; i < precedenti.length; i++) {
    if (!visti[precedenti[i].id]) rimossi.push(precedenti[i].id);
  }

  return { operazioni: operazioni, rimossi: rimossi };
}

/*
  Le operazioni sul DOM. Si cammina l'ordine di arrivo e si mette ogni scheda al suo posto:
  `insertBefore` su un nodo che è già nel documento lo **sposta**, non lo duplica, ed è ciò che
  rende il riordino una riga sola invece di un caso a parte.
*/
function applicaOperazioni(esito, conEvidenza) {
  var i;

  for (i = 0; i < esito.rimossi.length; i++) {
    var uscita = schede[esito.rimossi[i]];
    if (!uscita) continue;
    scarta(uscita);
    if (uscita.parentNode) uscita.parentNode.removeChild(uscita);
    delete schede[esito.rimossi[i]];
  }

  for (i = 0; i < esito.operazioni.length; i++) {
    var op = esito.operazioni[i];
    var card = schede[op.evento.id];

    if (!card) {
      card = creaScheda(op.evento);
      schede[op.evento.id] = card;
    } else if (op.tipo === 'aggiornata') {
      aggiornaScheda(card, op.evento);
    }

    /*
      **Sempre**, anche su `invariata`, ed è una riga che sembra ridondante e non lo è: i campi
      visibili possono essere identici mentre `updatedAt` è andato avanti. Lasciando qui i dati
      vecchi, il controllo ottimistico della prossima scrittura dichiarerebbe una versione che non
      esiste più — cioè lo stesso difetto della closure, entrato da un'altra porta.
    */
    card._evento = op.evento;
    impostaFascia(card, fascia(op.evento));

    if (conEvidenza && op.tipo !== 'invariata') evidenzia(card);

    var attuale = elenco.children[i];
    if (attuale !== card) elenco.insertBefore(card, attuale || null);
  }
}

/* Si riparte da zero: cambiati i permessi, le schede hanno una forma diversa (i comandi). */
function azzeraElenco() {
  for (var id in schede) scarta(schede[id]);
  schede = {};
  disegnati = [];
  elenco.textContent = '';
}

/*
  [conEvidenza] dice se le schede toccate devono farsi notare. È `false` in due casi, e sono
  entrambi lo stesso caso travestito:

  - **alla prima risposta**, dove ogni scheda è «appena inserita» e evidenziarle tutte vorrebbe dire
    far lampeggiare l'intera pagina all'apertura — il difetto che l'aggiornamento mirato esiste per
    togliere, rientrato dalla porta di servizio;
  - **alla chiusura del modulo**, dove il ridisegno è rimasto fermo per minuti e riparte con tutte
    le modifiche accumulate: annunciarle in blocco non è un segnale, è un lampo.
*/
function disegna(conEvidenza) {
  /* Il modulo è aperto: sotto c'è quello che l'utente sta scrivendo, e non si tocca. */
  if (moduloAperto) return;

  if (permessiDisegnati !== puoScrivere) {
    azzeraElenco();
    permessiDisegnati = puoScrivere;
  }

  applicaOperazioni(riconcilia(disegnati, eventi), conEvidenza === true);
  /* `eventi` viene **sostituito** a ogni risposta, mai modificato sul posto: tenerne il
     riferimento è sicuro, e alla chiusura del modulo `disegnati` è ancora ciò che si vede. */
  disegnati = eventi;

  vuoto.hidden = !(primaRisposta && eventi.length === 0);
  bottoneNuovo.hidden = !puoScrivere;
}

/*
  Il colore al passare dell'ora, **senza ricostruire niente**: cambia una classe e basta.

  Prima questo era `disegna()` chiamato ogni sessanta secondi, cioè l'elenco intero rifatto per
  cambiare quattro sfondi. Non ha bisogno della rete e non ha bisogno del DOM: è tutto calcolo
  locale su dati che sono già qui.
*/
function aggiornaColori() {
  for (var id in schede) {
    var card = schede[id];
    impostaFascia(card, fascia(card._evento));
  }
}

function mostraAvviso(testo) {
  avviso.textContent = testo;
  avviso.hidden = !testo;
}

/*
  Che cosa fare di una risposta che porta la lista: `200`, `201` e anche `409`.

  Il `409` la porta di proposito — chi ha perso il confronto deve vedere **il valore vero**, e ce
  l'ha già dentro la risposta che gli dice di aver perso. Un giro di rete in meno, e nessuna
  finestra in cui la pagina mostra il valore sbagliato.
*/
function assorbi(risposta, dati) {
  etag = risposta.headers.get('ETag');
  applica(dati);
}

function applica(dati) {
  if (!dati) return;
  if (dati.versione !== VERSIONE_ATTESA) {
    mostraAvviso('Questa pagina è di una versione diversa da quella del telefono. ' +
      'Ricarica per aggiornarla.');
    return;
  }
  /* Prima che `primaRisposta` diventi vera: è questa la distinzione che quella variabile serve a
     fare, e la ragione per cui l'apertura della pagina non evidenzia quaranta schede insieme. */
  var evidenziabile = primaRisposta;
  eventi = dati.eventi || [];
  puoScrivere = dati.permessi === 'scrittura';
  primaRisposta = true;
  disegna(evidenziabile);
}

/*
  Un giro di rete: si chiede, si aspetta, si riparte.

  **La differenza con la versione precedente è tutta nel parametro `attendi`**: la richiesta non
  torna appena il telefono ha guardato, torna quando c'è qualcosa da dire — o dopo venticinque
  secondi, se non è successo niente. Il ciclo non ha più un intervallo: si riprogramma da sé alla
  fine di ogni giro, ed è questo a rendere immediato l'aggiornamento invece di lasciarlo cadere nel
  prossimo scatto di un timer.

  Il parametro si manda sempre. Un telefono che non lo conosce lo ignora e risponde subito: è la
  compatibilità all'indietro, e non costa una riga di codice in più — costa il pavimento di
  [INTERVALLO_MINIMO], che c'è comunque.
*/
function giro() {
  if (inCorso || fermato) return;
  inCorso = true;
  interrotta = false;
  annullaProssimo();

  var partenza = Date.now();
  var cambiato = false;

  var intestazioni = {};
  if (etag) intestazioni['If-None-Match'] = etag;

  controllore = new AbortController();
  /*
    La guardia. Una `fetch` che non si risolve mai — e succede: una connessione che muore senza
    che nessuno lo dica, un telefono che si addormenta a metà frase — bloccherebbe il ciclo per
    sempre, e la pagina resterebbe a mostrare dati vecchi **con l'aria di essere aggiornata**. È il
    guasto peggiore possibile qui: non si vede.
  */
  timerGuardia = setTimeout(function () {
    timerGuardia = null;
    controllore.abort();
  }, ATTESA_SERVER + MARGINE_GUARDIA);

  fetch('/api/eventi?t=' + encodeURIComponent(token) + '&attendi=1', {
    cache: 'no-store',
    headers: intestazioni,
    signal: controllore.signal
  }).then(function (risposta) {
    if (risposta.status === 304) {
      // Niente di nuovo: non si ridisegna, così la posizione di scorrimento resta dov'era.
      mostraAvviso('');
      return null;
    }
    if (risposta.status === 403) {
      // Su una **lettura** il 403 vuol dire una cosa sola: l'indirizzo è scaduto. Su una
      // scrittura ne vorrebbe dire un'altra — vedi `spiegaRifiuto`.
      mostraAvviso('Indirizzo non più valido: la porta è stata riaperta e serve il nuovo ' +
        'indirizzo mostrato sul telefono.');
      return null;
    }
    if (!risposta.ok) {
      mostraAvviso('Il telefono ha risposto ' + risposta.status + '.');
      return null;
    }
    etag = risposta.headers.get('ETag');
    cambiato = true;
    return risposta.json();
  }).then(function (dati) {
    if (dati) {
      mostraAvviso('');
      applica(dati);
    }
    chiudiGiro();
    ritardo = RITARDO_MINIMO;
    /*
      Se qualcosa è cambiato si riparte subito: un `200` vuol dire che l'impronta si è mossa, e
      l'impronta non si muove senza che qualcuno abbia scritto — quindi non può diventare una
      raffica. Se invece non è cambiato niente, il pavimento: vedi [INTERVALLO_MINIMO].
    */
    programma(cambiato ? 0 : Math.max(0, INTERVALLO_MINIMO - (Date.now() - partenza)));
  }).catch(function () {
    var voluta = interrotta;
    interrotta = false;
    chiudiGiro();

    if (voluta) {
      /* L'abbiamo chiusa noi perché la scheda è sparita. Se nel frattempo è tornata — la `catch`
         può arrivare dopo — si riparte adesso, altrimenti si riparte quando torna. */
      if (!fermato) programma(0);
      return;
    }

    mostraAvviso('Il telefono non risponde. Controlla che l\'app sia aperta e che la porta ' +
      'sia ancora accesa.');
    programma(ritardo);
    ritardo = Math.min(ritardo * 2, RITARDO_MASSIMO);
  });
}

function chiudiGiro() {
  inCorso = false;
  controllore = null;
  if (timerGuardia !== null) {
    clearTimeout(timerGuardia);
    timerGuardia = null;
  }
}

function programma(fra) {
  annullaProssimo();
  if (fermato) return;
  timerGiro = setTimeout(function () {
    timerGiro = null;
    giro();
  }, fra);
}

function annullaProssimo() {
  if (timerGiro !== null) {
    clearTimeout(timerGiro);
    timerGiro = null;
  }
}

/* --- Scritture ------------------------------------------------------------------------------ */

/*
  Il 403 su una scrittura non significa quello che significa su una lettura: qui vuol dire che
  questo è l'indirizzo di sola lettura. Dirlo «indirizzo scaduto» manderebbe a riaccendere
  l'interruttore per niente. La distinzione si fa **qui**, in base a che cosa si stava facendo:
  sul filo le due risposte sono identiche di proposito.
*/
function spiegaRifiuto(stato, corpo) {
  if (stato === 403) {
    return 'Questo indirizzo permette solo di guardare. Per modificare serve l\'altro indirizzo, ' +
      'quello che il telefono mostra sotto «Serve anche modificare dal browser?».';
  }
  if (stato === 409) {
    return 'Questo promemoria è stato modificato sul telefono nel frattempo. ' +
      'Qui sopra c\'è il valore aggiornato: se serve, rifai la modifica.';
  }
  if (stato === 422) {
    return corpo || 'Un campo non è valido.';
  }
  if (stato === 404) {
    return 'Questo promemoria non esiste più.';
  }
  return 'Il telefono ha risposto ' + stato + '.';
}

/*
  Manda una scrittura. `onFatto` riceve l'header che dice che cosa è stato scritto; `onErrore`
  riceve il messaggio già in italiano — e **non** viene chiamato con il modulo chiuso, così il
  testo digitato non va perso.
*/
function invia(percorso, metodo, corpo, onFatto, onErrore) {
  fetch(percorso + '?t=' + encodeURIComponent(token), {
    method: metodo,
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(corpo)
  }).then(function (risposta) {
    if (risposta.ok) {
      var scritto = risposta.headers.get('X-Promemoria-Scritto');
      return risposta.json().then(function (dati) {
        assorbi(risposta, dati);
        mostraAvviso('');
        onFatto(scritto);
      });
    }
    if (risposta.status === 409) {
      // Anche il conflitto porta la lista aggiornata: si assorbe **prima** di dirlo, così quando
      // il messaggio compare la pagina mostra già il valore vero.
      return risposta.json().then(function (dati) {
        assorbi(risposta, dati);
        onErrore(spiegaRifiuto(409, null));
      });
    }
    return risposta.text().then(function (testo) {
      onErrore(spiegaRifiuto(risposta.status, testo));
    });
  }).catch(function () {
    onErrore('Il telefono non risponde. Quello che hai scritto è ancora qui: riprova.');
  });
}

function completa(evento) {
  invia('/api/eventi/' + evento.id, 'PUT', {
    titolo: evento.titolo,
    descrizione: evento.descrizione || null,
    dateTimeMillis: evento.dateTimeMillis,
    advanceMinutes: anticipoDi(evento),
    completato: true,
    attesoUpdatedAt: evento.updatedAt
  }, function (scritto) {
    // Da qui in poi l'evento non è più nella lista — sono solo gli aperti — quindi il suo
    // `updatedAt` nuovo esiste solo in questo header. Senza, l'annullamento non avrebbe che cosa
    // dichiarare nel controllo ottimistico.
    var pezzi = (scritto || '').split(':');
    daAnnullare = {
      evento: evento,
      updatedAt: pezzi.length === 2 ? parseInt(pezzi[1], 10) : null
    };
    mostraAnnulla(evento.titolo);
  }, mostraAvviso);
}

function annullaCompletamento() {
  if (!daAnnullare || daAnnullare.updatedAt === null) return;
  var evento = daAnnullare.evento;
  invia('/api/eventi/' + evento.id, 'PUT', {
    titolo: evento.titolo,
    descrizione: evento.descrizione || null,
    dateTimeMillis: evento.dateTimeMillis,
    advanceMinutes: anticipoDi(evento),
    completato: false,
    attesoUpdatedAt: daAnnullare.updatedAt
  }, function () {
    nascondiAnnulla();
    mostraAvviso('');
  }, function (messaggio) {
    nascondiAnnulla();
    mostraAvviso(messaggio);
  });
}

function mostraAnnulla(titolo) {
  testoAnnulla.textContent = '«' + titolo + '» segnato come fatto.';
  barraAnnulla.hidden = false;
  spazioPerLaBarra();
}

/*
  L'elenco cede spazio alla barra ancorata, che altrimenti coprirebbe l'ultima scheda proprio
  mentre la si guarda. Si misura invece di indovinare: il testo va a capo su schermi stretti e
  l'altezza non è una costante.
*/
function spazioPerLaBarra() {
  document.body.style.paddingBottom =
    barraAnnulla.hidden ? '' : (barraAnnulla.offsetHeight + 32) + 'px';
}

function nascondiAnnulla() {
  daAnnullare = null;
  barraAnnulla.hidden = true;
  spazioPerLaBarra();
}

/*
  L'anticipo non viaggia come tale: il server manda `notificationMillis` già calcolato, perché la
  formula non venga riscritta qui. Per rimandarlo indietro si ricava la differenza — che è esatta,
  perché è la stessa sottrazione fatta al contrario sugli stessi due numeri.
*/
function anticipoDi(evento) {
  return Math.round((evento.dateTimeMillis - evento.notificationMillis) / 60000);
}

/* --- Il modulo ------------------------------------------------------------------------------ */

function apriModulo(evento) {
  inModifica = evento || null;
  moduloTitolo.textContent = evento ? 'Modifica promemoria' : 'Nuovo promemoria';
  campoTitolo.value = evento ? evento.titolo : '';
  campoDescrizione.value = evento && evento.descrizione ? evento.descrizione : '';
  /* Gli stessi valori iniziali dell'app: fra un'ora, quindici minuti di anticipo. */
  campoQuando.value = perCampo(evento ? evento.dateTimeMillis : Date.now() + 3600000);
  campoAnticipo.value = evento ? String(anticipoDi(evento)) : '15';
  erroreModulo('');

  moduloAperto = true;
  modulo.showModal();
  campoTitolo.focus();
}

function chiudiModulo() {
  moduloAperto = false;
  inModifica = null;
  modulo.close();
  /* Sospeso finché era aperto: adesso si recupera ciò che nel frattempo è cambiato — **senza
     evidenziare**, perché sarebbero tutte insieme le modifiche di minuti, cioè un lampo. */
  disegna(false);
}

function erroreModulo(testo) {
  moduloErrore.textContent = testo;
  moduloErrore.hidden = !testo;
}

function salvaModulo() {
  var titolo = campoTitolo.value.trim();
  if (!titolo) {
    erroreModulo('Il titolo non può essere vuoto.');
    campoTitolo.focus();
    return;
  }
  var quando = daCampo(campoQuando.value);
  if (quando === null) {
    erroreModulo('Data e ora non sono valide.');
    campoQuando.focus();
    return;
  }

  var corpo = {
    titolo: titolo,
    descrizione: campoDescrizione.value.trim() || null,
    dateTimeMillis: quando,
    advanceMinutes: parseInt(campoAnticipo.value, 10)
  };

  erroreModulo('');

  if (inModifica) {
    corpo.completato = false;
    corpo.attesoUpdatedAt = inModifica.updatedAt;
    invia('/api/eventi/' + inModifica.id, 'PUT', corpo, chiudiModulo, erroreModulo);
  } else {
    invia('/api/eventi', 'POST', corpo, chiudiModulo, erroreModulo);
  }
}

document.getElementById('modulo-salva').addEventListener('click', salvaModulo);
document.getElementById('modulo-annulla').addEventListener('click', chiudiModulo);
/* Esc chiude il dialogo per conto suo: senza questo il ridisegno resterebbe sospeso per sempre. */
modulo.addEventListener('close', function () {
  if (moduloAperto) chiudiModulo();
});
bottoneNuovo.addEventListener('click', function () { apriModulo(null); });
bottoneAnnulla.addEventListener('click', annullaCompletamento);

/* --- Il ritmo ------------------------------------------------------------------------------- */

/*
  **Interrompere la richiesta in corso, non solo il timer.**

  Prima qui bastava fermare un `setInterval`, che è istantaneo. Adesso una richiesta è già partita e
  il telefono la tiene appesa fino a venticinque secondi: senza `abort`, ogni scheda dimenticata in
  fondo a una finestra si porterebbe via una coroutine e un socket per tutto quel tempo, e il tetto
  delle attese verrebbe consumato da pagine che nessuno sta guardando.
*/
document.addEventListener('visibilitychange', function () {
  if (document.hidden) {
    fermato = true;
    annullaProssimo();
    if (controllore) {
      interrotta = true;
      controllore.abort();
    }
  } else {
    fermato = false;
    ritardo = RITARDO_MINIMO;
    /* Se un giro è ancora in corso — la `catch` dell'interruzione può non essere ancora arrivata —
       sarà lei a far ripartire il ciclo: chiamare `giro()` qui non farebbe niente e lascerebbe la
       pagina ferma per sempre. */
    if (!inCorso) giro();
  }
});

// Il colore si aggiorna anche a rete ferma: è tutto calcolo locale, e adesso non ridisegna niente.
setInterval(aggiornaColori, INTERVALLO_COLORI);

giro();
