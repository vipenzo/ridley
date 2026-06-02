# 14. Colore e materiali

In Ridley il colore è uno strumento di modellazione, non solo di visualizzazione. I colori che assegni alle mesh determinano come le parti verranno mappate ai filamenti nella stampa multi-materiale. Questo capitolo copre l'assegnazione dei colori, le proprietà dei materiali, e il workflow per esportare modelli multi-colore.

## 14.1 Colorare le forme

### Colore globale

`color` senza un nome di mesh come primo argomento imposta il colore per tutte le mesh create da quel punto in poi:

```clojure
(color 0xff0000)           ;; rosso, da qui in avanti
(register red-box (box 20))

(color 0x0000ff)           ;; blu
(register blue-cyl (cyl 10 30))
```

<!-- example-source: color-global
(color 0xff0000)
(register red-box (box 20))
(color 0x0000ff)
(register blue-cyl (attach (cyl 10 30) (f 40)))
-->

Il colore è memorizzato nello stato della tartaruga e viene applicato a ogni mesh creata dopo la chiamata (primitive, estrusioni, loft, ecc.).

La forma RGB con tre argomenti separati funziona allo stesso modo:

```clojure
(color 255 0 0)            ;; rosso, componenti 0-255
```

### Colore per mesh

Per cambiare il colore di una mesh già registrata, senza ricrearla:

```clojure
(register b (box 20))
(color :b 0xff8800)        ;; arancione per :b
(color :b 255 128 0)       ;; stessa cosa, forma RGB
```

<!-- example-source: color-per-mesh
(register b (box 20))
(color :b 0xff8800)
-->

Il primo argomento è il nome registrato (keyword). La mesh nel registro viene aggiornata con il nuovo colore.

### Colore su valori mesh

`color` può anche prendere un valore mesh (non registrato) come primo argomento. In questo caso restituisce una nuova mesh con il colore impostato, senza modificare nulla nel registro:

```clojure
(def colored-box (color (box 20) 0xff0000))
(register r colored-box)
```

Questa forma è utile per composizioni inline con `register`:

```clojure
(register part (color (mesh-union a b) 0x00ff00))
```

## 14.2 Materiali

### Proprietà dei materiali

`material` imposta le proprietà PBR (physically-based rendering) delle mesh:

```clojure
;; Globale: vale per le mesh create da qui in poi
(material :metalness 0.8 :roughness 0.2)

;; Per mesh registrata
(material :my-mesh :metalness 0.8 :roughness 0.2)

;; Su valore mesh (restituisce nuova mesh)
(register bowl (material (make-bowl 30 20) :opacity 0.3))
```

<!-- example-source: material-metallic
(register shiny-sphere (material (sphere 20) :metalness 0.9 :roughness 0.1))
-->

Le proprietà disponibili sono quelle del materiale standard Three.js: `:metalness` (0-1), `:roughness` (0-1), `:opacity` (0-1), `:color` (hex, impostabile anche da `material`).

`(reset-material)` riporta le proprietà al default.

### Trasparenza

L'opacità si imposta con `:opacity` dentro `material`:


<!-- example-source: material-transparent
(register ghost (material (box 30) :opacity 0.3))
(register glass (material (attach (sphere 15) (f 40)) :opacity 0.5 :color 0x88ccff))
-->

La trasparenza è utile in fase di modellazione per vedere attraverso un pezzo e capire come si incastrano le parti interne. Non ha effetto sull'export: STL e 3MF non trasportano opacità.

## 14.3 Multi-material: il pattern register + color

Il workflow per stampanti multi-materiale (Bambu AMS, Prusa MMU) è semplice: registra le parti come mesh separate, assegna un colore a ciascuna, esporta in 3MF.

Nel file 3MF risultante, ogni mesh diventa un oggetto separato con il suo colore. Quando lo apri in Bambu Studio o OrcaSlicer, le parti appaiono con i colori preassegnati, pronte per essere mappate agli slot del filamento.

Alcune cose da sapere:

Mesh con lo stesso colore condividono un'unica entry materiale nel 3MF (stesso filamento, parti distinte). Mesh senza colore vengono esportate come geometria senza informazioni di materiale, come in un STL.

