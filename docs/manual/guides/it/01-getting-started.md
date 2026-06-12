<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Capitolo 1 "Per iniziare", scritto a giugno 2026 a colmare la lacuna
(era nel piano §3.1 fin dall'inizio, mai steso). Scaffold: 1.1 Ciao
Ridley, 1.2 L'interfaccia, 1.3 Run e REPL, 1.4 La tartaruga (5 comandi
base), 1.5 Basi di Clojure (minimo per leggere il cap. 2; il cap. 16
resta l'approfondimento).

DETTAGLI UI — VERIFICATI da Vincenzo il 2026-06-12 (capitolo confermato
con quattro correzioni: click di mouse, REPL in basso a sinistra,
Opzione ⌥ al posto di Alt, b ≡ f negativo). Lista conservata come storico:
- Controlli camera nel viewport: mappatura esatta di orbita / pan /
  zoom (la 1.2 li descrive in forma generica "trascina / rotella").
- Tartaruga visibile di default nel viewport? (la 1.4 lo afferma,
  esiste show-turtle/hide-turtle quindi un default c'è).
- Autocompletamento e tooltip con firma nell'editor: come si
  attivano, aspetto (la 1.3 li descrive in forma generica).
- Opzione(⌥)+Click su una mesh -> catena di link nella status bar
  (verificato 2026-06-12; Architecture.md dice "Alt", su Mac è ⌥).
  NOTA APERTA: il salto status bar -> editor non è sincronizzato col
  workspace di provenienza (es. mesh generata dal Run di un esempio:
  editor e viewport non corrispondono). Tracciato in
  dev-docs/code-issues.md, candidato Roadmap.
- Nome del pannello principale: "Definitions" (da Architecture.md).
- Distribuzione esatta fra top bar e barra laterale degli elementi
  citati in 1.2 (manuale / Workspace / librerie / impostazioni): la
  prosa li elenca senza attribuirli a una barra precisa; dopo verifica
  si può decidere se vale la pena precisarlo o se così basta.
- Cmd+Enter: esiste un equivalente Ctrl+Enter su Windows/Linux?
  Il resto del manuale dice solo Cmd+Enter, qui idem.
- Paredit / editing strutturale: il piano lo prevedeva in 1.3, ma non
  ne ho trovato traccia verificabile. NON menzionato. Se esiste,
  aggiungere una sottosezione alla 1.3.
- Beep di feedback a fine eval (Architecture: opzionale, da settings).
  Menzionato di sfuggita nella 1.2.

I marker <!– level: ... –> seguono la convenzione del brief
dev-docs/brief-level-badges.md (rendering da implementare).
=========================================================================
-->

# 1. Per iniziare

<!-- level: base -->

Questo capitolo ti porta dal primo avvio al primo oggetto: dove si scrive il codice, cosa premere per eseguirlo, come si muove la tartaruga, e quel minimo di Clojure che serve per leggere il resto del manuale. Niente teoria che non venga usata subito. Se Ridley è già aperto davanti a te, meglio: ogni esempio è fatto per essere provato.

## 1.1 Ciao Ridley

Apri Ridley. Lo schermo è diviso in due metà: a sinistra un editor di testo, a destra una vista 3D vuota. Tutto quello che farai in Ridley passa da qui: scrivi un programma a sinistra, lo esegui, e l'oggetto descritto dal programma appare a destra.

Scrivi nell'editor questa riga:

<!-- example-source: hello-ridley -->
```clojure
(register hello (box 20))
```

e premi **Cmd+Enter**. Nel viewport appare un cubo di lato 20.

Quella riga dice tre cose. `(box 20)` costruisce un cubo. `register` gli dà un nome, `hello`, e lo mette in scena: è il comando che rende visibile una forma. Le parentesi sono il modo in cui Clojure, il linguaggio di Ridley, scrive ogni operazione: prima il nome dell'operazione, poi i suoi argomenti. Ci torniamo nella 1.5; per ora basta il pattern.

Adesso cambia `20` in `35` e premi di nuovo Cmd+Enter. Il cubo cresce. Questo è il ciclo fondamentale di Ridley, quello che ripeterai migliaia di volte: **modifica il codice, esegui, guarda**. Non c'è niente da trascinare e nessuna proprietà da impostare in un pannello: il programma è il modello, e cambiare il modello significa cambiare il programma.

Un dettaglio che vale la pena notare subito: quando premi Cmd+Enter la scena non viene "aggiornata", viene **ricostruita da zero** eseguendo tutto il programma dall'inizio. Sembra un dettaglio tecnico, ma è la garanzia più importante di Ridley: quello che vedi è sempre, esattamente, quello che il codice descrive. Niente stati nascosti, niente residui di operazioni precedenti.

## 1.2 L'interfaccia

I pezzi che userai da subito sono quattro.

**L'editor** (il pannello di sinistra) contiene il programma del modello. È un editor di codice vero: numeri di riga, colorazione della sintassi, e un aiuto attivo mentre scrivi: digitando il nome di una funzione Ridley te la riconosce e può mostrarti la firma e un collegamento alla sua scheda nella Reference del manuale. Quando un nome non ti torna, la risposta è a un click di mouse di distanza.

**Il viewport** (il pannello di destra) mostra la scena. Trascina con il mouse per orbitare attorno al modello, usa la rotella per avvicinarti e allontanarti, trascina con l'altro tasto per spostare la vista. La posizione della camera sopravvive alle esecuzioni: puoi inquadrare un dettaglio e continuare a modificare il codice senza perdere il punto di vista. Nella toolbar del viewport trovi alcuni interruttori che incontrerai nei prossimi capitoli, come "Lines" e "Stamps" per le tracce della tartaruga e le anteprime dei profili 2D.

**La REPL** (la riga di comando in basso a sinistra) esegue una singola espressione alla volta, senza ricostruire la scena. È lo strumento per fare domande al modello mentre lavori. La differenza fra REPL e Cmd+Enter è il tema della prossima sezione.

**Le barre di contorno.** Attorno ai due pannelli, distribuito fra la barra in alto e quella laterale, c'è il resto: questo manuale, i Workspace (i documenti su cui lavori, cap. 9), le librerie (codice riusabile, ancora cap. 9) e le impostazioni. Per ora non ti serve nulla di tutto questo: un solo Workspace, l'editor e il viewport bastano per i prossimi capitoli.

Due comodità da conoscere fin d'ora. Se esegui e senti un breve suono, è il feedback di fine esecuzione (si disattiva dalle impostazioni). E se in una scena affollata non ricordi quale codice ha generato un pezzo, un click con il tasto Opzione premuto (⌥, Alt su tastiere non Apple) sulla mesh nel viewport mostra nella barra di stato i collegamenti alle righe di codice che l'hanno prodotta.

## 1.3 Eseguire: Run e REPL

Ridley ha due modi di eseguire codice, ed è utile fissarli subito perché sono intenzionalmente diversi.

**Run** (Cmd+Enter nell'editor) esegue l'intero programma da zero: svuota la scena, riparte da uno stato pulito, e ricostruisce tutto. È il ciclo principale. Il vantaggio l'hai già visto: il risultato dipende solo dal testo del programma, sempre.

**REPL** (Invio nella riga di comando in basso a sinistra) valuta una singola espressione *nel contesto lasciato dall'ultimo Run*, senza svuotare niente. Serve a ispezionare e provare. Qualche esempio di quello che si fa in REPL, con funzioni che incontrerai nei prossimi capitoli:

```clojure
(bounds :hello)        ; quanto è grande il cubo che ho registrato?
(objects)              ; cosa c'è in scena?
(hide :hello)          ; nascondilo un momento
(show :hello)          ; rieccolo
```

La regola pratica: il programma vive nell'editor e si esegue con Run; la REPL è per le domande e gli esperimenti. Se in REPL trovi un valore che ti piace (una misura, una posizione), riportalo a mano nel programma: alla prossima esecuzione la REPL riparte dal nuovo stato, e solo ciò che sta nell'editor persiste.

## 1.4 La tartaruga

In `about-ridley` hai letto la filosofia; qui ci sono i comandi. La tartaruga è il cursore 3D di Ridley: ha una posizione, una direzione in cui guarda (l'*heading*) e un alto (l'*up*), e la vedi nel viewport. Tutto in Ridley nasce dove sta la tartaruga: il cubo della 1.1 è comparso centrato su di lei, all'origine.

I comandi di movimento sono tre, più i loro contrari:

`(f 20)` avanti di 20, nella direzione in cui la tartaruga guarda. `(b 20)` o `(f -20)` indietro.

`(rt 20)` spostati a destra di 20, senza girare. `(lt 20)` a sinistra.

`(u 20)` spostati in alto di 20. `(d 20)` in basso.

E due rotazioni:

`(th 90)` gira la testa di 90° sul piano orizzontale (a sinistra; angoli negativi girano a destra).

`(tv 30)` inclina la testa di 30° verso l'alto, sul piano verticale.

Mentre si muove, la tartaruga lascia una traccia: una linea di costruzione nel viewport (la penna è abbassata per default; si alza con `(pen :off)`, e l'interruttore "Lines" della toolbar nasconde tutte le tracce). Il programma più classico della geometria della tartaruga è un quadrato:

<!-- example-source: turtle-square -->
```clojure
(f 20) (th 90)
(f 20) (th 90)
(f 20) (th 90)
(f 20) (th 90)
```

Avanti, gira a sinistra, quattro volte. Nota che non c'è nessuna coordinata: il quadrato viene disegnato *dove sta la tartaruga, orientato come la tartaruga*. Prova ad aggiungere `(tv 30)` come prima riga ed esegui di nuovo: lo stesso identico codice disegna il quadrato su un piano inclinato. È questa l'idea che regge tutto Ridley: i comandi sono relativi alla tartaruga, non al mondo, e qualsiasi cosa tu costruisca si può riposizionare e riorientare semplicemente portando prima la tartaruga da un'altra parte.

Le linee sono tracce, non oggetti: servono a vedere percorsi e costruzioni, non finiscono nei file esportati. Gli oggetti veri li creano le primitive, e anche loro nascono sulla tartaruga:

<!-- example-source: turtle-snowman -->
```clojure
(register body (sphere 12))
(u 16)
(register head (sphere 8))
(u 11)
(register hat (box 9 5 9))
```

Una sfera all'origine, la tartaruga sale di 16, una sfera più piccola, sale ancora, un cubetto: un pupazzo di neve. La tartaruga è il sistema di posizionamento: muovila, costruisci, muovila ancora. Nei prossimi capitoli incontrerai modi più potenti di posizionare le cose (`attach`, i percorsi registrati, i marcatori), ma sono tutti elaborazioni di questo stesso gesto.

Esiste una terza rotazione, `tr`, che fa ruotare la tartaruga su se stessa come un aereo che rolla. Per i primi capitoli non ti servirà: la incontrerai quando i percorsi diventeranno tridimensionali.

## 1.5 Quel tanto di Clojure che serve

Ridley si programma in Clojure. Il cap. 16 è il riferimento per chi vuole capirlo davvero; qui c'è il minimo indispensabile per leggere i prossimi capitoli senza inciampare.

**Tutto è `(operazione argomenti...)`**. La sintassi di Clojure è una sola: parentesi aperta, nome dell'operazione, argomenti separati da spazi, parentesi chiusa. `(box 20)`, `(f 20)`, `(th 90)`. Anche l'aritmetica: `(+ 2 3)` fa 5, `(* 10 3)` fa 30. Le espressioni si annidano: `(box (* 10 3))` è un cubo di lato 30. Sembra strano per un giorno, poi diventa invisibile.

**`def` dà un nome a un valore.**

```clojure
(def size 20)
(register cube (box size))
```

Da qui in poi `size` vale 20, e ogni uso di `size` nel programma si aggiorna cambiando un numero solo. È il primo passo verso i modelli parametrici, e il cap. 2 ci costruisce sopra.

**`defn` dà un nome a una ricetta.** Dove `def` nomina un valore, `defn` nomina una funzione con dei parametri:

```clojure
(defn tower [width height]
  (box width height width))

(register small-tower (tower 10 30))
```

`tower` è una funzione tua, e si usa esattamente come `box`: in Ridley non c'è differenza fra i comandi predefiniti e quelli che definisci tu.

**`dotimes` ripete.** Il quadrato della sezione precedente, scritto da qualcuno che conosce un ciclo:

<!-- example-source: clojure-square-dotimes -->
```clojure
(dotimes [_ 4]
  (f 20) (th 90))
```

"Quattro volte: avanti e gira." Il trattino basso è il nome del contatore, che qui non ci serve. Con un contatore vero, `(dotimes [i 6] ...)`, il valore di `i` cambia a ogni giro: lo userai per generare serie di oggetti.

**I commenti iniziano con `;`**. Tutto ciò che segue un punto e virgola, fino a fine riga, viene ignorato. Li trovi in tutti gli esempi del manuale.

C'è molto altro (`let`, `if`, `for`, le funzioni anonime), e prima o poi ti servirà: quando succede, il cap. 16 è il posto dove andare. Ma con `def`, `defn` e `dotimes` puoi leggere e capire tutto il cap. 2, ed è lì che si va adesso: a costruire oggetti veri.
