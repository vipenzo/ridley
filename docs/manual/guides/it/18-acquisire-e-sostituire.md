<!--
Convenzioni accertate per il capitolo 18 (2026-07-16):
- Import: due canali documentati — procedura di libreria (genera decode-mesh con
  dati inlined + mesh-translate) e import-stl da path (desktop only, :recenter).
  decode-mesh presentata come quasi-internal del codice generato (Vincenzo, 2026-07-16).
- Il flusso documentato è quello reale del working tree: edit-mesh-split
  (senza gesto separa), emissione nuda con spec ad albero, split-tree/split-parts,
  mesh-components come DSL manuale, mesh-board con viste inset (brief-mesh-board-views).
- Import mesh: STL e OBJ (import-obj / import-mesh, Fase 1 del canale scanner,
  2026-07-18). Dell'OBJ si legge solo la geometria; .mtl mancante non è errore.
- Il resto del canale scanner (PLY, riparazione buchi, calibrazione scala) è previsto
  dal design (mesh-board-design.md, canali e profili di qualità) ma NON implementato:
  la guida lo dice in una riga e non oltre.
- Drag/zoom delle finestrelle (iterazione 2 del brief viste) non documentati finché
  non stabili.
- Il numero 18 era riservato a "Estendere Ridley" (mai scritto): slittato a 19,
  aggiornare manual-redesign-plan quando si registra il capitolo.
- Codice esempi in inglese, prosa in italiano. No em-dash.
-->

# 18. Acquisire e sostituire

<!-- level: advanced -->

## 18.1 La falsariga

C'è un modo di lavorare che i capitoli precedenti non coprono: partire da un oggetto che esiste già. Un pezzo di ricambio da riprodurre, un STL scaricato da modificare, una scansione da cui ricavare un modello pulito. In tutti questi casi la mesh importata non è il prodotto finale: è la *falsariga* su cui costruire un oggetto Ridley nativo, parametrico e modificabile, che alla fine la sostituirà del tutto.

Il flusso ha quattro momenti: importare la mesh, smontarla nei suoi pezzi logici, costruire i sostituti confrontandoli col riferimento, e infine far cadere l'impalcatura. Questo capitolo li percorre in ordine.

Oggi il canale d'ingresso è la mesh via STL o OBJ; il resto dell'acquisizione da scanner (PLY, riparazione dei buchi, calibrazione di scala) è previsto dal design ma non ancora implementato.

## 18.2 Importare

Le strade per portare un STL dentro Ridley sono due. La più immediata è la procedura di **import della libreria** (cap. 9): scegli il file e Ridley genera una entry di libreria con i dati della geometria incorporati. Il sorgente generato ha questa forma:

```clojure
(def mount
  (-> (decode-mesh "...STL data...")
      (mesh-translate [0.039 -0.001 -1.842])))
```

`decode-mesh` è la funzione che ricostruisce la mesh dai dati incorporati: la incontri nel codice generato, raramente la scrivi a mano. La `mesh-translate` emessa insieme rende visibile (e modificabile) l'eventuale ricentraggio.

L'alternativa scriptabile è `import-stl`, che legge il file da disco (solo desktop):

```clojure
(def mount (import-stl "/path/to/mount.stl" :recenter true))
```

A differenza della entry di libreria, la geometria non è incorporata nel sorgente: il programma riferisce solo il path. Utile quando l'STL non è ridistribuibile, o quando è grande e non vuoi gonfiare il sorgente.

Il formato non è per forza STL. `import-obj` legge un Wavefront OBJ — il formato che le app di fotogrammetria esportano più spesso — e `import-mesh` sceglie da sé il parser dall'estensione, così c'è un nome solo da ricordare:

```clojure
(def scan (import-mesh "/path/to/scan.obj" :recenter true))
```

Dell'OBJ si legge solo la geometria: coordinate texture, normali e materiali (`.mtl`) vengono ignorati. Questo è deliberato, ed è il motivo per cui **un OBJ senza il suo `.mtl` a fianco importa senza errori** — i materiali non vengono mai consultati. Le facce con più di tre vertici vengono triangolate a ventaglio.

In entrambi i casi, da qui `mount` è una mesh come le altre: puoi misurarla (cap. 10), farne sezioni (cap. 7.5), diagnosticarla (cap. 7.7). Ma è un blocco monolitico: migliaia di triangoli senza struttura. Il primo passo è dargliela.

## 18.3 Smontare: edit-mesh-split

Potresti scrivere a mano una `mesh-split` con i suoi piani (cap. 7.8), ma trovare gli offset giusti per tentativi è tedioso. `edit-mesh-split` è l'editor interattivo che fa questo lavoro:

```clojure
(edit-mesh-split mount)
```

