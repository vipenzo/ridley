# 10. Analizzare e misurare

Modellare non è solo costruire: è anche capire cosa hai costruito. Quanto è largo questo pezzo? I due fori sono alla distanza giusta? La mesh è watertight o ha buchi? Questa sezione è entrata da qualche parte in modo strano?

Ridley offre strumenti per rispondere a queste domande sia da codice (nella REPL o nel programma) sia interattivamente (con il mouse nel viewport). Questo capitolo li raccoglie tutti.

## 10.1 Misurare distanze e dimensioni

### Distance

`distance` calcola la distanza euclidea fra due riferimenti. I riferimenti possono essere mesh registrate (per nome), facce di una mesh, o punti arbitrari nello spazio:

<!-- example-source: distance-forms :no-run
(register box1 (box 30))
(register box2 (attach (box 30) (f 80) (u 20)))

;; Fra i centroidi di due mesh
(println (distance :box1 :box2))

;; Fra i centri di due facce
(println (distance :box1 :top :box2 :bottom))

;; Fra due punti
(println (distance [0 0 0] [100 0 0]))

;; Misto: centro di una faccia e un punto
(println (distance :box1 :top [0 0 50]))
-->

Il risultato è un numero: la distanza in unità Ridley (millimetri, se stai progettando per la stampa 3D).

### Bounds

`bounds` restituisce il bounding box di una mesh registrata:

<!-- example-source: bounds-basic :no-run
(register my-piece (attach (box 40 30 20) (f 50)))
(println (bounds :my-piece))
;; => {:min [x y z] :max [x y z] :size [x y z] :center [x y z]}
-->

`:size` è la dimensione del bounding box lungo ogni asse: utile per verificare che un pezzo stia dentro un volume di stampa, o per calcolare margini. `:center` è il centro geometrico del box.

### Area

`area` calcola l'area di una faccia:

<!-- example-source: area-basic :no-run
(register box1 (box 30))
(println (area :box1 :top))    ;; area della faccia top di box1
-->

## 10.2 Misurazione interattiva

### Ruler da codice

`ruler` ha le stesse forme di argomento di `distance`, ma aggiunge un overlay visivo nel viewport: una linea con marcatori ai due estremi e un'etichetta flottante con la distanza.

<!-- example-source: ruler-basic
(register box1 (box 30))
(register box2 (attach (box 30) (f 80) (u 20)))
(ruler :box1 :box2)                ;; fra centroidi
(ruler :box1 :top [0 0 50])        ;; da faccia a punto
(ruler [0 0 0] [100 0 0])          ;; fra punti
-->

I ruler persistono fra comandi REPL (puoi aggiungerne più d'uno e ispezionarli insieme), ma vengono cancellati automaticamente al prossimo Cmd+Enter. `(clear-rulers)` li rimuove manualmente.

### Ruler interattivi con Shift+Click

Per misurare senza scrivere codice:

1. **Shift+Click** su un punto della superficie di una mesh: piazza un marcatore di misura.
2. **Shift+Click** su un altro punto: crea un ruler fra i due punti.
3. **Esc**: cancella il marcatore pendente e tutti i ruler.

È il modo più veloce per verificare una dimensione durante il lavoro.

### Ruler live in tweak mode

I ruler si aggiornano in tempo reale quando usi `tweak` su una mesh registrata:

<!-- example-source: ruler-tweak :no-run
(register A (box 30))
(register B (attach (box 30) (f 140) (u 50)))
(ruler :A :B)
(tweak :all :B)    ;; i ruler seguono B mentre trascini gli slider
-->

Quando confermi il tweak, i ruler usano la mesh finale. Quando annulli, tornano alla posizione originale.

## 10.3 Identificare facce

`find-faces` è lo strumento per selezionare facce di una mesh in base alla loro direzione. L'hai già incontrato nella 7.4 per modificare le facce; qui lo vediamo come strumento di analisi.

Le direzioni sono relative alla creation-pose della mesh, non al mondo:

<!-- example-source: find-faces-basic
(register my-piece (attach (box 40 30 20) (rt 20) (tv 15)))
(doseq [face (find-faces (get-mesh :my-piece) :top)]
  (flash-face (get-mesh :my-piece) (:id face)))
-->

`:threshold` controlla la tolleranza dell'allineamento (default 0.7, dove 1.0 è perfetto):

```clojure
(find-faces mesh :top :threshold 0.9)    ;; solo facce molto allineate
```

`:where` aggiunge un predicato sulla mappa della faccia:

```clojure
(find-faces mesh :top :where #(> (:area %) 100))    ;; solo facce grandi
```

Le funzioni di selezione puntuale (`face-at`, `face-nearest`, `largest-face`) sono trattate nella 7.4. Qui il punto è che `find-faces` è utile non solo per *fare* qualcosa con le facce, ma anche per *capire* la struttura di una mesh dopo una serie di operazioni booleane.

## 10.4 Diagnostica della mesh

Gli strumenti di diagnostica della mesh (`mesh-diagnose`, `mesh-status`, `merge-vertices`) sono trattati nella sezione 7.1. Qui aggiungiamo una precisazione su `manifold?`.

`manifold?` restituisce `true` se la mesh è watertight, `nil` altrimenti. Il motivo del `nil` (e non `false`) è che sotto il cofano Manifold WASM lancia un'eccezione quando l'oggetto non è manifold, e Ridley la cattura restituendo `nil`. In pratica non cambia nulla (entrambi sono falsy), ma vale la pena saperlo se usi il risultato in un `cond` o in un'espressione che distingue `false` da `nil`.

## 10.5 AI describe

Ridley include un sistema di descrizione AI per rendere la geometria accessibile a chi non può vederla. `describe` avvia una sessione interattiva: Ridley cattura 7 viste del modello da angolazioni diverse, le invia insieme al codice sorgente a un provider AI (Gemini, Claude, o GPT-4o), e restituisce una descrizione testuale della geometria.

```clojure
(describe :gear)         ;; descrivi un pezzo specifico
(describe)               ;; descrivi tutta la geometria visibile

(ai-ask "How thick are the gear teeth?")    ;; domanda di follow-up
(ai-ask "Would this print well without supports?")

(end-describe)           ;; chiudi la sessione
```

La sessione resta aperta per domande successive finché non la chiudi con `end-describe`. `cancel-ai` interrompe una chiamata in corso senza chiudere la sessione. `ai-status` mostra la configurazione corrente del provider AI.

La feature richiede un provider AI con capacità visive, configurabile dal pannello Settings. La qualità delle risposte dipende dal modello e dal tipo di geometria: forme semplici vengono descritte bene, assemblaggi complessi possono essere approssimativi.

## 10.6 Visualizzare in XR

Ridley supporta la visualizzazione dei modelli in realtà virtuale e aumentata tramite WebXR. I bottoni per entrare in modalità VR e AR sono nel viewport. La scena che vedi nel visore è la stessa che vedi nel browser, con gli stessi oggetti, colori e posizioni.

L'interazione in XR è oggi solo visiva: puoi esplorare il modello nello spazio, ma non modificarlo dal visore. L'editing continua nel browser.

La documentazione completa della modalità XR è nella guida dedicata dell'applicazione.
