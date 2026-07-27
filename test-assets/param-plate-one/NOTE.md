# Sessione piatto di registrazione — collaudo fetta B

Scopo: la prima registrazione fatta sui MARK del piatto (`p` sui dischetti
della corona) invece che sugli spigoli del pezzo. Il pezzo è lo stesso
lettore SD di `param-acq-reader` (raccordi ~1.7 mm), che al gate finale del v1
teneva l'RMS bloccato a ~14 px proprio perché "l'angolo" non è un punto. Il
piatto porta landmark netti a coordinate note: il numero che decide il
collaudo è l'RMS per foto — deve crollare ben sotto i 14 px.

## Il pezzo

- Lettore di schede SD (lo stesso di `param-acq-reader`), appoggiato AL CENTRO
  del piatto di registrazione stampato (⌀130, corona di 12 dischetti + zero).
- La corona di dischetti deve restare visibile tutt'intorno al pezzo in ogni
  foto (il pezzo non deve coprirla).

## Quote di calibro del PEZZO (non del piatto)

Note per riferimento: nella registrazione col piatto NON entrano (la posa
camera viene dai mark, non dal fit giradischi). Sono le stesse del lettore.

- X: 15.3 mm
- Y: 42.1 mm
- Z: 80.05 mm

## Setup

- Data: 2026-07-27
- iPhone 15 Pro Max, 2x (focale letta da EXIF, 4032×3024 = 4:3 come le
  sessioni precedenti)
- Distanza ~250 mm; giradischi

## Foto

θ = `libera` per tutte: la registrazione è PER-FOTO via PnP sui mark del
piatto, quindi l'angolo del giradischi non serve e non c'è fit congiunto (`f`)
da alimentare. La colonna resta per far riconoscere la riga al parser.

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8927.jpeg | libera | |
| IMG_8928.jpeg | libera | |
| IMG_8929.jpeg | libera | |
| IMG_8930.jpeg | libera | |
| IMG_8931.jpeg | libera | |
| IMG_8932.jpeg | libera | |
| IMG_8933.jpeg | libera | |
| IMG_8934.jpeg | libera | |
| IMG_8935.jpeg | libera | |
| IMG_8936.jpeg | libera | |
| IMG_8937.jpeg | libera | |

## Per Code

- Proxy = il PIATTO, non un box: valuta `examples/param-acq-plate.clj` (che
  definisce `piatto` coi mark sotto `:anchors`) e apri con
  `(edit-acquire "test-assets/param-plate-one/" {:proxy piatto})`.
- `session.json` si autocostruisce da questo NOTE all'apertura (fetta
  auto-init); in fallback: `node out/paq.js --init-session test-assets/param-plate-one`.
- Registrazione: su ogni foto `p` → clicca ≥6 mark evidenziati → `r`. NON usare
  `s`/`f` (edge-snap e fit giradischi sono per il box, non per il piatto).
- Esito atteso: RMS per foto ben sotto i 14 px del lettore. Le frizioni si
  raccolgono a fine sessione — è il collaudo live di fetta B.
