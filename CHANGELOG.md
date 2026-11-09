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
