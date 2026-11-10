# Audit dei sotto menu (layout e design)

Obiettivo: ogni sotto menu usa gli stessi mattoni (titolo, buddy, sezioni, righe, pulsanti, campi, stati vuoti), con gli stessi spazi.
Metodo: per ciascuno, screenshot (`work/submenus/<nome>.png`, QA 480x640) + lettura del codice; si annota cosa differisce dallo standard e si corregge uno alla volta.

## Standard (da definire dopo il primo giro)
(vedi sezione "Standard" in fondo)

## Elenco (check uno alla volta)
| # | Schermata | debug_screen | Funzione | Stato |
|---|-----------|--------------|----------|-------|
| 1 | Impostazioni | settings | settingsScreen | vedi matrice |
| 2 | Fornitore | dash | providerScreen | vedi matrice |
| 3 | Aspetto | look | lookScreen | vedi matrice |
| 4 | Sicurezza | security | securityScreen | vedi matrice |
| 5 | Registro azioni | actionlog | actionLogScreen | vedi matrice |
| 6 | Info / aggiornamenti | about | aboutScreen | vedi matrice |
| 7 | Strumenti | tools | tools | vedi matrice |
| 8 | Traduttore | translator | translator | vedi matrice |
| 9 | Note | notes | notesScreen | vedi matrice |
| 10 | Sveglie | alarms | alarmsScreen | vedi matrice |
| 11 | Nuova sveglia | alarm_new | alarmEditor | vedi matrice |
| 12 | Timer | timers | timersScreen | vedi matrice |
| 13 | Routine (locali) | routines | localRoutinesScreen | vedi matrice |
| 14 | Nuova routine | routine_new | routineEditor | vedi matrice |
| 15 | Attivita Hermes | jobs | hermesJobsScreen | vedi matrice |
| 16 | Nuova attivita Hermes | (da jobs) | hermesJobEditor | vedi matrice |
| 17 | Competenze | skills | skillsScreen | vedi matrice |
| 18 | App | apps | appsScreen | vedi matrice |
| 19 | Controlli | controls | controlsScreen | vedi matrice |
| 20 | Memoria agente | memory | memoryScreen | vedi matrice |
| 21 | Gestione chat | manage | manageChats | vedi matrice |
| 22 | Spazio agente | space | space | vedi matrice |
| 23 | Editor agente | agent_new | editor | vedi matrice |

## Note per schermata

### Primo giro (screenshot QA 480x640, work/submenus)
- Intestazione: titolo a quote diverse (impostazioni/informazioni/app a y=73, gli altri a y=82) perche' il buddy piccolo da 64 dp alzava l'altezza. -> altezza minima fissa 70 dp.
- Spazio agente: indietro + buddy + titolo (ordine diverso dagli altri). -> indietro, titolo, buddy a destra.
- Paragrafi introduttivi: 12/13/14/15/16 sp con padding diversi (sicurezza, memoria, informazioni, importa, fornitore). -> `intro()` 15 sp (testata) e `hint()` 13 sp (sotto un controllo).
- Pagine "hub" (impostazioni, informazioni, app) con buddy grande sotto il titolo; le altre col buddy piccolo a destra: regola: hub = buddy grande, foglie ed editor = buddy piccolo.
- Sfumature di accento: strumenti/sistema arancione, agente = colore dell'agente: coerente per dominio, invariato.

