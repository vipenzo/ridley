# 15. Mettere a fuoco e risolvere i problemi

Quando un modello non fa quello che ti aspetti, hai bisogno di strumenti per capire cosa sta succedendo. Ridley ne offre diversi, da quelli più semplici (stampare un valore nella console) a quelli più sofisticati (tweaking interattivo con slider, pannelli 3D posizionati nella scena). Questo capitolo li raccoglie in un unico posto, con rimandi ai capitoli dove sono già stati introdotti.

## 15.1 Pannelli 3D di testo

I pannelli sono billboard di testo posizionati nella scena 3D. Sono lo strumento di debug più visivo: puoi piazzare un pannello accanto a un pezzo e fargli mostrare i valori che ti interessano.

```clojure
;; Crea un pannello alla posizione corrente della tartaruga
(register debug (panel 40 20))

;; Scrivi qualcosa
(out :debug "Hello World")

;; Aggiungi testo
(append :debug "\nVertici: 1234")

;; Cancella il contenuto
(clear :debug)
```

Le opzioni di stile:

```clojure
(register debug (panel 40 20
  :font-size 3
  :bg 0x333333cc        ;; sfondo semi-trasparente
  :fg 0xffffff          ;; testo bianco
  :padding 2
  :line-height 1.4))
```

I pannelli si comportano come le mesh: supportano `show`/`hide`, `register`, `attach`/`attach!`. Puoi posizionarli con `attach` accanto al pezzo che stai debuggando.

Un pattern utile: un pannello che mostra le dimensioni di un pezzo mentre lo costruisci:

```clojure
(register part (box W D H))
(register info-panel (attach (panel 30 15) (f 40)))
(out :info-panel
  (str "W=" W " D=" D " H=" H
       "\n" (:n-faces (mesh-diagnose part)) " faces"))
```

## 15.2 Tecniche di debug

### Println e la console

Il modo più diretto: `println` stampa nella console del browser (e nel pannello output di Ridley):

```clojure
(def m (box 20))
(println "vertices:" (:n-verts (mesh-diagnose m)))
(println "manifold?" (manifold? m))
(println "bounds:" (bounds :my-piece))
```

Non sottovalutare `println`. Per la maggior parte dei problemi ("perché questa booleana fallisce?", "quanto è grande questo pezzo?", "la creation-pose è dove penso?") un `println` mirato è più veloce di qualsiasi strumento sofisticato.

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

```clojure
;; Slider per il primo numero letterale (15)
(tweak (extrude (circle 15) (f 30)))

;; Slider per tutti i numeri
(tweak :all (extrude (circle 15) (f 30) (th 90) (f 20)))

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

### Pilot: posizionamento interattivo via tastiera

`pilot` apre una sessione modale in cui la tastiera muove la tartaruga a partire dalla posa di una mesh esistente. I comandi accumulati appaiono in un pannello laterale e il loro effetto si vede nel viewport in tempo reale. Quando confermi, `pilot` sostituisce la sua chiamata nel sorgente con un `attach` contenente la sequenza di comandi.

```clojure
(register part (box 20))
(pilot :part)
```

Pilot ha tre modi (stile vim): movimento (`f`/`b`/`rt`/`lt`/`u`/`d`), rotazione (`th`/`tv`/`tr`), scala (`stretch-f`/`stretch-rt`/`stretch-u`). I tasti per cambiare modo e i dettagli operativi sono nella Reference.

## 15.4 Anteprima forme

`stamp` renderizza una shape 2D nel viewport come contorno piatto, senza estrudere. È lo strumento per verificare che un profilo sia quello che ti aspetti prima di estrudere o loftare.

```clojure
(stamp (circle 20))
(stamp (rect 30 15))
(stamp (text-shape "Hello" :size 20))
```

Trattato nel dettaglio nella sezione 3.6 (se presente) e usato in tutti i capitoli sulle forme 2D.

## 15.5 Follow path

`follow-path` disegna un percorso nel viewport come tracciato della tartaruga, mostrando la sequenza di movimenti senza costruire geometria. È lo strumento per verificare che un path sia quello che ti aspetti prima di usarlo per un'estrusione o un loft.

```clojure
(def skel (path (mark :A) (f 20) (th 45) (f 15) (mark :B)))
(follow-path skel)
```

Trattato nel capitolo 5 (Path).

## 15.6 Misurazione

`ruler`, `distance`, `bounds`, e la misurazione interattiva con Shift+Click sono trattati nel capitolo 10 (Analizzare e misurare). Qui il promemoria: se non sai quanto è grande un pezzo o a che distanza sono due punti, il capitolo 10 ha tutti gli strumenti.
