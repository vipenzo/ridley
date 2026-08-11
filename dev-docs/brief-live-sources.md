# Nota: sorgenti live — complemento a HANDOVER-grab-and-register.md

Riscritta 2026-08-11 (Claude-docs). La valutazione delle opzioni scritta qui
in prima stesura è SUPERATA da `dev-docs/HANDOVER-grab-and-register.md`, che
porta vincoli ACCERTATI (Continuity Camera per prima; telefono-come-client-web
bloccato dal contesto sicuro di getUserMedia; scrittura desktop-only via
`/write-file`; permessi camera assenti in Tauri; precisione da misurare col
residuo). Quel handover è l'entry point del fronte. Qui restano solo DUE
aggiunte che non contiene.

## 1. La pagina LAN per gli SCATTI FERMI non è bloccata dall'HTTPS

Il vincolo "getUserMedia vuole un contesto sicuro" vale per lo STREAM. Ma una
pagina servita via `http://<ip>:9000` può usare
`<input type="file" accept="image/*" capture>`: non è getUserMedia, non
richiede contesto sicuro, e apre l'app fotocamera NATIVA del telefono →
scatto a PIENA risoluzione, CON EXIF, upload diretto nella cartella di
sessione (endpoint gemello di `/write-file`).

Perché può valere la pena, anche a Grab costruito: è il canale per le viste
DI PRECISIONE. Grab (Continuity) dà fotogrammi video comodi ma con meno
pixel; la pagina LAN dà lo scatto vero del telefono senza il giro
scarica-copia-rinomina. I due convivono: Grab per inquadrare e aggiungere
viste, la pagina per lo scatto fermo dove il residuo deve stare basso.
Costo: un endpoint upload + una pagina statica. Nessuna app, nessun
certificato.

## 2. ARKit: esplicitamente DOPO la visione v2

La companion app con posa allegata resta la sorgente più ricca ma costa
un'app nativa da mantenere. Se e quando, la posa ARKit deve entrare come
OSSERVAZIONE del solutore (con la sua incertezza), non come verità: quindi ha
senso solo a valle dei gradini 2/4/5 di
`brief-observation-driven-acquire.md` (i vincoli di posa nel bundle). Non
prima.
