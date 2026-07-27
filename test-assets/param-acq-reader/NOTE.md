# Sessione lettore SD — gate finale del v1 (end-to-end da utente)

Scopo: il collaudo completo del canale sul pezzo che ha aperto la storia
(brief-param-acq-v1, verifica end-to-end): registrazione PnP con proxy box →
ricalco del bezel su vista in posa con edit-path-2d normale → pezzo nativo →
confronto mesh-board con la scansione KIRI dello stesso lettore. Vincenzo
opera da utente puro; le frizioni si annotano, non si debuggano in linea.

## Il pezzo

- Lettore di schede SD (l'oggetto delle sessioni di luglio), su piatto con
  pongo. Superfici opacizzate dove serve: ______ (nastro sì/no, dove)

## Quote di calibro (ingombro complessivo, per il proxy box)

- X: _15.3_____ mm (escursione 0.1______)
- Y: __42.1____ mm (escursione __0.1____)
- Z: __80.05____ mm (escursione _0.1_____)

## Setup

- Data: 2026-08-__
- iPhone 15 Pro Max, 2x — verificato da EXIF: 48 mm-eq su tutte e 10 le
  foto, inclusa quella dall'alto. AE/AF lock: sì/no
- Distanza ~250 mm; piatto ⌀130 mm; convenzione θ: letture della scala,
  mod 360, come le sessioni precedenti
- **Foto fuori anello**: la vista dall'alto ha θ = `libera` (il parser lo
  accetta: esclusa dal fit `f` e dal seed, si registra solo via `p`/PnP)

## Foto

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8917.jpeg | 0 | |
| IMG_8918.jpeg | 30| |
| IMG_8919.jpeg | 60| |
| IMG_8920.jpeg | 90| |
| IMG_8921.jpeg | 120| |
| IMG_8922.jpeg | 180| |
| IMG_8923.jpeg | 210| |
| IMG_8924.jpeg | 270| |
| IMG_8925.jpeg | 310| |
| IMG_8926.jpeg | libera| |

## Per Code

- `--init-session test-assets/param-acq-reader` (legge questo NOTE).
- **Guard necessario**: le foto senza θ sono fuori-anello — il fit
  congiunto `f` deve ignorarle (tengono la posa PnP libera), MAI inglobarle
  nel modello giradischi. Se il guard non c'è ancora, va aggiunto prima
  del fit.
- Esito atteso della sessione: registrazione (hold-out a verbale), bezel
  ricalcato come `(path-2d …)` nel sorgente utente, fedeltà mesh-board
  contro la scansione KIRI. Le frizioni annotate da Vincenzo vanno
  raccolte a fine sessione: sono il collaudo del v1 e la scaletta di P5.
