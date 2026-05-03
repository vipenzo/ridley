# 3MF multimateriale: stato attuale e proposta di evoluzione

> **Aggiornamento 1 (proposta §2 implementata)**: la sezione `<basematerials>`
> viene emessa quando almeno una mesh ha colore, e ogni mesh registrata ottiene
> `name`, `pid` e `pindex` sull'`<object>`. Vedi `src/ridley/export/threemf.cljs`
> e `test/ridley/export/threemf_test.cljs`. Doc utente in `docs/Spec.md` §16.
>
> **Aggiornamento 2 (rischio §3.2 confermato)**: BambuStudio 2.5.0.66 e l'attuale
> OrcaSlicer **ignorano completamente `<basematerials>` standard** sui file
> esterni. Verificato il 2026-05-03 sul file di reference `test-with-basematerials.3mf`:
> entrambi gli slicer importano i due oggetti come grigi/uniformi su filament 1.
> Nessun dialog "Standard 3MF Import Color". Nessuna preference rilevante.
> Il workflow multimateriale via 3MF Materials Extension è **morto in pratica**.
>
> **Aggiornamento 3 (workaround Bambu/Orca)**: implementato il file proprietario
> `Metadata/model_settings.config` accanto al `<basematerials>` standard. Il
> config assegna a ogni `<object>` un `extruder=N` 1-based, dove N è la
> posizione del colore nel palette dedotto. Verificato che Bambu rispetta
> questa assegnazione senza dialog né conferme. `<basematerials>` resta come
> hint visivo per slicer terzi (PrusaSlicer eredita la spec standard, da
> verificare). Vedi §4 sotto per i dettagli.

Questo documento risponde a tre domande:

1. **Cosa fa oggi** Ridley quando si esporta in 3MF una scena con più mesh colorate?
2. **Cosa va aggiunto** per ottenere un workflow multimateriale end-to-end (Ridley → slicer → AMS)?
3. **Quali vincoli e rischi** vanno tenuti presenti?

---

## 1. Stato attuale

### 1.1 Cosa scrive Ridley nel file 3MF

Il generatore 3MF vive in [src/ridley/export/threemf.cljs](../src/ridley/export/threemf.cljs) e produce un archivio ZIP minimale conforme al **3MF Core Specification** (nessuna extension):

```
3MF/
├── [Content_Types].xml
├── _rels/.rels
└── 3D/3dmodel.model
```

