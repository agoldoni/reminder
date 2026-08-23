'use strict';

/*
  La pagina fa tre cose separate, e tenerle separate è il punto:

  - chiede al telefono se qualcosa è cambiato, e **solo se è cambiato** ridisegna;
  - ricalcola da sola la fascia cromatica al passare dell'ora, senza chiedere niente;
  - manda le modifiche, quando l'indirizzo con cui è stata aperta lo permette.

  La seconda esiste perché un promemoria che scade alle 18:00 deve diventare "scaduto" alle 18:00
  anche se nessun dato è cambiato. È la controparte della scelta di formattare le date qui invece
  che sul telefono: gli orari seguono il fuso di questo browser, ma in cambio il colore resta
  giusto senza traffico.

  La terza porta con sé il problema che governa metà di questo file: **il ridisegno cancella
  l'elenco e lo ricostruisce**, quindi mentre un modulo è aperto non deve girare. Il modulo sta
  fuori da `#elenco` (prima difesa, in index.html) e il ridisegno si sospende (seconda, qui sotto).
*/

var INTERVALLO_RETE = 30000;
var INTERVALLO_COLORI = 60000;
var GIORNO = 24 * 60 * 60 * 1000;
var VERSIONE_ATTESA = 2;

var token = new URLSearchParams(location.search).get('t') || '';
var etag = null;
var eventi = [];
var primaRisposta = false;
var inCorso = false;
var timerRete = null;

/* Che cosa permette questo indirizzo, e se in questo momento lo permette davvero. */
var puoScrivere = false;
var scritturaDisponibile = false;

/* Mentre è aperto, il ridisegno non gira: dentro c'è quello che l'utente sta scrivendo. */
var moduloAperto = false;
/* L'evento in modifica, o null se si sta creando. */
var inModifica = null;
/* L'ultima completazione, per poterla annullare: {id, dati, updatedAt}. */
var daAnnullare = null;

var elenco = document.getElementById('elenco');
var vuoto = document.getElementById('vuoto');
var avviso = document.getElementById('avviso');
var serveApp = document.getElementById('serve-app');
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
  /* Spento, non nascosto: vedi il commento in index.html accanto a `#serve-app`. */
  b.disabled = !scritturaDisponibile;
  b.addEventListener('click', onClick);
  return b;
}

function riga(evento) {
  var card = document.createElement('article');
  card.className = 'evento ' + fascia(evento);

  var titolo = document.createElement('div');
  titolo.className = 'titolo';
  titolo.textContent = evento.titolo;
  card.appendChild(titolo);

  if (evento.descrizione) {
    var descrizione = document.createElement('div');
    descrizione.className = 'descrizione';
    descrizione.textContent = evento.descrizione;
    card.appendChild(descrizione);
  }

  var quando = document.createElement('div');
  quando.className = 'quando';
  quando.textContent = formatta(evento.dateTimeMillis);
  card.appendChild(quando);

  var notifica = document.createElement('div');
  notifica.className = 'notifica';
  notifica.textContent = 'Notifica: ' + formatta(evento.notificationMillis);
  card.appendChild(notifica);

  if (puoScrivere) {
    var comandi = document.createElement('div');
    comandi.className = 'comandi';
    comandi.appendChild(bottone('Fatto', false, function () { completa(evento); }));
    comandi.appendChild(bottone('Modifica', true, function () { apriModulo(evento); }));
    card.appendChild(comandi);
  }

  return card;
}

function disegna() {
  /* Il modulo è aperto: sotto c'è quello che l'utente sta scrivendo, e non si tocca. */
  if (moduloAperto) return;

  // textContent invece di innerHTML: i titoli sono scritti dall'utente e non devono poter
  // diventare marcatura. Con textContent non c'è niente da sfuggire, quindi niente da dimenticare.
  elenco.textContent = '';
  for (var i = 0; i < eventi.length; i++) elenco.appendChild(riga(eventi[i]));
  vuoto.hidden = !(primaRisposta && eventi.length === 0);

  bottoneNuovo.hidden = !puoScrivere;
  bottoneNuovo.disabled = !scritturaDisponibile;
  serveApp.hidden = !(puoScrivere && !scritturaDisponibile);
  bottoneAnnulla.disabled = !scritturaDisponibile;
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
  eventi = dati.eventi || [];
  puoScrivere = dati.permessi === 'scrittura';
  scritturaDisponibile = !!dati.scritturaDisponibile;
  primaRisposta = true;
  disegna();
}

function controlla() {
  if (inCorso) return; // su rete lenta le richieste non si devono accavallare
  inCorso = true;

  var intestazioni = {};
  if (etag) intestazioni['If-None-Match'] = etag;

  fetch('/api/eventi?t=' + encodeURIComponent(token), {
    cache: 'no-store',
    headers: intestazioni
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
    return risposta.json();
  }).then(function (dati) {
    if (!dati) return;
    mostraAvviso('');
    applica(dati);
  }).catch(function () {
    mostraAvviso('Il telefono non risponde. Controlla che l\'app sia aperta e che la porta ' +
      'sia ancora accesa.');
  }).then(function () {
    inCorso = false;
  });
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
  if (stato === 503) {
    return corpo || 'Apri l\'app sul telefono per modificare.';
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
      // Un 503 vuol dire che l'app si è chiusa: la pagina lo saprà comunque al giro successivo,
      // ma aspettare trenta secondi per spegnere i comandi sarebbe una bugia nel frattempo.
      if (risposta.status === 503) {
        scritturaDisponibile = false;
        if (!moduloAperto) disegna();
      }
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
}

function nascondiAnnulla() {
  daAnnullare = null;
  barraAnnulla.hidden = true;
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
  /* Sospeso finché era aperto: adesso si recupera ciò che nel frattempo è cambiato. */
  disegna();
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

function avviaControlli() {
  if (timerRete === null) timerRete = setInterval(controlla, INTERVALLO_RETE);
}

function fermaControlli() {
  if (timerRete !== null) { clearInterval(timerRete); timerRete = null; }
}

document.addEventListener('visibilitychange', function () {
  if (document.hidden) {
    // Una scheda dimenticata non deve continuare a interrogare il telefono.
    fermaControlli();
  } else {
    controlla(); // subito, non fra trenta secondi: chi torna vuole vedere adesso
    avviaControlli();
  }
});

// Il colore si aggiorna anche a rete ferma: è tutto calcolo locale.
setInterval(disegna, INTERVALLO_COLORI);

controlla();
avviaControlli();
