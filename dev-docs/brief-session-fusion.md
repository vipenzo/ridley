# Brief: fusione di sessioni di scatto (multi-sessione)

Aperto 2026-08-01 (Vincenzo + Claude-docs). Stato: PROPOSTA di design, non
assegnata.

> **VINCOLO D'ORDINE CADUTO (verificato 2026-08-02, Code).** Il prerequisito in
> coda ("chiudere il Seguito 3 di `brief-plane-marks.md` scegliendo la regola dei
> frame con la fusione in mente") è soddisfatto: il Seguito 3 è chiuso (`6bbcd90`
> + i giri successivi), e la regola che ne è uscita è già compatibile con la
> fusione, anche se non fu scelta pensando a lei. Misurato sul codice: i `:marks`
> emessi sono pose MONDO nel frame registrato (`acquire_stage.cljs`, `world-frame`),
> `acquire` li restituisce verbatim, il proxy è già posato e `:faces` è CALCOLATO
> dalla posa (`edit_acquire.cljs`, `face-poses`) — cioè tutto il valore di una
> `acquire` vive in UN frame solo, quindi UNA T rigida lo trasporta tutto. Il
> timore "sposta le camere ma non i mark" apparteneva al primo abbozzo col
> write-back della `:pose`; `acquire-union` applica la sua T al VALORE e non
> tocca la posa. Il vincolo si riformula: *`acquire-union` deve trasportare con
> la stessa T proxy, `:marks`, `:shapes` e le camere registrate dello stage* (la
> macchina esiste: `transport-registered-cameras!`, `attachment/transform-pose-rigid`),
> e non deve ri-applicare la `:pose` a ciò che è già in mondo (doppio trasporto).
>
> **Il momento giusto NON è però ora** (giudizio 2026-08-02): la registrazione a
> sessione singola sta ancora restituendo difetti di correttezza sotto uso vero
> (`55c7159`, `e401dad`), e la fusione SOMMA l'errore di due sessioni — fondere
> adesso renderebbe illeggibili i residui. Segnale d'ingaggio dichiarato: un
> oggetto vero portato dall'inizio alla fine senza correzioni al programma.
> Fetta anticipabile che non si spreca: il **gate di floor** (sotto), fattibile
> con le foto già in casa spezzando una sessione in due mezze sessioni
> registrate indipendentemente — misura il termine che la fusione aggiunge (la
> triangolazione dei mark d'aggancio) senza scrivere una riga di UI.

> **FETTA A COSTRUITA (2026-08-03), gate con foto vere DA FARE.** Vincenzo:
> «passerei alla acquire-union: la necessità che veramente si sente sono foto da
> angolazioni più sparse». Fatto: `ridley.photogrammetry.fuse` (puro — seme in
> forma chiusa per composizione di terne + raffinamento LM sui 6 DOF, residuo
> per mark in mm, rifiuto onesto sui casi indeterminati) e il binding SCI
> `(acquire-union a b …)`. NON fatto: il palcoscenico multi-sessione (le foto di
> B non sono ancora navigabili) — vedi "Cosa deve crescere". Per ora lo stage
> mostra la PRIMA sessione con TUTTI i mark, trasportati compresi: un mark
> misurato in B che cade sull'oggetto nelle foto di A è già la verifica visiva
> della fusione, a costo zero.
>
> Verificato: test sintetici (moto noto → recuperato; collineari no; base < 5 mm
> no; il gemello sbagliato viene NOMINATO invece che mediato; l'identità resta
> identità) più prove live in SCI. Suite 848 test, 0 fallimenti.
>
> **SECONDO GIRO (2026-08-05), prima del gate — due correzioni di sostanza dette
> da Vincenzo mentre preparava le sessioni.**
>
> (1) *«Conta solo il piano o anche la posizione del mark nel piano? Spero solo
> il piano: quello è riscontrabile facilmente nelle diverse sessioni, un punto
> specifico no.»* Aveva ragione e la geometria è d'accordo: l'origine di un
> mark-piano è il centroide di dove si è cliccato, non una feature dell'oggetto.
> Ora un mark è creduto come PIANO — residuo punto-piano (una componente) più
> l'allineamento delle normali a piena forza — e scivolare dentro il piano non
> costa niente. Chi ha un'origine che è davvero un punto fisico lo dichiara con
> `:point? true`. Conseguenza sui requisiti: **tre piani con normali
> indipendenti**, non più due mark qualsiasi; due piani lasciano libera la
> traslazione lungo la loro intersezione. La guardia non è una casistica ma il
> RANGO del sistema (`lm/covariance-spectrum` sullo jacobiano alla soluzione),
> per cui "due piani + un punto vero" funziona senza che nessuno l'abbia
> enumerato.
>
> (2) *«Se li svincoli viene fuori un problema di nomi: due `:piano-1` che
> coincidono come piano ma hanno posizioni diverse — forse servono tutti e due.
> Passare a acquire-union la lista dei mark da considerare uguali.»* Adottata:
> forma dichiarata `(acquire-union [[:A a] [:B b]] [[:A/piano-1 :B/piano-1] …])`.
> I nomi non devono più coincidere (i `:piano-N` automatici collidono per
> caso), ogni mark resta distinguibile come `:label/nome`, entrambi
> sopravvivono. La forma corta resta per il caso in cui i nomi coincidono
> davvero. Verificato live: origini fatte scivolare di 12-15 mm DENTRO i piani →
> rms 4.1e-15 mm, e il mark di B conserva la propria origine trasportata.

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

## Vincolo d'ordine (SUPERATO — vedi il riquadro in testa)

*Testo originale, conservato per il perché.* La fusione rende PORTANTE la
semantica dei frame dei mark (Seguito 3 di brief-plane-marks): perché tutto si
trasporti gratis, i `:marks` devono essere memorizzati nel frame DI SESSIONE e
posati alla restituzione (viaggiare con la `:pose`), come le camere. Se oggi i
mark sono baked nel mondo, fondere sposterebbe le camere ma non i mark. →
Chiudere la diagnosi del Seguito 3 SCEGLIENDO la regola dei frame con la fusione
in mente, PRIMA di costruire questo brief.

**Esito**: i mark SONO in mondo, e va bene lo stesso — perché anche camere e
proxy posato lo sono, e `acquire-union` trasporta il valore invece di riscrivere
la `:pose`. La condizione da preservare non è più "dove stanno i mark" ma
"tutto ciò che una `acquire` restituisce sta nello stesso frame": chi in futuro
esprimesse una di quelle chiavi relativamente alla `:pose` romperebbe la fusione.

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
   **Variante a costo zero (2026-08-02)**: non servono scatti nuovi — si spezza
   una sessione esistente in due cartelle di 5-6 foto e si registrano
   separatamente. Entrambe restano ancorate al piatto, quindi la T attesa è
   l'identità per costruzione e il residuo isola esattamente il termine che la
   fusione aggiunge: la triangolazione dei mark d'aggancio. Dichiararlo per
   quello che è (non misura il rimettere l'oggetto sul piatto in un'altra posa).
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
