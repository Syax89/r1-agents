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

0.5.15 (code 22): tabelle Markdown allineate in carattere fisso, Indietro dentro una funzione del carosello torna all agente, messaggio "codice copiato".

0.5.16 (code 23): avviso visibile quando si esce da una risposta in corso (prima non compariva), scheda di benvenuto, conferme e errori classificati verificati sul dispositivo.

0.5.17 (code 24): dentro una funzione del carosello il click della rotella tenuto premuto torna all agente (il click breve esegue la funzione); suggerimento aggiornato.

0.5.18 (code 25): buddy doppi: i buddy ritirati durante un volo non si riaccendono piu quando il clone viene preso in carico, un solo volo alla volta tra rotella e pagine del carosello, rete di sicurezza che toglie cloni orfani a riposo, niente riaccensione sotto una scheda aperta, la chat vuota mostra un solo buddy.

0.5.19 (code 26): animazioni piu leggere e coerenti: a riposo il respiro e gli sguardi si fermano dopo 20 s (da 41 a circa 6 fotogrammi al secondo), i buddy invisibili o coperti non animano piu, il ticker degli oggetti gira solo dove serve, dissolvenze delle pagine senza sovrapporre i testi (volo home/chat e glance/home), titoli del carosello senza ripetizioni, livelli hardware sempre rilasciati.

0.5.20 (code 27): il buddy delle pagine con lista (impostazioni, app, ecc.) non e piu tagliato dal bordo e sta sopra la barra del titolo; la lista delle app si crea a gruppi (scorrimento p95 da 39 a 22 ms).

0.5.21 (code 28): scorrimento nelle pagine con testata fissa: la riga del buddy cambia altezza a passi di 3 dp invece che a ogni pixel (meno ricalcoli del layout, nel confronto A/B 25 fotogrammi lenti contro 69 nelle impostazioni).

0.5.22 (code 29): causa trovata dei buddy doppi sovrapposti: scorrendo in fretta il carosello restavano nel livello delle funzioni colonne di gesti interrotti (con il loro buddy e oggetto), visibili dietro quella giusta. Ora a ogni cambio si tiene solo la colonna in uscita e un controllo periodico toglie i residui; verificato con un fuzz di interazioni (prima fino a 5 buddy visibili, ora al massimo 3 con la ruota e il nuovo agente).

0.5.23 (code 30): sotto menu unificati: titolo alla stessa quota in tutte le pagine (altezza minima dell intestazione), spazio agente con indietro-titolo-buddy come gli altri, paragrafi introduttivi e note con dimensioni e spazi uguali (intro/hint), titoli dei gruppi in Aspetto non piu arancioni. Checklist in work/submenu-audit.md.

0.5.24 (code 31): pulsanti primari uniformi: salva fornitore, importa, collegamento Hermes e conferma voce usano l accento come salva/avvia/aggiungi (prima verde fisso).

0.5.25 (code 32): controlli: la riga di stato usa lo stesso stile dei paragrafi introduttivi; solo nel pacchetto QA, --ez qa_visible true permette gli screenshot delle pagine con campi segreti per le verifiche visive.

0.5.26 (code 33): corretto un difetto: le pagine Hermes (attivita e competenze) restavano su "leggo da Hermes..." se il server rispondeva subito con un errore (risultato scartato prima dell ingresso della pagina); ora mostrano l errore e "riprova", anche nelle competenze.

0.5.27 (code 34): controlla aggiornamenti: pulsante in accento (era blu) ed errore in parole semplici.

0.5.28 (code 35): corretto un difetto introdotto in 0.5.20: nelle pagine con scroll diretto (fornitore, memoria, gestisci chat, editor) il contenuto si disegnava sopra la barra del titolo; il buddy esce dalla barra solo nelle liste con buddy fisso.

0.5.29 (code 36): errori delle azioni Hermes (salva, pausa, elimina, modello, comprimi...) in parole semplici invece dei messaggi tecnici di rete.

0.5.30 (code 37): errori in parole semplici anche per indirizzi non validi (senza schema) nelle pagine Hermes; audit dei sotto menu con passata finale su tutte le pagine.

