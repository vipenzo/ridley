# Brief: mesh-split composita + edit-mesh-split — decomposizione interattiva a ghigliottina

## Contesto

Secondo brief della famiglia "acquisizione STL". Il primo (`dev-docs/brief-mesh-split.md`, consegnato) ha fissato i mattoni: la primitiva `(mesh-split mesh)` che taglia sul piano della posa corrente (punto = position, normale = heading) e ritorna `{:behind :ahead}` con `:behind` = lato del materiale (convenzione saldata a `sdf-half-space`/`extrude` da test); il predicato `(convex? mesh)` via hull-ratio; la metà vuota come risultato legittimo; la policy metadati carry-meta su entrambe le metà. L'accertamento (`dev-docs/mesh-split-accertamento.md`) garantisce i budget: `mark` salva la posa completa e `goto` la ripristina (Q2), split ~7 ms e `convex?` ~2.6 ms su ~4600 tri (Q1/Q4) — il ricalcolo live a ogni tasto è affordabile.

Questo brief consegna tre cose: la forma composita di `mesh-split` (path + marks, ghigliottina lineare), l'helper `split-parts`, e `edit-mesh-split`, terzo strumento modale della famiglia `edit-bezier`/`pilot`. Le decisioni di design sono state prese conversazionalmente con Vincenzo e sono vincolanti; le alternative scartate sono in fondo con rationale.

Principio guida dello strumento: la sessione interattiva è un generatore di sorgente. Il transitorio (tasti, esplorazione, ripensamenti) sparisce; resta una forma dichiarativa riproducibile che si ri-apre in editing cambiando `mesh-split` in `edit-mesh-split`.

## Lavoro richiesto

### Parte 1 — mesh-split composita

Arità:

- `(mesh-split m)` — la primitiva esistente, invariata.
- `(mesh-split m path)` — ghigliottina su tutti i mark del path, nell'ordine in cui appaiono.
- `(mesh-split m path marks-vector)` — ghigliottina sui soli mark elencati, nell'ordine del vettore.

Semantica: il path è risolto dalla posa corrente della turtle al momento della chiamata, con lo stesso resolver degli altri consumatori di path (una sola verità sulla risoluzione delle pose, vedi accertamento Q2). Ogni mark è un taglio: il piano è la posa del mark (position + heading), `:behind` è il pezzo staccato definitivamente, `:ahead` fluisce al taglio successivo. La reduce è interna, non nel codice utente.

Ritorno: annidato, `{:behind pezzo-1 :ahead <resto>}` dove `<resto>` è la mesh remaining finale (ultimo livello) oppure un altro nodo `{:behind :ahead}`. Con un solo mark il ritorno è letteralmente identico a quello della primitiva: questa identità è un invariante da preservare e da testare. I nodi split NON sono taggati con `:type` — il test di foglia è `(= :mesh (:type x))`, nessuna convenzione nuova. La struttura è oggi una catena (spina destra, ogni `:behind` foglia) ma la forma è già quella di un albero: non introdurre nulla che impedisca a un `:behind` di essere a sua volta un nodo in futuro.

Taglio a vuoto: un mark il cui piano manca il remaining produce `:behind` = mesh vuota al suo posto nella catena, preservando la corrispondenza posizionale, senza eccezioni (rappresentazione della metà vuota decisa nel brief 1). Il tool interattivo lo previene a monte; la forma scritta a mano deve degradare in modo leggibile.

Errori leggibili, mai normalizzazione silenziosa: un mark nel marks-vector che non esiste nel path → throw con messaggio che nomina il mark mancante; `(mesh-split m path)` su un path senza alcun mark → throw (la chiamata non ha significato).

Metadati: ogni pezzo eredita secondo la policy carry-meta del brief 1, applicata a ogni livello della catena.

### Parte 2 — Helper split-parts

`(split-parts result)` percorre la struttura e ritorna il vettore delle foglie in ordine: per la catena lineare `[pezzo-1 … pezzo-N remaining]`. L'implementazione deve essere ricorsiva sulla forma del nodo (un `:behind` che fosse a sua volta nodo viene percorso, ordine depth-first behind-prima), così l'estensione futura all'albero è gratis. Su una mesh nuda ritorna `[mesh]` (totalità).

L'helper è giustificato da casi concreti già sul tavolo (pezzo troppo grande per il piatto → le N parti da stampare; union/stamping dei pezzi), non è astrazione preventiva. È l'unico helper di questo brief.

### Parte 3 — edit-mesh-split (sessione modale)

Arità speculari alla composita: `(edit-mesh-split m)` = sessione vergine; `(edit-mesh-split m path)` / `(edit-mesh-split m path marks-vector)` = re-entry — i tagli esistenti vengono ricaricati nello stack della sessione (undo funziona anche su di loro) e la sessione continua da lì.

Substrato: lo stesso layer modale di `pilot`/`edit-bezier` (Code conosce lo stato attuale del substrato condiviso; riusare, non duplicare). Keymap: allineata alle convenzioni dei tool modali esistenti — Code adotta la keymap di famiglia, non ne inventa una.

