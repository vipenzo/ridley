# 17. Esportare e stampare

<!-- level: base -->

Una volta che il modello è pronto nel viewport, lo esporti come file per lo slicer o per un altro software. Ridley supporta due formati: STL (il formato universale della stampa 3D) e 3MF (il formato più recente, con supporto per colori e materiali multipli).

## 17.1 STL

### Export da registro

```clojure
(export :my-piece)                   ;; un pezzo, per nome
(export :piece-a :piece-b)           ;; più pezzi, fusi in un unico file
(export my-mesh)                     ;; per riferimento (variabile)
```

`export` cerca la mesh nel registro per nome (keyword) o accetta direttamente un valore mesh. Il file viene scaricato dal browser (o salvato tramite file picker sul desktop).

Quando esporti più mesh in STL, vengono fuse in un unico file. Non c'è modo di mantenere parti separate in STL: è un limite del formato.


### Cosa finisce nel file

L'STL contiene solo la geometria: vertici e facce triangolari. Non trasporta colori, materiali, nomi, o la distinzione fra parti. Se il tuo modello ha più pezzi colorati diversamente e li esporti in STL, ottieni un unico blocco grigio.

## 17.2 3MF multi-materiale

### Export base

```clojure
(export :my-piece :3mf)              ;; singolo pezzo in 3MF
(export :piece-a :piece-b :3mf)      ;; più pezzi in 3MF
```

La keyword `:3mf` come ultimo argomento seleziona il formato. Senza di essa, `export` produce STL.

### Multi-materiale

Quando le mesh esportate hanno colori assegnati con `color`, il 3MF li trasporta:

```clojure
(register base (box 40 40 3))
(register label (attach (extrude-text "OK" :size 15 :depth 1) (th 90) (rt -4) (u 2.5)))

(color :base 0xff0000)
(color :label 0xffffff)

(export :base :label :3mf)
```

Nel file 3MF risultante ogni mesh è un oggetto separato con il suo colore. Nello slicer (Bambu Studio, OrcaSlicer, PrusaSlicer) le parti appaiono con i colori preassegnati, pronte per essere mappate agli slot filamento.

Mesh con lo stesso colore condividono la stessa entry materiale (stesso filamento, parti distinte). Mesh senza colore vengono esportate come geometria senza informazioni di materiale.


### Quando usare STL, quando 3MF

STL è il formato più supportato: ogni slicer, ogni servizio di stampa online, ogni software CAD lo legge. Usalo quando non hai bisogno di colori o materiali multipli, o quando devi condividere il file con qualcuno che potrebbe non avere un slicer moderno.

3MF è il formato da preferire per la stampa multi-materiale e in generale quando lavori con Bambu Studio o OrcaSlicer, che lo supportano nativamente. È anche più compatto (compresso) e trasporta più informazioni (unità, nomi delle parti).

### Consigli per la stampa

Il modello che vedi nel viewport è esattamente quello che finisce nel file: stesse dimensioni, stessa geometria, stessa risoluzione. Se un pezzo sembra sfaccettato nel viewport, sarà sfaccettato anche nella stampa. Alza `resolution` prima dell'export se serve.

Le unità di Ridley sono millimetri. Se il tuo modello è parametrico, controlla con `bounds` che le dimensioni finali siano quelle che ti aspetti prima di esportare.

`mesh-diagnose` (capitolo 7.7) è lo strumento per verificare che la mesh sia sana prima dell'export. La maggior parte degli slicer ripara automaticamente piccoli difetti, ma mesh con molti open-edge o non-manifold-edge possono produrre risultati imprevedibili nello slicing.

## 17.3 Orientare per la stampa

Lo slicer si aspetta il modello appoggiato sul piano di stampa. Se il pezzo è stato costruito in un orientamento qualsiasi, `lay-flat` lo ruota in modo che una sua faccia poggi sul piano XY del mondo, e ricentra il risultato sull'origine. È il passo esplicito per dare al pezzo l'orientamento di stampa senza rimodellarlo.

<!-- example-source: lay-flat-default -->
```clojure
(register bracket (-> (box 30 20 8) (lay-flat)))
```

Senza argomenti `lay-flat` usa `:bottom`: prende la faccia inferiore più grande e la appoggia sul piano. Per i pezzi asimmetrici l'orientamento di stampa migliore spesso non è il fondo. Una keyword di direzione (`:top`, `:bottom`, `:up`, `:down`, `:left`, `:right`) sceglie la faccia più grande su quel lato e la mette giù.

<!-- example-source: lay-flat-direction -->
```clojure
(register sideways (-> (box 40 10 20) (lay-flat :left)))
```

Quando la faccia di stampa non è allineata a una direzione cardinale, la si può marcare come anchor con `attach-path` e passare il nome dell'anchor a `lay-flat` (`(lay-flat :part :print-face)`): viene messo a piano il piano dell'anchor, qualunque sia il suo orientamento.

Due cose da ricordare. `lay-flat` ricentra il pezzo sull'origine: se ti serve tenere un angolo preciso a `[0 0 0]`, segui con un `translate`. E opera sui vertici, non sulla `:creation-pose`, che resta all'origine di costruzione; se serve riancorarla c'è `reset-creation-pose`. Per le mesh senza face group, come i risultati di una booleana, i gruppi di facce vengono dedotti automaticamente per adiacenza complanare e la faccia più grande viene scelta da quel raggruppamento.
