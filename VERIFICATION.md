# Prove e limiti — 8 novembre 2026

La verifica completa è aperta. Sorgenti/candidato successivi all’APK installato: vedere HANDOVER.md. Implementato, automatico e provato dal vivo sono stati distinti.

## Candidato funzionale

Percorsi relativi a `work/functional/`:

| Evidenza | Esito / copertura |
| --- | --- |
| `checkpoint-build.log` | Release/QA/runner, lint, 91 test JVM PASS; catalogo 560 testi it/en verificato |
| `baseline.log` | Prima delle correzioni: 8 fallimenti su 10, conservati come riproduzione |
| `device-crud-final.log` | 27 PASS: backup/rollback/merge/segreti/date, dati e paginazione, agenti e routine, ricevitori disattivati/singoli, allegati univoci/interrotti/limiti/tardivi, mic in background; CRUD UI note, memoria, sveglie, routine, editor, agenti e timer |
| `voice-regression.log` | Voce, annullamento, invio unico, rotazione, navigazione e bozze PASS con mic/server simulati |
| `runtime-regression.log` | 13 PASS: foreground/background, notifica, stop per chat, recupero senza reinvio, QR, PIN, inglese, aggiornamenti/firma diversa |
| `CHECKPOINT.json`, `device-artifacts.json` | Hash e provenienza: APK personale installato, QA effettivamente provato e candidato successivo separati |

Dopo le prove native: modificati solo payload del ramo e guardia della callback. Build/unit/lint ripetuti; ramo corretto **non ancora provato sul server**. Provider dei file lenti/interrotti presente solo in `app/src/qa`, escluso dalla release. Ricevitori testati intercettando l’avvio dei servizi, senza suono; mic simulato non certifica riconoscimento reale.

## Hermes dal vivo

