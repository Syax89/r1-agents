# R1 Agents — stato attuale (10 novembre 2026)

**Versione 0.5.33 (code 40) pubblicata e installata sul Rabbit** (release GitHub v0.5.33, `update.json` su `main`). R1 Agents resta la schermata iniziale.
- Firma: la release e' ancora firmata con la chiave debug di questo Mac; il passaggio al keystore dedicato (`../signing-private`, mai pubblicato) e' la fase 4 della review e richiede una reinstallazione pulita, solo con ok esplicito dell'utente.
- Qualita': 130 test JVM, lint, 6 suite strumentate sul Rabbit (generale, development, functional, scenes, scene_edges, voice). `device-check.sh` trova Java da solo; `check.sh` compila solo la release: il QA va compilato con `assembleQa assembleQaAndroidTest` prima delle suite. `development` dura piu' di 3 minuti (usare timeout >= 330 s).
- Prove visive solo nel pacchetto QA: `--ez qa_visible true` (pagine con campi segreti), `--ez qa_portrait true` / `--ez qa_landscape true` (orientamento della sola activity). `debug_screen`, `debug_req`, `debug_md` funzionano solo nelle build debuggabili; `tools/smoke.sh` e `tools/perf.py` usano `dev.r1agents.qa`.
- Sotto menu: audit e checklist finale in `work/submenu-audit.md` (21 pagine, verticale e orizzontale, con dati, alla 0.5.33).
- Piano di review in corso (fasi): 1 sicurezza (fatta, 0.5.31), design unificato (chip unico, interruttore e pillola accessibili: 0.5.32-0.5.33); da fare: 2 Hermes e motore, 3 interfaccia e leak, 4 processo e firma, 5 snellire MainActivity, 6 accessibilita' restante, 7 integrazioni. Dettagli in ROADMAP.md.

---
## Storico (note precedenti)

# R1 Agents — ripartenza essenziale (aggiornato 8 novembre 2026, notte)

