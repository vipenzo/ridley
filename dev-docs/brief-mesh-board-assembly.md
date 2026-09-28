# Brief: `mesh-board` per vedere la cucitura — solido, esploso, sezione

## Contesto

Decisione del 2026-09-25 (Vincenzo), a valle di `brief-split-anchors.md` (anchor di
taglio sui pezzi) e `brief-joint-layout.md` (`layout-anchors`). Con quei due si
scrive una giunzione in cinque righe:

```clojure
(def halves (mesh-split (box 40 60 80)))
(def L (layout-anchors (:behind halves) :cut :inset 5 :spacing 10))
(register pins (on-anchors L "pin" :align (cyl 3 10)))
(register B (mesh-difference (:behind halves) pins))
(register A (mesh-difference (:ahead halves) pins))
```

ma la cucitura sta DENTRO il materiale: se il perno entra nel foro col gioco giusto,
se le due metà combaciano, se un perno buca una parete, non si vede. Vincenzo ha
proposto una sessione modale che registri i pezzi e permetta di nasconderli, renderli
trasparenti, esploderli. Discussione: non è un editor (non scrive nel sorgente, quindi
non è `edit-*`) e non deve registrare (tre `register` che dicono una cosa sola valgono
più di una forma che ne fa tre). È un VISORE, e Ridley ne ha già uno: `mesh-board`.
Decisione: **estendere `mesh-board`**, niente modale in questa fetta. Se rivalutare
per cambiare vista si rivela scomodo, una sessione con i tasti verrà dopo, sopra
queste stesse viste.

Fatti verificati sul sorgente prima di questo brief:

- La forma «show» di `mesh-board` spinge le foglie nell'accumulatore
  `state/scene-accumulator :scaffolds`; `viewport/core.cljs`
  (`update-scaffolds-display`) le disegna come `LineSegments` fantasma più una mesh
  solida INVISIBILE per la collisione del picking. Non esiste un modo solido/opaco
  per gli scaffali: va aggiunto nel viewport.
- La forma «compare» monta finestre picture-in-picture (`viewport/inset.cljs`:
  `mount!`, `set-content!` con `{:ghost mesh :highlight mesh :label str}`): un
  fantasma e UN evidenziato solido per finestra. `:intersection` tra un pezzo e i
  perni dà già il volume d'interferenza: è la vista «il perno buca?» e non richiede
  codice nuovo, solo un esempio nella scheda.
- Ogni pezzo uscito da `mesh-split` porta gli anchor di taglio con `:cut true` e
  heading USCENTE: è la direzione naturale di un esploso. I perni costruiti con
  `on-anchors` non hanno anchor di taglio.
- `material :opacity` esiste sui pezzi registrati: l'utente può già rendere
  trasparente un pezzo a mano, ma è un'altra riga per pezzo e cambia il pezzo, non
  la vista.

## Lavoro richiesto

Tutto nella forma show, `(mesh-board t opts)`, con `t` mappa `{nome mesh}` come oggi
(`{:A A :B B :pins pins}`); il valore di ritorno resta il primo argomento invariato.

### Parte 1: scaffali solidi

- `:solid true` — le foglie si disegnano come solidi semitrasparenti invece che
  fantasma. `:opacity` (default 0.35) regola la trasparenza. Un colore per foglia,
  dalla stessa tavolozza dei pezzi di `edit-mesh-split`, nell'ordine della mappa.
- Nel viewport: lo scaffold-data porta `:render {:solid? true :opacity o :color c}`;
  `update-scaffolds-display` lo onora creando una mesh con materiale trasparente
  (`transparent true`, `depthWrite false` per non tagliare ciò che sta dietro) al
  posto del wireframe. Senza `:render`, comportamento identico a oggi.
- Resta possibile `:only` per mostrare un sottoinsieme.

### Parte 2: esploso

- `:explode d` — ogni foglia è traslata di `d` lungo la SOMMA delle heading dei suoi
  anchor con `:cut true` (normalizzata); una foglia senza anchor di taglio (i perni)
  resta ferma. Un pezzo di mezzo con due facce opposte ha somma zero e resta fermo,
  che è giusto: si allontanano gli altri due.
- L'esploso è solo di VISTA: sposta lo scaffale, non il valore. `mesh-board` continua
  a ritornare `t` com'è.
- Vale sia per gli scaffali fantasma sia per quelli solidi.

### Parte 3: sezione

