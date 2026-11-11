# Lavoro aperto — ordine di ripresa (aggiornato alla 0.5.47)

Dalla code review del 10 novembre 2026 (seconda passata a 0.5.34, piano in fasi 0/A-F). Fatte: sicurezza (0.5.31), design unificato e audit dei sotto menu (0.5.32-0.5.34), processo di rilascio e Hermes/motore (0.5.35: teardown unico del socket, READY a socket aperto, resume che non perde il contesto, routine sul dash condiviso, suonerie senza orfane, tool_use incompleti, NousAuth con lock unico, ErrorKind con cause; check.sh con QA e audit, device-check --all-suites con controllo versione, publish-update con note della versione, impronta e versionCode, watchdog per suite, test development senza gara di tempi).

Fatte in 0.5.46: socket Hermes a riposo dopo un minuto in background e ricollegato al ritorno; `prompt.submit` mai con il session_id di una connessione precedente (generazione della connessione, ripresa una volta); approvazione non inviata segnalata all'utente; stato offline immediato (NetworkMonitor) con il messaggio che parte da solo al ritorno della rete; prompt caching Anthropic (strumenti, sistema stabile, ultimo turno); `phone_action` con esito reale in modalità automatica; multitasking proprio (AppSwitcher). Già fatte prima e confermate: bozza e allegati passati alla chat sostituita, microfono a clic e annuncio dello stato.

Fatte in 0.5.47: azioni volume, luminosità (con permesso concesso dall'utente), media, promemoria e calendario (evento da confermare nell'app del calendario); routine a evento (carica, scollega, wifi, cuffie; pausa di 10 minuti, finché il processo è vivo); backup con password (AES-256-GCM, PBKDF2); azioni, multitasking e pagina dell'agente fuori da MainActivity (2130 righe).

1. **Snellire MainActivity (resto, facoltativo).** Navigazione e trasformazione dell'avatar (`Navigator`, `BuddyDirector`) e corsie della home: codice molto intrecciato, da spostare solo con i test di scena accanto.
2. **`OnBackInvokedCallback`** quando si alzerà targetSdk (oggi 34).
3. **Multitasking, seguito facoltativo.** Anteprime vere delle app con un servizio di accessibilità attivato dall'utente; oggi le schede mostrano icona e colore.
4. **Non verificabile senza l'utente.** Rotella fisica, modello reale con chiave API, ramo ed eliminazione su Hermes reale, eventi reali delle routine (cavo, cuffie).
