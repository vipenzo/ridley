# 17. Esportare e stampare

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

### Export diretto

Se hai la mesh in mano come valore (non registrata), usa `save-stl`:

```clojure
(save-stl my-mesh)                   ;; nome suggerito "model.stl"
(save-stl my-mesh "bracket.stl")     ;; nome suggerito custom
```

`save-stl` apre il file picker nativo. Il nome che passi è un suggerimento: l'utente può cambiarlo nel picker.

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
(register label (attach (extrude-text "OK" :size 15 :depth 1) (u 2.5)))

(color :base 0xff0000)
(color :label 0xffffff)

(export :base :label :3mf)
```

Nel file 3MF risultante ogni mesh è un oggetto separato con il suo colore. Nello slicer (Bambu Studio, OrcaSlicer, PrusaSlicer) le parti appaiono con i colori preassegnati, pronte per essere mappate agli slot filamento.

Mesh con lo stesso colore condividono la stessa entry materiale (stesso filamento, parti distinte). Mesh senza colore vengono esportate come geometria senza informazioni di materiale.

### Export diretto

```clojure
(save-3mf my-mesh)                   ;; nome suggerito "model.3mf"
(save-3mf my-mesh "bracket.3mf")     ;; nome suggerito custom
```

`save-mesh` è la forma generica che accetta entrambi i formati:

```clojure
(save-mesh my-mesh "part.stl")       ;; STL
(save-mesh my-mesh "part.3mf" :3mf)  ;; 3MF
```

Il formato effettivamente scritto segue l'estensione digitata dall'utente nel picker: se passi `.3mf` a `save-stl`, il file prodotto è comunque un 3MF.

### Quando usare STL, quando 3MF

STL è il formato più supportato: ogni slicer, ogni servizio di stampa online, ogni software CAD lo legge. Usalo quando non hai bisogno di colori o materiali multipli, o quando devi condividere il file con qualcuno che potrebbe non avere un slicer moderno.

3MF è il formato da preferire per la stampa multi-materiale e in generale quando lavori con Bambu Studio o OrcaSlicer, che lo supportano nativamente. È anche più compatto (compresso) e trasporta più informazioni (unità, nomi delle parti).

### Consigli per la stampa

Il modello che vedi nel viewport è esattamente quello che finisce nel file: stesse dimensioni, stessa geometria, stessa risoluzione. Se un pezzo sembra sfaccettato nel viewport, sarà sfaccettato anche nella stampa. Alza `resolution` prima dell'export se serve.

Le unità di Ridley sono millimetri. Se il tuo modello è parametrico, controlla con `bounds` che le dimensioni finali siano quelle che ti aspetti prima di esportare.

`mesh-diagnose` (capitolo 7.1) è lo strumento per verificare che la mesh sia sana prima dell'export. La maggior parte degli slicer ripara automaticamente piccoli difetti, ma mesh con molti open-edge o non-manifold-edge possono produrre risultati imprevedibili nello slicing.
