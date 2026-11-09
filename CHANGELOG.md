# Changelog essenziale

**0.4.0 installata:** continuità del buddy, voce fullscreen con solo risposte, menu e rotazione; dettagli delle prove in VERIFICATION.md.

**Correzioni successive nei sorgenti/candidato, non installate:** backup atomico e date note; routine/ricevitori; allegati e callback tardive; microfoni in background; selezione immediata di durate/orari. Eliminazione chat Hermes: record locale rimosso solo dopo l’esito remoto. Ramo Hermes corretto nel payload, da riprovare dal vivo. Stato in HANDOVER.md, lavoro aperto in ROADMAP.md.

Cronologia completa conservata nell’archivio documentale descritto in `../archivi/README.md`.

Tocco interattivo: schede inclinate verso il dito con rimbalzo, icone che saltano al tocco (DESIGN.md §13).

Buddy legato allo scroll (affonda, si allunga, si offende e perdona), nuove reazioni, dissolvenza incrociata al cambio aspetto (DESIGN.md §14).

Repertorio di 14 comparse e 8 sparizioni del buddy, nascondino al terzo tocco e capolini spontanei (DESIGN.md §15).

**0.4.1 (code 6):** eliminazione chat Hermes che chiude prima la sessione live e conserva i dati su errore; ramo corretto; buddy e scroll, comparse e recitazione, dissolvenza al cambio aspetto.

Animazioni dell'interfaccia: comparsa/scomparsa fluida dei pezzi UI, scambio microfono/invio/stop, scorrimento fluido in chat, messaggio inviato dal campo di testo (DESIGN.md §16).

Buddy spinto in su a mezza sfera con occhi curiosi, riga che si restringe (più spazio alla lista), sfumatura in alto al posto del taglio netto; gesti per pagina (timer, sveglie, note, memoria, PIN, sicurezza, squillo, standby) — DESIGN.md §17.

**0.5.0 (code 7):** launcher agente-centrico selezionabile (mai impostato da solo): cassetto delle app con ricerca, preferiti e azioni, «chiedi all'agente», comando «apri <app>» in chat, home/indietro da launcher, impostazione «schermata iniziale» (DESIGN.md §18).

Carosello orizzontale (fotocamera, impostazioni con martello, app, strumenti, traduttore, note, attività di Hermes), rotella in orizzontale con inversione regolabile, evidenziazione di focus spenta (DESIGN.md §19).

Colonna «agenti», puntini di rotazione solo sugli agenti, «impostazioni android», stato Hermes dal vivo su home e standby, funzione «competenze», doppia pressione del tasto laterale per parlare.

0.5.2 (code 9): credenziali mascherate, sessioni Hermes cifrate, notifiche private, tile delicati, buddy mai sopra le schede, un solo buddy nei voli. 0.5.1 (code 8): i livelli statici del buddy (corpo, vestiti) sono texture della GPU: a home ferma il RenderThread passa da 35–48% a meno dell'1,3%, frame janky da 4,3% a 0%. PropView anima solo quando è visibile. Baseline e strumenti in `tools/perf.py` (risultati in `work/perf/`).

0.5.3 (code 10): PIN solo sulle chat; inattività del buddy impostabile; tile delicati; buddy senza sovrapposizioni e doppioni; credenziali: https obbligatorio, import backup sicuro, FLAG_SECURE sui campi segreti, errori senza chiavi.

0.5.4 (code 11): menu a pannello: il buddy non si sposta più scorrendo la lista; testo Sicurezza aggiornato.

0.5.5 (code 12): chiusura del menu dal carosello senza buddy doppio; cambio pagina del carosello con scambio dell'attrezzatura (il buddy resta).

0.5.6 (code 13): carosello: passaggio agenti↔pagine con buddy che vola (uno solo in scena).

0.5.7 (code 14): le istruzioni nascoste per l'agente non compaiono più nel primo messaggio della chat Hermes.

0.5.8 (code 15): registro delle azioni eseguite dagli agenti sul telefono (Sicurezza → azioni dell'agente), mai nei backup.

0.5.9 (code 16): strumento phone_action per gli agenti locali (apri app, timer, sveglia, torcia, nota, pannello: pulsanti o esecuzione automatica secondo le impostazioni); contesto del telefono opzionale (Sicurezza), spento di default.

0.5.10 (code 17): fotocamera del carosello: «scatta e chiedi a un agente» (la foto arriva come allegato nella chat scelta) oppure fotocamera di sistema.

0.5.11 (code 18): pagina attività unica in modalità Hermes: sotto i cron del dashboard anche le routine locali di questo telefono.

0.5.12 (code 19): avvisi opzionali dalle attività di Hermes (Sicurezza, spenti di default): ogni 30 minuti controlla i cron e notifica quelli che hanno girato, con anteprima privata.

0.5.13 (code 20): rifinitura UX da analisi del codice: bersagli da 48 dp e testi più leggibili (contrasti del tema chiaro), conferma a doppio tocco per archivia/elimina cron/svuota registro/elimina nota/rimuovi allegato, errori in parole semplici con "riprova" e menu sul pallino Hermes, uscita dalla chat con risposta in corso con conferma, stato "interrotta", Markdown con liste numerate, citazioni e link, Aspetto con etichette tradotte, benvenuto al primo avvio, esito dei cron e comando Hermes copiabile.

0.5.14 (code 21): ricerca tra le skill, Aspetto in sezioni (lingua e tema, rotella e schermo, agente sul telefono, testo e sfondo), tasto "copia" sui blocchi di codice, testo di sistema fino a 1,3x.