- `:section anchor-name` (o un vettore di nomi) — per ogni nome apre una finestra
  picture-in-picture con l'assieme tagliato da un piano che CONTIENE l'asse di
  quell'anchor: normale = right dell'anchor (heading × up), posizione = quella
  dell'anchor. Ogni foglia è tagliata con `mesh-split` alla posa del piano e si
  mostra la metà `:behind`, solida, un colore per foglia; in fantasma l'assieme
  intero per orientarsi. Etichetta: il nome dell'anchor.
- L'anchor si cerca su TUTTE le foglie della mappa (i `:pin-N` stanno sui perni solo
  se `on-anchors` li propaga — verificare; altrimenti `:section` accetta anche una
  posa esplicita `{:position :heading :up}` e una mappa di anchor come quella di
  `layout-anchors`: `:section [L :pin-1]`).
- `inset/set-content!` va esteso ad accettare più evidenziati con colore, oppure
  la sezione si consegna come un'unica mesh concatenata per foglia con il colore
  cotto nei vertici: dire quale delle due strade si è presa. Con più `:section` le
  finestre si impilano come quelle di compare.

### Parte 4: la scheda

- Esempio «cucitura» nella scheda di `mesh-board` che mostra le tre viste sulle
  cinque righe del Contesto, più la ricetta dell'interferenza già disponibile:
  `(mesh-board B pins {:views [:intersection] :label "B vs pins"})` — volume atteso
  uguale al volume dei mezzi perni; per una spina nel foro col gioco, zero.
- Guida cap. 7.8: un paragrafo.

## Verifica

Allo schermo, sulle cinque righe del Contesto e su `examples/joints.clj` (tenone e
spina, già verificati da Vincenzo):

1. `(mesh-board {:A A :B B :pins pins} {:solid true})`: tre solidi trasparenti, i perni
   visibili DENTRO le due metà, colori diversi.
2. `{:solid true :explode 30}`: A e B si allontanano lungo la normale del taglio, i
   perni restano sul piano di mezzo; con `:explode 0` tornano al posto.
3. `{:section :pin-1}`: finestra con il taglio lungo l'asse del primo perno; si
   distinguono il perno, il foro e, se c'è, l'intercapedine; A e B con due colori.
4. Tenone di `joints.clj` (`:a` con il perno integrale, `:b` col foro): la sezione
   mostra il gioco `t` su entrambi i lati del perno; l'interferenza
   `(mesh-board (:b ab) (mesh-union (:a ab)) {:views [:intersection]})` è zero.
5. Spina: sezione con la spina in `:extras` e i due fori; esploso con tre pezzi.
6. Non regressione: `(mesh-board (split-tree AA))` e la forma compare invariati;
   `mesh-board` ritorna sempre il primo argomento identico (`=`), anche con
   `:explode`.
7. Se durante le prove cambiare vista rivalutando risulta scomodo, dirlo: è il gate
   per la sessione a tasti.

## Fuori scopo

Sessione modale con tasti, registrazione automatica dei pezzi, misura numerica del
gioco (la sezione lo mostra, non lo quota), modifica dei materiali dei pezzi
registrati.

---

## Addendum (2026-09-27): una sola grammatica

Rilievo di Vincenzo dopo il gate delle tre viste: `mesh-board` è diventata due
funzioni sotto un nome. La forma «confronto» lavora su due elementi (riferimento e
candidato) e ne mostra le relazioni booleane; la forma «show» ora prende una mappa di
elementi e li mostra solidi, esplosi, sezionati. Le viste dell'una servirebbero
all'altra: l'interferenza perno-pezzo è un'intersezione sull'assieme, la sezione ha
senso anche sui due solidi. Decisione: **unificare**, non separare.

### Il modello

`mesh-board` è UNA direttiva di visualizzazione su **un insieme di mesh nominate più
delle viste**. Il confronto è il caso con due elementi.

```clojure
(mesh-board {:A A :B B :pins pins} opts)     ; l'unica forma
(mesh-board ref cand opts)                   ; scorciatoia: {:reference ref :candidate cand}
```

- Ritorna sempre il primo argomento invariato, come oggi.
- `t` mappa (nomi → mesh), vettore (`:piece-N`), o singola mesh (`:piece`), come oggi.
- La scorciatoia a due argomenti resta con lo stesso comportamento di oggi, così
  nessun programma esistente cambia: senza `:views` mostra le tre viste booleane
  di default tra `:reference` e `:candidate`, stampa la fedeltà, `:ghost` e `:label`
  come prima.

### Le opzioni, ortogonali alla forma

