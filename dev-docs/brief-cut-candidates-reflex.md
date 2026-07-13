# Brief: cut-candidates :reflex — candidati dagli spigoli riflessi (V2)

## Contesto

Chiude la famiglia dei generatori di candidati, terza specie accanto a simmetria (`y`) ed eventi di profilo (next-event): propone tagli **dove la concavità vive**. Fondamenta misurate (accertamento fase 2, B4): gli spigoli riflessi (angolo diedro > 180°) di una mesh reale sono migliaia sui raccordi tassellati, ma il **clustering per piano candidato è refinement-invariante** e li riduce a numeri gestibili (10 sul mount squadrato; 72 con superfici cilindriche → serve ranking). Ogni spigolo riflesso offre due candidati: i piani delle due facce adiacenti, estesi.

Eredita: il contratto di ritorno di `cut-candidates` (`{:pose :kind :salience}`, brief cut-candidates); le convenzioni UI dell'addendum 3 (bottoni con ragioni, pending, nessun no-op silenzioso); il keep-alive Manifold per pezzo.

Differenza di interazione, decisa in discussione: i candidati riflessi sono **pose complete sparse nello spazio** (orientamento + posizione insieme), senza un DOF che le ordini — il modello è **"proponi e cicla"** come `y`, non il next-event lungo un asse.

## Lavoro richiesto

### Parte 1 — Il modo :reflex in cut-candidates

`(cut-candidates mesh {:mode :reflex})`. Non legge la posa della turtle (solo mesh), documentato nel docstring.

Pipeline:
- **Spigoli riflessi**: per ogni edge condiviso da due facce, angolo diedro dalle normali; riflesso se eccede 180° oltre una tolleranza (che scarta il quasi-piatto da tessellazione), pinnata e regolabile via opts.
- **Due candidati per spigolo**: i piani delle due facce adiacenti.
- **Clustering per piano** in tolleranza (normale + offset), come misurato in B4 — refinement-invariante per costruzione.
- **Salienza = massa di concavità del cluster**: somma delle lunghezze degli spigoli contribuenti, pesate per l'eccesso d'angolo riflesso. I tagli che risolvono tanta concavità vengono prima; i cluster-briciola da fillet in coda.
- **Posa restituita**: heading = normale del piano del cluster (orientamento deterministico e documentato — la normale uscente della faccia generatrice), position = centroide pesato dei punti medi degli spigoli contribuenti proiettato sul piano (il teletrasporto atterra vicino alla concavità, non in un punto arbitrario del piano infinito), up libero.
- Ritorno: vettore `{:pose … :kind :reflex :salience …}` ordinato per salienza decrescente. Mesh convessa → vettore vuoto. Funzione pura (B5).

### Parte 2 — Gesto "proponi taglio dalla concavità"

- Tasto + bottone (addendum 3): teletrasporto sul candidato più saliente; pressioni successive ciclano in ordine di salienza. Modello identico a `y`.
- Precondizioni visibili: su un pezzo convesso il bottone è disabilitato con ragione ("nessuna concavità: il pezzo è convesso") — coerenza naturale col semaforo: verde ⇒ gesto spento.
- Calcolo on-demand con stato pending; cache per pezzo aperto (mesh immutabile), come gli altri generatori.

## Verifica

- Prisma a L: cluster ai due piani interni dello spigolo concavo; accettare il candidato top produce pezzi convessi in 1–2 tagli.
- Box+boss (il rosso 0.78 di A4): candidati alla base del boss; il top per salienza è quello che stacca il boss.
- Salienza: concavità grande sopra fillet piccolo, verificato su forma costruita con entrambi; ordinamento coerente con la massa calcolata indipendentemente.
- Forma con cilindri (replica B4): conteggio cluster nell'ordine misurato; col ranking, i primi 3–5 candidati sono quelli sensati.
- Mesh convessa → `[]`; bottone disabilitato con ragione sul pezzo verde.
- Purezza: input identici → output identici.
- Emissione invariata (il candidato accettato è un taglio ordinario); test WASM con idioma skip, citare l'entry di `code-issues.md`.
- **Accettazione sul mount reale** (desktop): i rossi osservati in A4 (piastra+gancio 0.55) risolti partendo dai candidati proposti; riportare quanti cicli servono e le frizioni.
- Suite completa verde.

## Fuori scope

- Applicazione automatica in catena dei candidati (VHACD-lite): l'umano dispone, sempre.
- `seek-cut` scriptabile: orizzonte del linguaggio vero, invariato.
- Snap magnetico mouse; asse di ciclo configurabile.

## Alternative considerate e scartate

- **Funzione separata** (`reflex-candidates`): scartata — un solo punto d'ingresso (`cut-candidates` + `:mode`) per tutti i generatori, per script e tool.
- **Next-event per i candidati riflessi**: scartato — non esiste un DOF lungo cui ordinarli; pose sparse ⇒ proponi-e-cicla per salienza, come `y`.
- **Salienza = conteggio spigoli del cluster**: scartata — sovrapesa i raccordi finemente tassellati (tanti spigoli corti quasi piatti); lunghezza × eccesso d'angolo misura la concavità vera.