0.5.31 (code 38): sicurezza: le schermate di prova (debug_screen, debug_req, debug_md) funzionano solo nelle build debuggabili; suoneria e bozza vocale accettano solo intent con il token privato dell app; la condivisione rifiuta file:// e i provider dell app; web_fetch fissa gli indirizzi verificati e blocca anche 100.64/10 e fc00::/7, leggendo la pagina per intero; l import dei backup toglie i percorsi degli allegati; la build debug ha il suffisso .debug.

0.5.32 (code 39): design unificato: un solo chip di scelta in tutte le pagine (48 dp, stessi spazi e colori), interruttore riconosciuto da TalkBack e dalla rotella con il suo stato; tolto un componente non usato.

0.5.33 (code 40): la pillola del microfono si attiva anche con TalkBack o la tastiera (un clic ascolta senza tenere, un altro invia); etichetta del pallino Hermes tradotta.

0.5.34 (code 41): il microfono della chat si usa anche con TalkBack, tastiera e rotella (un clic ascolta, il successivo trascrive); documenti di stato allineati.

0.5.35 (code 42): Hermes più solido: una connessione caduta chiude subito i turni in attesa invece di lasciarli appesi, il collegamento è pronto solo a socket aperto, un attimo di rete persa non fa più perdere il contesto della chat, le routine usano la stessa connessione (approvazioni comprese); due sveglie insieme non lasciano la prima a suonare; una risposta tagliata a metà di uno strumento non blocca più la chat; accesso Nous senza logout per rinnovi contemporanei; errori riconosciuti anche dentro altri errori; annunci TalkBack per lo stato della chat; controlli di rilascio con il QA, tutte le suite e la firma.

0.5.36 (code 43): microfono e allegati restano attivi quando una chat viene aggiornata da Hermes o ne copre un altra; tornando a una chat bloccata il PIN viene richiesto e la protezione dagli screenshot si decide in un solo punto; il click della rotella nel carosello esegue l azione e Indietro riporta all agente anche partendo dallo standby; un doppio tocco apre una sola chat e una risposta in ritardo non apre pagine dove sei andato altrove; puntini e pallino non animano più quando non si vedono; codice delle schermate di impostazioni e strumenti diviso in file separati.

0.5.37 (code 44): bozza e allegati non inviati restano quando Hermes aggiorna una chat appena aperta; spostare un agente, eliminare una chat e salvare un messaggio avvengono tutti insieme o per niente; un solo client di rete condiviso per voce, aggiornamenti, Hermes e pagine web; il prompt iniziale di kiwi e il messaggio sui file rifiutati seguono la lingua dell app; home, chat e standby in file separati.

0.5.38 (code 45): menu ridisegnati: le voci vicine stanno in un unica scheda con divisori sottili (sicurezza, informazioni, app per sezione), una freccia indica cosa si apre, le icone stanno su un fondo tenue nel colore dell app, i titoli di sezione sono più leggibili e la voce a fuoco con la rotella diventa una pillola dentro la scheda; la rotella non si ferma più sul buddy in alto.

0.5.39 (code 46): anche sveglie, timer, note, routine, attività di Hermes, competenze e chat da gestire stanno in un unica scheda con divisori e pillola a fuoco; le schede quadrate di strumenti e impostazioni hanno i titoli alla stessa altezza e una freccia in alto a destra.

0.5.40 (code 47): anche la pagina dell agente, il fornitore, la memoria e la gestione delle chat usano le liste raggruppate con freccia e pillola a fuoco; nel fornitore le due voci in alto hanno la stessa misura.

0.5.41 (code 48): pillola liquida nei menu: con la rotella una goccia di colore scivola da una voce all altra, si allunga nel verso del movimento e si ricompatta, e compare anche sotto il dito; all apertura di una pagina le voci si dispiegano a fisarmonica una dopo l altra; l icona della voce a fuoco fa un piccolo salto elastico. Tutto si anima solo quando cambia qualcosa.