- `:only [nomi]` — sottoinsieme (mappa).
- `:solid true`, `:opacity 0.35`, `:explode d` — come nella fetta fatta, su qualunque
  insieme, due o venti elementi. L'originale registrato omonimo viene nascosto.
- `:views [vista …]` — ogni vista è un vettore `[tipo & args]`; apre una finestra:
  - `[:intersection a b]`, `[:missing a b]`, `[:excess a b]` — le viste booleane di
    oggi, tra DUE elementi nominati (`a`, `b` chiavi della mappa). Sul confronto
    `[:intersection]` senza argomenti vale `[:intersection :reference :candidate]`
    (retrocompatibilità). Sull'assieme `[:intersection :B :pins]` è l'interferenza.
    Etichetta: `intersection B∩pins: 226 mm³` (nomi + volume, come oggi).
  - `[:section at & {:keys [offset]}]` — l'assieme intero tagliato dal piano
    dell'anchor `at` (heading = normale, convenzione di Ridley; `at` è un nome
    cercato in `:anchors` e poi sulle foglie in ordine di mappa, oppure una posa),
    traslato di `offset` lungo la normale (default 0). Metà «avanti» solide, un
    colore per elemento, niente fantasma, la finestra segue il viewport. Vale
    anche sul confronto: sezione di riferimento e candidato insieme, due colori.
  - Le forme brevi restano accettate: `:section :cut` ≡ `:views [[:section :cut]]`;
    una vista scritta come keyword nudo (`:intersection`) è la vista di default
    sulla coppia riferimento/candidato.
- `:anchors` — mappa di anchor extra in cui cercare i nomi di `:section`.
- `:label` — disambigua le finestre di chiamate concorrenti, come oggi.

### Il piano di sezione pilotato dall'utente

`offset` fa scorrere il piano lungo la normale: `[:section :cut :offset 8]` è la
sezione 8 mm dentro il pezzo «avanti». Per un piano inclinato si passa una posa
intera. Lo scorrimento LIVE si ottiene con `tweak`, che già trasforma i numeri di
una forma in slider e rivaluta:

```clojure
(tweak (mesh-board {:A A :B B :pins pins} {:views [[:section :cut :offset 8]]}))
```

Da verificare: `tweak` si aspetta di mostrare in anteprima il risultato
dell'espressione, che qui è la mappa `t`, non una mesh. Se l'anteprima non regge una
mappa, `tweak` va reso tollerante (risultato non-mesh → nessuna anteprima propria,
valgono gli effetti di `mesh-board`), non si costruisce una sessione modale
apposita. È il gate 7 del brief risolto con un mezzo che esiste; il caso d'uso è la
baionetta di `examples/joints.clj`: dal piano di taglio ai nottolini con lo slider.

### Lavoro

1. `mesh-board`: un solo percorso interno (insieme + viste); la forma a due argomenti
   costruisce la mappa e le viste di default e delega. `compare!` e `sections!` si
   fondono in un `views!` che monta/riconcilia una finestra per vista, chiavi
   `mesh-board:<label>:<tipo>:<args>`; `reset-compare-views!` invariata.
2. Viste booleane su una coppia nominata: `view-mesh` prende le due mesh dalla mappa.
3. `:offset` nella sezione.
4. `tweak` tollerante a un risultato non-mesh, se necessario (punto da verificare).
5. Scheda `mesh-board` riscritta attorno al modello unico; Spec; guide 7.8 (una
   riga); l'esempio «cucitura» con `[:intersection :B :pins]` nella stessa chiamata.

### Verifica

1. Retrocompatibilità: `(mesh-board (split-tree AA))`, `(mesh-board ref cand)`,
   `(mesh-board ref cand {:views [:intersection] :ghost true :label "x"})` producono
   scaffali, finestre e messaggio di fedeltà identici a oggi.
2. Assieme: `{:solid true :explode 30 :views [[:section :cut] [:intersection :B :pins]]}`
   apre due finestre: la faccia di taglio con la sezione dei perni, e l'interferenza
   con volume = mezzi perni.
3. Confronto con sezione: `(mesh-board ref cand {:views [[:section pose]]})` mostra
   i due solidi tagliati, due colori.
4. `:offset`: `[:section :cut :offset 8]` sposta il piano; `(tweak …)` sulla stessa
   chiamata dà lo slider e la sezione scorre live; sulla baionetta di `joints.clj`
   (dopo la correzione delle `apply` sulle macro) si vedono i nottolini.
5. Non regressione Node: `mesh_board_test` passa senza modifiche ai test esistenti.
