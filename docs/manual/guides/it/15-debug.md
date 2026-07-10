# 15. Mettere a fuoco e risolvere i problemi

<!-- level: intermediate -->

Quando un modello non fa quello che ti aspetti, hai bisogno di strumenti per capire cosa sta succedendo. Ridley ne offre diversi, da quelli più semplici (stampare un valore nella console) a quelli più sofisticati (tweaking interattivo con slider, pannelli 3D posizionati nella scena). Questo capitolo li raccoglie in un unico posto, con rimandi ai capitoli dove sono già stati introdotti.

## 15.1 Pannelli 3D di testo

I pannelli sono billboard di testo posizionati nella scena 3D. Sono lo strumento di debug più visivo: puoi piazzare un pannello accanto a un pezzo e fargli mostrare i valori che ti interessano.

<!-- example-source: panels
;; Crea un pannello alla posizione corrente della tartaruga
(register debug (panel 40 20))

;; Scrivi qualcosa
(out :debug "Hello World")

;; Aggiungi testo
(append :debug "\nVertici: 1234")

;; Cancella il contenuto
;(clear :debug)
-->

Le opzioni di stile:

<!-- example-source: panels2
(register debug (panel 56 15
                  :font-size 8
  :bg 0xff3333cc        ;; sfondo semi-trasparente (cc, byte basso, è l'alfa)
  :fg 0xffffff          ;; testo bianco
  :padding 2
  :line-height 1.4))
(out :debug "Your Ad Here")
-->

I pannelli si comportano come le mesh: supportano `show`/`hide`, `register`, `attach`/`attach!`. Puoi posizionarli con `attach` accanto al pezzo che stai debuggando.

Un pattern utile: un pannello che mostra le dimensioni di un pezzo mentre lo costruisci:

<!-- example-source: panel-showing-mesh-details
(def W 10)
(def H 20)
(def D 40)

(register part (box W H D))
(register info-panel (attach (panel 30 15) (f -40)))
(out :info-panel
  (str "W=" W " D=" D " H=" H
       "\n" (:n-faces (mesh-diagnose part)) " faces"))
-->

## 15.2 Tecniche di debug

### Println e la console

Il modo più diretto: `println` stampa nella console del browser (e nel pannello output di Ridley):

<!-- example-source: println :no-run
(def m (box 20))
(println "vertices: " (:n-verts (mesh-diagnose m)))
(println "manifold? " (manifold? m))
(println "bounds: " (bounds m))
-->

Non sottovalutare `println`. Per la maggior parte dei problemi ("perché questa booleana fallisce?", "quanto è grande questo pezzo?", "la creation-pose è dove penso?") un `println` mirato è più veloce di qualsiasi strumento sofisticato.

### T: stampare un valore senza interrompere il flusso

`T` è un helper di debug che stampa un valore con un'etichetta e lo restituisce invariato. Serve a sbirciare un valore nel mezzo di un'espressione senza doverla spezzare.

<!-- example-source: tap
(register cube (box (T "size" (* 10 3))))
-->

Stampa `size : 30` nella console e passa comunque `30` a `box`, quindi il cubo viene creato normalmente. Siccome restituisce il valore che riceve, puoi avvolgere con `T` qualsiasi sottoespressione lasciando intatto il resto del codice: è il modo più rapido per scoprire quale dei tanti valori calcolati è quello sbagliato.

### Ispezionare lo stato della tartaruga

Quando il dubbio non è su un valore ma su dove si trova la tartaruga, `turtle-position`, `turtle-heading` e `turtle-up` restituiscono posizione, direzione di avanzamento e vettore up correnti. Stampali per verificare che la geometria nascerà dove pensi.

<!-- example-source: turtle-inspect :no-run
(f 50)
(println "pos:" (turtle-position))
(th 45)
(println "heading:" (turtle-heading))
(println "up:" (turtle-up))
-->

### Show/hide per isolare

Quando la scena è affollata e non capisci quale pezzo crea problemi, nascondi tutto tranne quello che stai esaminando:

```clojure
(hide-all)
(show :suspect-piece)
```

Oppure il contrario: nascondi un pezzo per vedere cosa c'è sotto:

```clojure
(hide :outer-shell)
```

`show-all` riporta tutto alla visibilità. `objects` elenca i nomi visibili, `registered` elenca tutti i nomi nel registro.

### Trasparenza per vedere dentro

Se nascondere un pezzo è troppo drastico, rendilo trasparente:

```clojure
(material :outer-shell :opacity 0.3)
```

Vedi attraverso il guscio esterno senza perderlo dalla vista. Ricorda che l'opacità non sopravvive all'export.

### Info e bounds

`info` restituisce un riepilogo di una mesh registrata:

```clojure
(info :my-piece)
;; => {:name :my-piece :visible true :vertices 1234 :faces 2468 :bounds {...}}
```

`bounds` restituisce il bounding box, utile per verificare che un pezzo sia dove pensi:

```clojure
(bounds :my-piece)
;; => {:min [x y z] :max [x y z] :center [x y z] :size [sx sy sz]}
```

Le funzioni `height`, `width`, `depth`, `top`, `bottom`, `center-x`, `center-y`, `center-z` estraggono singole dimensioni dal bounding box.

### Highlight delle facce

Per capire quale faccia stai selezionando con `find-faces` o `face-at`:

```clojure
(flash-face my-mesh :top 2000 0x00ff00)    ;; highlight verde, 2 secondi
(highlight-face my-mesh :top)              ;; highlight permanente
(clear-highlights)
```

Trattato nel dettaglio nella sezione 7.4.

## 15.3 Tweaking interattivo

`tweak` è lo strumento per esplorare i parametri di un'espressione con slider in tempo reale. Valuta l'espressione, mostra il risultato nel viewport, e crea slider per i numeri letterali nel codice.

<!-- example-source: tweak1 :no-run
;; Senza filtro: slider per ogni numero letterale (15 e 30) — come :all
(register t (tweak (extrude (circle 15) (f 30))))
-->

Slider per tutti i numeri
<!-- example-source: tweak2 :no-run
(register tube (tweak :all (extrude (circle 15) (f 30) (th 90) (f 20))))
-->

```clojure
;; Slider per un indice specifico (0-based, depth-first left-to-right)
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))    ;; tweaka 90

;; Indice negativo (dall'ultimo)
(tweak -1 (extrude (circle 15) (f 30) (th 90) (f 20)))   ;; tweaka 20
```

Lo slider parte con un range centrato sul valore corrente (`[valore * 0.1, valore * 3]`). I bottoni zoom (`-`/`+`) restringono o allargano il range. OK conferma e stampa l'espressione finale nella REPL, Cancel (o Esc) ripristina.

### Tweak su mesh registrate

Quando passi una keyword, `tweak` opera sulla mesh registrata: nasconde l'originale durante il tweaking, ri-registra il risultato su OK, ripristina l'originale su Cancel.

```clojure
(register A (extrude (circle 15) (f 30)))

;; Tweaka usando il source form salvato da register
(tweak :A)
(tweak :all :A)

;; Tweaka con espressione esplicita
(tweak :all :A (extrude (circle 20) (f 40)))
```

`(tweak :A)` funziona perché `register` salva automaticamente il source form. Se la form contiene chiamate a funzioni definite dall'utente (`(register A (make-a 1))`), quelle funzioni devono essere già definite al momento del tweak.

### Edit-attach: posizionamento interattivo con gizmo e tastiera

`edit-attach` apre una sessione modale in cui riposizioni e deformi una mesh esistente. Sulla creation-pose della mesh appare un gizmo: tre frecce di traslazione e tre anelli di rotazione orientati sugli assi della tartaruga, più tre maniglie di stretch. Ogni drag diventa un comando turtle (`f`, `rt`, `u`, `th`, `tv`, `tr`, `stretch-*`) che appare nel pannello laterale, con l'effetto visibile nel viewport in tempo reale. Quando confermi, `edit-attach` si riscrive nel sorgente come `attach` con la sequenza di comandi accumulata; con Esc annulli e resta la sola espressione di partenza.

<!-- example-source: edit-attach :no-run
(register part (edit-attach (box 20)))
-->

La chiamata va messa dove il suo valore viene usato, qui dentro `register`: alla conferma l'`edit-attach` si riscrive in un `attach` al suo posto, e la form diventa `(register part (attach (box 20) ...))`. Una `(edit-attach ...)` da sola a top level edita una copia il cui risultato non viene consumato, e il lavoro va perso.

La tastiera resta come strumento di precisione, con i tre modi storici (stile vim): movimento (`f`/`b`/`rt`/`lt`/`u`/`d`), rotazione (`th`/`tv`/`tr`), scala (`stretch-f`/`stretch-rt`/`stretch-u`). I drag scattano sulla stessa griglia dei passi tastiera (i parametri step/angle/scale del pannello); tenendo Shift il movimento è libero.

Il toggle oggetto/origin nel pannello cambia il significato dei gesti: in modalità origin muovi la creation-pose relativamente alla geometria (la famiglia `cp-*` del cap. 8), e un click su un vertice della mesh aggancia la pose esattamente lì. È il modo rapido per mettere l'ancora, e quindi il pivot di rotazioni e stretch, su un punto significativo del pezzo.

Un `attach` già scritto è ri-editabile: voce "Edit" del menu contestuale sulla form (o prefisso `edit-` aggiunto a mano), i comandi esistenti entrano nel pannello intatti e i nuovi gesti si accodano. `pilot`, il vecchio nome di questa sessione, funziona ancora come alias. I tasti e i dettagli operativi sono nella Reference.

### Una terza sessione: edit-bezier

`edit-bezier` usa lo stesso meccanismo modale di `tweak` ed `edit-attach` per disegnare da tastiera una curva di Bezier cubica, e alla conferma la riscrive a sorgente come `bezier-to`. Siccome è prima di tutto uno strumento per le curve, è trattata nel § 11.2.

## 15.4 Dove trovare gli strumenti

Alcuni strumenti che usi in fase di debug hanno la loro casa in altri capitoli. Il promemoria, in una tabella:

| Strumento | A cosa serve quando indaghi | Casa |
|---|---|---|
| `stamp` | vedere un profilo 2D prima di estruderlo o loftarlo | § 3.2 |
| `follow-path` | vedere un path come tracciato, senza costruire geometria | § 5.2 |
| `ruler`, `distance`, `bounds`, Shift+Click | misure e quote, anche interattive | cap. 10 |
| `mesh-diagnose`, `manifold?` | capire perché una mesh non è sana | § 7.7 |
