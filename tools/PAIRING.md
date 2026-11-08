# Collegare Hermes con un QR

Sul PC dove hai il progetto, esegui:

```
tools/pair-qr.sh http://192.168.1.20:9119 nomeutente collegamento.png
```

Apri l’immagine sul PC. In R1 Agents apri **impostazioni → fornitore → Hermes → collega con QR**: scatta il QR oppure scegli un’immagine dalla galleria. Controlla indirizzo e utente prima di confermare, poi inserisci la password nella schermata del fornitore e tocca **accedi**.

Il QR non contiene password o token. Il collegamento non esegue automaticamente l’accesso. Per server pubblici serve HTTPS; HTTP è consentito solo per indirizzi locali. Il generatore funziona offline con JDK 17 e la dipendenza ZXing già scaricata dalla build Android. La schermata QR dell’app può anche mostrare il collegamento corrente per configurare un altro dispositivo.
