# cage-presa — copie di lavoro per il gate del rilevatore della gabbia

Copie di due foto della sessione **Presa** (`~/Pictures/RidleyScan/Presa`),
gabbia ⌀176 su fondo nero, iPhone a 48mm equivalenti, EXIF Orientation 6
(4032×3024 sul sensore, **3024×4032 in coordinate di visualizzazione** — è il
sistema in cui stanno i pick, le predizioni e le riproiezioni).

- `IMG_9014.jpeg` — foto 0 della sessione. È quella con la verità nota, scritta
  come fixture in `test/ridley/photogrammetry/blob_detect_test.cljs`.
- `IMG_9015.jpeg` — foto 1. Serve al controllo di PRECISIONE: qui il fondo di
  pelle è illuminato e la sua **grana** è visibile, ed è la grana che produceva
  cinquanta falsi candidati prima che il test di contrasto la escludesse.

Le jpeg NON sono tracciate (`.gitignore`: `test-assets/*/*.jpeg`). Il test reale
è protetto e viene SALTATO quando mancano; la verità che conta è nel file di
test, non qui.

## Perché le copie, e non la cartella di Vincenzo

Il 22 agosto un collaudo dal vivo sulla sessione vera ha distrutto quattro punti
di ricalco suoi. Si prova sulle copie.

## La verità su IMG_9014, e come è stata letta

Ogni punto è stato guardato sulla foto a 4× prima di essere creduto. Tre dei
quattordici pick di `acquire-state.json` non hanno superato quella lettura —
sono tutti e tre `proposed?`, cioè predizioni accettate, non click:

- `28` (1390.0, 2523.2) e `30` (931.3, 2474.6) stanno sul fondo nero, lontani da
  qualsiasi mark;
- `7` (1949.7, 2776.5) sta su banda bianca vuota: il mark che nomina è a
  (2071, 2853), **143px più in là**.

Gli altri undici sono centrati sui dischetti entro un paio di pixel.

## Il diametro, misurato

Vincenzo, 2026-08-24, **metro a nastro**: «leggermente meno di 176, direi 175.5».
Cioè lo 0.3% — dentro il rumore di un metro a nastro, e comunque **la scala non
si osserva dalla registrazione**: un diametro sbagliato viene assorbito dalla
distanza della camera, i residui restano ottimi e sono le MISURE del pezzo a
uscire scalate. Il modo di dichiararla, quando la si misura col calibro, è
`(acquire-cage/measured 176 175.5)` come `:proxy` della sessione.

Con un metro a nastro l'incertezza (±1mm) è più grande della correzione (0.5mm),
quindi applicarla non è meglio che lasciare 176.

## L'anello di mezzo non è perfettamente piano

Vincenzo, 2026-08-24, guardando il pezzo: «il ring Y non è perfettamente planare
(di poco comunque)». Lo si tenga presente leggendo i residui: a questa
inquadratura l'anello di mezzo sta a ~16 px/mm, quindi i **17-23px** che restano
dopo la rilettura corretta valgono **circa 1.1-1.4mm** sul pezzo — la taglia
giusta per una svergolatura «di poco», e qualcosa che nessuna `:phase` (che è una
rotazione rigida) può correggere.
