# Brief: Audit Spec↔codice e Lint Script

## Rationale

Il piano di ristrutturazione del manuale (`docs/manual-redesign-plan.md`, in particolare §6.7 e §6.9) prevede che la nuova Reference del manuale sia generata a partire da Spec.md. Per farlo bene, Spec.md deve coprire tutte le funzioni che un utente Ridley può legittimamente usare. La discovery precedente ha mostrato che 204 simboli su 435 (47%) sono bound in SCI ma non citati in Spec.md, di cui ~70 sono candidati genuini a essere documentati.

Questo brief chiede due lavori paralleli:

1. **Audit fine dei ~70 simboli sospetti**: applicare il criterio editoriale di §6.7 del piano per classificare ciascun simbolo in una di tre destinazioni (A: Reference standard, B: Reference Internals, C: Allowlist).
2. **Lint script Babashka**: drafting di uno script che data una versione futura di Spec.md + allowlist verifica che non ci siano simboli bound senza documentazione né allowlist.

I due lavori sono indipendenti e possono procedere in parallelo. (1) sblocca l'aggiornamento di Spec.md, prerequisito del brief B (infrastruttura documentazione). (2) è infrastruttura di CI/pre-release che verrà usata regolarmente in seguito.

## Riferimenti al piano

- §6.7 "Criterio editoriale di destinazione" — definizione delle tre destinazioni A/B/C
- §6.8 "Articolazione delle Internals" — sotto-categorie in cui i simboli di destinazione B verranno organizzati nella Reference
- §6.9 "Workflow di gestione" — sequenza dei tre lavori (audit, lint script, aggiornamento Spec)
- §10.1 "Prerequisiti paralleli" — questi lavori come prerequisiti del brief B

## Task 1 — Audit fine dei ~70 simboli sospetti

### Input

La lista dei 204 simboli orfani è in `/tmp/orphans.txt` (dal lavoro di discovery precedente). I ~70 sospetti sono quelli già pre-filtrati nella discovery (escludendo plumbing evidente: `-impl`, shadow `pure-*`, recording `rec-*`, namespace `turtle-*`, dynamic vars, costruttori-helper).

### Criterio (da §6.7 del piano)

Ogni simbolo va classificato in una di tre destinazioni in base alla domanda **"per chi è scritto?"**:

- **A — Spec + Reference standard (Functions)**: funzioni che sono *materia ordinaria di scrittura Ridley*. Un utente normale che scrive programmi deve poterle trovare. Vanno in Spec.md e diventano schede della Reference standard.
- **B — Spec + Reference Internals**: API per chi *estende Ridley* con codice proprio (pattern di registry, introspezione, hooks). Vanno in Spec.md e diventano schede *Internals*.
- **C — Allowlist**: plumbing interno esposto a SCI per ragioni tecniche, ma non parte del linguaggio. Non va in Spec, non va in Reference. Va in una *allowlist* documentata con un commento per ciascun simbolo che spiega perché è esposto.

Casi limite da non risolvere unilateralmente: se un simbolo è ambiguo (potrebbe essere A o B, oppure B o C), segnalalo come **"da chiarire"** anziché classificare a indovinare.

### Output

Una tabella in Markdown (`docs/symbol-audit-report.md`) con le colonne:

| Simbolo | Destinazione | Sotto-categoria (se B) | Note |
|---|---|---|---|

Per la sotto-categoria (se B): scegliere tra le 7 sotto-categorie di §6.8 (Contracts, Registry pattern, Selection & visibility, Animation API, Collisions & pilot, Source tracking & metaprogramming, Runtime settings). Se nessuna calza, proporre una nuova sotto-categoria in colonna "Note".

In coda al report:

- Riepilogo numerico (totali A/B/C/da chiarire).
- Lista di "Da chiarire" con domanda esplicita per Vincenzo, una riga per simbolo.
- Eventuali pattern emersi durante l'audit (es. "tutti i `register-X!` sono B sotto-categoria Registry").

### Non-goals

- Non scrivere ancora le schede Reference (saranno generate in seguito a partire da Spec.md aggiornato).
- Non aggiornare Spec.md (è il task successivo, dopo validazione del report).
- Non implementare nulla.

## Task 2 — Lint script Babashka

### Obiettivo

Uno script Babashka che, dato Spec.md + un file di allowlist, verifica che ogni simbolo bound in SCI sia o documentato in Spec o presente in allowlist. Output: lista di simboli "orfani" (bound ma non documentati né in allowlist), exit code non-zero se ce ne sono.

### Riferimento

Il pattern è quello di `gen_rag_chunks.bb` (script che già parsa Spec.md per il RAG). Stesso approccio di parsing.

### Output

- `bb/spec_lint.bb` (o nome analogo, coerente con la convenzione esistente)
- Documentazione minima in testa al file (cosa fa, come si usa, come si interpreta l'output)
- Esempio di file allowlist (formato consigliato: lista di simboli con commento `;;` per ciascuno che spiega *perché* è esposto ma non documentato)

### Comportamento atteso

```
$ bb bb/spec_lint.bb
✗ 3 simboli bound ma non documentati né in allowlist:
  - example-1     (suggerimento: aggiungere a Spec.md o ad allowlist)
  - example-2
  - example-3
```

Se tutto a posto, exit 0 e messaggio di successo.

### Configurazione

Il path di Spec.md e dell'allowlist dovrebbero essere parametrizzabili (default ragionevoli: `Spec.md` e `bb/spec_allowlist.edn`).

### Non-goals

- Non integrare ancora in CI o pre-release hooks (si farà in seguito, una volta che il workflow è validato).
- Non popolare l'allowlist con i ~150 plumbing — quello sarà l'output dell'audit (Task 1, destinazione C). L'allowlist nasce vuota; lo script funziona già su qualsiasi quantità.

## Output complessivo del brief

Due deliverables:

1. `docs/symbol-audit-report.md` — tabella di classificazione dei ~70 simboli + sezione "da chiarire"
2. `bb/spec_lint.bb` (o nome equivalente) + esempio di `bb/spec_allowlist.edn`

Quando entrambi sono pronti, segnala in conversazione con Vincenzo. Il passo successivo (aggiornamento Spec.md con i simboli A e B + popolamento dell'allowlist con i simboli C) sarà un brief separato, basato sulla tabella validata.