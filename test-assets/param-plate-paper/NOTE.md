# Sessione piatto DI CARTA — collaudo definizione marker (variante carta)

Scopo: verificare che i marker su CARTA (stampati a dimensione effettiva,
incollati sul piatto liscio) diano dischetti NETTI → centroidi precisi → RMS del
PnP più basso e stabile anche sulle viste oblique, rispetto alla stampa 3D
bicolore (dischetti sfumati, RMS 1.8–8.8px su `param-plate-one`). Registrazione
PER-FOTO via PnP sui mark del piatto di carta (`piatto-carta`).

## Il pezzo

- Lettore di schede SD (lo stesso delle sessioni precedenti), al CENTRO del
  piatto di CARTA (⌀130, corona di 12 dischetti + zero-indice, su carta
  incollata su un piatto liscio).
- La corona deve restare visibile tutt'intorno al pezzo in ogni foto.

## Scala del foglio (barre di calibrazione)

- Barra X (orizzontale): **100.0 mm** misurati (nominale 100.0) → sx = 1.000
- Barra Y (verticale): **100.0 mm** misurati (nominale 100.0) → sy = 1.000
- Nessuna correzione di scala misurabile: nell'esempio `MEASURED-X` =
  `MEASURED-Y` = 100.0 → `piatto-carta` usa le posizioni nominali.

## Quote di calibro del PEZZO (non del piatto)

Nella registrazione col piatto NON entrano (la posa camera viene dai mark, non
dal fit giradischi). Sono le stesse del lettore.

- X: 15.3 mm
- Y: 42.1 mm
- Z: 80.05 mm

## Setup

- Data: 2026-07-27
- iPhone 15 Pro Max, 2x (focale letta da EXIF: 48 mm-eq, verificata; 4032×3024 = 4:3)
- Distanza ~250 mm; giradischi

## Foto

θ = `libera` per tutte: la registrazione è PER-FOTO via PnP sui mark del piatto,
quindi l'angolo del giradischi non serve e non c'è fit congiunto (`f`) da
alimentare. La colonna resta per far riconoscere la riga al parser.

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8938.jpeg | libera | |
| IMG_8939.jpeg | libera | |
| IMG_8940.jpeg | libera | |
| IMG_8941.jpeg | libera | |
| IMG_8942.jpeg | libera | |
| IMG_8943.jpeg | libera | |
| IMG_8944.jpeg | libera | |
| IMG_8945.jpeg | libera | |
| IMG_8946.jpeg | libera | |
| IMG_8947.jpeg | libera | |

## Per Code

- La sessione si auto-inizializza in-app da questo NOTE + le foto (nessun
  `--init-session` a mano).
- Registrazione: valuta `(load-file "examples/param-acq-plate.clj")` e poi
  `(edit-acquire "test-assets/param-plate-paper/" {:proxy piatto-carta})` nella
  stessa esecuzione.
- Esito atteso: dischetti NETTI in foto → RMS del PnP sotto i ~2–3px anche sulle
  viste oblique (contro i 1.8–8.8px del piatto 3D sfumato). Le frizioni annotate
  a fine sessione sono il collaudo della variante carta.