**Versione 0.5.0 (code 7) installata sul Rabbit** (`adb install -r`, dati conservati). Novità: **launcher agente-centrico** (obiettivo dell'utente; sostituisce il vincolo precedente «mai launcher», ora volontario e reversibile).

## Stato attuale della schermata iniziale
**R1 Agents è la schermata iniziale predefinita del Rabbit** (impostata su ordine esplicito dell'utente con `cmd role add-role-holder --user 0 android.app.role.HOME dev.r1agents`, 8 novembre 2026, sera). Provato dal vivo: tasto home da home/chat/foglio → prima schermata dell'app; «indietro» dalla radice non esce; aprire un'app (Calcolatrice) e premere home riporta all'app. Non provati: riavvio e tasto laterale (nessuna autorizzazione al riavvio).
**Dopo il riavvio vero del Rabbit (fatto dall'utente, 8 novembre notte): il ruolo HOME è rimasto `dev.r1agents`.**
**Carosello orizzontale della home** (scorri a destra/sinistra, o rotella/DPAD sinistra-destra): il buddy mostra la funzione con il suo oggetto: impostazioni (martello e chiave), app (griglia), a destra fotocamera (apre la camera di sistema), strumenti (cronometro), traduttore (globo), note (taccuino), attività di Hermes (calendario; mostra la prossima attività dal vivo). Su/giù dentro una funzione torna all'agente. `LaneProps.kt` (oggetti disegnati con tracciati), `home()` in MainActivity.
**Integrazione Hermes nel launcher**: sulla home l'agente con una chat in lavorazione lo dice sotto il nome («sta lavorando…», «ti aspetta») e assume la posa (aggiornamento ogni 15 s); lo standby (Glance) ha il widget «hermes» dal vivo (chat in corso + prossima attività); nel carosello le funzioni «competenze» (conteggio attive dal vivo) e «attività di Hermes» (prossima attività). La colonna centrale si chiama «agenti»; fuori da lì i puntini di rotazione degli agenti spariscono. Impostazioni → «impostazioni android» (pagina di sistema). **Tasto laterale**: doppia pressione = ascolto senza tenere premuto, una pressione successiva ferma e invia (`dispatchKeyEvent`, provata dal vivo; un ascolto reale ha mandato un messaggio vocale a Hermes).
**Rotella in orizzontale**: verso invertito rispetto al calcolo teorico, regolabile in Aspetto («rotella in orizzontale: normale/invertita»; `r1WheelLandscapeFlip`). Filtro grigio sul carosello (segnalato dall'utente): evidenziazione di focus di sistema spenta su pagine e carosello (ipotesi, da confermare).
**Per tornare al launcher originale:** `adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.android.launcher3` (oppure Impostazioni → schermata iniziale → launcher originale).

## Come funziona il launcher
- L'app dichiara un alias `HomeAlias` con categoria HOME: compare nel selettore di sistema delle schermate iniziali. **Non si imposta mai da sola** (testato: ruolo HOME resta `com.android.launcher3`). Si sceglie da Impostazioni → «schermata iniziale» (apre la pagina di sistema) oppure dal selettore di Android; si annulla riscegliendo il launcher originale nella stessa pagina (o: Impostazioni di sistema → App → App predefinite → App home).
- **Cassetto delle app** (menu → «app»): tutte le app con icona, ricerca, preferiti (tocco prolungato: apri / preferito / info / disinstalla). Senza risultati compare «chiedi a <agente>: «…»» che apre la chat con quel testo. In chat «apri calcolatrice», «avvia calendario»… apre l'app (solo corrispondenza unica, `LauncherCommand.kt`).
- **Recenti e aggiornamento**: il cassetto mostra le ultime 5 app aperte («recenti»); l'elenco si invalida da solo quando installi/aggiorni/togli un'app (receiver dei pacchetti) e comunque dopo 60 s.
- **Dock**: le app preferite (tocco prolungato nel cassetto) compaiono come icone sulla schermata dello standby (orologio), la schermata iniziale quando l'app fa da home.
- Da schermata iniziale predefinita: **indietro non esce** (come ogni launcher); il tasto home chiude fogli/pagine e torna alla prima schermata (`goHome`). Tasto laterale, barra di sistema e funzioni di sistema non sono toccati.
- Manifest: `queries` per LAUNCHER e HOME (visibilità pacchetti), permesso `REQUEST_DELETE_PACKAGES` (solo per la schermata di disinstallazione di sistema, con conferma di Android).
- Test: unit `LauncherCommandTest` (+ matching), DevelopmentQa (selezionabile senza impostarsi, cassetto, ricerca, «chiedi all'agente», home → prima schermata). Dal vivo: cassetto, ricerca, preferiti, sheet, avvio e ritorno, comando in chat.
- **Non provato**: l'effetto reale di impostare l'app come home (pressione del tasto home, riavvio, tasto laterale) — è una scelta di sistema che spetta all'utente. Se qualcosa si comporta male: riscegliere il launcher originale.

| Artefatto | SHA-256 |
| --- | --- |
| `r1agents.apk` (installato, firma debug storica) | `805a8964d9d0809a3a255402bb7510251d09ba58a7fd550ec45f6073a949ee73` |
| `../r1agents-release-signed.apk` (firma di rilascio, non installabile sopra l'installato) | `99e862237d06c62b3ac7ed61f4182b0b6ddfba868b8d295ebb603b8acb363f1c` |
`../update.json` ha ancora l'URL segnaposto `https://TUO-SERVER/…` (nessun hosting scelto).

**Verifiche 8 novembre** (dettagli in VERIFICATION.md): 94 test JVM, lint, catalogo 560 testi; suite QA generale (13), funzionale, voce, scene, bordi scene e sviluppo PASS; dal vivo standby (Glance), timer, squillo sonoro (USAGE_ALARM, fermato), colori, font, schermata di squillo, compressione/archivio/ramo/eliminazione su Hermes reale (eliminazione rifiutata dal server finché la sessione è live → corretta in 0.4.1; la prova finale è stata interrotta dal controllo di sicurezza).

**Ancora da fare dell'utente:** rotella fisica; standby con schermo spento; scelta del canale HTTPS per gli aggiornamenti; eliminazione dal vivo della chat «risposta ok su richiesta» (chat usa-e-getta nata dalla prova, 2 messaggi) con la 0.4.1 — esiste in Hermes e nell'app.
Nota: le suite QA riscrivono la configurazione del pacchetto `dev.r1agents.qa` (server simulato): non collegare Hermes reale a quel pacchetto.

---
# R1 Agents — ripartenza essenziale

Stato: 8 novembre 2026. Fonte autorevole: `Download/Rabbit R1/r1agents`. Leggere poi ROADMAP.md e VERIFICATION.md; evitare di caricare cronologie, backup e log interi senza necessità. Verifica funzionale non conclusa; goal automatico rimosso dall’utente. Proseguire su richiesta.

## APK e sorgenti

| Artefatto | Stato |
| --- | --- |
| `r1agents.apk` | Installato sul Rabbit, 0.4.0/code 5; SHA256 `ff588e1f1fd7c5158def3e59ff1f6b2d41fef7399d224419c1ee1fdfc4021946` |
| `work/functional/r1agents-functional-candidate.apk` | Correzioni successive nei sorgenti; compilato, non installato. Ancora 0.4.0/code 5; SHA256 `f24a7516e7de520eb8dd21f7dc3e679a1f79eb6cda4c9d469fb8607b1b60de29` |
| `work/functional/tested-qa.apk` e `tested-qa-runner.apk` | Esatti APK delle 27 prove funzionali PASS, precedenti alla correzione del ramo Hermes |

`work/functional/CHECKPOINT.json` e `device-artifacts.json` conservano hash e provenienza. Non distinguere build con lo stesso numero solo dal nome. Compilare produce `app/build/outputs/apk/release/app-release.apk`; `tools/check.sh --copy` sovrascrive `r1agents.apk`: usarlo solo quando si intende aggiornare quell’artefatto.

## Cosa c’è nel candidato

Importazione validata e transazionale, rollback su errore SQLite, lettura limitata a 20 MB, date note conservate. Routine eliminate con l’agente, pianificazioni disattivate/orfane ignorate, sveglie singole non duplicate. Allegati univoci, copie parziali eliminate, risultati dei selettori legati alla chat originale. Microfoni sospesi con pagina/background/rotazione e trascrizioni tardive scartate. Tamburi: scelta logica immediata, animazione separata; routine locali limitate agli agenti disponibili.

Dopo le prove native sono cambiati solo il payload `session.branch_stored` della biforcazione Hermes (`parent_session_id`, risultato `stored_session_id`) e il controllo della pagina viva nella callback. Compilazione verificata; riprova reale del ramo ancora necessaria.

## Architettura e comportamenti da conservare

- `MainActivity`: navigazione e superfici. `BuddyFlight`: percorso condiviso avanti/indietro, interruzioni dalla posizione corrente e ancore aggiornate al layout. Buddy fuori dallo scroll; editor con testata durante la scrittura; PIN sempre protetto da FLAG_SECURE.
- `BuddySceneContext`: configura/legge/ricorda/attende; gesti brevi, poi riposo. `BuddyVisualState` e `BuddyMotionLayer`: stato comune chat/voce, liquido da microfono normalizzato e handoff con destinatario solo se identificato dagli eventi.
- `VoiceConversation`: tenere premuto, rilasciare invia, trascinare in alto annulla; solo risposte nei fumetti, nessuna riapertura automatica del microfono. Trascrizioni nel contesto della chat. Rotazione conserva pagina/bozza e annulla il gesto senza invio tardivo. TTS locale a blocchi, mai sul PC.
- `HermesRuntime`/`HermesTurnService`: turni Hermes continuano fuori pagina e recuperano senza reinvio. `Engine` locale per turno e provider fissato; uscita/cambio provider cancellano il lavoro locale, non altre chat Hermes. Traduttore cancella engine/mic all’uscita; `Screen.onSuspend` ferma i microfoni; `ViewTicker` lavora solo sulla superficie visibile.

## Vincoli

Conservare dati/preferenze. App normale: niente root, launcher, rimappatura tasto laterale o modifiche automatiche alle impostazioni di sistema. Prove automatiche solo `dev.r1agents.qa`; prove server su chat dedicate. Audio rinviato: Rabbit senza motore TTS, nessuna app aggiuntiva installata. Selettore voce nelle impostazioni, `voice_tts` separato da `tts` della chat; fullscreen pulito.

Chiave esistente `../signing-private/r1agents-release.p12`, alias `r1agents`, password nel file privato adiacente: non rigenerare, stampare o pubblicare. La firma privata differisce da quella installata: candidato separato, nessuna disinstallazione/migrazione automatica. Backup escludono credenziali, PIN, allegati, foto di sfondo, timer e chat Hermes remote; PIN senza recupero, non cifra il database. Hosting/aggiornamento HTTPS e audio restano rinviati.

## Compilare e verificare

JDK17: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; SDK: `~/Library/Android/sdk`. Rabbit ADB: `919129A5I160003672A7`. Deve essere collegato e già sbloccato; QA mantiene lo schermo attivo temporaneamente senza cambiare timeout.

```sh
./gradlew :app:assembleRelease :app:assembleQa :app:assembleQaAndroidTest :app:testDebugUnitTest :app:lintRelease --offline
adb -s SERIALE install -r app/build/outputs/apk/qa/app-qa.apk
adb -s SERIALE install -r app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
adb -s SERIALE shell am instrument -w -e suite functional dev.r1agents.qa.test/dev.r1agents.RuntimeInstrumentation
```

Altre suite: `voice`, `scenes`, `scene_edges`, `development`; senza `-e suite …` esegue i 13 controlli generali. Rigenerare `update-test/resigned.apk` con stessa versione QA e firma diversa a ogni cambio versione. Traduzioni: `python3 tools/i18n/audit.py`. Profilo release installata: `python3 tools/profile-ui.py --serial SERIALE --runs 3`. Firma e QR: tools/RELEASE.md e tools/PAIRING.md.

Pulizia: output Gradle e artefatti intermedi rimossi, rigenerabili. Ripristino della build installata e documentazione originale in `../archivi/`; inventario in `work/cleanup.json`. I campioni QA intermedi dei vecchi manifest possono non essere più presenti.
