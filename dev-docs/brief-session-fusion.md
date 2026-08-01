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

## Il principio (la fusione è UNA posa per sessione)

Ogni sessione resta autonoma: cartella, corona, camere ancorate al piatto.
Due sessioni dello stesso oggetto differiscono per UNA rototraslazione rigida
(l'oggetto è rigido; la scala è mm in entrambe, data dalla corona).

La rappresentazione esiste già: la **`:pose` della seconda acquire**. Scritta
lì, trasporta rigidamente l'intero impianto della sessione (piatto, camere,
mark) nel mondo della prima; il piatto-2 resta "appeso" dov'è finito (è
impalcatura). La macchina di trasporto rigido camere-dietro-posa esiste
(`transport-registered-cameras!`, stabilità della registrazione).

```clojure
(def A (acquire "scans/reader-in-piedi/" {:proxy (registration-plate) :pose {...}}))
(def B (acquire "scans/reader-capovolto/" {:proxy (registration-plate)
                                           :pose T-B->A}))   ; la fusione è QUI
```

## Il gesto: corrispondenze dichiarate tra sessioni

Stessa filosofia che ha ucciso Klein: l'identità la dichiara l'utente.
≥3 punti fisici riconoscibili, cliccati e triangolati in ENTRAMBE le sessioni
(la macchina dei mark-piano c'è già: triangolate.cljs, origine per click).

- **Convenzione candidata**: stesso nome = stesso punto fisico (mark
  `:spigolo-a` presente in A e in B → entra nel fit). Elegante, zero UI; il
  rischio è la collisione accidentale di nomi → in alternativa un pairing
  esplicito (es. un blocco `:align {:a :spigolo-a :b :spigolo-a}` o un
  prefisso riservato). Da decidere con Vincenzo.
- Il fit emette la `:pose` (write-back chirurgico, come i mark) + il residuo
  in mm per punto.

**Nota implementativa (stile casa, niente SVD)**: Kabsch classico vuole una
SVD; con 3 punti la R si costruisce per composizione di due terne ortonormali
(base del triangolo in A e in B); con >3 quella è il SEME e rifinisce l'LM
(lm/solve) sui 6 DOF minimizzando le distanze punto-punto. Stesso pattern
seme-chiuso + LM di tutto il canale.

**Guardie**: punti non collineari e ben distribuiti (base larga); residuo per
punto riportato (stile :per-point); leave-one-out per nominare un click
sbagliato (già scritto per la triangolazione, si riusa).

## Cosa deve crescere: multi-acquire sul palcoscenico

È la "decisione D" lasciata aperta in `HANDOVER-p4b-stage-toolbar.md` (oggi lo
stage tiene UNA acquire, l'ultima). Servono: frustum di più acquire nella
stessa scena (distinguibili, es. per colore), navigazione foto attraverso le
sessioni (ordine θ per sessione? unificato?), toolbar consapevole. Il ricalco
non cambia: edit-path-2d resta identico, semplicemente le foto disponibili
diventano quelle di TUTTE le sessioni fuse.

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
2. Gate vero: lettore SD in piedi + capovolto; 3 corrispondenze; fusione;
   ricalco di un contorno su foto della sessione A verificato dalle foto della
   sessione B; una estrusione che combacia in TUTTE le viste delle due
   sessioni.

## Fuori perimetro (dichiarato)

- Allineamento automatico senza click (feature matching tra sessioni): stessa
  riserva di texture del gradino 3 dei mark-piano. Dopo, semmai.
- Più di 2 sessioni: il principio scala (una :pose ciascuna, tutte verso la
  prima), ma il collaudo parte da 2.
- Bundle adjustment congiunto tra sessioni (raffinare le camere di B coi
  click di A): non serve per il v1 della fusione.