Il formato STL non supporta colori o materiali multipli. Se esporti in STL, tutte le mesh vengono fuse in una sola e il colore si perde. Per multi-materiale, usa sempre 3MF.

### Il pattern bicolor label

L'esempio più comune di multi-materiale è un'etichetta bicolore: una base piatta con testo in contrasto. Il pattern è:

1. Costruisci la base come una mesh
2. Costruisci il testo come mesh separata, posizionata sulla faccia superiore della base
3. Assegna colori distinti
4. Esporta entrambe in 3MF


La chiave è che base e testo sono mesh separate, non fuse con `mesh-union`. Se le unisci, diventano un unico oggetto nel 3MF e non puoi assegnare colori diversi.

### Una funzione riutilizzabile: testo incassato

La funzione `bicolor-label` sotto *incassa* il testo nella base: le lettere vengono sottratte dalla piastra con `mesh-difference` e riempite da una seconda mesh dello stesso spessore, così i due colori giacciono sullo stesso piano e la stampa non ha lettere sporgenti.

La funzione  incapsula l'intero procedimento e restituisce un vettore con le due mesh `[base testo]`, già colorate e pronte da registrare ed esportare:


L'uso è poi una riga: destrutturi il vettore e registri le due parti.

```clojure
(let [[base txt] (bicolor-label "Ridley")]
  (register label-base base)
  (register label-text txt))
```

<!-- example-source: bicolor-label-fn
(defn bicolor-label
  [text & {:keys [font-sz label-depth font margin-ratio tolerance full-cut
                  base-color text-color]
           :or   {font-sz 15 label-depth 3 font :roboto
                  margin-ratio 1.1 tolerance 0.3 full-cut false
                  base-color 0xffffff text-color 0xff00ff}}]
  (let [len       (text-width text font font-sz)
        mgn       (fn [x n] (* (+ 1 (* n (- margin-ratio 1))) x))
        cut-depth (if full-cut label-depth (/ label-depth 2))
        t-shape   (text-shape text :size font-sz :font font :center true)
        n-t-shape (shape-offset t-shape tolerance)
        base      (color (mesh-difference
                           (attach (box (mgn len 2) (mgn font-sz 2) label-depth)
                                   (cp-f (/ label-depth -2)))
                           (extrude n-t-shape (f cut-depth)))
                         base-color)
        e-text    (color (extrude t-shape (f cut-depth)) text-color)]
    [base e-text]))

(let [[base txt] (bicolor-label "This is Ridley")]
  (register label-base base)
  (register label-text txt))
  
;(export :label-base :label-text :3mf)

(let [[base txt] (bicolor-label "3D Printing is nice"
                   :label-depth 5
                   :full-cut true
                   :font-sz 24
                   :margin-ratio 1.05
                   :tolerance 0.8
                   :base-color 0xffff00
                   :text-color 0x999988
                   )] ; testo solo sul lato -X
  (register A (attach txt (u 100)))
  (register B (attach base (u 100))))

-->

I parametri:

- `text` è l'unico argomento obbligatorio.
- `:font-sz` (15), `:font` (`:roboto`) e `:label-depth` (3) controllano corpo del carattere, font e spessore della piastra.
- `:margin-ratio` (1.1) imposta il margine attorno al testo: la piastra è dimensionata sulla larghezza reale del testo (misurata con `text-width`) più questo margine.
- `:tolerance` (0.3) allarga il foro rispetto alle lettere (`shape-offset`), lasciando il gioco necessario perché le due parti si incastrino nella stampa multi-materiale.
- `:base-color` (bianco) e `:text-color` (magenta) sono i due colori.
- `:full-cut` (`false`) decide se il testo attraversa la piastra da parte a parte (`true`, scritta visibile da entrambi i lati) o resta inciso solo sulla faccia frontale fino a metà spessore (`false`, con un fondo solido del colore base).

La funzione non registra né dà nomi alle mesh: restituisce un vettore, così sei tu a decidere come chiamarle e a passarle a `(export ... :3mf)`.
