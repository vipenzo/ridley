# Brief — Recording ad alto livello, Fase 3: la falciatura dei tag orfani

## Contesto

Fase finale del piano in `dev-docs/high-level-recording-accertamento.md`, sbloccata dal brief lettura-2D. **Prerequisito formale prima di iniziare:** le due evidenze pendenti di quel brief devono essere arrivate — il grep di uscita (zero letture di `:pure`/`:span` in `src/`) e il commit della lettura 2D. Se una delle due manca, prima quella.

**Cosa cade e cosa resta — la distinzione è il cuore del brief:**

Cadono i tag di **ricostruzione**: quelli che esistevano per riportare a valle l'informazione analitica che la tessellazione a record time distruggeva. Dopo le Fasi 1–2b e la lettura 2D nessuno li legge più:

- `:pure` e `:span` (ultimo lettore: il seed 2D, migrato) — il recording alto livello *è* l'informazione che il rider contrabbandava;
- `:veer-deg` (ultimo lettore: il vecchio guard micro, sostituito in 2b da `curve-entry-veer-deg` sulle componenti locali di `:c1`).

Restano i tag di **protocollo del lowering**: quelli che il consumer micro (l'estrusione) legge ancora, generati e consumati dentro la stessa cucitura tessellazione→estrusione:

- `:smooth` (letto da `corner-rotation?`: distingue passo di curva da corner utente sullo stream micro);
- `:arc-cap :lead/:trail` e `:bez-cap :lead` (letti da `split-leading-cap`/`leading-cap-window` per lo stamping del cap).

Non sono più risarcimenti sparsi per il sistema: sono l'annotazione privata con cui `lower-commands` parla al suo unico interlocutore. La loro eventuale eliminazione richiederebbe che l'estrusione consumi segmenti alto livello direttamente — un'altra migrazione, di un altro ordine di grandezza, fuori da questo piano.

**Alternative considerate e rigettate:**

- *Rimuovere anche `:smooth`/`:arc-cap`/`:bez-cap` "già che ci siamo"* — hanno lettori vivi; rimuoverli significa migrare l'estrusione ai segmenti, che non è una falciatura ma un raccolto diverso. Un difetto (qui: un tag orfano) una cura.
- *Lasciare i tag orfani emessi "per sicurezza"* — è esattamente il codice morto per scaramanzia che il metodo vieta; ogni futuro lettore del lowering dovrebbe chiedersi a cosa servono, e la risposta sarebbe "a niente, ma nessuno ha osato".

## Lavoro richiesto

1. Rimuovere dall'emissione di `lower-commands` (`src/ridley/turtle/core.cljs`): il rider `:pure` (con il suo `:span`) da `lower-bezier-to` e famiglia, e la chiave `:veer-deg` dalle rotazioni di lead (il tag `:bez-cap :lead` resta — lo legge il cap). Verificare con grep che non esistano altri punti di emissione residui.
2. Aggiornare il golden corpus: la diff deve mostrare **esclusivamente** la scomparsa delle chiavi `:pure`/`:span`/`:veer-deg` — zero variazioni numeriche, zero variazioni di struttura o conteggio comandi. Diff ispezionata a mano e allegata al commit; una qualunque variazione oltre le chiavi rimosse è una regressione da fermare, non da assorbire.
3. Rimuovere i test o le asserzioni che esistevano solo per pinnare i tag rimossi (es. l'oracolo `:veer-deg` della Family 6, che ha esaurito il suo compito: il suo commento di congedo può dirlo); i test che verificano *comportamenti* restano tutti.
4. Grep di uscita: `:pure`, `:span`, `:veer-deg` a zero occorrenze in `src/` (test e golden esclusi dal conteggio solo se citazioni storiche in docstring, come da precedente 2b).
5. Aggiornamento **meccanico** di `docs/Architecture.md` §6.3.1: via le righe `:pure` e `:veer-deg` dalla tabella, aggiornata la riga `:bez-cap` (perde la menzione `:veer-deg`). La riscrittura **editoriale** del capitolo — la riframmatura dei tag superstiti come protocollo della cucitura e l'accorciamento della sezione — non è compito di questo brief: la fa Claude all'accettazione, sul codice atterrato.
6. Aggiornare l'«Esito» di `high-level-recording-accertamento.md`: Fase 3 chiusa, resta la 2c come ultimo item del piano.

## Verifica

- Prerequisito verificato e citato nel report: grep di uscita della lettura 2D + hash del suo commit.
- Diff dei golden: solo chiavi rimosse (punto 2), allegata e ispezionata — è l'unico delta ammesso di tutta la fase.
- Pinned **intatte**: i tag non hanno mai toccato una mesh; una pinned che cambia qui è il segnale di una dipendenza nascosta, cioè esattamente ciò che il grep di uscita giurava non esistere — fermarsi e capire, non aggiornare.
- Suite completa 0 failures; round-trip editor 3D e 2D verdi invariati; il canary del frame canonico (mesh end == ultimo nodo a resolution multiple) verde invariato.
- Commit proprio, verde, con la diff dei golden nel messaggio o allegata.

## Fuori scope

- `:smooth`, `:arc-cap`, `:bez-cap :lead`: restano (lettori vivi nel consumer micro). La loro riframmatura è editoriale, non di codice.
- La 2c (resolution di consumo) — ultimo item del piano, discussione semantica a sé.
- La migrazione dell'estrusione ai segmenti alto livello (non pianificata).
- Le voci code-issues aperte (`anim`/`span`, STL import, segno di `arc-v-endpoint`).
- La riscrittura editoriale di §6.3/§6.3.1 (a Claude, dopo l'atterraggio).