Nuova chat **Prova R1 funzioni 20261108**: risposta unica `R1-OK-20261108` e rinomina conservata dopo riapertura PASS. Ramo fallito: `session_id: Extra inputs are not permitted`; nessun ramo creato. Screenshot `hermes-branch-open.png`. Corretto in sorgente a `parent_session_id`, risultato `stored_session_id`, secondo i [contratti ufficiali](https://github.com/NousResearch/hermes-agent/blob/main/tui_gateway/contracts/sessions.py). Gli altri comandi reali restano aperti nella ROADMAP.

## Build visiva installata

Evidenza in `work/development-0.4.0/`: `scenes-exact-release.log` PASS (23 destinazioni, due orientazioni, scroll/tastiera/PIN, 5.759 frame senza assenza del buddy); suite voce/sviluppo e 13 regressioni generali PASS. `installed-interface.json` prova APK compilato/estratto identici. Non attribuire questi risultati automaticamente al nuovo candidato.

Tre misure release in `interface-release-profile/`: **14/641, 12/645, 12/639** frame janky, p95 **16/16/15 ms**, p99 **38/36/38 ms**. Zero scatti non raggiunto. Le reazioni producono più frame: percentuale minore da sola non dimostra costo inferiore. FrameMetrics QA e jank gfxinfo sono misure diverse; registrazione/rete reali da profilare.

Audio reale rinviato: fumetto ricevuto, suono assente; nessun servizio TTS installato, volume multimediale non muto (7/30). Nessun motore aggiunto. HTTPS, fotocamera, suonerie e provider reali mancanti non sono certificati dai mock.

I manifest di checkpoint conservano hash degli snapshot originali, compresi documenti poi riscritti. La successiva pulizia ha rimosso cache, screenshot ripetuti e prove intermedie. Esiti finali, APK di riferimento, sorgenti e chiavi sono conservati; dettagli in `work/cleanup.json`. XML dei 91 test e lint salvati in `work/functional/`.

## Ripetizione sul candidato — 8 novembre 2026 (pomeriggio)

Build QA/runner/lint, 91 test JVM e catalogo (560 testi) senza errori; correzione eliminazione chat Hermes compresa. Su Rabbit, solo `dev.r1agents.qa`: generale (13 controlli), funzionale (27), voce, scene (23 destinazioni, due orientazioni), bordi scene e sviluppo **PASS**. Due esecuzioni su quattro delle scene sono terminate con `Riattiva e sblocca il Rabbit`: guardia di precondizione (schermo spento), non difetto dell'app; al rilancio con schermo attivo PASS. Resta aperto tutto ciò che richiede server o hardware reali (ROADMAP 1–6, 8).

**Mock rigoroso (ROADMAP 2, parziale):** `MockHermes` ora rifiuta metodi sconosciuti (-32601), parametri mancanti o non previsti (es. `session_id` nel ramo) e `delete`/`archive` su sessioni attive. Suite generale: 13 controlli PASS, incluso il nuovo «contratto Hermes» (ramo con id distinto e cronologia copiata, archivio, eliminazione sconosciuta e attiva rifiutate, chat locale conservata dopo il rifiuto). Esteso anche a: allegati immagine/PDF (base64 e formato validati), compressione, comandi con barra, modelli (conferma per modello costoso), sottoagenti (elenco, indirizzamento, stop) — PASS. Anche REST: routine (crea con profilo, pausa, riprendi, esegui, elimina, doppia eliminazione rifiutata) e skill (elenco, attiva, nome inesistente rifiutato) — PASS; voce, scene e funzionale ripetute col mock severo: PASS. Tutto su Hermes reale resta aperto.

## Passata visiva dei menu — 8 novembre 2026

22 schermate aperte sul Rabbit (pacchetto QA, 5 s di assestamento, app riavviata a ogni schermata): menu, impostazioni, fornitore (dash), aspetto (look), sicurezza, gestisci chat, strumenti, sveglie, nuova sveglia, timer, traduttore, note, memoria, attività pianificate, nuova attività/routine, competenze, nuovo agente/aspetto, informazioni, spazio agente, scheda. Tutte a regime: freccia indietro, titolo, buddy in alto a destra, accento unico, stesse righe e tile. Attività e competenze mostrano «leggo da hermes…» perché la copia QA punta al server simulato spento. Non coperti: rotella; azioni su Hermes reale.

## Interazioni, standby, timer, colori — 8 novembre 2026 (sera)

Sul Rabbit (pacchetto QA, schermo orizzontale 640×480 com'era), con registrazioni e fotogrammi in `smoke-out/transitions/`:
- **Scroll e buddy:** affonda a metà e sfuma scorrendo, rispunta con un cenno tornando in cima; tirato oltre l'inizio si allunga e compare il viso con sopracciglia (fastidio/offesa). Controllo automatico in DevelopmentQa: affonda, si allunga, tre tiri → broncio, tocco → perdono.
- **Standby (Glance):** orologio, data, batteria, buddy che sbircia; al tocco morph verso la home con il nome dell'agente. PASS.
- **Timer:** tamburo con inerzia (flick 5→15→25→20 min, ritorno a 1 min), avvio, scheda con conto alla rovescia (00:36, «scade alle 15:41»), icona sveglia nella barra, annulla con scheda che sfuma e si chiude; allarme cancellato. Nessun squillo reale provato.
- **Colori:** accento arancione→blu→verde→rosa→rosso, modo scuro→chiaro→scuro e ritorno ai valori predefiniti. Aggiunta la dissolvenza incrociata: prima c'era un lampo scuro con buddy in volo durante `recreate()`.
- **Regressioni:** 91 test JVM, catalogo 560 testi, lint; suite generale (13), funzionale (27), voce, scene, bordi scene, sviluppo PASS. Test scena e voce aggiornati: il buddy può affondare scorrendo, in cima deve essere intero.
Non provati: rotella fisica, standby reale con schermo spento (non risveglio né spengo il Rabbit), squillo del timer, font diversi da rubik, Hermes reale.

## Comparse del buddy, font e squillo — 8 novembre 2026 (notte)

- **22 voci di BuddyEntrances** registrate sul Rabbit (14 comparse + 8 sparizioni); le comparse mostrano movimento fluido da tutti i lati; le sparizioni lasciano la riga fuori vista come previsto (nel gioco sono seguite da una comparsa). Nascondino con tre tocchi: ride due volte, poi sprofonda e rispunta. Controllo automatico in DevelopmentQa: ogni voce parte, si muove a metà corsa e la riga torna a riposo; nascondino compreso. 3 test JVM sul repertorio.
- **Font:** rubik, roboto, flex, source, serif, mono, typewriter, casual, script, smallcaps applicati e letti sul Rabbit (preferenza salvata verificata); ripristinato rubik.
- **Squillo timer/sveglia:** schermata di anteprima provata (buddy in posa d'attesa che apre gli occhi, titolo, «stop», «rimanda 10 min»); nessun suono avviato, stop e posticipa non toccati.
- **Regressioni:** 94 test JVM, lint, catalogo 560 testi; suite generale (13), funzionale, voce, scene, bordi scene, sviluppo PASS.
Non provati: rotella fisica, standby con schermo spento, squillo sonoro reale, Hermes reale.

## Hermes reale con l'app installata — 8 novembre 2026 (sera)
Richiesta dell'utente: «prova con quella buona» (app 0.4.0 installata). Sulla chat «prova r1 funzioni 20261108`: **compressione PASS** («comprimo… / compressione: compressed»). **Ramo non verificato**: il tocco successivo non ha creato nessuna chat nuova (l'elenco resta con la chat di prova a 4 messaggi e «riassunto delle note», chat preesistente dell'utente solo letta); il Rabbit stava ruotando in mano all'utente (rotazione 0↔270) e i tocchi di adb hanno colpito altre voci. Archivio ed eliminazione non provati. Il ramo corretto è comunque solo nel candidato, non installabile sopra la 0.4.0 per la firma diversa.

**Ramo su Hermes reale (app 0.4.0 installata, Rabbit fermo in orizzontale):** rifiutato dal server con «invalid params for session.branch_stored: session_id: Extra inputs are not permitted — the client and the Hermes backend are out of sync». Nessuna chat creata, nessun effetto. Conferma il difetto del payload della 0.4.0 (`session_id` al posto di `parent_session_id`), già corretto nel candidato, non installato per la firma diversa. Ramo corretto ancora da riprovare con il candidato. Archivio/eliminazione non provati (servirebbe una copia del ramo; non uso la chat di prova né le chat dell'utente).

## Consegna 0.4.1 — 8 novembre 2026 (sera)
- **Squillo sonoro reale** (pacchetto QA, timer 10 s): notifica «timer finito» con «rimanda 10 min» / «stop», riproduzione `USAGE_ALARM` avviata e poi fermata con «stop»; dopo lo stop nessuna riproduzione né notifica.
- **Hermes reale, app installata:** nuova chat + messaggio → risposta «OK»; **archivio PASS** (chat sparita senza errori); eliminazione con la 0.4.0: la chat tornava al riallineamento (errore remoto non mostrato); con la 0.4.1 il server risponde «cannot delete an active session», l'app lo mostra e conserva la chat. Dai contratti ufficiali `session.delete` rifiuta le sessioni live: la 0.4.1 ora riprende e chiude la sessione prima di eliminarla (coperto dal mock con sessioni «live»). La prova dal vivo con questa ultima correzione non è stata completata (azione bloccata dal controllo di sicurezza).
- **Release:** `tools/check.sh --copy` (94 test, lint), `tools/release-sign.sh`, `tools/publish-update.py` (manifest locale con URL segnaposto); suite QA generale PASS con la firma diversa rifiutata.
