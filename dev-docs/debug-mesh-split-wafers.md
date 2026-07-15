# Debug protocol — wafer sulla superficie di taglio (edit-mesh-split)

Bug: STL multiboard mount, piano ruotato di 90° (perpendicolare a Z), terzo
"gradino" (candidato :step), Accept → la superficie di taglio mostra superfici
sottilissime con disegno irregolare.

Stato del codice (working tree, non committato): esistono già due fix mirati —
`cut-cand/step-pose` (snap della heading alla normale media della faccia del
gradino, "live-confirmed 2026-07-14") e il plane-clustering in
`step-candidates` ("STL-confirmed 2026-07-15"). Il bug però si ripresenta
identico all'utente. Tre ipotesi restano aperte; il protocollo sotto le
discrimina **oggettivamente**, senza giudizi a occhio.

## Ipotesi

- **H1 — build stantia.** L'app che l'utente usa non contiene i fix
  (hot-reload fallito per warning/errore di compilazione, cache del browser,
  o build desktop Tauri non rigenerata). Spiegherebbe interamente la disputa
  "stai usando una versione vecchia".
- **H2 — artefatto di rendering, non di geometria.** Proprio *perché* lo snap
  funziona, il taglio è ora esattamente a filo della faccia del gradino: cap e
  faccia originale coincidono sullo stesso piano, e la preview disegna le due
  metà (behind 0.88 / ahead 0.18, double-sided) più il quad semitrasparente →
  z-fighting, che a schermo appare come "superfici sottilissime dal disegno
  irregolare". L'STL esportato risulterebbe pulito (coerente con la conferma
  via STL del 2026-07-15).
- **H3 — bug geometrico residuo** nel clustering o nello split (wafer reali
  nel mesh risultante).

## Passo 1 — Chiudere la disputa di versione (2 minuti)

Il REPL nREPL (porta in `.shadow-cljs/nrepl.port`) valuta nel **runtime vivo
del browser**: quello che risponde è per definizione la versione in esecuzione.

```bash
clj-nrepl-eval -p $(cat .shadow-cljs/nrepl.port) "(shadow/repl :app)"
clj-nrepl-eval -p <port> "(exists? ridley.geometry.cut-candidates/step-pose)"
```

- `false` → **H1 confermata.** Nessun altro test ha senso finché la build non
  è allineata. Verificare: shadow-cljs watch attivo e senza errori (un errore
  di compilazione lascia in silenzio il JS vecchio), hard-refresh
  (Cmd-Shift-R), e — se si usa l'app desktop — rigenerare il bundle
  (`desktop/build.sh`), che non si aggiorna mai da solo.
- `true` → la build è giusta, passare al Passo 2.

Fix permanente consigliato: stampare in console all'avvio un build-stamp
(macro compile-time con timestamp) così "che versione stai eseguendo?" non è
mai più una discussione.

## Passo 2 — Riproduzione headless con metriche (nessun occhio, nessuna UI)

Nel REPL CLJS, con l'STL del mount caricato nell'app (registrato p.es. come
`:mount`):

```clojure
(require '[ridley.manifold.core :as mf]
         '[ridley.turtle.core :as t]
         '[ridley.scene.registry :as reg] :reload)

(def m (reg/get-mesh :mount))

;; La rotazione "imperfetta" della UI: tv 90 dal pose iniziale
(def pose (t/tv (t/make-turtle) 90))
(def h (:heading pose))
(prn :heading-ruotata h)          ; atteso: ≈ asse, ma NON esatto (es. 6.1e-17)

;; Candidati step lungo quella heading
(def bbox-c (let [[[x0 x1] [y0 y1] [z0 z1]] (ridley.sdf.core/mesh-bounds m)]
              [(* 0.5 (+ x0 x1)) (* 0.5 (+ y0 y1)) (* 0.5 (+ z0 z1))]))
(def cands (mf/cut-candidates m {:mode :translation :heading h
                                 :position bbox-c :up (:up pose)}))
(def steps (filterv #(= :step (:kind %)) cands))
(def c3 (nth steps 2))

;; Verifica dello snap: con il fix attivo la heading del candidato è la
;; NORMALE della faccia (componenti esatte ±1/0), non la h ruotata.
(prn :heading-candidato (:heading (:pose c3)))

;; Split e metriche
(def hh (:heading (:pose c3)))
(def off (t/dot hh (:position (:pose c3))))
(def r (mf/split-by-plane m hh off))

(defn tri-metrics [{:keys [vertices faces]}]
  (let [tri (fn [[i j k]]
              (let [a (nth vertices i) b (nth vertices j) c (nth vertices k)
                    ab (mapv - b a) ac (mapv - c a)
                    cx (let [[x1 y1 z1] ab [x2 y2 z2] ac]
                         [(- (* y1 z2) (* z1 y2))
                          (- (* z1 x2) (* x1 z2))
                          (- (* x1 y2) (* y1 x2))])
                    area (* 0.5 (Math/sqrt (reduce + (map * cx cx))))
                    lens (map (fn [[p q]] (Math/sqrt (reduce + (map #(let [d (- %1 %2)] (* d d)) p q))))
                              [[a b] [b c] [c a]])
                    lmax (apply max lens)]
                {:area area :aspect (if (pos? area) (/ (* lmax lmax) area) ##Inf)}))
        ms (map tri faces)]
    {:n (count faces)
     :slivers (count (filter #(or (< (:area %) 1e-6) (> (:aspect %) 1e4)) ms))
     :min-area (when (seq ms) (apply min (map :area ms)))}))

(prn :behind (tri-metrics (:behind r)))
(prn :ahead  (tri-metrics (:ahead r)))
(prn :vol-conservation
     [(:behind-volume r) (:ahead-volume r)])
```

Criterio PASS/FAIL, non estetico:

- `:slivers` = 0 su entrambe le metà, e heading candidato snappata → la
  **geometria è sana** → il problema visto a schermo è **H2 (rendering)**.
- `:slivers` > 0 → **H3**: c'è ancora un wafer reale; i numeri (`min-area`,
  conteggio) diventano la definizione riproducibile del bug e il test di
  regressione da mettere in `test/ridley/geometry/cut_candidates_test.cljs`
  (con un mesh a gradini costruito programmaticamente — due box impilati —
  invece dell'STL).

## Passo 3 — Discriminare H2 direttamente dalla UI

Riprodurre il flusso a mano (ruota 90°, terzo gradino, Accept), poi esportare
il pezzo accettato come STL (export nativo) e:

1. `mesh-diagnose` sul pezzo, oppure
2. aprire l'STL in uno slicer/MeshLab.

STL pulito ma schermo "sporco" → z-fighting nella preview. Fix di rendering,
non di geometria: `polygonOffset` sui materiali delle metà, oppure scostare
la preview di un ε lungo la normale del piano. Da notare che i due fix
geometrici già fatti *aumentano* la probabilità di z-fighting, perché rendono
il taglio esattamente complanare alla faccia del gradino.

## Nota di metodo

Ogni futura affermazione "fixato" su questo bug deve arrivare con l'output
del Passo 2 (metriche) eseguito nel runtime vivo — mai solo con la lettura
del codice.