0.5.42 (code 49): al clic la goccia si gonfia e ne parte un onda di luce; il bordo della scheda si illumina all altezza della goccia e la segue; il titolo della sezione si accende quando la goccia entra nella sua scheda; scorrendo veloce le schede si inclinano appena in 3D e tornano dritte con un rimbalzo; col dito la goccia compare solo se la voce resta premuta, non durante gli scorrimenti.

0.5.43 (code 50): transizioni ad acqua, delicate: toccando una voce sullo schermo, dal punto del dito parte un onda leggera e trasparente che increspa appena lo schermo (shader su Android 13 e successivi), senza colore, e il sotto menu affiora dentro l onda: vale per tutte le pagine, per quelle aperte da un foglio e per le finestre sopra la pagina (come il PIN). Le app toccate si aprono allo stesso modo, senza lo scorrimento laterale. Solo per i tocchi reali: con la rotella e chiudendo le transizioni restano quelle di sempre. Aggiornamenti: l indirizzo non compare più (è fisso nell app), il controllo parte da solo aprendo la pagina e una scheda dice se sei aggiornato, quale versione è disponibile con le sue note, o perché non si può installare (per esempio una firma diversa, segnalata prima di scaricare). Design uniforme: un solo raggio per tutte le schede e durate e curve comuni anche per goccia e acqua; anche i fogli hanno le voci raggruppate con la pillola liquida (senza frecce), la ricerca filtra dentro i gruppi; la scheda aggiornamenti e il traduttore cambiano in dissolvenza. Corretto: nella home l etichetta «lavora» poteva sparire dopo una ricostruzione.

0.5.44 (code 51): design uniforme anche fuori dai menu: la pagina di accesso a Nous ha l intestazione di tutte le altre, bolle e schede della chat hanno il raggio delle schede, pillole e fogli raggi comuni. Verificate tutte le schermate in tema scuro e chiaro.

0.5.45 (code 52): anche la recitazione del buddy (occhiate, bocca, cuori) usa i tempi comuni dell app: tutte le animazioni ora parlano lo stesso linguaggio.

0.5.46 (code 53): multitasking: dal fondo dello schermo scorri in alto e tieni (o menu, recenti) e le app aperte di recente fluttuano come schede su sfondo nero; rotella o dito per scegliere, tocco per aprirla a tutto schermo con l acqua, colpo in alto per toglierla. Senza rete il messaggio resta pronto e parte da solo quando torna; con Claude il prompt resta in cache (meno costi e attese); le azioni sul telefono dicono all agente se sono riuscite; Hermes si riposa in background e non perde messaggi o approvazioni dopo una riconnessione.

0.5.47 (code 54): gli agenti possono anche regolare volume e luminosità, comandare la musica, mettere un promemoria a un orario e proporre un evento da confermare nel calendario. Routine a evento: partono quando il Rabbit va in carica, si scollega, trova il wifi o si collegano le cuffie. Esportazione con password (AES-256): senza la password il file non si legge. MainActivity più snella (azioni, multitasking, pagina dell agente in file propri).

0.5.48 (code 55): anteprime vere nel multitasking, se le attivi (Sicurezza, anteprime nel multitasking): l ultima schermata di ogni app, salvata solo sul telefono, con icona e nome in basso; le app protette restano escluse e spegnendo il servizio le immagini si cancellano. Con il servizio attivo anche le app aperte da fuori entrano nei recenti. Nei timer il testo dei promemoria accanto all orario.

0.5.49 (code 56): Indietro passa dal nuovo sistema di Android (OnBackInvokedCallback), pronto per le prossime versioni; esportazione, importazione e scossa in file propri, MainActivity sotto le 2100 righe.

0.5.50 (code 57): MainActivity sotto le 1800 righe: sfondi, fogli, messaggi brevi, allegati e strumenti in ActivityParts.kt; restano nell activity solo navigazione, volo del buddy e ciclo di vita. Nessun cambiamento visibile.

