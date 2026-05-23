# Brief: badge di warning sugli example-source

## Contesto

Alcuni esempi nel manuale sono lenti (5-6 secondi per shell/woven-shell con resolution 512) o richiedono funzionalità non disponibili ovunque (SDF richiede desktop). Premere Run senza saperlo è frustrante. Serve un feedback visivo accanto ai bottoni Run/Edit che avvisi l'utente prima dell'esecuzione.

## Design

### Sintassi nel Markdown

Un attributo opzionale `:warning` nel marker `<!-- example-source -->`:

```markdown
<!-- example-source: shell-voronoi :warning slow -->
<!-- example-source: sdf-basic :warning desktop-only -->
<!-- example-source: normal-example -->
```

Nessun attributo = nessun badge. Retrocompatibile: i marker esistenti continuano a funzionare senza modifiche.

### Warning previsti

| Valore | Badge | Significato |
|--------|-------|-------------|
| `slow` | ⏱ Slow | L'esecuzione richiede diversi secondi |
| `desktop-only` | 🖥 Desktop only | Richiede funzionalità non disponibili in browser mobile |

La lista è estensibile in futuro (es. `requires-registered` per esempi che dipendono da registrazioni precedenti), ma per ora partiamo con questi due.

### Rendering

Un piccolo badge inline accanto ai bottoni Run e Edit, con testo breve e un'icona. Colore neutro (non rosso, non è un errore — è un'informazione). Il badge è statico, visibile prima che l'utente prema Run.

Dimensione e stile coerenti con i bottoni esistenti, ma visivamente distinto (es. sfondo grigio chiaro, testo piccolo).

## Implementazione

1. Nel parser dei marker `<!-- example-source -->`, estrarre l'attributo `:warning` se presente.
2. Passare il valore al componente che renderizza il blocco codice.
3. Se il warning è presente, renderizzare il badge accanto ai bottoni.

## Verifica

- Un blocco con `:warning slow` mostra il badge "⏱ Slow" accanto a Run/Edit.
- Un blocco con `:warning desktop-only` mostra il badge "🖥 Desktop only".
- Un blocco senza `:warning` non mostra nessun badge.
- I bottoni Run e Edit continuano a funzionare normalmente.

## Fuori scope

- Bloccare l'esecuzione (il badge è informativo, non impedisce il Run).
- Aggiungere i warning ai marker nei file `.md` (lo faremo noi durante la revisione).