### Secondo giro (dopo le correzioni)
- Intestazione: titolo alla stessa quota in tutte le pagine (altezza minima 70 dp, anche spazio agente col buddy a destra).
- Paragrafi: `intro()`/`hint()` in sicurezza, memoria, informazioni, importa, fornitore, aspetto.
- Aspetto: i titoli dei gruppi non sono piu arancioni (l'arancione resta per cio che si tocca): 18 sp bianchi.
- Pagine verificate con screenshot: impostazioni, sicurezza, azioni agente, informazioni, strumenti, traduttore, note, sveglie, nuova sveglia, timer, routine, nuova routine, app, controlli, memoria, gestisci chat, spazio, editor agente, aspetto.
- Pagine con FLAG_SECURE (fornitore) e Hermes (attivita, competenze, nuova attivita): controllate dal codice (stessi helper, `hint()` al posto dei testi a 12-14 sp).

### Terzo giro (le due schermate non viste prima)
- Nuova attivita Hermes (app reale, senza salvare): intestazione, etichette, campi, selettore ora, giorni e `hint()` come "nuova routine": gia coerente.
- Fornitore (FLAG_SECURE, struttura letta con uiautomator): titolo alla stessa quota (y 63-101), etichette e campi uguali alle altre pagine; unica differenza: il pulsante "salva" era verde fisso, mentre salva/avvia/aggiungi nelle altre pagine usano l'accento. Uniformati a `toolAccent` anche "importa", la conferma in Aspetto/voce e il collegamento Hermes (4 pulsanti).
- Restano verdi per scelta solo i pulsanti di approvazione nelle richieste (consenti) e l'interruttore "on".

### Terzo giro: rilettura di TUTTE le pagine dopo la 0.5.24 (screenshot QA in work/pass3, pagine con campi segreti incluse grazie a `--ez qa_visible true`, solo pacchetto QA)
| Pagina | Esito |
|---|---|
| Impostazioni | OK: intestazione a y=82, buddy grande sotto, tessere e righe uguali |
| Fornitore (custom) | OK: etichette/campi uguali, "salva" ora arancione (accento) |
| Fornitore (Hermes) | OK: "collega con QR" come riga standard, campi e puntini, pulsante in accento |
| Aspetto | OK: titoli dei gruppi bianchi 18 sp, schede e chip uniformi |
| Sicurezza | OK: paragrafo `intro()` con 12 dp prima delle righe |
| Azioni agente / registro | OK: stato vuoto centrato come sveglie e routine |
| Informazioni | OK: titolo a y=82; buddy grande; testi 15 sp |
| Strumenti | OK: tessere 2x2, titolo e buddy a destra |
| Traduttore | OK: scheda lingue, campo, pulsanti principale/secondario come note |
| Note | OK: stessa coppia di pulsanti; card nota uguali alle altre card |
| Sveglie / nuova sveglia | OK: stato vuoto e riga "nuova", selettore e chip giorni |
| Timer | OK: selettore, preset, pulsante "avvia" in accento |
| Routine / nuova routine | OK: stesso schema di sveglie, etichette e campi |
| App | OK: buddy grande, ricerca, righe con icona |
| Controlli | corretto: la riga di stato batteria/spazio non aveva i 4 dp di rientro ne i 12 dp sotto: ora `intro()` |
| Memoria | OK (pulsante "aggiungi" col colore dell'agente, per regola di dominio) |
| Gestisci chat | OK: stato vuoto "nessuna chat" |
| Spazio agente | OK: indietro, titolo+descrizione, buddy a destra |
| Editor agente | OK: pulsante "salva" in accento |
| Attivita / competenze / nuova attivita Hermes | OK (app reale): intestazione, righe, campo di ricerca e selettore come gli altri |

### Quarto giro: pagine Hermes e stati (work/pass4)
- Attivita pianificate, competenze, nuova attivita (app reale, dopo la 0.5.25): intestazione con titolo a y=82 e buddy a destra, righe con interruttore, campo di ricerca, selettore ora: OK, come gli altri sotto menu.
- Stato "leggo da Hermes..." (QA, dashboard non raggiungibile): intestazione, buddy e riga "nuova attivita" uguali agli altri; lo stato di errore con "riprova" non l'ho visto entro 28 s: la connessione tenta 3 volte con timeout di 8 s, quindi l'errore compare dopo oltre mezzo minuto. Da accorciare (timeout piu brevi per le liste), non e' un problema di layout.
- Stati con molti dati verificati: note (24), registro azioni (lista), app (decine di righe), competenze (centinaia), attivita (5). Non verificati: sveglie e routine con piu elementi.

### Quinto giro: sveglie e routine con dati, stato di errore Hermes
- Sveglie con 3 elementi e routine con 2 (QA): righe con orario grande/titolo, sottotitolo e interruttore uguali alle righe delle attivita Hermes: OK.
- Errore Hermes: TROVATO UN DIFETTO REALE. Se il caricamento falliva subito (server non raggiungibile), il risultato arrivava prima che la pagina fosse in finestra e veniva scartato: restava "leggo da Hermes..." per sempre. Corretto con `whenAttached()` (attivita e competenze); ora si vede il messaggio ("non riesco a raggiungere il server: controlla rete e indirizzo") e il pulsante "riprova" in accento (aggiunto anche alle competenze). Verificato con screenshot nel QA.

## Matrice finale (cosa e stato davvero verificato, pagina per pagina)
Legenda: V = visto in screenshot dopo l ultima modifica; C = verificato solo leggendo il codice; - = non verificato.

| Pagina | Vuoto | Con dati | Errore / caricamento |
|---|---|---|---|
| Impostazioni | V | V | C |
| Fornitore (custom / Hermes) | V | V | C (esito del "prova" non provocato) |
| Aspetto | V | V | - |
| Sicurezza | V | V | - |
| Azioni agente / registro | V (nessuna azione) | V (5 voci) | - |
| Informazioni | V | V | C (errore aggiornamenti: provato un solo tentativo, saltato per la rotazione del telefono) |
| Strumenti | V | V | - |
| Traduttore | V | V (campo/tasti) | C |
| Note | V | V (24) | - |
| Sveglie | V | V (3) | - |
| Nuova sveglia / timer | V | V | - |
| Routine locali | V | V (2) | - |
| Nuova routine | V | V | - |
| App | V | V | C (caricamento lista) |
| Controlli | V | V | - |
| Memoria | V | - | - |
| Gestisci chat | V | V (1) | - |
| Spazio agente | V | V | - |
| Editor agente | V | V | - |
| Attivita Hermes | V | V (app reale) | V (errore + riprova, QA) |
| Competenze | V | V (app reale) | V (errore + riprova, QA) |
| Nuova attivita Hermes | V | V | C |

Non verificati: gli errori fuori da attivita e competenze (provocarli richiederebbe un server o una rete guasta), "memoria" con note, la lista app con molti dati dopo l'ultima modifica.
