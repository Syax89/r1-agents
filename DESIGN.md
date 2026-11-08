# Design — regole correnti

Rabbit R1: 480×640 px, anche landscape; touch e rotella equivalenti, fuoco visibile, comandi gestuali con alternativa visibile. Rotazione conserva pagina e bozza. Una principale azione per schermata; stato vuoto con buddy, frase e azione; errori brevi e azionabili. Testo utente preservato; UI it/en e chiaro/scuro.

## Buddy e movimento

- Buddy sempre visibile nelle superfici interne, con un’ancora esplicita. BuddyFlight mantiene aspetto/accessori e lo separa dalla dissolvenza del contenuto; entrata e ritorno percorrono la stessa geometria al contrario. Interruzioni riprendono dalla posizione corrente; layout, fumetti e tastiera aggiornano la destinazione.
- Buddy fisso fuori dallo scroll, contenuti ritagliati alla loro area. Carosello: centro scheda; glance: basso; chat: testata; voce: palco/colonna sinistra. Impostazioni/strumenti: area superiore; editor: anteprima → testata durante testo, modulo scorrevole. Fogli: sopra il pannello; richieste/QR/login/PIN/suoneria: ancora propria fissa. Prima configurazione: buddy neutro senza agente fittizio salvato.
- Configurazione = gesto di lavoro; lettura/schede = studia; note/memoria = organizza; sicurezza/richieste = attende. Gesti d’arrivo brevi, poi riposo. Attività reali di chat/voce/autenticazione hanno precedenza. Handoff: destinatario solo se riconosciuto negli eventi, mai inventato.
- PIN all’avvio/background immediatamente opaco e FLAG_SECURE; blocco manuale/sblocco accompagnano il buddy. Nessuna sovrapposizione alle superfici Android esterne.
- Usare Motion/Theme/Spring esistenti, nessuna durata arbitraria nei menu; rispettare movimento ridotto. Fermare animatori/ticker fuori finestra e usare una sola animazione per il liquido filtrato, non una per campione audio. La scelta logica dei tamburi è immediata, indipendente dai frame intermedi.

## Componenti

Fogli condivisi dal basso, maniglia/titolo/chiusura, scrim e contenuto scorrevole; chiusura da sfondo, indietro o trascinamento. Azione dopo la chiusura; ricerca/sezioni quando servono. Distruttive rosse e separate. Usare componenti esistenti invece di nuove varianti.

Scala spazi 4/8/12/16/24 dp, margini 14 dp; raggi 14 campi, 20 righe, 22–24 schede, 28 fogli. Rubik: titolo 28/700, corpo 16–18/500, aiuto 14/500; numeri tabulari. Corpo leggibile ≥15 sp e bersagli ≥48 dp. Colori Theme BG/CARD/FOCUS/FG/MUTED, accento agente; conferma verde, attenzione ambra, distruttivo rosso; icone coerenti.

## Voce

Pressione per parlare; rilascio invia, gesto in alto annulla. Solo risposte nei fumetti; niente trascrizione visibile o mic riaperto automaticamente. Liquido da livello normalizzato filtrato, stop a fine ascolto/background. Selettore audio nelle impostazioni (`voice_tts` separato da `tts` chat). TTS locale a blocchi, stop su uscita/interruzione/nuova registrazione; errore audio lascia il fumetto leggibile. Scorrimento dito/rotella, buddy e fumetto affiancati in landscape.

Accettazione: ingresso/ritorno/interruzioni, scroll, tastiera, rotazione, testi lunghi e quattro combinazioni lingua/tema; controllo visivo oltre alla presenza della vista, poi tre misure release. Nessuna dichiarazione di zero scatti senza evidenza. Risultati attuali in VERIFICATION.md.

## 13. Tocco interattivo (8 novembre)
`Motion.touch`: la scheda si inclina fino a 4,5° e scivola di 1,5 dp verso il punto toccato (segue il dito in MOVE), poi torna con rimbalzo (POP); solo per viste basse (<220 dp) e non con animazioni di sistema a zero. Nei tile/righe grandi l'icona salta a 1,14x al tocco. Il buddy compagno continua a guardare l'elemento toccato (`onAttention`). Stile dei tile invariato (icona unica, nessun pastello). Verificato: screenshot con tocco prolungato e suite scene/funzionale/sviluppo/generale PASS.

