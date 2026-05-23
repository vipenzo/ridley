# Brief: Consolidamento numerazione di Spec.md

## Rationale

T-008 ha integrato in `docs/Spec.md` una nuova sezione `## 19. Internals` prima dell'esistente `## 18. Not Yet Implemented`, ribattezzata `## 20`. Risultato: gap di numerazione (§17 → §19 → §20, niente §18). Il gap è funzionante ma inusuale e va chiuso prima che fonti esterne (RAG chunks, bookmark utenti, citazioni in altri documenti) si abituino alla numerazione transitoria.

Decisione presa il 2026-05-16 da Vincenzo: chiudere il gap rinominando Internals a §18 e Not Yet Implemented a §19.

## Riferimenti al piano

- §14.5 — voce "Gap di numerazione in Spec.md (§17 → §19 → §20) accettato come transitorio" — la decisione di chiudere è già fissata lì.
- T-008 — task di provenienza del gap, già chiuso.

## Convenzioni di output

I file modificati da questo brief sono **`docs/Spec.md`** e, se necessario, eventuali file accessori che referenzino le sezioni rinumerate (vedi Task 2 sotto). Qualunque report o nota intermedia prodotta da Code va in `/dev-docs/`, non in `/docs/`.

## Task 1 — Rinumerazione delle sezioni di Spec.md

### Operazione

In `docs/Spec.md`:

- `## 19. Internals` → `## 18. Internals`
- `## 20. Not Yet Implemented` → `## 19. Not Yet Implemented`

Le sotto-sezioni `### 19.1` ÷ `### 19.9` diventano `### 18.1` ÷ `### 18.9`.

Lo stato finale ha 19 sezioni numerate consecutive, da §1 a §19.

### Verifica preliminare già fatta

Prima del brief è stato verificato:

- I riferimenti interni a Spec.md (link Markdown tipo `[Mesh Operations](#7-mesh-operations)`) usano **slug nominati** derivati dai titoli, non ancore numerate fisse. Esempio: `#11-sdf-modeling` è uno slug stabile rispetto a rinominazioni di altri capitoli, perché il numero nello slug è la posizione attuale di SDF Modeling (§11), non un riferimento esterno.
- Nessun riferimento `§18` / `§19` / `§20` in Spec.md (verificato via `grep -nE "§(18|19|20)"`).
- Le ancore degli `### 19.x` (es. `#191-registry-pattern`) cambieranno in `#181-registry-pattern` solo se ci sono link interni che vi puntano. Verifica: `grep -n "#19[0-9]-\|#20[0-9]-" docs/Spec.md`.

### Output

`docs/Spec.md` con sezioni rinumerate. Nessuna modifica di contenuto, solo dei numeri di sezione (titoli `##` e `###`) e degli eventuali riferimenti interni se presenti.

### Non-goals

- Non modificare il contenuto delle sezioni, solo i numeri dei titoli.
- Non riorganizzare l'ordine delle sotto-sezioni.

## Task 2 — Verifica e aggiornamento di riferimenti esterni a Spec.md

### Operazione

Cercare in tutto il repo (escluso `.git`, `node_modules`, `.shadow-cljs`) riferimenti a Spec.md che usino numeri di sezione:

```bash
grep -rn "Spec.md#19\|Spec.md#20\|Spec §19\|Spec §20\|section 19\|section 20" \
  --include="*.md" --include="*.cljs" --include="*.bb" --include="*.edn" \
  /Volumes/Rogue/Progetti/Ridley
```

Per ciascun match: se referenzia "Internals" (era §19, diventa §18) o "Not Yet Implemented" (era §20, diventa §19), aggiornare. Se referenzia altro che si chiama §19 o §20 in un contesto diverso, lasciare invariato.

Particolare attenzione a:

- `scripts/gen_rag_chunks.bb` — script che parsa Spec.md per generare i chunk del RAG. Probabilmente non dipende dai numeri ma dai titoli; verificare comunque.
- `scripts/spec_lint.bb` — non dovrebbe avere riferimenti a numeri di sezione, ma verificare.
- `docs/manual-redesign-plan.md` — contiene riferimenti narrativi al gap "§19 Internals / §20 Not Yet Implemented" nelle voci del quaderno (§14.5) e nello storico revisioni. **Non vanno modificati**: descrivono lo stato transitorio storico, non lo stato corrente. La voce "Gap di numerazione accettato come transitorio" in §14.5 va aggiornata da "accettato come transitorio" a "chiuso (vedi T-008b)" dopo che T-008b è completato — questa modifica al piano la farà Claude in chat, non Code.

### Output

Lista in chat (al report finale) dei file effettivamente modificati e di quelli verificati ma lasciati invariati.

### Non-goals

- Non modificare i riferimenti **storici** nel piano `manual-redesign-plan.md` — descrivono la transizione, non lo stato attuale. Lasciare al refresh successivo del piano, che farà Claude.

## Task 3 — Verifica finale che il lint passi

### Operazione

Eseguire `bb scripts/spec_lint.bb` dopo la rinumerazione. Deve continuare a uscire con exit 0 (lo script non dipende dai numeri di sezione, ma vale la pena confermare che la rinumerazione non abbia introdotto refusi).

### Output

Conferma exit 0 nel report finale.

## Sequenza operativa

1. Code esegue Task 1 (rinumerazione Spec.md).
2. Code esegue Task 2 (verifica riferimenti esterni).
3. Code esegue Task 3 (lint).
4. Code riporta a Vincenzo in chat:
   - Conferma rinumerazione completata.
   - Lista di file esterni a Spec.md che sono stati modificati (probabilmente nessuno).
   - Conferma `spec_lint.bb` exit 0.
   - Eventuali sorprese.

Vincenzo valida. Se tutto a posto, T-008b chiuso. Claude poi aggiornerà il piano (storico revisioni + voce §14.5 "gap accettato come transitorio" → "gap chiuso").
