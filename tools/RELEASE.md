# Firma e aggiornamenti

La firma locale è conservata nella cartella privata `../signing-private`, separata dai sorgenti. Alias `r1agents`, formato PKCS12. Conserva la chiave e la password: tutti gli aggiornamenti successivi devono usare la stessa firma.

Dopo i controlli della versione finale:

```
tools/check.sh --copy
tools/release-sign.sh
python3 tools/publish-update.py ../r1agents-release-signed.apk https://TUO-SERVER/r1agents-release-signed.apk ../update.json --notes-file CHANGELOG.md
```

Il generatore legge versione e hash dall’APK, verifica la firma e rifiuta il pacchetto QA. I comandi preparano file locali e non pubblicano nulla. APK e `update.json` andranno caricati sul server HTTPS scelto. Chiave e password non vanno pubblicate.

L’APK di prova `r1agents.apk` resta firmato con la vecchia firma debug per aggiornare l’installazione attuale senza perdere dati. Il primo passaggio alla firma di rilascio richiede esportazione dei dati e installazione esplicita della nuova identità; Android rifiuta di aggiornare direttamente un’app con una firma diversa. Le chiavi API e le password sono escluse dai backup e vanno reinserite. Non disinstallare automaticamente l’app utente.