## 14. Buddy legato allo scroll (8 novembre)
`BuddyScrollFx` (guidato da `R1ScrollView`, quindi vale anche per la rotella) pilota il buddy compagno della pagina:
- **Scorrendo** affonda dietro le righe fino a metà (traslazione ≤ 42% della riga, scala fino a 0,72, opacità fino a 0,6), guarda nel verso dello scroll e si inclina in base alla velocità (±12°), poi torna con rimbalzo. Se la pagina va su e giù 6 volte in 2,5 s gli gira la testa (spin). Tornando in cima fa un cenno (`nod`).
- **Tirato oltre l'inizio** (dito o rotella) si allunga come gomma; oltre ~140 dp si infastidisce: 1° torto `annoyed` (scuote la testa), 2° `offended` (si gonfia e si volta 2,2 s), dal 3° `sulk` (broncio 3,8 s, voltato). Il conteggio dei torti si azzera dopo 25 s. Un tocco durante il broncio lo fa perdonare (`love`). Tirate lievi lo fanno ridere (`giggle`).
- Nuove reazioni in `CharacterView.react`: annoyed, offended, sulk, shiver, nod, relief (occhi «brows», «surprised», «happy», «calm»).
- Cambio aspetto (colore/modo/font/lingua): la schermata precedente, fotografata prima di `recreate()`, sfuma sopra la nuova (`lookSnapshot`), senza lampo né buddy in volo.
I test di scena ammettono il buddy affondato (≥ 0,3 di opacità, scala ≥ 0,6) solo a pagina scorsa; in cima deve essere intero.

## 15. Repertorio di comparse e sparizioni (8 novembre)
`BuddyEntrances` anima la riga che contiene il buddy compagno (non il personaggio: niente conflitti con `BuddyScrollFx` e con le reazioni del viso) e a fine corsa, anche interrotta, rimette tutto a riposo.
- **14 comparse (`IN`):** rise (dal basso da dietro le righe), drop (dall'alto con rimbalzo), slideLeft/slideRight (dai lati, schiacciato dalla velocità), peekLeft/peekRight (spunta a metà dal bordo, guarda, poi entra), peekBottom (sale a metà da dietro le righe e si guarda intorno), pop, spin, zoom, hop (a saltelli da destra), sneak (furtivo: sale, si ritira, sale), roll (rotola da sinistra), dangle (penzola dall'alto come un pendolo).
- **8 sparizioni (`OUT`):** hideBottom, hideLeft, hideRight, hideTop, shrink, spinOut, hopOut, halfDown (resta a mezzo busto).
- **Nascondino:** ogni terzo tocco ravvicinato (entro 4 s) sul buddy di una pagina con riga compagna: sparisce in un modo a caso, aspetta 0,35–1,25 s, ricompare in un altro. Il primo e il secondo tocco restano la risata.
- **Capolino spontaneo:** ogni 22–42 s, con pagina in vista, buddy fermo e intero, non offeso e nessun volo in corso, compare con una comparsa a caso. Mai nel pacchetto QA.
- Con le animazioni di sistema a zero non parte nulla. Debug: `--es debug_entrance <id>` (solo build debuggabili).

### 15b. Recitazione (8 novembre)
Ogni voce di `BuddyEntrances` ha un copione (`Acting`): gli occhi anticipano la direzione prima di partire, una reazione a metà corsa (sorpresa nella caduta, risatina nei salti, brivido nel furtivo), sguardi in giro quando sbircia (sinistra, destra, centro) e un gesto all'arrivo (occhiolino, rimbalzo, sollievo, cenno, fastidio dopo il rotolamento). Il personaggio usa le sue reazioni (`CharacterView.react`), quindi viso e corpo restano coerenti.

## 16. Audit delle animazioni (8 novembre, passata del «team animatori e grafici»)
Metodo: inventario per punti di chiamata (≈80 tra `animate()`/`ValueAnimator`), giudizio per gruppo su tempo, curva, coerenza, anticipazione e seguito, uso di `Motion.reduced`, e prova sul Rabbit.