**Il gizmo è la turtle.** Il piano di taglio è renderizzato alla posa corrente della turtle: quad semi-trasparente con bordo, dimensionato sui bounds del remaining. La manipolazione è il movimento della turtle con la semantica di `pilot` (avanti/indietro lungo l'heading, rotazioni). Nessun oggetto gizmo separato: il piano è già definito da position+heading, un gizmo introdurrebbe una seconda verità sulla posa.

**Semaforo live.** A ogni keystroke: split del remaining alla posa corrente + `convex?` sulle metà (budget: ~7 + 2×2.6 ms, dentro il frame per mesh tipiche). Due stati degeneri, distinti da QUALE metà è vuota — un fatto della posa, nessuna nozione di "direzione" coinvolta: `:behind` vuoto → taglio no-op, piano grigio, Enter disabilitato (mai un taglio fantasma); `:ahead` vuoto → piazzamento terminale, piano con colore dedicato (non grigio) + hint "Enter chiude la sessione". Le metà non vuote segnalano la convessità: verde se convessa, rossa se concava — vale per `behind` e per `ahead`, non solo per `behind`. Accettare un `behind` concavo è permesso — il modello a ghigliottina ha i suoi limiti dichiarati — ma mai silenzioso: il colore rosso è già il warning visibile, nessuna conferma aggiuntiva richiesta.

**Orientamento del piano.** Il quad deve mostrare quale lato è `:behind` — non solo negli stati degeneri, in OGNI taglio: l'utente deve sapere cosa sta per accettare premendo Enter. Un piano semi-trasparente uguale sui due lati non lo dice; serve un indicatore direzionale (es. un piccolo cono/freccia dal centro del piano lungo `-heading`, tinto come il piano) — nella stessa famiglia visiva del cono-corpo del turtle-indicator esistente.

**Pezzi accettati**: restano in place, ghosted/desaturati, così il contesto spaziale non si perde.

**Undo a stack.** Stato della sessione: mesh originale + stack delle pose-taglio accettate (ogni elemento può tenersi in cache il suo `{:behind :remaining}` per rendere l'undo istantaneo, ma anche il ricalcolo integrale è ~7 ms a taglio). Undo = pop. Nessun editing dei tagli passati: nel modello lineare modificare il taglio k invaliderebbe i successivi, quindi l'undo a catena è l'unica semantica offerta. Il fix chirurgico di un taglio intermedio si fa editando la forma emessa e ri-entrando.

**Dopo un accept**: se il nuovo remaining è convesso, il piano si ri-posiziona automaticamente in piazzamento terminale (invece di restare fermo), così "tutto verde" diventa azionabile con un solo Enter successivo. Se il remaining resta concavo, la turtle resta dov'è (comportamento originale V1). Nessun criterio più intelligente di questo in questo brief (V2 reflex-edge è orizzonte dichiarato, fuori scope).

**Uscita — schema a tasto unico.** Niente tasto/concetto separato per "commit della sessione": `Enter` significa sempre e solo "accetta `:behind` come pezzo definitivo". L'effetto dipende dallo stato del semaforo, non dal tasto:
- piano grigio (`:behind` vuoto) → Enter disabilitato.
- piano attivo (entrambe le metà non vuote) → accept: il taglio va sullo stack, `remaining := ahead`, la sessione continua (vedi "Dopo un accept" sopra per il riposizionamento automatico).
- piano in piazzamento terminale (`:ahead` vuoto) → Enter chiude la sessione ed emette, usando il `remaining` corrente come pezzo finale. Questo NON aggiunge un mark: chiudere è smettere di tagliare, non un taglio in più — il `remaining` è già l'`:ahead` finale della forma emessa.

`Ctrl+Enter` sopravvive come acceleratore documentato, zucchero puro su primitive già definite: teletrasporta il piano in piazzamento terminale (posizione lungo heading oltre i bounds del remaining corrente) poi applica la stessa logica di Enter — l'emissione è identica per costruzione, nessun percorso di codice separato. Utile per uscire presto con un remaining ancora concavo (workflow ad albero: si ri-entra dopo sul pezzo, vedi Fuori scope).

Esc annulla tutto senza emettere, sempre — invariato.

**Emissione.** Il transcript dei tasti NON è il programma emesso. Per ogni taglio accettato il tool sintetizza il cammino canonico minimo dalla posa del mark precedente (o dalla posa d'ingresso per il primo) alla posa del taglio — un delta del tipo `(th a) (tv b) (f d)` (più `(tr c)` se serve allineare l'up), con numeri liberi non arrotondati ("i numeracci sono la norma" — decisione esplicita di Vincenzo, nessuno snap). La sintesi del delta è una piccola routine geometrica che deve essere esatta: ri-eseguire il path emesso riproduce le pose dei tagli entro epsilon — questa proprietà è un test obbligatorio, incluse pose che richiedono la rotazione dell'up.

La forma emessa è la composita con destrutturazione annidata già scritta e pezzi nominati:

```clojure
(let [{piece-1 :behind {piece-2 :behind remaining :ahead} :ahead}
      (mesh-split m
                  (path (f …) (th …) (mark :cut-1)
                        (f …) (tv …) (mark :cut-2))
                  [:cut-1 :cut-2])]
  …)
```

Nomi dei mark generati `:cut-1 … :cut-N`; in re-entry i nomi esistenti vanno preservati e i nuovi generati senza collisioni. Il vettore dei mark è emesso letterale (il tool conosce i tagli, niente scoperta a runtime).

## Verifica

- Composita, caso guida end-to-end: una forma a plus (croce) decomposta con due tagli paralleli → tre pezzi, tutti `convex?` true; volumi che sommano al volume originale entro epsilon.
- Identità con la primitiva: composita a un mark → struttura di ritorno identica (stesse chiavi, foglie mesh) a `(mesh-split m)` eseguito alla stessa posa.
- Default e selezione: path con tre mark senza vettore → tre tagli nell'ordine di apparizione; con vettore `[:cut-3 :cut-1]` → due tagli in quell'ordine.
- Errori leggibili: mark inesistente nel vettore → throw col nome del mark; path senza mark → throw.
- Taglio a vuoto in mezzo alla catena: `:behind` vuoto al suo posto, catena che prosegue, corrispondenza posizionale preservata.
- `split-parts`: catena di N tagli → N+1 foglie in ordine; mesh nuda → `[mesh]`; nodo con `:behind` a sua volta nodo (costruito a mano) → percorso depth-first corretto (future-proofing dell'albero).
- Round-trip di emissione, il test decisivo: data una sequenza di pose-taglio (simulando l'esito di una sessione), la forma emessa valutata via SCI produce pezzi con volumi/bbox uguali a quelli calcolati direttamente dalle pose. Sintesi del delta canonico: property test posa-A→posa-B → replay del delta → posa B entro epsilon, con casi che includono rotazioni dell'up.
- Re-entry: valutare una forma emessa, ri-aprirla con `edit-mesh-split`, undo di un taglio, commit → forma coerente, nomi senza collisioni.
- Le parti UI/modali non testabili in Node vanno verificate live end-to-end e documentate nel report; i test WASM seguiranno l'idioma skip esistente — citare nel report l'entry di code-issues.md sui test WASM che skippano in CI, così la superficie scoperta resta a verbale.
- Suite completa verde.

## Fuori scope

- Proposta V2 (candidati dagli spigoli riflessi, ciclo con Tab): orizzonte dichiarato, richiederà il suo accertamento (numero di candidati su mesh reali, necessità di ranking).
- Sessioni ad albero (re-entry su un pezzo concavo dentro la stessa sessione): la forma di ritorno la supporta già rappresentazionalmente, ma il tool resta lineare; l'albero si fa ri-entrando manualmente sul pezzo.
- Wrapper `{:mark :cut-k :mesh pezzo}` nei `:behind`: evoluzione possibile documentata, aspetta un caso convincente (romperebbe l'identità col ritorno della primitiva).
- Vista esplosa dei pezzi accettati; manipolazione mouse/drag del piano (l'emissione canonica disaccoppiata la rende possibile in futuro senza toccare il formato emesso).
- Il converter `stl->convex-sdf` (brief 3) e la decomposizione automatica (VHACD).

## Alternative considerate e scartate

- **Ritorno a vettore piatto `[p1 … pN remaining]`.** Scartato: non generalizza all'albero (il limite dichiarato della ghigliottina sono le forme non sbucciabili, che richiedono ri-entry sul pezzo concavo); la forma annidata è identica alla primitiva a N=1, eliminando la doppia forma di ritorno dell'overload.
- **Ritorno a mappa per nome di mark.** Nessun caso concreto oggi che richieda l'associazione pezzo↔mark; si riapre solo col wrapper di cui sopra, se un caso emerge.
- **Gizmo come oggetto separato dalla turtle.** Scartato: due verità sulla posa da riconciliare a ogni frame; il piano è già position+heading della turtle, e la manipolazione eredita la semantica di `pilot`.
- **Emissione come transcript dei tasti.** Scartato: l'esplorazione produrrebbe rumore (`(f 5) (b 5) (rt 10) (lt 10) …`) e la proposta automatica futura non avrebbe tasti da trascrivere. Precedente di famiglia: `edit-bezier` emette i punti di controllo, non la cronologia del mouse. Il delta canonico disaccoppia interazione ed emissione.
- **Editing dei tagli passati in sessione.** Scartato: nel modello lineare invaliderebbe i tagli successivi; l'undo a catena è l'unica semantica onesta, e il fix chirurgico vive nel testo emesso (è la filosofia dello strumento).
- **Snap/arrotondamento dei numeri emessi.** Scartato da Vincenzo: i numeri liberi sono la norma per uno strumento percettivo; restano editabili a mano dopo.
- **Nome distinto per la composita (`mesh-decompose` e simili).** Scartato: l'overload per arità con ritorno unificato annidato non ha più l'asimmetria che poteva giustificare un nome separato.
