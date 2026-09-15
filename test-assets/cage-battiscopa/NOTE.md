# cage-battiscopa — il banco del "zero click"

Copie della sessione **battiscopa** (`~/Pictures/RidleyScan/battiscopa`,
2026-08-25): otto foto della GABBIA NUOVA — chiave di montaggio, portapezzi,
un pezzo di battiscopa al centro — tutte registrate A MANO da Vincenzo
(3.8–13.8px, focale rifinita 48.9mm) al termine della settimana di recupero.
`acquire-state.json` è la verità: pose camera e pick validati.

Le jpeg NON sono tracciate (`.gitignore`). Il banco (`node out/cage-auto.js`)
misura `match-cage/auto-read` — la lettura SENZA click — contro queste pose:
quante foto registra da sola, e a quanti mm atterra la sua camera dalla mano.

Stato al 2026-08-27: **2/8 da sola (3.5mm e 1.0mm), zero falsi positivi**,
rifiuti in 10–23s. Le sei rifiutate falliscono tutte allo stadio delle ELLISSI
(l'anello vero non emerge fra le ipotesi RANSAC) — è lì la prossima frontiera.
La foto 8 (IMG_9034) è quella che a mano ha richiesto due giorni: da sola la
macchina la registra a 1.0mm.
