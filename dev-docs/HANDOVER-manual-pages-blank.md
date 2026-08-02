# Handover — le pagine del manuale online sono vuote

Aperto 2026-08-02 su segnalazione di Vincenzo. **Urgente, non ancora
diagnosticato**: quanto segue è la mappa del meccanismo e i primi accertamenti,
raccolti in pochi minuti alla fine di un'altra sessione. Nessuna causa è
confermata — non prendere le ipotesi qui sotto per verdetti.

## Il sintomo

Nell'ultimo rilascio, sul manuale online: **l'indice si vede, ma appena si apre
una pagina questa resta vuota**. Vale sia per il *reference* sia per le *guide*.
In locale (dev) non risulta segnalato.

Il fatto che l'indice funzioni e le pagine no è il dato più informativo: **due
strade diverse**. L'indice è compilato dentro il bundle
(`src/ridley/manual/reference_index.cljs`, generato da
`bb scripts/build_reference_index.bb`); il CORPO di ogni pagina è invece un file
Markdown **scaricato a runtime**. Quindi il guasto sta quasi certamente nella
seconda, non nei dati dell'indice.

## Come funziona la catena (verificato leggendo il codice)

1. **Sorgente**: `docs/manual/reference/en/*.md`, `docs/manual/guides/{it,en}/*.md`.
2. **Pubblicazione**: `npm run sync-manual` =
   `rm -rf public/manual && mkdir -p public/manual && cp -R docs/manual/. public/manual/`.
   Gira come `predev` in locale e come step dedicato nel deploy.
3. **`public/manual/` è in `.gitignore`** (riga 7). Non è nel repo: esiste solo
   dopo il sync. Se il sync non gira, o gira e il risultato non finisce
   nell'artifact, il sito ha l'indice e non i testi — esattamente il sintomo.
4. **Runtime**: `reference_browser.cljs/card-url` costruisce l'URL togliendo il
   prefisso `docs/` dal `:path` dell'indice:
   `docs/manual/reference/en/x.md` → **`manual/reference/en/x.md`**, cioè un
   URL **RELATIVO**.

## Ordine nel workflow (verificato)

`.github/workflows/deploy.yml` fa, in quest'ordine: checkout → setup node →
`npm ci` → **`npm run sync-manual`** → `shadow-cljs release app` → configure-pages
→ upload di `./public` → deploy. **L'ordine è giusto** e `upload-pages-artifact`
non rispetta `.gitignore`, quindi `public/manual/` *dovrebbe* essere nel
pacchetto. Questo indebolisce l'ipotesi "il deploy non copia i file" — ma non la
esclude: va verificata sull'artifact vero, non sul workflow.

## Primi accertamenti, in ordine di potere discriminante

1. **Il file c'è sul sito?** Aprire direttamente
   `https://<host>/<base>/manual/reference/en/turtle.md`. Se dà 404 il problema è
   di PUBBLICAZIONE (sync/artifact); se restituisce il Markdown il problema è nel
   CLIENT (fetch o render). Questo taglia il campo a metà ed è il primo passo.
2. **Se è 404**: scaricare l'artifact della build dalla run di Actions e
   guardare se contiene `manual/`. Distingue "il sync non ha prodotto niente" da
   "il pacchetto non l'ha portato".
3. **Se il file c'è**: aprire la console del browser sulla pagina vuota e
   guardare l'URL effettivo della richiesta. L'URL è **relativo**, quindi si
   risolve contro l'URL corrente: se la navigazione del manuale ha cominciato a
   spingere un path (routing, hash, trailing slash), un `manual/reference/en/x.md`
   relativo può finire su `…/manual/reference/manual/reference/en/x.md`. È
   l'ipotesi che spiega meglio "prima funzionava, ora no" senza che il deploy sia
   cambiato — ma è un'ipotesi.
4. **Se la fetch va a buon fine e la pagina resta vuota**: il guasto è nel
   render del Markdown. Verificare se lancia un'eccezione (console) e se succede
   su OGNI pagina o solo su alcune.
5. **Confronto dev/prod**: se in locale funziona, la differenza sta nel build di
   release (DCE) o nel base-path. Provare `npx shadow-cljs release app` e servire
   `public/` da un sottopercorso, per riprodurre le condizioni di Pages.

## Cosa NON è

- Non è l'indice: si vede, quindi il bundle e i dati compilati stanno in piedi.
- Non è (probabilmente) il contenuto dei `.md`: sono rotte *tutte* le pagine di
  *entrambe* le sezioni, e sono file diversi scritti in tempi diversi.
- Non è il lavoro sul branch `edit-acquire-registration-stability`: lì il
  manuale è stato solo ampliato (due schede nuove, `plane-mark` e
  `edit-plane-mark`, con l'indice rigenerato), e quel branch non è rilasciato.

## Nota di metodo

Se si finisce a collaudare nel browser: **verificare che il bundle servito
contenga davvero il codice nuovo** prima di credere a un test dal vivo — un
`compile` che risponde "ok" non lo garantisce (vedi
`dev-docs/HANDOVER-plane-marks.md`, nota di metodo, e la memoria di progetto).