`3D/3dmodel.model` ha questa struttura ([threemf.cljs:67-89](../src/ridley/export/threemf.cljs#L67-L89)):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="1" type="model">
      <mesh>
        <vertices>...</vertices>
        <triangles>...</triangles>
      </mesh>
    </object>
    <object id="2" type="model">
      <mesh>...</mesh>
    </object>
  </resources>
  <build>
    <item objectid="1"/>
    <item objectid="2"/>
  </build>
</model>
```

Quindi:

- ✅ **Le mesh restano oggetti separati** — una `<object>` per ogni mesh in input, ognuna con il proprio `<item>` nella build section. Niente fusione (questo è il vero vantaggio rispetto a STL, dove `meshes->stl-binary` riconcatena tutto in un unico array di triangoli).
- ❌ **Nessuna informazione di colore viene scritta**. Il generatore non emette `<basematerials>`, `<m:colorgroup>`, attributi `pid`/`pindex`, né attributo `name` sugli `<object>`. Il colore esiste in memoria nel campo `:material :color` del mesh-map ma viene letteralmente ignorato dal serializer.
- ❌ **Nessun nome assegnato agli oggetti**. Anche le mesh registrate (`(register :supporto …)`) perdono il nome nel file: gli `<object>` hanno solo `id` numerico.

### 1.2 Come si arriva al colore in memoria

Per completezza, il colore vive nel mesh-map sotto `:material :color` come intero hex (`0xRRGGBB`). Le tre forme dell'API ([implicit.cljs:156-178](../src/ridley/editor/implicit.cljs#L156-L178)):

```clojure
(color 0xff0000)              ;; turtle globale (mesh successive)
(color :supporto 0xff0000)    ;; mesh registrata, in-place via registry/update-mesh-material!
(color my-mesh 0xff0000)      ;; pura, ritorna nuovo mesh-map
```

Il rendering viewport legge questo campo e lo passa a `THREE.MeshStandardMaterial` ([viewport/core.cljs:547-562](../src/ridley/viewport/core.cljs#L547-L562)). La pipeline di export in [export/stl.cljs:263-324](../src/ridley/export/stl.cljs#L263-L324) accetta meshes come parametro ma — per il 3MF — invoca solo `threemf/meshes->3mf-blob` che, come visto, scarta il `:material`.

### 1.3 Test concreto

Per misurare cosa vede uno slicer ho generato due file di test in `/tmp/3mf-test/`:

| File | Contenuto |
|---|---|
| `test-current.3mf` | Riproduce **esattamente** il formato che produce Ridley oggi: due oggetti (un cubo 20×20×10 mm e un cilindro r=6 h=15 sopra), nessun colore. |
| `test-with-basematerials.3mf` | Stessi due oggetti, ma con `<basematerials>` per-oggetto — colorando il cubo di rosso (`#FF0000`) e il cilindro di blu (`#0066FF`), più attributo `name`. |

Il generatore ([gen_test_files.py](file:///tmp/3mf-test/gen_test_files.py)) emette per il primo file lo stesso identico XML che produce `meshes->model-xml`; per il secondo, l'aggiunta minima descritta nella proposta della §2.

#### Cosa mostra Bambu Studio (atteso)

> ⚠️ **Nota onesta**: gli screenshot reali non sono inclusi in questa stesura perché in fase di redazione non ho potuto pilotare in modo affidabile la GUI di Bambu Studio in background (manca permesso accessibility). I file sono pronti in `/tmp/3mf-test/` e si aprono con `open -a "BambuStudio" /tmp/3mf-test/test-current.3mf`. I comportamenti descritti sotto sono dedotti dalla [wiki Bambu sul parsing dei 3MF standard](https://wiki.bambulab.com/en/bambu-studio/Standard-3MF-File-Color-Parsing) e dalle issue del repo BambuStudio su GitHub; vanno confermati con un test manuale prima di chiudere la fase di analisi.

**`test-current.3mf`** (formato Ridley odierno):
- Bambu importa **due oggetti separati**, visibili come voci distinte nell'object list a sinistra e selezionabili indipendentemente.
- Entrambi appaiono nel **colore di default** del filamento attivo in slot 1 (di solito grigio chiaro).
- L'utente **può** assegnare un filamento diverso a ciascun oggetto manualmente (right-click → filament, o tasti 1–9), ma non c'è alcuna informazione "rosso/blu" precaricata: lo slicer non sa che si tratta di parti di colore diverso.
- È quindi un workflow **multipart** ma **non multimateriale**: l'utente deve ricordarsi a memoria quale parte va in quale slot.

**`test-with-basematerials.3mf`** (formato proposto):
- Da Bambu Studio 2.5 in poi appare il dialog **"Standard 3MF Import Color"** che propone di mappare i `displaycolor` letti dal file ai filamenti caricati.
- L'utente conferma e i due oggetti compaiono colorati: cubo rosso, cilindro blu, già assegnati a due slot AMS distinti.
- Le parti restano oggetti separati nell'object list (preservato `name="supporto"` e `name="scritta"`).

OrcaSlicer si comporta in modo analogo a Bambu Studio per quanto riguarda l'importazione di 3MF standard: stessa codebase per il parser core, le divergenze stanno altrove (UI delle assegnazioni AMS, dialog di mapping). PrusaSlicer non è installato sulla macchina di sviluppo e non posso testarlo direttamente; la documentazione 3MF Materials Extension è uno standard pubblico e PrusaSlicer storicamente legge `basematerials`, ma vedi §3 per i dubbi.

#### Conclusione della §1

Il workflow multimateriale **oggi non funziona** end-to-end: l'utente che scrive `(color :supporto 0xff0000)` non vedrà la parte rossa nello slicer. Vede due parti grigie. Per chiudere il loop serve scrivere informazione di colore nel 3MF, ed è quello che propone la §2.

---

## 2. Proposta tecnica

L'aggiunta è chirurgica: tutto quello che serve è in `threemf.cljs` e consiste nell'iniettare un blocco `<basematerials>` e nell'aggiungere tre attributi (`name`, `pid`, `pindex`) sugli `<object>`. Niente Rust, niente nuove dipendenze, nessuna modifica all'API utente.

### 2.1 Punti del codice da toccare

**File principale**: [src/ridley/export/threemf.cljs](../src/ridley/export/threemf.cljs)

Modifiche:

1. **`meshes->model-xml`** ([linee 67-89](../src/ridley/export/threemf.cljs#L67-L89)): pre-calcolare la mappa `colore → indice basematerials`, emettere il blocco `<basematerials>` se ci sono mesh colorate, e propagare `pid`/`pindex` agli `<object>`.

2. **`meshes->3mf-blob`** ([linea 104](../src/ridley/export/threemf.cljs#L104)): nessun cambiamento nella firma. Il colore arriva già nei mesh-map sotto `:material :color`.

**File da non toccare**:

- `stl.cljs` — la pipeline di esportazione (picker, write su disco, dispatch per estensione) è agnostica al formato e passa i meshes interi al builder.
- `bindings.cljs` / `implicit.cljs` — l'API `(color …)` resta identica.
- `registry.cljs` — il colore è già attaccato al mesh-map prima dell'export.
- `geo_server.rs` — il 3MF è generato browser-side; il server Rust scrive solo bytes.

### 2.2 Formato 3MF lato slicer

Bambu Studio e OrcaSlicer (entrambi derivati del codebase Slic3r/Prusa) riconoscono il **3MF Materials Extension** standard. Per assegnare un colore a un oggetto intero la forma più semplice e robusta è quella per-object (non per-face), tramite `pid`/`pindex` sull'`<object>` che puntano a un elemento `<basematerials>` nel core namespace.

XML generato (esempio con due mesh registrate `:supporto` rossa e `:scritta` blu):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter"
       xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <basematerials id="100">
      <base name="supporto" displaycolor="#FF0000FF"/>
      <base name="scritta"  displaycolor="#0066FFFF"/>
    </basematerials>

    <object id="1" type="model" name="supporto" pid="100" pindex="0">
      <mesh>...</mesh>
    </object>
    <object id="2" type="model" name="scritta"  pid="100" pindex="1">
      <mesh>...</mesh>
    </object>
  </resources>
  <build>
    <item objectid="1"/>
    <item objectid="2"/>
  </build>
</model>
```

Note sul formato:

- `<basematerials>` vive nel **core namespace**, non serve dichiarare `xmlns:m="…/material/2015/02"` (questo serve solo per `<m:colorgroup>`, `<m:texture2dgroup>` e simili che qui non usiamo).
- L'`id` di `<basematerials>` deve essere unico nello scope `resources`; uso `100` per non confliggere con gli `id` degli `<object>` (1, 2, …). Va bene qualsiasi valore non già usato.
- `displaycolor` è una stringa `#RRGGBBAA` (alpha sempre `FF` se l'opacità non è in scope).
- `name` sull'`<object>` è opzionale ma utile: Bambu lo mostra nell'object list sostituendo l'id numerico.
- L'attributo `name` su `<base>` è obbligatorio per spec ma usato dallo slicer solo come label.

### 2.3 API utente

**Non cambia nulla**. Il design già scelto (`color` come unica API) è già sufficiente:

```clojure
(register :supporto (box 20 20 10))
(register :scritta  (cyl :r 6 :h 15) :on :supporto)
(color :supporto 0xff0000)
(color :scritta  0x0066ff)
(export :supporto :scritta :3mf)
```

Il flusso interno:

1. `(color :supporto 0xff0000)` aggiorna `:material :color` sul mesh registrato in `scene-meshes`.
2. `(export … :3mf)` raccoglie le mesh e le passa a `threemf/meshes->3mf-blob`.
3. `meshes->model-xml` legge `:material :color` per ogni mesh, costruisce la `basematerials`, emette gli `<object>` con i puntatori.

Lo slicer all'apertura mostra il dialog "Standard 3MF Import color" e l'utente conferma la mappatura colore → slot AMS. L'assegnazione esatta `colore → slot N` è competenza dello slicer e non va cablata da Ridley (l'utente potrebbe avere il rosso in slot 3, non in slot 1: è una sua scelta locale).

**Estensione opzionale futura** (non implementare ora): se servisse forzare uno slot specifico, la strada non è inquinare l'API `color` ma aggiungere una funzione dedicata, per esempio `(filament :supporto 1)` che salvi un suggerimento in `:material :slot-hint`. Per emetterlo nel 3MF servirebbe però la sezione `<metadata>` proprietaria di Bambu (`Metadata/model_settings.config`), che è un'altra storia: complica il file e lo legano a un brand. Meglio fermarsi al colore standard.

### 2.4 Casi limite

| Caso | Comportamento proposto | Razionale |
|---|---|---|
| Una sola mesh, con colore | `<basematerials>` con un solo `<base>`, `pid`/`pindex` sull'object. | Caso uniforme col multipart. |
| Una sola mesh, senza colore | Nessuna `<basematerials>`, nessun `pid` (output identico a oggi). | Niente regressione né rumore in file da utenti che non usano i colori. |
| Più mesh, **nessuna** colorata | Nessuna `<basematerials>` (output identico a oggi). | Stesso ragionamento. |
| Più mesh, **alcune** colorate, **alcune** no | `<basematerials>` con i soli colori distinti presenti; gli object colorati ottengono `pid`/`pindex`, gli altri restano senza. | Lo slicer assegna il colore default a quelli senza pid: comportamento minimo-sorpresa. |
| Più mesh, **stesso** colore | Una sola `<base>` nel blocco, condivisa via stesso `pindex` sui vari object. | Allineato alla preferenza esplicitata nel brief: "stesso colore = stesso filamento". L'utente vede in slicer una sola assegnazione AMS che controlla più parti. Le parti restano comunque oggetti distinti (ID e `<item>` separati): l'utente le seleziona e le sposta indipendentemente. |
| Mesh con colore opaco e una con `:opacity < 1.0` | Per ora ignoriamo l'opacità: `displaycolor` finisce sempre con `FF`. | Bambu/Orca non stampano in trasparenza comunque; opacità è solo viewport. Eventualmente tradurla in alpha più avanti se serve. |

Sulla deduplicazione: la mappa `colore → indice` si costruisce in un singolo pass `(reduce …)` sui meshes, mantenendo l'ordine di prima apparizione. La chiave è l'intero hex.

### 2.5 Stima del lavoro

| Sotto-task | Tempo |
|---|---|
| Modifica `meshes->model-xml` + helper di dedup colori | 1–2 h |
| Test unit ClojureScript (3-4 casi: solo colorate, miste, stesso colore, single mesh) | 1 h |
| Test manuale di apertura in Bambu Studio + OrcaSlicer | 30 min |
| Aggiornamento `docs/Spec.md` (sezione export, nota sul multimateriale) | 30 min |
| Aggiornamento `docs/Architecture.md` (pipeline export) | 15 min |
| Buffer (gotcha XML, edge case in slicer) | 1–2 h |
| **Totale** | **mezza giornata** (4–6 h) |

Se si vuole anche aggiungere la guida utente (la guida-pilota della targhetta menzionata nel brief), aggiungere altre 2–4 h per scrittura e screenshot, indipendenti dal codice.

---

## 3. Vincoli e rischi

### 3.1 Vincoli del formato

- Il 3MF Core ammette N oggetti senza limite pratico; `<basematerials>` può contenere N entries. Non sono noti tetti rilevanti per Ridley (siamo nell'ordine di unità o decine di parti per progetto).
- L'unità è già `millimeter` come da spec: nessun cambiamento.
- `displaycolor` è sRGB 8-bit per canale; le sfumature sotto-quantizzate non sono supportate (irrilevante per il workflow filamento dove i colori sono discreti).

### 3.2 Rischi di compatibilità

- **Bambu Studio 2.5+ ha cambiato il behavior**. La [issue #9666 nel repo BambuStudio](https://github.com/bambulab/BambuStudio/issues/9666) segnala che dalla 2.5 il dialog "Standard 3MF Import color" converte i colori per-object in **Color Painting** (per-face) anziché preservare l'assegnazione object-level. Risultato pratico per l'utente: i colori arrivano comunque, ma il modello viene trattato come "monoblocco dipinto" invece che come "parti distinte assegnabili separatamente". È una regressione di UX lato Bambu su cui non possiamo agire dal lato Ridley; nelle versioni recenti il workaround è disattivare il dialog "Standard 3mf Import color" nelle preferenze. Vale la pena testare con la versione attualmente installata e documentare in guida quale versione si è validata.
- **OrcaSlicer**: condivide molto codice con Bambu, comportamento atteso analogo. Da verificare manualmente quale dialog mostra (è probabile che non abbia introdotto la stessa modifica della 2.5).
- **PrusaSlicer**: non installato sulla macchina di sviluppo. La spec 3MF Materials Extension è pubblica e Prusa la legge da anni, ma il dialog di import e l'assegnazione automatica al slot potrebbero differire. Da verificare quando si vuole dichiarare ufficialmente il supporto.

### 3.3 Punti su cui non sono sicuro e che vale la pena discutere prima di implementare

1. **Bambu standard 3MF vs Bambu project 3MF**. Bambu ha un suo formato proprietario per i progetti (`.gcode.3mf`/project) che include `Metadata/model_settings.config`, configurazioni di stampa, mappature AMS pre-fatte e altro. Quello standard (quello che proponiamo) è meno potente ma portabile. Se il target esclusivo di Ridley fosse Bambu, varrebbe la pena valutare il loro formato; ma sembra più sano restare sul formato standard, che funziona bene anche su Orca/Prusa. **Domanda per discussione**: confermiamo che vogliamo restare sullo standard?

2. **Cosa succede se due basematerials hanno lo stesso `displaycolor` ma `name` diverso**. La spec non lo vieta; gli slicer dovrebbero trattarli come due materiali distinti che casualmente hanno lo stesso colore. Ma è ambiguo come venga proposta la mappatura AMS. La nostra strategia "stesso colore = stessa entry" elude il problema, ma se l'utente in futuro registra colori "logicamente diversi" che si ritrovano allo stesso hex (per es. due rossi leggermente diversi che diventano stesso `0xff0000` per arrotondamento), li unisce. Trade-off accettabile?

3. **Vale la pena emettere `name` sugli `<object>` solo se la mesh è registrata?** Per le mesh anonime non c'è un nome significativo. Si potrebbe sintetizzare `"object-1"`, `"object-2"`, ma rischia di essere rumore inutile. Proposta: emettere `name` solo se la mesh ha un `:name` esplicito (registrata), altrimenti omettere l'attributo (lo slicer userà l'`objectid`).

4. **Validazione del 3MF prodotto**. Esistono validatori CLI (`lib3mf` ha un tool di check) ma nessun setup esistente in Ridley. Per ora andiamo a sentimento + apertura in slicer; se in futuro vogliamo CI sul formato, è uno step a parte.

---

## Output di questa analisi

- ✅ Capito che il problema è limitato al solo serializer XML in `threemf.cljs`.
- ✅ Trovato un design implementabile in mezza giornata, no breaking change all'API utente.
- ⚠️ Test in slicer ancora da fare visivamente: file `/tmp/3mf-test/test-current.3mf` e `/tmp/3mf-test/test-with-basematerials.3mf` pronti per apertura manuale.

Quando vuoi procedere con l'implementazione, basta dirlo: il diff su `threemf.cljs` è di poche decine di righe.

---

## 4. Workaround Bambu/Orca (2026-05-03)

### 4.1 Cosa abbiamo scoperto a posteriori

Con BambuStudio 2.5.0.66 e OrcaSlicer corrente:

- `<basematerials>` standard viene **letto come metadata** ma **non usato per assegnare colori** all'import. Sia il file Ridley sia il reference Python `test-with-basematerials.3mf` arrivano grigi.
- Né BambuStudio né Orca espongono una preference per ripristinare il vecchio comportamento.
- Il dialog "Standard 3MF Import Color" descritto nella wiki Bambu non appare più.

Il rischio §3.2 è quindi **realtà**, non più rischio.

### 4.2 Sample del formato Bambu project

Quando BambuStudio salva una scena dopo aver assegnato manualmente i filament, produce un ZIP che include:

- `3D/3dmodel.model` riscritto con `xmlns:p` (production extension), `xmlns:BambuStudio`, e oggetti riferiti via `<component p:path="/3D/Objects/object_N.model">`.
- `3D/Objects/object_N.model` con i veri vertices/triangles.
- `Metadata/model_settings.config` proprietario, con la struttura:

  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <config>
    <object id="3">
      <metadata key="name" value="..."/>
      <metadata key="extruder" value="3"/>
      <part id="1" subtype="normal_part">
        <metadata key="name" value="supporto"/>
        <metadata key="extruder" value="1"/>
        ...
      </part>
      <part id="2" subtype="normal_part">
        <metadata key="name" value="scritta"/>
        <metadata key="extruder" value="2"/>
        ...
      </part>
    </object>
  </config>
  ```

Sample disponibile in `/tmp/test-with-basematerials-salvato-da-BambuStudio.3mf`.

### 4.3 Strategia adottata

Ridley non genera il formato project completo (sub-files + UUID + production
extension) — sarebbe un investimento sproporzionato. Si limita a:

1. Mantenere lo standard 3MF Core con `<basematerials>` (stato di prima della
   sessione).
2. Aggiungere `Metadata/model_settings.config` con un `<object>` per mesh
   colorata e `<metadata key="extruder" value="N"/>`. **Nessun `<part>` annidato**:
   ogni mesh resta un oggetto separato a livello del modello principale, e Bambu
   onora l'extruder a quel livello.
3. Aggiornare `[Content_Types].xml` per includere `Default Extension="config"`.

Test pratico (verificato sul cubo bicolor `/tmp/3mf-test/test-extruder-per-object.3mf`):
i due cubi arrivano in BambuStudio già assegnati a filament 1 e filament 2, senza
dialog né conferme dell'utente.

### 4.4 Quello che NON facciamo

- **Non emettiamo il production extension** (sub-files): aggiungerebbe complessità (3 file XML invece di 1, gestione UUID, rels aggiuntivi) per zero guadagno utente.
- **Non emettiamo `<part>` annidati**: Bambu li usa quando una scena viene fusa in un super-oggetto multi-parte; in Ridley le mesh registrate restano semanticamente oggetti separati.
- **Non emettiamo `Metadata/project_settings.config`** (54k byte di config di stampa): vincolerebbe il file a un printer model specifico. L'utente importa il file su qualsiasi profilo printer.
- **Non emettiamo `xmlns:BambuStudio`**: il file resta aperto dichiarativamente come 3MF standard; è solo l'aggiunta di un file di metadata che lo rende anche "letto da Bambu".

### 4.5 Verifica su altri slicer

| Slicer | `<basematerials>` | `model_settings.config` | Risultato attuale |
|---|---|---|---|
| BambuStudio 2.5.0.66 | ignora (mappato a slot 1) | onora `extruder` per object | ✅ Funziona |
| OrcaSlicer corrente | ignora | onora `extruder` per object (presumibile, codice condiviso con Bambu) | ✅ Atteso |
| PrusaSlicer | onora (la spec è standard) | ignora (file proprietario sconosciuto) | ⚠️ Da verificare |
| Cura, IdeaMaker, altri | dipende; molti ignorano basematerials | ignorano | ⚠️ Probabilmente grigi |

Per gli slicer della terza colonna, il workflow è "carica → assegna manualmente". È lo stato di partenza che abbiamo migliorato per Bambu/Orca senza peggiorare niente per gli altri.