| Gruppo | Stato | Note |
| --- | --- | --- |
| Navigazione pagine (push/pop/replace, fade, parallasse, bordo-indietro) | buono | curve `SETTLE`/`EXIT`, SLOW/BASE; già rifinito (§11–12) |
| Fogli/menu (sheet, stagger delle voci) | buono | uscita più veloce dell'ingresso; stagger limitato alle prime righe |
| Tocco (press, inclinazione, salto icona, jelly, tamburo) | buono | §13: segue il dito, rimbalzo `POP`; spento con animazioni di sistema a zero |
| Buddy: volo condiviso, scroll, comparse, reazioni, recitazione | molto buono | §12, §14, §15 |
| Cambio aspetto (colore/modo/font/lingua) | corretto | dissolvenza incrociata al posto del lampo di `recreate()` |
| **Comparsa/scomparsa di pezzi UI** (vai in fondo, allegati, riga di stato, testo d'ascolto) | **corretto ora** | erano `GONE/VISIBLE` di colpo → `Motion.show/hide` (salita + respiro di scala in ingresso, sfumata in uscita) |
| **Scambio microfono ↔ invio ↔ stop** | **corretto ora** | prima il vecchio spariva di colpo; ora esce in 90 ms, il nuovo entra con rimbalzo (`Motion.swap`, con generazione per evitare gare) |
| **Scorrimento in chat** | **corretto ora** | seguire nuovi messaggi/streaming e «vai in fondo» usano scorrimento fluido; l'apertura resta istantanea |
| **Messaggio inviato** | **corretto ora** | sale dal campo di testo (dy 40 dp, SLOW) invece di una traslazione generica |
| Ancora da fare | aperto | durate dei cicli (punti di scrittura 1100 ms, loop del buddy vocale 1400 ms, pallino di connessione 650 ms) ancora letterali; transizione dell'editor di aspetto/sfondi; riga di errore e banner «collegamento interrotto» |

## 17. Coreografia del buddy pagina per pagina (team animatori, 8 novembre)
Lavoro in due: il lead e un sub-agente «character animator» (sola lettura) hanno discusso la coreografia; il resto è stato applicato. Lista d'azioni per pagina (trigger → azione):

| Pagina | Azioni applicate |
| --- | --- |
| Impostazioni / Info (riga compagna) | scorrendo la riga si restringe da piena a 44 dp: il buddy è spinto in su e resta mezza sfera con gli occhi che guardano sinistra/destra/su (`BuddyCuriosity`); la lista guadagna ~80 dp; le righe sfumano in alto (bordo sfumato 56 dp) invece di essere tagliate; tirato oltre l'inizio si allunga/si infastidisce/si offende/broncio; capolini spontanei e nascondino |
| Altre liste con testata (64 dp) | sguardo nel verso dello scroll, cenno tornando in cima; micro-sguardi rari (passo ×8, budget 20 s) |
| Sveglie | interruttore ON → sbadiglio + sguardo in basso; OFF → sollievo |
| Timer | avvio → cenno + sguardo sulla scheda; ogni 10 s pendolo con gli occhi; ultimi 10 s occhiata a ogni secondo; scadenza → sorpresa; annulla → brivido |
| Note | aggiungi → cenno + sguardo in basso; elimina → occhiata |
| Memoria agente | aggiungi → cenno + sguardo in basso |
| Sicurezza | all'arrivo scansione occhi sinistra → destra |
| PIN gate | errore → fastidio (1–4), offesa (blocco al 5°) |
| Squillo | sorpresa all'arrivo, poi brividi e sguardi alterni (max 8, non nel pacchetto QA) |
| Standby (Glance) | si guarda intorno (passo ×4), ogni tanto si alza un poco; caricatore → rimbalzo; batteria <15% → brivido; ora piena → cenno; dorme dopo 75 s |
| Chat | errore → scossa; messaggio inviato sale dal campo; microfono/invio/stop con scambio fluido |
Gesto d'evento comune `cue()`: rispetta animazioni ridotte, broncio, voli, buddy visibile/intero, al massimo uno ogni 2×SLOW. Applicati anche: richieste Hermes (sorpresa all'apertura), traduttore (sguardo all'avvio che scorre col testo, cenno alla fine, sorpresa all'errore), home (sguardo in basso verso il microfono mentre tieni premuto), gestisci chat (sorpresa al primo tocco, brivido alla conferma), routine/job/skill (occhiolino all'attivazione, rimbalzo su «esegui», sorpresa sugli errori), editor agente (fastidio sugli errori del nome), tamburo del timer (occhiata nel verso), caricamento (si guarda intorno, passo ×2,5), voce (guarda il lato del fumetto che arriva).
Verificato dal vivo sul Rabbit: scroll delle impostazioni (riga che si restringe, mezza sfera, sfumatura), note (cenno dopo «aggiungi»), timer (cenno all'avvio; annullato). Non verificati dal vivo uno a uno: richieste Hermes, traduttore, voce, caricamento, gestisci chat, routine/job/skill, PIN (coperti dalla compilazione e dalle suite QA).
Chiusi anche: contesto `TIME` (posa d'attesa) per sveglie, editor sveglia e timer; tamburo dell'ora/minuti della sveglia (occhiata nel verso); editor agente (lo sguardo segue il cursore lungo la riga); peek dei fogli (guarda la lista che si apre); login Nous (amore al successo, sorpresa all'errore).
Visti dal vivo: sveglia (tamburo → reazione in testata), gestisci chat (primo tocco → sorpresa), caricamento (buddy che pensa), note, timer, impostazioni. Coperti da compilazione e suite ma non rivisti uno a uno dal vivo: traduttore, richieste Hermes, voce, routine/job/skill, PIN, login Nous, editor agente (testo), peek.

### 17b. Verifiche oggettive dei gesti (test automatici)
Alcuni gesti sono troppo brevi o su buddy troppo piccoli per essere visti a occhio nelle riprese; li verificano i test leggendo lo stato del viso (`CharacterView.expression`):
- richiesta di permesso Hermes → `surprised` entro 4,5 s (DevelopmentQa);
- PIN errato → `brows` (fastidio) entro 2,5 s (suite generale);
- traduttore: errore → `surprised` entro 5 s; editor agente: scrivendo gli occhi si spostano (`eyeOffsetX`);
- routine locali: riattivando l'interruttore → `wink` (DevelopmentQa);
- repertorio BuddyEntrances: 14 comparse + 8 sparizioni + nascondino, riga sempre a riposo a fine corsa;
- buddy e scroll: affonda, si allunga, tre tiri → broncio, un tocco perdona.
La schermata del PIN non si può riprendere (FLAG_SECURE, voluto).

## 18. Launcher agente-centrico (0.5.0)
L'agente è la schermata principale (carosello); il cassetto delle app è un servizio dell'agente, non il contrario: ricerca con ripiego «chiedi a <agente>», comando «apri <app>» in chat/voce, buddy compagno in testa al cassetto (stesso scroll-fx delle impostazioni), righe con icona reale dell'app nello stile delle righe esistenti (scheda scura, tocco inclinato, stagger). Il launcher è scelto dall'utente (alias HOME), mai imposto; `isDefaultLauncher()` usa `RoleManager`.
Dock: fino a 5 icone dei preferiti nello standby (Glance), con tocco inclinato e ingresso a cascata.

## 19. Carosello orizzontale della home (0.5.0)
Scorrendo a destra/sinistra l'agente in primo piano resta il protagonista: cambia l'oggetto che tiene in mano e il titolo, mai il personaggio. Lane: sinistra impostazioni (martello+chiave) e app (griglia); destra fotocamera, strumenti (cronometro), traduttore (globo), note (taccuino+matita), attività di Hermes (calendario+orologio, sottotitolo dal vivo). Transizione: la vecchia lane esce di 0,4 larghezze sfumando (BASE, `EXIT`), la nuova entra da lato (SLOW, `SETTLE`); l'oggetto entra con oscillazione (`POP`), il buddy lo guarda e saltella; al tocco l'oggetto fa il suo gesto (lampo della fotocamera, martello che batte) e poi si apre la funzione. Puntini orizzontali in cima; su/giù dentro una funzione torna all'agente; la rotella cambia funzione quando ne hai una aperta.
Carosello: colonna centrale «agenti» con didascalia, puntini di rotazione degli agenti (destra) solo lì; funzione «competenze» (pezzo di puzzle con stella). Tasto laterale: doppia pressione entro 450 ms = ascolto continuo.