0.5.51 (code 58): aperture delle pagine più fluide sul Rabbit. Le voci che si aprono a fisarmonica e le pagine durante l onda e lo scorrimento si disegnano una volta sola e poi si muovono, il velo delle increspature non ridisegna più la pagina, e l anello e l alone del buddy semitrasparenti non creano più buffer extra a ogni fotogramma. Aprendo impostazioni, strumenti e controlli i fotogrammi oltre 32 ms passano da 55-175 a 10-14 ogni 6 aperture (p95 da 42 a 26 ms). Aspetto invariato.

0.5.52 (code 59): tasto laterale. Per Android è il tasto di accensione: la pressione breve resta lo standby, mentre il «tieni premuto» può aprire R1 Agents come assistente digitale. In Impostazioni, tasto laterale, trovi i due passaggi di sistema e scegli cosa fa: parla (tieni premuto per ascoltare, di nuovo per inviare), chat, recenti, orologio o torcia.

0.5.53 (code 60): il tasto laterale funziona davvero. R1 Agents è ora un assistente digitale completo per Android, quindi la scelta resta anche dopo gli aggiornamenti. Con «parla», tenendo premuto si apre il pannello vocale sopra qualunque app; si invia tenendo premuto di nuovo o toccando il pannello, Indietro annulla. Se sul dispositivo non c è un riconoscimento vocale di sistema, l app chiede di usare Groq Whisper.

0.5.54 (code 61): riconoscimento vocale a scelta in Aspetto e voce. Oltre a Groq ci sono: altro Whisper, cioè qualunque servizio compatibile OpenAI come OpenAI o un Whisper sul tuo PC (indirizzo, chiave facoltativa, modello); Hermes, sperimentale, che riceve il vocale come allegato e lo trascrive con il Whisper del PC; offline con Vosk, senza Internet né chiavi, scaricando una volta il modello della lingua (circa 50 MB), che viene tolto dalla memoria dopo 2 minuti di inattività. Il messaggio sul riconoscimento di sistema mancante ora compare anche con la copia di prova installata.

0.5.55 (code 62): impostazioni più ordinate. Le scelte a testo sono righe con il valore a destra, e il tocco apre il foglio con le opzioni (spuntata quella attiva); le scelte sì/no e quelle multiple sono interruttori; colori, caratteri e palette restano anteprime. Le righe vicine stanno nella stessa scheda, separate da un divisore. Vale per Aspetto e per il tasto laterale.

0.5.56 (code 63): standby. Se lo schermo si spegne mentre sei in R1 Agents, riaccendendolo vedi l orologio sopra il blocco di Android, che resta sotto: toccando o con la rotella Android chiede il suo sblocco e poi torni dov eri; con un altra app in primo piano non cambia nulla. In carica, dopo un minuto senza tocchi, l orologio resta acceso con la luce bassa e si spegne normalmente staccando il cavo. Due interruttori in Aspetto, accesi.

0.5.57 (code 64): scorrimento dei menu fluido sul Rabbit. Impostazioni, sicurezza, strumenti e le altre pagine passano da 25-110 ms a circa 9 ms per fotogramma. Le cause erano tre. La sfumatura ai bordi della lista ora è un gradiente dipinto sopra invece della sfumatura di Android, che usava due buffer fuori schermo a ogni fotogramma. La riga del buddy in testa non cambia più altezza mentre scorri: è la lista che le scivola sopra. Infine non c è più la piccola inclinazione 3D delle schede, che costringeva a ricalcolare il ritaglio degli angoli a ogni fotogramma.

0.5.58 (code 65): collegando Hermes ad app già aperta, gli agenti compaiono subito. Prima la home li cercava solo quando veniva costruita: se in quel momento Hermes non rispondeva, restava vuota fino al riavvio o almeno per un minuto. Ora li rilegge appena Hermes risulta collegato, e anche tornando alla home se mancano. C è un test sul dispositivo che riproduce il caso.

0.5.59 (code 66): nuova pagina «voce e audio» nelle Impostazioni, accanto a tasto laterale e sicurezza, con la lettura delle risposte e il riconoscimento vocale. Prima queste scelte stavano in fondo ad Aspetto senza titolo. La riga mostra il riconoscimento in uso.
