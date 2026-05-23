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

```clojure
(register ghost (material (box 30) :opacity 0.3))
(register glass (material (sphere 15) :opacity 0.5 :color 0x88ccff))
```

<!-- example-source: material-transparent
(register ghost (material (box 30) :opacity 0.3))
(register glass (material (attach (sphere 15) (f 40)) :opacity 0.5 :color 0x88ccff))
-->

La trasparenza è utile in fase di modellazione per vedere attraverso un pezzo e capire come si incastrano le parti interne. Non ha effetto sull'export: STL e 3MF non trasportano opacità.

## 14.3 Multi-material: il pattern register + color

Il workflow per stampanti multi-materiale (Bambu AMS, Prusa MMU) è semplice: registra le parti come mesh separate, assegna un colore a ciascuna, esporta in 3MF.

```clojure
;; Due parti con colori distinti
(register base (box 40 40 3))
(register label (attach (extrude-text "OK" :size 15 :depth 1) (u 2.5)))

(color :base 0xff0000)
(color :label 0xffffff)

;; Export 3MF multi-materiale
(export :base :label :3mf)
```

<!-- example-source: multi-material-export
(register base (box 40 40 3))
(register label (attach (extrude-text "OK" :size 15 :depth 1) (u 2.5)))
(color :base 0xff0000)
(color :label 0xffffff)
-->

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

```clojure
(def W 60) (def D 25) (def H 3) (def text-h 1)

(register base (box W D H))
(register text-part
  (attach (extrude-text "RIDLEY" :size 14 :depth text-h)
          (u (/ H 2))))

(color :base 0x222222)
(color :text-part 0xffffff)

(export :base :text-part :3mf)
```

<!-- example-source: bicolor-label
(def W 60) (def D 25) (def H 3)
(register base (box W D H))
(register text-part
  (attach (extrude-text "RIDLEY" :size 14 :depth 1)
          (u (/ H 2))))
(color :base 0x222222)
(color :text-part 0xffffff)
-->

La chiave è che base e testo sono mesh separate, non fuse con `mesh-union`. Se le unisci, diventano un unico oggetto nel 3MF e non puoi assegnare colori diversi.
