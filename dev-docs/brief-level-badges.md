# Brief — Badge di livello nelle Guide (rendering dei marker `level`)

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-10
**Contesto:** ristrutturazione leggibilità del manuale (cap. 1 + pagina `how-to-read` + percorsi di lettura). Vedi `docs/manual-redesign-plan.md` §14.5, voce 2026-06-10.

## Obiettivo

Le guide dichiarano il livello di difficoltà di capitoli e singole sezioni tramite marker invisibili nel Markdown. Il renderer delle guide (`draft_renderer`) deve riconoscerli e mostrarli come badge accanto al titolo corrispondente. Lo scopo editoriale: un lettore che scorre un capitolo deve poter vedere a colpo d'occhio "questa sezione posso saltarla al primo giro".

## Convenzione del marker (già in uso nei file)

Il marker è un commento HTML su una riga propria:

```
<!-- level: base -->
<!-- level: intermediate -->
<!-- level: advanced -->
<!-- level: advanced | prereq: 3 5 -->
```

Grammatica:

- `level:` seguito da uno dei tre token **`base` | `intermediate` | `advanced`**. I token sono fissi e identici nei file IT e EN (così il marker non si traduce e l'editing parallelo IT/EN copia la stessa riga).
- Opzionale, separato da `|`: `prereq:` seguito da uno o più riferimenti separati da spazio. Un riferimento è un numero di capitolo (`3`) o di sezione (`7.4`).
- Spazi liberi attorno a `:`, `|` e ai token.

## Regole di attacco (a quale heading appartiene un marker)

1. **Livello di capitolo**: il primo marker `level` che compare nel file *prima del primo heading `##`* vale per l'intero capitolo. Per convenzione editoriale sta subito dopo il titolo `#` (eventualmente separato da una riga vuota), ma la regola robusta è "prima del primo `##`".
2. **Livello di sezione**: un marker che compare *dopo* un heading `##` (o `###`) e *prima del successivo heading di pari o superiore livello* vale per quella sezione. Per convenzione editoriale sta sulla prima riga non vuota dopo l'heading.
3. **Ereditarietà**: una sezione senza marker eredita il livello del capitolo. Non serve renderizzare il badge ereditato sulle sezioni (sarebbe rumore): si renderizza solo dove il marker è esplicito, cioè dove il livello *devia* o l'autore ha voluto ribadirlo.
4. Un solo marker per heading; se ce ne sono più d'uno vale il primo, gli altri si ignorano.
5. Marker malformati o con token sconosciuti: ignorare in silenzio (degradazione: il commento HTML è già invisibile nel rendering attuale, quindi nessuna regressione possibile).

## Rendering

- **Badge capitolo**: chip accanto (o sotto) al titolo `#` della pagina. Tre varianti visive per i tre livelli; suggerimento: tinte coerenti col tema scuro, niente colori semaforici aggressivi. Testo localizzato:
  - IT: `base` / `intermedio` / `avanzato`
  - EN: `basic` / `intermediate` / `advanced`
- **Badge sezione**: chip più piccolo, inline a destra del testo dell'heading `##`/`###`.
- **Prerequisiti** (se presenti, sia su capitolo sia su sezione): resi come testo accanto al badge o in tooltip del badge, localizzato: IT `Prerequisiti: cap. 3, 5`, EN `Prerequisites: ch. 3, 5`. I riferimenti con punto (`7.4`) si rendono senza il prefisso `cap.`/`ch.` ripetuto: `cap. 3, 5, §7.4` va bene; non serve linkare (i link ai capitoli possono essere un'estensione futura). **Ordine**: i riferimenti si rendono nell'ordine in cui compaiono nel marker (nessun riordino, nessun raggruppamento cap./§); il prefisso `cap.`/`ch.` si emette una sola volta in testa, i riferimenti con punto portano `§` ciascuno.
- La lingua del badge segue la lingua della pagina renderizzata (stessa logica del fallback esistente).

## Interazioni da non rompere

- I marker sono commenti HTML come gli `<!-- example-source: ... -->`: il parser degli esempi non deve confonderli (il discriminante è il prefisso `level:` vs `example-source:`). Verificare che il marker dentro o adiacente a un blocco example-source non venga interpretato come parte dell'esempio.
- I file con marker esistono già: `docs/manual/guides/it/01-getting-started.md` (marker di capitolo `base`) e prossimamente i capitoli 2-17 in entrambe le lingue (annotazione editoriale a carico di Claude/Vincenzo, non di questo brief).
- La pagina `how-to-read` descrive i livelli al lettore (sezione "I livelli"): quando il rendering è attivo, quella descrizione diventa vera senza modifiche.

## Note di implementazione (pipeline del renderer)

Il `draft_renderer` processa una pagina in più stadi (vedi `render-draft-markdown`): `extract-example-sources` → `strip-remaining-comments` → `marked` → passata sul DOM (`attach-runnable-panels!`). Tre vincoli che ne derivano:

1. **Ordinamento — il più importante**: l'estrazione dei marker `level:` deve avvenire **prima** di `strip-remaining-comments`, che cancella *tutti* i commenti HTML residui con una regex generica (`<!--[\s\S]*?-->`). Se i marker non vengono consumati prima, spariscono e nessun badge viene mai renderizzato. (Effetto collaterale positivo: questo garantisce la degradazione silenziosa dei marker malformati senza codice aggiuntivo.)
2. **Pattern da riutilizzare**: replicare lo schema già usato per `example-source` — un pre-pass sul Markdown che sostituisce il marker con una sentinella (o annota l'heading con un `data-level`/`data-prereq`), e un post-pass sul DOM che inserisce il chip. Non serve inventare un meccanismo nuovo.
3. **Associazione marker→heading posizionale**: gli `id` degli heading vengono assegnati *dopo* il render, in `collect-headings!`, camminando il DOM. Quindi la corrispondenza tra un marker e la sua sezione va calcolata nel pre-pass sul testo Markdown (per posizione rispetto agli heading), non a valle: a quel punto il commento è già stato rimosso.

## Fuori scope

- L'annotazione dei capitoli esistenti (lavoro editoriale, sweep separato).
- Filtri di navigazione per livello nella sidebar (possibile estensione futura, non ora).
- Badge nelle schede Reference (le schede non hanno livelli).

## Accettazione

1. Un file con `<!-- level: base -->` dopo il `#` mostra il chip "base"/"basic" sul titolo, in IT e in EN.
2. Una `##` con `<!-- level: advanced | prereq: 3 5 -->` sulla riga successiva mostra il chip "avanzato"/"advanced" con i prerequisiti.
3. Sezioni senza marker: nessun chip.
4. Marker malformato: nessun chip, nessun errore, nessun testo spurio nel rendering.
5. Gli esempi eseguibili nelle pagine annotate continuano a funzionare (in particolare nel cap. 1 appena scritto).
