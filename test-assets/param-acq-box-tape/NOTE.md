# Sessione blocco NASTRATO — test discriminante materiale vs intrinseche

Scopo: il Gate 1 sul blocco nudo mostra un bias costante di ~0.5 mm/lato verso
l'interno (firma da silhouette spostata — sospetto subsurface scattering del
PLA traslucido). Qui il blocco è coperto di nastro carta opaco:

- bias per-lato che crolla (→ ~0 / leggermente positivo) → **materiale**:
  si documenta il vincolo "superfici opache" e il metodo è certificato.
- bias che resta ~0.5 mm/lato → **intrinseche**: parte la stima
  principal point + k1 (Code, già in coda).

## Il pezzo

- Stesso blocco della sessione param-acq-box, con **nastro carta opaco**:
  un solo strato su 4 facce laterali + faccia superiore, tagliato a filo
  degli spigoli, niente sovrapposizioni sui bordi. Faccia inferiore nuda.

## Quote di calibro COL NASTRO (la verità di riferimento è il blocco nastrato)

- X: 60.2______ mm (escursione  __0.1____)
- Y: 20.2______ mm (escursione ___0.1___)
- Z: 40.1______ mm (escursione ___0.1___)

## Setup (identico a param-acq-box)

- Data: 2026-07-__
- iPhone 15 Pro Max, 2x (crop sensore), AE/AF lock: sì/no
- Distanza ~250 mm; piatto ⌀130 mm, stessa convenzione θ

## Foto (bastano le 4 di bootstrap)

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8908.jpeg | 0 | |
| IMG_8909.jpeg | 30 | |
| IMG_8910.jpeg | 60 | |
| IMG_8911.jpeg | 90 | |
| IMG_8912.jpeg | 180 | |
| IMG_8913.jpeg | 270 | |

## Per Code

`--init-session test-assets/param-acq-box-tape` (legge questo NOTE), flusso
solito (tool → click 4 foto → `--match`). Nel report: la **firma del bias**
(mm/lato e %) affiancata a quella della sessione nuda — è il confronto che
emette il verdetto.
