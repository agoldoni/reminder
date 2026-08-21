'use strict';

/*
  La pagina fa due cose separate, e tenerle separate è il punto:

  - chiede al telefono se qualcosa è cambiato, e **solo se è cambiato** ridisegna;
  - ricalcola da sola la fascia cromatica al passare dell'ora, senza chiedere niente.

  La seconda esiste perché un promemoria che scade alle 18:00 deve diventare "scaduto" alle 18:00
  anche se nessun dato è cambiato. È la controparte della scelta di formattare le date qui invece
  che sul telefono: gli orari seguono il fuso di questo browser, ma in cambio il colore resta
  giusto senza traffico.
*/

var INTERVALLO_RETE = 30000;
var INTERVALLO_COLORI = 60000;
var GIORNO = 24 * 60 * 60 * 1000;

var token = new URLSearchParams(location.search).get('t') || '';
var etag = null;
var eventi = [];
var primaRisposta = false;
var inCorso = false;
var timerRete = null;

var elenco = document.getElementById('elenco');
var vuoto = document.getElementById('vuoto');
var avviso = document.getElementById('avviso');

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

  return card;
}

function disegna() {
  // textContent invece di innerHTML: i titoli sono scritti dall'utente e non devono poter
  // diventare marcatura. Con textContent non c'è niente da sfuggire, quindi niente da dimenticare.
  elenco.textContent = '';
  for (var i = 0; i < eventi.length; i++) elenco.appendChild(riga(eventi[i]));
  vuoto.hidden = !(primaRisposta && eventi.length === 0);
}

function mostraAvviso(testo) {
  avviso.textContent = testo;
  avviso.hidden = !testo;
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
    eventi = dati.eventi || [];
    primaRisposta = true;
    mostraAvviso('');
    disegna();
  }).catch(function () {
    mostraAvviso('Il telefono non risponde. Controlla che l\'app sia aperta e che la porta ' +
      'sia ancora accesa.');
  }).then(function () {
    inCorso = false;
  });
}

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
