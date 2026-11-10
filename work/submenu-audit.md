# Audit dei sotto menu (layout e design)

Obiettivo: ogni sotto menu usa gli stessi mattoni (titolo, buddy, sezioni, righe, pulsanti, campi, stati vuoti), con gli stessi spazi.
Metodo: per ciascuno, screenshot (`work/submenus/<nome>.png`, QA 480x640) + lettura del codice; si annota cosa differisce dallo standard e si corregge uno alla volta.

## Standard (da definire dopo il primo giro)
(vedi sezione "Standard" in fondo)

## Elenco (check uno alla volta)
| # | Schermata | debug_screen | Funzione | Stato |
|---|-----------|--------------|----------|-------|
| 1 | Impostazioni | settings | settingsScreen | [x] |
| 2 | Fornitore | dash | providerScreen | [x] |
| 3 | Aspetto | look | lookScreen | [x] |
| 4 | Sicurezza | security | securityScreen | [x] |
| 5 | Registro azioni | actionlog | actionLogScreen | [x] |
| 6 | Info / aggiornamenti | about | aboutScreen | [x] |
| 7 | Strumenti | tools | tools | [x] |
| 8 | Traduttore | translator | translator | [x] |
| 9 | Note | notes | notesScreen | [x] |
| 10 | Sveglie | alarms | alarmsScreen | [x] |
| 11 | Nuova sveglia | alarm_new | alarmEditor | [x] |
| 12 | Timer | timers | timersScreen | [x] |
| 13 | Routine (locali) | routines | localRoutinesScreen | [x] |
| 14 | Nuova routine | routine_new | routineEditor | [x] |
| 15 | Attivita Hermes | jobs | hermesJobsScreen | [x] |
| 16 | Nuova attivita Hermes | (da jobs) | hermesJobEditor | [x] |
| 17 | Competenze | skills | skillsScreen | [x] |
| 18 | App | apps | appsScreen | [x] |
| 19 | Controlli | controls | controlsScreen | [x] |
| 20 | Memoria agente | memory | memoryScreen | [x] |
| 21 | Gestione chat | manage | manageChats | [x] |
| 22 | Spazio agente | space | space | [x] |
| 23 | Editor agente | agent_new | editor | [x] |

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