Si apre una sessione sul viewport. Il piano di taglio è la posa della tartaruga: lo muovi con le frecce o trascinando il gizmo, e le due metà live si colorano per farti vedere cosa staccheresti. Sotto, una strip mostra il profilo di sezione A(t): l'area di materiale attraversata dal piano lungo la corsa. Le barrette sulla strip sono i punti di taglio naturali: azzurre per i gradini (discontinuità dell'area, calcolate esattamente dalle facce della mesh), arancioni per i colli (strozzature). Un click sulla strip salta al candidato più vicino.

I gesti essenziali: Enter accetta la metà `:behind` come pezzo definitivo e prosegue sul resto; `a` dichiara finito il pezzo corrente così com'è (anche se concavo: finito è una decisione, non un fatto geometrico); Backspace annulla l'ultimo gesto; `n` passa al prossimo pezzo aperto. Quando ogni pezzo è finito, Enter committa.

Alla chiusura l'editor scrive nel sorgente la chiamata corrispondente:

```clojure
(def AA (mesh-split mount
          (path (tv 90) (f -1.62) (mark :cut-1))
          [:cut-1]))
```

È una chiamata normale, senza scaffolding: puoi ritoccarla a mano, e puoi riaprirla nell'editor riaggiungendo `edit-` davanti a `mesh-split`. Questo round-trip è il contratto dello strumento: il sorgente resta l'unica verità.

Se un pezzo contiene parti sconnesse (una U tagliata alla base lascia due rebbi), l'editor non le separa: accettalo così com'è e separa dopo, nel codice, con `mesh-components`.

## 18.4 Dai tagli ai pezzi

Il valore di `AA` è un composito annidato. `split-tree` lo trasforma nella mappa dei pezzi, e da lì si registra:

```clojure
(def AAs (split-tree AA))

(register base  (AAs :piece-1))
(register forks (AAs :piece-2))

;; i due rebbi sono parti sconnesse dello stesso pezzo:
(let [[left right] (mesh-components (AAs :piece-2))]
  (register fork-l left)
  (register fork-r right))
```

A questo punto l'oggetto ha una struttura: nomi, pezzi, confini. È ancora tutta geometria importata, ma è indirizzabile.

## 18.5 Costruire e confrontare: mesh-board

Ora il lavoro vero: ricostruire ogni pezzo come oggetto Ridley nativo. Il sostituto va costruito e posizionato sopra il pezzo che rimpiazza, e qui serve un feedback che dica dove e quanto i due differiscono. È il mestiere di `mesh-board`:

```clojure
(def SOST (attach (extrude (polygon 8 12) (f 6)) (tv 90) (f -1)))

(mesh-board (AAs :piece-2) SOST)
```

La forma a due argomenti è il confronto: il primo è il riferimento (il pezzo da rimpiazzare), il secondo il candidato. A ogni valutazione compaiono le viste di confronto in finestrelle ai bordi del viewport, e nel pannello di output viene stampata la fedeltà, la percentuale di coincidenza volumetrica fra i due:

- `intersection`: la parte comune;
- `missing`: il materiale del riferimento che il candidato non copre ancora;
- `excess`: dove il candidato deborda.

Ogni finestrella mostra il risultato solido con l'etichetta e il volume, e segue l'orientamento della vista principale: ruoti l'oggetto, ruotano i confronti. Con `{:views [:excess]}` limiti le viste a quelle che ti servono; con `{:ghost true}` aggiungi i wireframe sovrapposti in place, utili per il posizionamento grossolano iniziale; con `:label` disambigui più confronti attivi nello stesso programma.

Il ciclo di messa a punto è tutto qui: ritocchi il sostituto, rivaluti, guardi `missing` ed `excess` svuotarsi e la fedeltà salire. `mesh-board` restituisce il primo argomento invariato e non lascia traccia nella scena esportata: è una direttiva di visualizzazione, non una trasformazione (la sua forma a un argomento, `(mesh-board AAs)`, mostra invece i pezzi come scaffold wireframe in place).

## 18.6 La caduta dell'impalcatura

Quando la fedeltà di un pezzo ti soddisfa, la sostituzione è un cambio di riga:

```clojure
;; prima:
(register fork-l (first (mesh-components (AAs :piece-2))))
;; dopo:
(register fork-l SOST)
```

La riga di `mesh-board` a quel punto si toglie: ha esaurito il suo compito. Pezzo dopo pezzo, i riferimenti importati escono dal programma; quando l'ultimo pezzo è nativo, cadono anche `mesh-split` e `split-tree`, e infine la `decode-mesh` stessa. Quello che resta è un oggetto Ridley puro, parametrico, che dell'originale conserva solo le misure. La falsariga si butta, il disegno resta.
