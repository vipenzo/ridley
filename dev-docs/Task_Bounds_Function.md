# Task: Implementare funzione `bounds` per bounding box

## Contesto

Per il posizionamento preciso di oggetti ("metti B sopra A" = contatto, non intersezione), l'AI ha bisogno di conoscere le dimensioni degli oggetti. Per le primitive è calcolabile dai parametri, ma per oggetti complessi (extrude, revolve, booleani) serve una funzione che calcoli il bounding box dai vertices.

---

## 1. Funzione `bounds`

Aggiungere in `src/ridley/geometry/` (o dove appropriato):

```clojure
(defn bounds 
  "Calcola bounding box di un mesh dai suoi vertices.
   Accetta mesh diretto o keyword di oggetto registrato."
  [mesh-or-name]
  (let [mesh (if (keyword? mesh-or-name)
               (get-registered-mesh mesh-or-name)
               mesh-or-name)
        vertices (:vertices mesh)
        xs (map #(nth % 0) vertices)
        ys (map #(nth % 1) vertices)
        zs (map #(nth % 2) vertices)]
    {:min [(apply min xs) (apply min ys) (apply min zs)]
     :max [(apply max xs) (apply max ys) (apply max zs)]
     :center [(/ (+ (apply min xs) (apply max xs)) 2)
              (/ (+ (apply min ys) (apply max ys)) 2)
              (/ (+ (apply min zs) (apply max zs)) 2)]
     :size [(- (apply max xs) (apply min xs))
            (- (apply max ys) (apply min ys))
            (- (apply max zs) (apply min zs))]}))
```

---

## 2. Esporre in SCI

Aggiungere `bounds` alle funzioni esposte nel contesto SCI in `src/ridley/editor/repl.cljs`.

---

## 3. Aggiornare `info`

Estendere la funzione `info` per includere bounds:

```clojure
(info :my-object)
;; => {:name :my-object 
;;     :visible true 
;;     :vertices 24 
;;     :faces 12
;;     :bounds {:min [-20 -20 0] 
;;              :max [20 20 40] 
;;              :center [0 0 20] 
;;              :size [40 40 40]}
;;     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}
```

---

## 4. Helper functions (opzionali ma utili)

```clojure
(defn height [mesh-or-name]
  "Altezza dell'oggetto (dimensione Z)"
  (get-in (bounds mesh-or-name) [:size 2]))

(defn width [mesh-or-name]
  "Larghezza dell'oggetto (dimensione X)"
  (get-in (bounds mesh-or-name) [:size 0]))

(defn depth [mesh-or-name]
  "Profondità dell'oggetto (dimensione Y)"
  (get-in (bounds mesh-or-name) [:size 1]))

(defn top [mesh-or-name]
  "Coordinata Z massima"
  (get-in (bounds mesh-or-name) [:max 2]))

(defn bottom [mesh-or-name]
  "Coordinata Z minima"
  (get-in (bounds mesh-or-name) [:min 2]))
```

---

## 5. Uso nel REPL

```clojure
;; Creo due oggetti
(register base (box 40))
(register column (extrude (circle 5) (f 30)))

;; Controllo dimensioni
(bounds :base)
;; => {:min [-20 -20 -20] :max [20 20 20] :center [0 0 0] :size [40 40 40]}

(bounds :column)  
;; => {:min [-5 -5 0] :max [5 5 30] :center [0 0 15] :size [10 10 30]}

;; Helper
(height :base)    ;; => 40
(top :base)       ;; => 20
(bottom :column)  ;; => 0
```

---

## 6. Uso dall'AI (Tier 2+)

Con questa funzione, l'AI può generare codice preciso:

```clojure
;; "metti column sopra base"
(register column 
  (attach (extrude (circle 5) (f 30))
    (tv 90) 
    (f (+ (top :base) (/ (height :column) 2)))
    (tv -90)))
```

O se l'oggetto è già registrato:

```clojure
;; Calcola offset per contatto
(let [offset (+ (top :base) (- (bottom :column)))]
  (register column (attach column (tv 90) (f offset) (tv -90))))
```

---

## Test

```clojure
;; Test 1: primitiva
(register cube (box 30))
(bounds :cube)
;; => size dovrebbe essere [30 30 30]

;; Test 2: extrude
(register tube (extrude (circle 10) (f 50)))
(bounds :tube)
;; => size Z dovrebbe essere ~50

;; Test 3: oggetto spostato
(register moved (attach (box 20) (f 100)))
(bounds :moved)
;; => center X dovrebbe essere ~100

;; Test 4: booleano
(register diff (mesh-difference (box 40) (cyl 10 50)))
(bounds :diff)
;; => dovrebbe funzionare anche su mesh risultanti da boolean
```

---

## Note

- `bounds` lavora sui vertices trasformati (dopo attach/move), non sulla definizione originale
- Per mesh vuoti o invalidi, gestire con errore o ritorno nil
- Considerare caching se il calcolo diventa costoso su mesh grandi
