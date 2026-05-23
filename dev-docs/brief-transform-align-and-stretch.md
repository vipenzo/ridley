# Brief: allineamento translate/scale/rotate mesh↔SDF + stretch-* locale in attach

## Contesto

L'audit del brief `brief-transform-pivot-audit.md` ha rivelato un'asimmetria tra mesh e SDF nel comportamento di `translate`, `scale`, `rotate`:

|            | scale pivot      | scale axes     | rotate pivot     | rotate axes |
|------------|------------------|----------------|------------------|-------------|
| Mesh oggi  | centroid         | creation-pose  | centroid         | world       |
| SDF oggi   | creation-pose    | world          | creation-pose    | world       |

L'obiettivo è unificare il comportamento e introdurre un meccanismo pulito per lo scale lungo assi locali dentro `attach`.

## Decisioni di design

### A. Unificare translate/scale/rotate su assi mondo + pivot creation-pose

Per entrambi mesh e SDF, `translate`, `scale`, `rotate` al top level devono comportarsi così:

| Operazione | Assi   | Pivot            |
|------------|--------|------------------|
| translate  | world  | N/A              |
| scale      | world  | creation-pose    |
| rotate     | world  | creation-pose    |

Cambiamenti rispetto a oggi:

- **Mesh scale**: assi da creation-pose → world. Pivot da centroid → creation-pose.
- **Mesh rotate**: pivot da centroid → creation-pose.
- **SDF**: nessun cambiamento (già conforme).

Razionale: la creation-pose è il riferimento naturale perché è controllabile dall'utente via `cp-*`, è coerente tra mesh e SDF, e non dipende dalla geometria risultante (il centroid dopo un boolean può finire ovunque). Assi mondo per le operazioni top-level perché `translate`, `scale`, `rotate` sono il vocabolario "CAD tradizionale" di Ridley: chi scrive `(scale mesh 1 2 1)` intende "scala Y mondo", non "scala Y locale".

### B. Rimuovere `scale` dentro attach

`(scale n)` e `(scale sx sy sz)` dentro il body di `attach`/`attach!` vengono rimossi. Oggi `(scale 0.5)` dentro attach è un caso legacy che scala la mesh attualmente attached. Questa forma viene eliminata.

Se un utente scrive `(attach mesh (scale 2))` o `(attach mesh (scale 1 2 1))` nel body di attach, deve ricevere un errore chiaro che suggerisce `stretch-f`/`stretch-rt`/`stretch-u` come alternativa.

Messaggio di errore suggerito: `"scale is not available inside attach. Use stretch-f, stretch-rt, stretch-u for local-axis scaling."`

### C. Introdurre stretch-f, stretch-rt, stretch-u dentro attach

Tre nuovi comandi disponibili esclusivamente dentro `attach`/`attach!`:

```clojure
(stretch-f factor)    ; scala lungo l'asse forward (heading) della turtle
(stretch-rt factor)   ; scala lungo l'asse right della turtle
(stretch-u factor)    ; scala lungo l'asse up della turtle
```

Questi operano nel frame locale della turtle al momento della chiamata, esattamente come `f`/`rt`/`u` per il movimento e `th`/`tv`/`tr` per la rotazione. La simmetria con il resto del DSL turtle è il punto.

Semantica: `(stretch-f 2)` raddoppia la dimensione della mesh lungo la direzione in cui la turtle sta guardando in quel momento. Se prima di `stretch-f` c'è stato un `(th 45)`, l'asse di scala è ruotato di 45°.

**Scope**: solo dentro `attach`/`attach!`. Al top level non sono disponibili (al top level si usa `scale` con assi mondo).

**Implementazione**: il meccanismo è lo stesso dello scale non-uniforme attuale, ma gli assi vengono presi dalla turtle corrente dentro l'attach, non dalla creation-pose della mesh. Cioè: la turtle dentro attach ha heading/up/right; `stretch-f` scala lungo heading, `stretch-rt` lungo right, `stretch-u` lungo up. Il pivot è la posizione corrente della turtle dentro l'attach (che di default è la creation-pose della mesh, ma può essere spostata da `f`/`rt`/`u` precedenti nel body).

## Riepilogo modifiche

1. **`src/ridley/turtle/attachment.cljs`** (o dove vive la dispatch):
   - `scale` su mesh: cambiare assi da creation-pose a world. Cambiare pivot da centroid a creation-pose.
   - `rotate` su mesh: cambiare pivot da centroid a creation-pose.

2. **Scale dentro attach**: rimuovere. Errore chiaro se invocato.

3. **`stretch-f`, `stretch-rt`, `stretch-u`**: implementare come comandi attach-only. Binding in SCI.

4. **`docs/Spec.md`**: aggiornare la tabella "Pivot conventions" e la prosa circostante. Documentare i nuovi `stretch-*`. Rimuovere la documentazione di `scale` dentro attach.

## Test

- `(scale (box 10 20 30) 1 2 3)` → box scalato su Y mondo (altezza raddoppiata in world space)
- `(scale (attach (box 10 20 30) (th 45)) 1 2 3)` → box ruotato poi scalato su Y mondo → parallelepipedo deformato obliquamente (non più scalato lungo assi locali come oggi)
- `(attach (box 10 20 30) (stretch-f 2))` → box allungato lungo heading (equivalente al vecchio scale lungo assi locali)
- `(attach (box 10 20 30) (th 45) (stretch-f 2))` → box allungato lungo heading ruotato di 45°
- `(attach (box 10 20 30) (scale 2))` → errore con messaggio che suggerisce stretch-*
- Stessi test su SDF (devono dare risultati identici alle mesh)
- `(rotate (attach (box 20) (f 30)) :z 45)` → ruota attorno alla creation-pose (non al centroid)
