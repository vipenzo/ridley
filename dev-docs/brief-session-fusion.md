# Brief: fusione di sessioni di scatto (multi-sessione)

Aperto 2026-08-01 (Vincenzo + Claude-docs). Stato: PROPOSTA di design, non
assegnata. Prerequisito d'ordine: chiudere prima il Seguito 3 di
`brief-plane-marks.md` (semantica dei frame dei mark) — vedi "Vincolo".

## Il bisogno

Il giradischi dà foto dettagliate ma su una FASCIA limitata di angoli:
mancano le viste dall'alto e dal basso. Il ricalco per riproiezione vive di
viste da angoli diversi (laterale + zenitale = profondità triangolata a
occhio): la fascia stretta è oggi il limite pratico principale.

Idea (Vincenzo): ripetere la sessione con l'oggetto GIRATO in pose diverse
(su un fianco, capovolto) e FONDERE le sessioni in un unico spazio di lavoro.

## Il principio (la fusione è UNA rototraslazione per sessione)

Ogni sessione resta autonoma: cartella, corona, camere ancorate al piatto.
Due sessioni dello stesso oggetto differiscono per UNA rototraslazione rigida
(l'oggetto è rigido; la scala è mm in entrambe, data dalla corona).

La rappresentazione è il VALORE fuso restituito da `acquire-union` (vedi "Il
gesto"): la trasformazione è ricalcolata a ogni eval dai mark omonimi, mai
scritta nel sorgente. (Il primo abbozzo di questo brief prevedeva il
write-back della `:pose` della seconda acquire; superato dalla proposta
`acquire-union` di Vincenzo — pura, senza numeri cotti che invecchiano.)
La macchina di trasporto rigido camere-dietro-posa esiste
(`transport-registered-cameras!`, stabilità della registrazione).

## Il gesto: `acquire-union`, la fusione come funzione pura (Vincenzo, 2026-08-01)

Niente UI di fusione. Le sessioni condividono mark OMONIMI (stesso nome =
stesso punto fisico, dichiarato dall'utente — la filosofia che ha ucciso
Klein), e la fusione è una forma nel sorgente:

```clojure
(let [a (acquire "scans/reader-in-piedi/" {...})
      b (acquire "scans/reader-capovolto/" {...})]
  (acquire-union a b))
```

- **Pura e ricalcolata a ogni eval**: `acquire-union` prende i mark omonimi
  delle due sessioni, risolve la rototraslazione e restituisce il valore fuso
  (mark e camere di b trasportati nel frame di a). NESSUN write-back della
  `:pose`: niente numeri cotti — migliori un mark, rilanci, la fusione si
  aggiorna. Prezzo dichiarato: i mark di aggancio devono RESTARE nel sorgente
  (sono l'ancora; cancellarli de-fonde).
- **Quanti mark servono**: i mark sono POSE, non punti. DUE mark-piano con
  origini ben separate + le loro normali determinano tutto (sovradeterminano).
  UNO solo no. Attenzione all'`:up`: NON è affidabile tra sessioni (viene
  proiettato dalla posa dell'oggetto, diversa per costruzione tra le
  sessioni) — il fit pesa origini e normali, ignora o quasi gli up. Con soli
  PUNTI ne servono tre non allineati.
- **Variadica come la famiglia union** (`mesh-union`, `shape-union`,
  `sdf-union`): `(acquire-union a b c)` → tutte allineate alla prima.
- **Restituzione del residuo**: precedente `mesh-board` (stampa la fedeltà
  nel pannello output) → `acquire-union` stampa il residuo per mark in mm.
  Sotto-determinata o degenere → nil onesto con messaggio, mai una posa
  spazzatura.
- **Politica dello stage risolta dal sorgente**: se in scena c'è una
  `acquire-union`, lo stage mostra QUELLA — l'insieme di lavoro lo dichiara
  il sorgente, non un click. Semplifica la "decisione D".
- **Da definire in implementazione**: la forma del valore fuso per i
  consumatori — `:marks` fusi in una mappa unica (gli omonimi di aggancio
  SONO lo stesso punto: collassano in uno, mediato); collisione di nomi tra
  mark NON di aggancio → errore onesto o prefisso di sessione, da decidere;
  `:faces` di quale proxy espone (la prima? entrambe con prefisso?).

**Nota implementativa (stile casa, niente SVD)**: Kabsch classico vuole una
SVD; qui la R si costruisce per composizione di terne ortonormali dalle pose
dei mark (origini + normali), e quella è il SEME per il raffinamento LM
(lm/solve) sui 6 DOF, minimizzando le distanze origine-origine con
l'allineamento delle normali pesato. Stesso pattern seme-chiuso + LM di tutto
il canale.

**Guardie**: origini ben separate (base larga); con DUE mark, normali non
parallele (due piani paralleli non fissano la rotazione attorno alla normale
— in quel caso serve un terzo punto, o l'up torna in gioco con cautela);
residuo per mark riportato (stile :per-point); leave-one-out riusato dalla
triangolazione.

## Cosa deve crescere: multi-acquire sul palcoscenico

È la "decisione D" lasciata aperta in `HANDOVER-p4b-stage-toolbar.md` (oggi lo
stage tiene UNA acquire, l'ultima). Servono: frustum di più acquire nella
stessa scena (distinguibili, es. per colore), navigazione foto attraverso le
sessioni (ordine θ per sessione? unificato?), toolbar consapevole. Il ricalco
non cambia: edit-path-2d resta identico, semplicemente le foto disponibili
diventano quelle di TUTTE le sessioni fuse.

**Visibilità delle impalcature (Vincenzo, 2026-08-01)**: dopo la fusione i
piatti delle sessioni secondarie restano "appesi" in pose strane e occludono
proprio la zona da ricalcare → visibilità regolabile PER SESSIONE e PER
COMPONENTE (piatto-proxy / frustum / dischetti dei mark, spegnibili
separatamente: i frustum di B servono a navigare anche col suo piatto
nascosto). Collocazione: è STATO DI VISTA, non programma → vive nella
toolbar (il selettore di sessione che il multi-acquire richiede comunque è il
posto naturale: un chip per sessione con l'occhietto), NON nella form
`acquire` nel sorgente. Default sensati che minimizzano i toggle: in posa,
solo l'impalcatura della sessione della foto corrente; in orbita libera,
piatti secondari spenti, frustum di tutte le sessioni accesi (per colore).

## Il premio

- Riproiezione live COMPLETA: ricalchi sulla vista laterale (sessione A) e
  controlli la profondità dalla vista dall'alto (sessione B).
- Il "mai visibile" si riduce alla faccia d'appoggio di ciascuna posa: con
  due pose ben scelte si copre l'oggetto intero.
- Lo stesso meccanismo fonde anche sessioni NON capovolte (es. un giro di
  dettaglio più ravvicinato dello stesso lato).

## Vincolo d'ordine (importante)

La fusione rende PORTANTE la semantica dei frame dei mark (Seguito 3 di
brief-plane-marks): perché tutto si trasporti gratis, i `:marks` devono
essere memorizzati nel frame DI SESSIONE e posati alla restituzione (viaggiare
con la `:pose`), come le camere. Se oggi i mark sono baked nel mondo, fondere
sposterebbe le camere ma non i mark. → Chiudere la diagnosi del Seguito 3
SCEGLIENDO la regola dei frame con la fusione in mente, PRIMA di costruire
questo brief.

## Budget d'errore (onestà)

I punti di aggancio sommano l'errore di triangolazione di ENTRAMBE le
sessioni; il frame fuso è buono quanto quei click. Con click a ~2px e base
larga l'ordine atteso è decimi di mm (dai numeri di triangulate_test), ma va
MISURATO: il gate deve includere un oggetto con feature note (o il piatto
stesso in due sessioni con l'oggetto fermo → T attesa = identità, il residuo
misura il floor dell'intero giro).

## Gate umano (giudice: Vincenzo)

1. Gate di floor: stessa scena fotografata in due sessioni SENZA muovere
   l'oggetto → fit su 3 mark omologhi → T ≈ identità; il delta è il floor.
2. Gate vero: lettore SD in piedi + capovolto; 2 mark-piano omonimi ben
   separati (o 3 punti); `(acquire-union a b)`; ricalco di un contorno su
   foto della sessione A verificato dalle foto della sessione B; una
   estrusione che combacia in TUTTE le viste delle due sessioni.

## Fuori perimetro (dichiarato)

- Allineamento automatico senza click (feature matching tra sessioni): stessa
  riserva di texture del gradino 3 dei mark-piano. Dopo, semmai.
- Più di 2 sessioni: il principio scala (una :pose ciascuna, tutte verso la
  prima), ma il collaudo parte da 2.
- Bundle adjustment congiunto tra sessioni (raffinare le camere di B coi
  click di A): non serve per il v1 della fusione.
