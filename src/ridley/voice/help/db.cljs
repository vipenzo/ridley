(ns ridley.voice.help.db
  "Help system database: all Ridley and Clojure symbols with templates and docs.
   Three tiers: Ridley DSL (~45), Clojure Base (~35), Clojure Extended (~50).")

;; ============================================================
;; Tier definitions
;; ============================================================

(def tiers
  {:ridley           {:order 1 :name {:it "Ridley DSL" :en "Ridley DSL"}}
   :clojure-base     {:order 2 :name {:it "Clojure Base" :en "Clojure Base"}}
   :clojure-extended {:order 3 :name {:it "Clojure Esteso" :en "Clojure Extended"}}})

;; ============================================================
;; Help entries — all symbols with templates, docs, aliases
;; ============================================================

(def help-entries
  {;; ============================================================
   ;; TIER 1: Ridley DSL
   ;; ============================================================

   ;; --- Movimento Turtle ---
   :f               {:tier :ridley :category :movement
                     :template "(f 20)"
                     :doc {:it "Muove avanti (negativo = indietro)" :en "Move forward (negative = backward)"}
                     :aliases {:it ["avanti"] :en ["forward"]}}
   :th              {:tier :ridley :category :movement
                     :template "(th 45)"
                     :doc {:it "Ruota orizzontale (yaw)" :en "Turn horizontal (yaw)"}
                     :aliases {:it ["ruota" "gira"] :en ["turn"]}}
   :tv              {:tier :ridley :category :movement
                     :template "(tv 45)"
                     :doc {:it "Ruota verticale (pitch)" :en "Turn vertical (pitch)"}
                     :aliases {:it ["alza" "abbassa"] :en ["pitch"]}}
   :tr              {:tier :ridley :category :movement
                     :template "(tr 45)"
                     :doc {:it "Ruota su asse (roll)" :en "Roll"}
                     :aliases {:it ["rollio"] :en ["roll"]}}
   :arc-h           {:tier :ridley :category :movement
                     :template "(arc-h 20 90)"
                     :doc {:it "Arco orizzontale" :en "Horizontal arc"}
                     :aliases {:it ["arco"] :en ["arc"]}}
   :arc-v           {:tier :ridley :category :movement
                     :template "(arc-v 20 90)"
                     :doc {:it "Arco verticale" :en "Vertical arc"}
                     :aliases {:it ["arco verticale"] :en ["vertical arc"]}}
   :bezier-to       {:tier :ridley :category :movement
                     :template "(bezier-to [50 30 0])"
                     :doc {:it "Curva bezier verso punto" :en "Bezier curve to point"}
                     :aliases {:it ["bezier"] :en ["bezier"]}}
   :bezier-to-anchor {:tier :ridley :category :movement
                      :template "(bezier-to-anchor :nome)"
                      :doc {:it "Bezier verso anchor" :en "Bezier to anchor"}
                      :aliases {:it [] :en []}}
   :bezier-as       {:tier :ridley :category :movement
                     :template "(bezier-as my-path)"
                     :doc {:it "Smoothing bezier di un path" :en "Bezier smoothing of a path"}
                     :aliases {:it [] :en []}}

   ;; --- Primitive 3D ---
   :box             {:tier :ridley :category :primitives
                     :template "(box 20)"
                     :doc {:it "Cubo o parallelepipedo" :en "Box or cuboid"}
                     :aliases {:it ["cubo" "scatola"] :en ["cube"]}}
   :sphere          {:tier :ridley :category :primitives
                     :template "(sphere 15)"
                     :doc {:it "Sfera" :en "Sphere"}
                     :aliases {:it ["sfera"] :en []}}
   :cyl             {:tier :ridley :category :primitives
                     :template "(cyl 10 30)"
                     :doc {:it "Cilindro" :en "Cylinder"}
                     :aliases {:it ["cilindro"] :en ["cylinder"]}}
   :cone            {:tier :ridley :category :primitives
                     :template "(cone 15 5 30)"
                     :doc {:it "Cono o tronco di cono" :en "Cone or frustum"}
                     :aliases {:it ["cono"] :en []}}

   ;; --- Forme 2D ---
   :circle          {:tier :ridley :category :shapes
                     :template "(circle 10)"
                     :doc {:it "Cerchio" :en "Circle"}
                     :aliases {:it ["cerchio"] :en []}}
   :rect            {:tier :ridley :category :shapes
                     :template "(rect 20 10)"
                     :doc {:it "Rettangolo" :en "Rectangle"}
                     :aliases {:it ["rettangolo"] :en ["rectangle"]}}
   :polygon         {:tier :ridley :category :shapes
                     :template "(polygon 6 15)"
                     :doc {:it "Poligono regolare" :en "Regular polygon"}
                     :aliases {:it ["poligono"] :en []}}
   :star            {:tier :ridley :category :shapes
                     :template "(star 5 20 10)"
                     :doc {:it "Stella" :en "Star shape"}
                     :aliases {:it ["stella"] :en []}}
   :shape           {:tier :ridley :category :shapes
                     :template "(shape (f 10) (th 120) (f 10) (th 120) (f 10))"
                     :doc {:it "Forma da percorso turtle" :en "Shape from turtle path"}
                     :aliases {:it ["forma"] :en []}}
   :stroke-shape    {:tier :ridley :category :shapes
                     :template "(stroke-shape my-path 2)"
                     :doc {:it "Converte path in outline 2D" :en "Stroke path to 2D outline"}
                     :aliases {:it ["contorno"] :en ["stroke"]}}
   :path-to-shape   {:tier :ridley :category :shapes
                     :template "(path-to-shape my-path)"
                     :doc {:it "Proietta path 3D su piano XY" :en "Project 3D path to XY plane"}
                     :aliases {:it [] :en []}}

   ;; --- Path ---
   :path            {:tier :ridley :category :paths
                     :template "(path (f 20) (th 90) (f 20))"
                     :doc {:it "Registra percorso" :en "Record path"}
                     :aliases {:it ["percorso" "cammino"] :en []}}
   :quick-path      {:tier :ridley :category :paths
                     :template "(quick-path 20 90 30 -45 10)"
                     :doc {:it "Path da notazione compatta" :en "Path from compact notation"}
                     :aliases {:it [] :en []}}
   :qp              {:tier :ridley :category :paths
                     :template "(qp 20 90 30)"
                     :doc {:it "Alias di quick-path" :en "Alias for quick-path"}
                     :aliases {:it [] :en []}}
   :follow          {:tier :ridley :category :paths
                     :template "(follow other-path)"
                     :doc {:it "Splice path dentro path" :en "Splice path inside path"}
                     :aliases {:it ["segui"] :en []}}
   :mark            {:tier :ridley :category :paths
                     :template "(mark :nome)"
                     :doc {:it "Marca posizione (dentro path)" :en "Mark position (inside path)"}
                     :aliases {:it ["marca"] :en []}}
   :follow-path     {:tier :ridley :category :paths
                     :template "(follow-path my-path)"
                     :doc {:it "Esegue path sulla turtle" :en "Execute path on turtle"}
                     :aliases {:it [] :en []}}

   ;; --- Anchor e Navigazione ---
   :with-path       {:tier :ridley :category :anchors
                     :template "(with-path skeleton\n  (goto :nome))"
                     :doc {:it "Risolve mark di un path" :en "Resolve marks of a path"}
                     :aliases {:it [] :en []}}
   :goto            {:tier :ridley :category :anchors
                     :template "(goto :nome)"
                     :doc {:it "Vai ad anchor" :en "Go to anchor"}
                     :aliases {:it ["vai"] :en ["go"]}}
   :look-at         {:tier :ridley :category :anchors
                     :template "(look-at :nome)"
                     :doc {:it "Orienta verso anchor" :en "Look at anchor"}
                     :aliases {:it ["guarda"] :en ["look"]}}
   :path-to         {:tier :ridley :category :anchors
                     :template "(path-to :nome)"
                     :doc {:it "Path verso anchor" :en "Path to anchor"}
                     :aliases {:it [] :en []}}
   :get-anchor      {:tier :ridley :category :anchors
                     :template "(get-anchor :nome)"
                     :doc {:it "Dati anchor" :en "Get anchor data"}
                     :aliases {:it [] :en []}}

   ;; --- Operazioni Generative ---
   :extrude         {:tier :ridley :category :operations
                     :template "(extrude (circle 5) (f 30))"
                     :doc {:it "Estrude forma lungo percorso" :en "Extrude shape along path"}
                     :aliases {:it ["estrudi" "estrusione"] :en []}}
   :extrude-closed  {:tier :ridley :category :operations
                     :template "(extrude-closed (circle 5) square-path)"
                     :doc {:it "Estrusione chiusa (torus)" :en "Closed extrusion (torus)"}
                     :aliases {:it [] :en []}}
   :loft            {:tier :ridley :category :operations
                     :template "(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))"
                     :doc {:it "Estrusione con trasformazione" :en "Extrude with shape transform"}
                     :aliases {:it [] :en []}}
   :loft-n          {:tier :ridley :category :operations
                     :template "(loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30))"
                     :doc {:it "Loft con N steps" :en "Loft with N steps"}
                     :aliases {:it [] :en []}}
   :revolve         {:tier :ridley :category :operations
                     :template "(revolve (circle 5))"
                     :doc {:it "Rivoluzione" :en "Revolution/lathe"}
                     :aliases {:it ["rivoluzione"] :en ["lathe"]}}

   ;; --- Stato Turtle ---
   :reset           {:tier :ridley :category :turtle-state
                     :template "(reset)"
                     :doc {:it "Reset turtle" :en "Reset turtle"}
                     :aliases {:it [] :en []}}
   :push-state      {:tier :ridley :category :turtle-state
                     :template "(push-state)"
                     :doc {:it "Salva stato su stack" :en "Save state to stack"}
                     :aliases {:it ["salva"] :en ["save" "push"]}}
   :pop-state       {:tier :ridley :category :turtle-state
                     :template "(pop-state)"
                     :doc {:it "Ripristina stato" :en "Restore state"}
                     :aliases {:it ["ripristina"] :en ["restore" "pop"]}}
   :clear-stack     {:tier :ridley :category :turtle-state
                     :template "(clear-stack)"
                     :doc {:it "Svuota stack" :en "Clear stack"}
                     :aliases {:it [] :en []}}
   :pen             {:tier :ridley :category :turtle-state
                     :template "(pen :on)"
                     :doc {:it "Controllo penna" :en "Pen control"}
                     :aliases {:it ["penna"] :en []}}
   :get-turtle      {:tier :ridley :category :turtle-state
                     :template "(get-turtle)"
                     :doc {:it "Stato turtle completo" :en "Full turtle state"}
                     :aliases {:it [] :en []}}
   :turtle-position {:tier :ridley :category :turtle-state
                     :template "(turtle-position)"
                     :doc {:it "Posizione corrente" :en "Current position"}
                     :aliases {:it ["posizione"] :en ["position"]}}
   :turtle-heading  {:tier :ridley :category :turtle-state
                     :template "(turtle-heading)"
                     :doc {:it "Heading corrente" :en "Current heading"}
                     :aliases {:it [] :en ["heading"]}}
   :turtle-up       {:tier :ridley :category :turtle-state
                     :template "(turtle-up)"
                     :doc {:it "Vettore up corrente" :en "Current up vector"}
                     :aliases {:it [] :en []}}
   :attached?       {:tier :ridley :category :turtle-state
                     :template "(attached?)"
                     :doc {:it "Turtle è attached?" :en "Is turtle attached?"}
                     :aliases {:it [] :en []}}
   :last-mesh       {:tier :ridley :category :turtle-state
                     :template "(last-mesh)"
                     :doc {:it "Ultima mesh creata" :en "Last created mesh"}
                     :aliases {:it [] :en []}}

   ;; --- Registry ---
   :register        {:tier :ridley :category :registry
                     :template "(register :nome mesh)"
                     :doc {:it "Registra mesh con nome" :en "Register mesh by name"}
                     :aliases {:it ["registra"] :en []}}
   :show!           {:tier :ridley :category :registry
                     :template "(show! :nome)"
                     :doc {:it "Mostra mesh" :en "Show mesh"}
                     :aliases {:it ["mostra"] :en ["show"]}}
   :hide!           {:tier :ridley :category :registry
                     :template "(hide! :nome)"
                     :doc {:it "Nascondi mesh" :en "Hide mesh"}
                     :aliases {:it ["nascondi"] :en ["hide"]}}
   :show-all!       {:tier :ridley :category :registry
                     :template "(show-all!)"
                     :doc {:it "Mostra tutto" :en "Show all"}
                     :aliases {:it [] :en []}}
   :hide-all!       {:tier :ridley :category :registry
                     :template "(hide-all!)"
                     :doc {:it "Nascondi tutto" :en "Hide all"}
                     :aliases {:it [] :en []}}

   ;; --- Boolean (Manifold) ---
   :mesh-union        {:tier :ridley :category :boolean
                       :template "(mesh-union a b)"
                       :doc {:it "Unione" :en "Union"}
                       :aliases {:it ["unione"] :en ["union"]}}
   :mesh-difference   {:tier :ridley :category :boolean
                       :template "(mesh-difference base tool)"
                       :doc {:it "Sottrazione" :en "Difference"}
                       :aliases {:it ["sottrazione" "differenza"] :en ["difference" "subtract"]}}
   :mesh-intersection {:tier :ridley :category :boolean
                       :template "(mesh-intersection a b)"
                       :doc {:it "Intersezione" :en "Intersection"}
                       :aliases {:it ["intersezione"] :en ["intersection"]}}
   :mesh-hull         {:tier :ridley :category :boolean
                       :template "(mesh-hull a b)"
                       :doc {:it "Convex hull" :en "Convex hull"}
                       :aliases {:it [] :en ["hull"]}}

   ;; --- Trasformazioni Mesh ---
   :attach          {:tier :ridley :category :mesh-transform
                     :template "(attach mesh (f 10) (th 45))"
                     :doc {:it "Trasforma mesh in place" :en "Transform mesh in place"}
                     :aliases {:it ["attacca"] :en []}}
   :clone           {:tier :ridley :category :mesh-transform
                     :template "(clone mesh (f 10) (th 45))"
                     :doc {:it "Copia trasformata" :en "Transformed copy"}
                     :aliases {:it ["clona"] :en []}}
   :attach-face     {:tier :ridley :category :mesh-transform
                     :template "(attach-face mesh :top (f 10))"
                     :doc {:it "Muove faccia" :en "Move face"}
                     :aliases {:it [] :en []}}
   :clone-face      {:tier :ridley :category :mesh-transform
                     :template "(clone-face mesh :top (f 10))"
                     :doc {:it "Estrude faccia" :en "Extrude face"}
                     :aliases {:it [] :en []}}
   :inset           {:tier :ridley :category :mesh-transform
                     :template "(inset 3)"
                     :doc {:it "Inset faccia (dentro attach/clone-face)" :en "Inset face (inside attach/clone-face)"}
                     :aliases {:it [] :en []}}

   ;; --- Trasformazioni Forma 2D ---
   :scale           {:tier :ridley :category :shape-transform
                     :template "(scale shape 0.5)"
                     :doc {:it "Scala forma" :en "Scale shape"}
                     :aliases {:it ["scala"] :en []}}
   :rotate-shape    {:tier :ridley :category :shape-transform
                     :template "(rotate-shape shape 45)"
                     :doc {:it "Ruota forma" :en "Rotate shape"}
                     :aliases {:it [] :en []}}
   :translate       {:tier :ridley :category :shape-transform
                     :template "(translate shape 10 5)"
                     :doc {:it "Trasla forma" :en "Translate shape"}
                     :aliases {:it [] :en []}}
   :translate-shape {:tier :ridley :category :shape-transform
                     :template "(translate-shape shape 10 5)"
                     :doc {:it "Alias translate" :en "Alias for translate"}
                     :aliases {:it [] :en []}}
   :morph           {:tier :ridley :category :shape-transform
                     :template "(morph shape-a shape-b 0.5)"
                     :doc {:it "Interpola forme" :en "Interpolate shapes"}
                     :aliases {:it [] :en []}}
   :resample        {:tier :ridley :category :shape-transform
                     :template "(resample shape 32)"
                     :doc {:it "Ricampiona a n punti" :en "Resample to n points"}
                     :aliases {:it [] :en []}}

   ;; --- Colore e Materiale ---
   :color           {:tier :ridley :category :material
                     :template "(color 0xff0000)"
                     :doc {:it "Imposta colore" :en "Set color"}
                     :aliases {:it ["colore"] :en []}}
   :material        {:tier :ridley :category :material
                     :template "(material :metalness 0.8 :roughness 0.2)"
                     :doc {:it "Imposta materiale" :en "Set material"}
                     :aliases {:it ["materiale"] :en []}}
   :reset-material  {:tier :ridley :category :material
                     :template "(reset-material)"
                     :doc {:it "Reset materiale default" :en "Reset to default material"}
                     :aliases {:it [] :en []}}

   ;; --- Panel (Text Billboard) ---
   :panel           {:tier :ridley :category :panel
                     :template "(panel 40 20)"
                     :doc {:it "Crea pannello testo 3D" :en "Create 3D text panel"}
                     :aliases {:it ["pannello"] :en []}}
   :out             {:tier :ridley :category :panel
                     :template "(out :nome \"testo\")"
                     :doc {:it "Scrive su pannello" :en "Write to panel"}
                     :aliases {:it ["scrivi"] :en ["write"]}}
   :append          {:tier :ridley :category :panel
                     :template "(append :nome \"testo\")"
                     :doc {:it "Aggiunge a pannello" :en "Append to panel"}
                     :aliases {:it [] :en []}}
   :clear           {:tier :ridley :category :panel
                     :template "(clear :nome)"
                     :doc {:it "Svuota pannello" :en "Clear panel"}
                     :aliases {:it ["pulisci"] :en []}}

   ;; --- Face Operations ---
   :list-faces      {:tier :ridley :category :faces
                     :template "(list-faces mesh)"
                     :doc {:it "Lista facce" :en "List faces"}
                     :aliases {:it [] :en ["faces"]}}
   :face-ids        {:tier :ridley :category :faces
                     :template "(face-ids mesh)"
                     :doc {:it "Solo ID facce" :en "Face IDs only"}
                     :aliases {:it [] :en []}}
   :face-info       {:tier :ridley :category :faces
                     :template "(face-info mesh :top)"
                     :doc {:it "Info dettagliate" :en "Detailed face info"}
                     :aliases {:it [] :en []}}
   :get-face        {:tier :ridley :category :faces
                     :template "(get-face mesh :top)"
                     :doc {:it "Info faccia" :en "Get face info"}
                     :aliases {:it [] :en []}}
   :flash-face      {:tier :ridley :category :faces
                     :template "(flash-face mesh :top)"
                     :doc {:it "Evidenzia temporaneo" :en "Flash highlight face"}
                     :aliases {:it [] :en ["flash"]}}
   :highlight-face  {:tier :ridley :category :faces
                     :template "(highlight-face mesh :top)"
                     :doc {:it "Evidenzia permanente" :en "Highlight face"}
                     :aliases {:it ["evidenzia"] :en ["highlight"]}}
   :clear-highlights {:tier :ridley :category :faces
                      :template "(clear-highlights)"
                      :doc {:it "Rimuovi evidenziazioni" :en "Clear highlights"}
                      :aliases {:it [] :en []}}

   ;; --- Settings ---
   :resolution      {:tier :ridley :category :settings
                     :template "(resolution :n 32)"
                     :doc {:it "Risoluzione curve" :en "Curve resolution"}
                     :aliases {:it ["risoluzione"] :en []}}
   :joint-mode      {:tier :ridley :category :settings
                     :template "(joint-mode :round)"
                     :doc {:it "Modo giunture" :en "Joint mode"}
                     :aliases {:it ["giuntura"] :en ["joint"]}}

   ;; --- Export ---
   :export          {:tier :ridley :category :export
                     :template "(export :nome)"
                     :doc {:it "Esporta STL" :en "Export STL"}
                     :aliases {:it ["esporta"] :en []}}

   ;; --- Debug ---
   :println         {:tier :ridley :category :debug
                     :template "(println \"debug:\" x)"
                     :doc {:it "Stampa con newline" :en "Print with newline"}
                     :aliases {:it ["stampa"] :en ["print"]}}
   :print           {:tier :ridley :category :debug
                     :template "(print \"no newline\")"
                     :doc {:it "Stampa senza newline" :en "Print without newline"}
                     :aliases {:it [] :en []}}
   :prn             {:tier :ridley :category :debug
                     :template "(prn {:a 1})"
                     :doc {:it "Stampa struttura dati" :en "Print data structure"}
                     :aliases {:it [] :en []}}
   :log             {:tier :ridley :category :debug
                     :template "(log \"debug\" x)"
                     :doc {:it "Output a console browser" :en "Output to browser console"}
                     :aliases {:it [] :en []}}
   :T               {:tier :ridley :category :debug
                     :template "(T \"label\" expr)"
                     :doc {:it "Tap: stampa e ritorna valore" :en "Tap: print and return value"}
                     :aliases {:it [] :en ["tap"]}}

   ;; ============================================================
   ;; TIER 2: Clojure Base
   ;; ============================================================

   ;; --- Definizioni ---
   :defn            {:tier :clojure-base :category :definitions
                     :template "(defn nome [x]\n  |)"
                     :doc {:it "Definisce funzione" :en "Define function"}
                     :aliases {:it ["funzione" "definisci"] :en ["function" "define"]}}
   :def             {:tier :clojure-base :category :definitions
                     :template "(def nome valore)"
                     :doc {:it "Definisce variabile" :en "Define variable"}
                     :aliases {:it ["variabile"] :en ["variable"]}}
   :let             {:tier :clojure-base :category :definitions
                     :template "(let [x 10]\n  |)"
                     :doc {:it "Binding locali" :en "Local bindings"}
                     :aliases {:it ["sia"] :en []}}
   :fn              {:tier :clojure-base :category :definitions
                     :template "(fn [x] |)"
                     :doc {:it "Funzione anonima" :en "Anonymous function"}
                     :aliases {:it [] :en ["lambda"]}}
   :defonce         {:tier :clojure-base :category :definitions
                     :template "(defonce nome valore)"
                     :doc {:it "Definisce una volta" :en "Define once"}
                     :aliases {:it [] :en []}}

   ;; --- Condizionali ---
   :if              {:tier :clojure-base :category :conditionals
                     :template "(if condizione\n  allora\n  altrimenti)"
                     :doc {:it "Se-allora-altrimenti" :en "If-then-else"}
                     :aliases {:it ["se"] :en []}}
   :when            {:tier :clojure-base :category :conditionals
                     :template "(when condizione\n  |)"
                     :doc {:it "Quando (senza else)" :en "When (no else)"}
                     :aliases {:it ["quando"] :en []}}
   :cond            {:tier :clojure-base :category :conditionals
                     :template "(cond\n  test1 risultato1\n  test2 risultato2\n  :else default)"
                     :doc {:it "Condizioni multiple" :en "Multiple conditions"}
                     :aliases {:it ["condizione"] :en []}}
   :case            {:tier :clojure-base :category :conditionals
                     :template "(case valore\n  val1 risultato1\n  val2 risultato2\n  default)"
                     :doc {:it "Match esatto" :en "Exact match"}
                     :aliases {:it ["caso"] :en []}}
   :if-let          {:tier :clojure-base :category :conditionals
                     :template "(if-let [x (something)]\n  (use x)\n  fallback)"
                     :doc {:it "If con binding" :en "If with binding"}
                     :aliases {:it [] :en []}}
   :when-let        {:tier :clojure-base :category :conditionals
                     :template "(when-let [x (something)]\n  (use x))"
                     :doc {:it "When con binding" :en "When with binding"}
                     :aliases {:it [] :en []}}

   ;; --- Iterazione ---
   :for             {:tier :clojure-base :category :iteration
                     :template "(for [x coll]\n  |)"
                     :doc {:it "List comprehension" :en "List comprehension"}
                     :aliases {:it ["per"] :en []}}
   :doseq           {:tier :clojure-base :category :iteration
                     :template "(doseq [x coll]\n  |)"
                     :doc {:it "Iterazione side-effect" :en "Side-effect iteration"}
                     :aliases {:it [] :en []}}
   :dotimes         {:tier :clojure-base :category :iteration
                     :template "(dotimes [i 10]\n  |)"
                     :doc {:it "Ripeti N volte" :en "Repeat N times"}
                     :aliases {:it ["ripeti"] :en ["repeat"]}}
   :loop            {:tier :clojure-base :category :iteration
                     :template "(loop [i 0]\n  (when (< i 10)\n    |\n    (recur (inc i))))"
                     :doc {:it "Loop con recur" :en "Loop with recur"}
                     :aliases {:it ["ciclo"] :en []}}
   :recur           {:tier :clojure-base :category :iteration
                     :template "(recur (inc i))"
                     :doc {:it "Ricorsione in coda" :en "Tail recursion"}
                     :aliases {:it [] :en []}}

   ;; --- Sequenze Base ---
   :map             {:tier :clojure-base :category :sequences
                     :template "(map f coll)"
                     :doc {:it "Applica f a ogni elemento" :en "Apply f to each element"}
                     :aliases {:it ["mappa"] :en []}}
   :filter          {:tier :clojure-base :category :sequences
                     :template "(filter pred coll)"
                     :doc {:it "Filtra elementi" :en "Filter elements"}
                     :aliases {:it ["filtra"] :en []}}
   :reduce          {:tier :clojure-base :category :sequences
                     :template "(reduce f init coll)"
                     :doc {:it "Riduce collezione" :en "Reduce collection"}
                     :aliases {:it ["riduci"] :en []}}
   :first           {:tier :clojure-base :category :sequences
                     :template "(first coll)"
                     :doc {:it "Primo elemento" :en "First element"}
                     :aliases {:it ["primo"] :en []}}
   :last            {:tier :clojure-base :category :sequences
                     :template "(last coll)"
                     :doc {:it "Ultimo elemento" :en "Last element"}
                     :aliases {:it ["ultimo"] :en []}}
   :rest            {:tier :clojure-base :category :sequences
                     :template "(rest coll)"
                     :doc {:it "Tutto tranne primo" :en "All but first"}
                     :aliases {:it [] :en []}}
   :nth             {:tier :clojure-base :category :sequences
                     :template "(nth coll 2)"
                     :doc {:it "Elemento alla posizione" :en "Element at index"}
                     :aliases {:it [] :en []}}
   :count           {:tier :clojure-base :category :sequences
                     :template "(count coll)"
                     :doc {:it "Numero elementi" :en "Number of elements"}
                     :aliases {:it ["conta"] :en []}}
   :range           {:tier :clojure-base :category :sequences
                     :template "(range 10)"
                     :doc {:it "Sequenza numeri" :en "Number sequence"}
                     :aliases {:it ["intervallo"] :en []}}

   ;; --- Aritmetica ---
   :+               {:tier :clojure-base :category :arithmetic
                     :template "(+ 1 2)"
                     :doc {:it "Somma" :en "Addition"}
                     :aliases {:it ["somma" "più"] :en ["add" "plus"]}}
   :-               {:tier :clojure-base :category :arithmetic
                     :template "(- 10 3)"
                     :doc {:it "Sottrazione" :en "Subtraction"}
                     :aliases {:it ["meno"] :en ["minus" "subtract"]}}
   :*               {:tier :clojure-base :category :arithmetic
                     :template "(* 2 3)"
                     :doc {:it "Moltiplicazione" :en "Multiplication"}
                     :aliases {:it ["per" "moltiplicazione"] :en ["times" "multiply"]}}
   (keyword "/")    {:tier :clojure-base :category :arithmetic
                     :template "(/ 10 2)"
                     :doc {:it "Divisione" :en "Division"}
                     :aliases {:it ["diviso" "divisione"] :en ["divide"]}}
   :inc             {:tier :clojure-base :category :arithmetic
                     :template "(inc x)"
                     :doc {:it "Incrementa di 1" :en "Increment by 1"}
                     :aliases {:it [] :en ["increment"]}}
   :dec             {:tier :clojure-base :category :arithmetic
                     :template "(dec x)"
                     :doc {:it "Decrementa di 1" :en "Decrement by 1"}
                     :aliases {:it [] :en ["decrement"]}}
   :mod             {:tier :clojure-base :category :arithmetic
                     :template "(mod 10 3)"
                     :doc {:it "Modulo" :en "Modulo"}
                     :aliases {:it [] :en ["modulo"]}}
   :max             {:tier :clojure-base :category :arithmetic
                     :template "(max 1 5 3)"
                     :doc {:it "Massimo" :en "Maximum"}
                     :aliases {:it ["massimo"] :en ["maximum"]}}
   :min             {:tier :clojure-base :category :arithmetic
                     :template "(min 1 5 3)"
                     :doc {:it "Minimo" :en "Minimum"}
                     :aliases {:it ["minimo"] :en ["minimum"]}}

   ;; --- Math ---
   :PI              {:tier :clojure-base :category :math
                     :template "PI"
                     :doc {:it "Pi greco" :en "Pi constant"}
                     :aliases {:it ["pi greco"] :en ["pi"]}}
   :sin             {:tier :clojure-base :category :math
                     :template "(sin x)"
                     :doc {:it "Seno" :en "Sine"}
                     :aliases {:it ["seno"] :en ["sine"]}}
   :cos             {:tier :clojure-base :category :math
                     :template "(cos x)"
                     :doc {:it "Coseno" :en "Cosine"}
                     :aliases {:it ["coseno"] :en ["cosine"]}}
   :sqrt            {:tier :clojure-base :category :math
                     :template "(sqrt x)"
                     :doc {:it "Radice quadrata" :en "Square root"}
                     :aliases {:it ["radice"] :en ["root"]}}
   :pow             {:tier :clojure-base :category :math
                     :template "(pow x y)"
                     :doc {:it "Potenza" :en "Power"}
                     :aliases {:it ["potenza"] :en ["power"]}}
   :abs             {:tier :clojure-base :category :math
                     :template "(abs -5)"
                     :doc {:it "Valore assoluto" :en "Absolute value"}
                     :aliases {:it [] :en ["absolute"]}}
   :floor           {:tier :clojure-base :category :math
                     :template "(floor x)"
                     :doc {:it "Arrotonda giù" :en "Round down"}
                     :aliases {:it [] :en []}}
   :ceil            {:tier :clojure-base :category :math
                     :template "(ceil x)"
                     :doc {:it "Arrotonda su" :en "Round up"}
                     :aliases {:it [] :en []}}
   :round           {:tier :clojure-base :category :math
                     :template "(round x)"
                     :doc {:it "Arrotonda" :en "Round"}
                     :aliases {:it ["arrotonda"] :en []}}
   :atan2           {:tier :clojure-base :category :math
                     :template "(atan2 y x)"
                     :doc {:it "Arctangent a due argomenti" :en "Two-argument arctangent"}
                     :aliases {:it [] :en []}}

   ;; --- Confronto ---
   :=               {:tier :clojure-base :category :comparison
                     :template "(= a b)"
                     :doc {:it "Uguaglianza" :en "Equality"}
                     :aliases {:it ["uguale"] :en ["equal" "equals"]}}
   :not=            {:tier :clojure-base :category :comparison
                     :template "(not= a b)"
                     :doc {:it "Diverso" :en "Not equal"}
                     :aliases {:it ["diverso"] :en ["not equal"]}}
   :<               {:tier :clojure-base :category :comparison
                     :template "(< a b)"
                     :doc {:it "Minore" :en "Less than"}
                     :aliases {:it ["minore"] :en ["less"]}}
   :>               {:tier :clojure-base :category :comparison
                     :template "(> a b)"
                     :doc {:it "Maggiore" :en "Greater than"}
                     :aliases {:it ["maggiore"] :en ["greater"]}}
   :<=              {:tier :clojure-base :category :comparison
                     :template "(<= a b)"
                     :doc {:it "Minore o uguale" :en "Less or equal"}
                     :aliases {:it [] :en []}}
   :>=              {:tier :clojure-base :category :comparison
                     :template "(>= a b)"
                     :doc {:it "Maggiore o uguale" :en "Greater or equal"}
                     :aliases {:it [] :en []}}

   ;; --- Logica ---
   :and             {:tier :clojure-base :category :logic
                     :template "(and a b)"
                     :doc {:it "E logico" :en "Logical and"}
                     :aliases {:it [] :en []}}
   :or              {:tier :clojure-base :category :logic
                     :template "(or a b)"
                     :doc {:it "O logico" :en "Logical or"}
                     :aliases {:it [] :en []}}
   :not             {:tier :clojure-base :category :logic
                     :template "(not x)"
                     :doc {:it "Negazione" :en "Negation"}
                     :aliases {:it [] :en []}}

   ;; ============================================================
   ;; TIER 3: Clojure Extended
   ;; ============================================================

   ;; --- Higher-Order ---
   :apply           {:tier :clojure-extended :category :higher-order
                     :template "(apply f args)"
                     :doc {:it "Applica con lista args" :en "Apply with arg list"}
                     :aliases {:it [] :en []}}
   :partial         {:tier :clojure-extended :category :higher-order
                     :template "(partial f arg1)"
                     :doc {:it "Applicazione parziale" :en "Partial application"}
                     :aliases {:it [] :en []}}
   :comp            {:tier :clojure-extended :category :higher-order
                     :template "(comp f g)"
                     :doc {:it "Composizione funzioni" :en "Function composition"}
                     :aliases {:it [] :en ["compose"]}}
   :identity        {:tier :clojure-extended :category :higher-order
                     :template "(identity x)"
                     :doc {:it "Ritorna argomento" :en "Returns argument"}
                     :aliases {:it [] :en []}}
   :constantly      {:tier :clojure-extended :category :higher-order
                     :template "(constantly 42)"
                     :doc {:it "Funzione costante" :en "Constant function"}
                     :aliases {:it [] :en []}}
   :complement      {:tier :clojure-extended :category :higher-order
                     :template "(complement pred)"
                     :doc {:it "Nega predicato" :en "Negate predicate"}
                     :aliases {:it [] :en []}}
   :juxt            {:tier :clojure-extended :category :higher-order
                     :template "(juxt f g)"
                     :doc {:it "Applica multiple fn" :en "Apply multiple fns"}
                     :aliases {:it [] :en []}}

   ;; --- Sequenze Avanzate ---
   :mapcat          {:tier :clojure-extended :category :advanced-seq
                     :template "(mapcat f coll)"
                     :doc {:it "Map + concat" :en "Map + concat"}
                     :aliases {:it [] :en []}}
   :keep            {:tier :clojure-extended :category :advanced-seq
                     :template "(keep f coll)"
                     :doc {:it "Map filtrando nil" :en "Map filtering nil"}
                     :aliases {:it [] :en []}}
   :remove          {:tier :clojure-extended :category :advanced-seq
                     :template "(remove pred coll)"
                     :doc {:it "Opposto di filter" :en "Opposite of filter"}
                     :aliases {:it [] :en []}}
   :take            {:tier :clojure-extended :category :advanced-seq
                     :template "(take 5 coll)"
                     :doc {:it "Primi N elementi" :en "Take first N"}
                     :aliases {:it ["prendi"] :en []}}
   :drop            {:tier :clojure-extended :category :advanced-seq
                     :template "(drop 5 coll)"
                     :doc {:it "Senza primi N" :en "Drop first N"}
                     :aliases {:it [] :en []}}
   :take-while      {:tier :clojure-extended :category :advanced-seq
                     :template "(take-while pred coll)"
                     :doc {:it "Prendi mentre true" :en "Take while true"}
                     :aliases {:it [] :en []}}
   :drop-while      {:tier :clojure-extended :category :advanced-seq
                     :template "(drop-while pred coll)"
                     :doc {:it "Salta mentre true" :en "Drop while true"}
                     :aliases {:it [] :en []}}
   :partition       {:tier :clojure-extended :category :advanced-seq
                     :template "(partition 2 coll)"
                     :doc {:it "Divide in gruppi" :en "Partition into groups"}
                     :aliases {:it [] :en []}}
   :partition-by    {:tier :clojure-extended :category :advanced-seq
                     :template "(partition-by f coll)"
                     :doc {:it "Divide per valore f" :en "Partition by value"}
                     :aliases {:it [] :en []}}
   :group-by        {:tier :clojure-extended :category :advanced-seq
                     :template "(group-by f coll)"
                     :doc {:it "Raggruppa per chiave" :en "Group by key"}
                     :aliases {:it [] :en []}}
   :sort            {:tier :clojure-extended :category :advanced-seq
                     :template "(sort coll)"
                     :doc {:it "Ordina" :en "Sort"}
                     :aliases {:it ["ordina"] :en []}}
   :sort-by         {:tier :clojure-extended :category :advanced-seq
                     :template "(sort-by f coll)"
                     :doc {:it "Ordina per chiave" :en "Sort by key"}
                     :aliases {:it [] :en []}}
   :reverse         {:tier :clojure-extended :category :advanced-seq
                     :template "(reverse coll)"
                     :doc {:it "Inverti" :en "Reverse"}
                     :aliases {:it ["inverti"] :en []}}
   :flatten         {:tier :clojure-extended :category :advanced-seq
                     :template "(flatten coll)"
                     :doc {:it "Appiattisce" :en "Flatten"}
                     :aliases {:it [] :en []}}
   :distinct        {:tier :clojure-extended :category :advanced-seq
                     :template "(distinct coll)"
                     :doc {:it "Rimuovi duplicati" :en "Remove duplicates"}
                     :aliases {:it [] :en ["unique"]}}
   :interleave      {:tier :clojure-extended :category :advanced-seq
                     :template "(interleave a b)"
                     :doc {:it "Alterna elementi" :en "Interleave elements"}
                     :aliases {:it [] :en []}}
   :interpose       {:tier :clojure-extended :category :advanced-seq
                     :template "(interpose sep coll)"
                     :doc {:it "Inserisci separatore" :en "Interpose separator"}
                     :aliases {:it [] :en []}}

   ;; --- Collezioni ---
   :conj            {:tier :clojure-extended :category :collections
                     :template "(conj coll x)"
                     :doc {:it "Aggiungi elemento" :en "Add element"}
                     :aliases {:it ["aggiungi"] :en ["add"]}}
   :cons            {:tier :clojure-extended :category :collections
                     :template "(cons x coll)"
                     :doc {:it "Prependi elemento" :en "Prepend element"}
                     :aliases {:it [] :en ["prepend"]}}
   :concat          {:tier :clojure-extended :category :collections
                     :template "(concat a b)"
                     :doc {:it "Concatena" :en "Concatenate"}
                     :aliases {:it ["concatena"] :en []}}
   :into            {:tier :clojure-extended :category :collections
                     :template "(into [] coll)"
                     :doc {:it "Versa in collezione" :en "Pour into collection"}
                     :aliases {:it [] :en []}}
   :empty           {:tier :clojure-extended :category :collections
                     :template "(empty coll)"
                     :doc {:it "Collezione vuota stesso tipo" :en "Empty same-type collection"}
                     :aliases {:it [] :en []}}
   :empty?          {:tier :clojure-extended :category :collections
                     :template "(empty? coll)"
                     :doc {:it "È vuota?" :en "Is empty?"}
                     :aliases {:it [] :en []}}
   :seq             {:tier :clojure-extended :category :collections
                     :template "(seq coll)"
                     :doc {:it "Converti a sequenza" :en "Convert to sequence"}
                     :aliases {:it [] :en ["sequence"]}}
   :vec             {:tier :clojure-extended :category :collections
                     :template "(vec coll)"
                     :doc {:it "Converti a vettore" :en "Convert to vector"}
                     :aliases {:it ["vettore"] :en ["vector"]}}
   :set             {:tier :clojure-extended :category :collections
                     :template "(set coll)"
                     :doc {:it "Converti a set" :en "Convert to set"}
                     :aliases {:it [] :en []}}

   ;; --- Mappe ---
   :get             {:tier :clojure-extended :category :maps
                     :template "(get m :key)"
                     :doc {:it "Ottieni valore" :en "Get value"}
                     :aliases {:it [] :en []}}
   :get-in          {:tier :clojure-extended :category :maps
                     :template "(get-in m [:a :b])"
                     :doc {:it "Ottieni nested" :en "Get nested value"}
                     :aliases {:it [] :en []}}
   :assoc           {:tier :clojure-extended :category :maps
                     :template "(assoc m :key val)"
                     :doc {:it "Associa valore" :en "Associate value"}
                     :aliases {:it [] :en ["associate"]}}
   :assoc-in        {:tier :clojure-extended :category :maps
                     :template "(assoc-in m [:a :b] val)"
                     :doc {:it "Associa nested" :en "Associate nested"}
                     :aliases {:it [] :en []}}
   :dissoc          {:tier :clojure-extended :category :maps
                     :template "(dissoc m :key)"
                     :doc {:it "Rimuovi chiave" :en "Remove key"}
                     :aliases {:it [] :en []}}
   :update          {:tier :clojure-extended :category :maps
                     :template "(update m :key f)"
                     :doc {:it "Aggiorna con fn" :en "Update with fn"}
                     :aliases {:it ["aggiorna"] :en []}}
   :update-in       {:tier :clojure-extended :category :maps
                     :template "(update-in m [:a :b] f)"
                     :doc {:it "Aggiorna nested" :en "Update nested"}
                     :aliases {:it [] :en []}}
   :merge           {:tier :clojure-extended :category :maps
                     :template "(merge m1 m2)"
                     :doc {:it "Unisci mappe" :en "Merge maps"}
                     :aliases {:it ["unisci"] :en []}}
   :select-keys     {:tier :clojure-extended :category :maps
                     :template "(select-keys m [:a :b])"
                     :doc {:it "Sottoinsieme chiavi" :en "Select keys"}
                     :aliases {:it [] :en []}}
   :keys            {:tier :clojure-extended :category :maps
                     :template "(keys m)"
                     :doc {:it "Lista chiavi" :en "List keys"}
                     :aliases {:it ["chiavi"] :en []}}
   :vals            {:tier :clojure-extended :category :maps
                     :template "(vals m)"
                     :doc {:it "Lista valori" :en "List values"}
                     :aliases {:it ["valori"] :en ["values"]}}
   :zipmap          {:tier :clojure-extended :category :maps
                     :template "(zipmap keys vals)"
                     :doc {:it "Crea mappa" :en "Create map from keys+vals"}
                     :aliases {:it [] :en []}}

   ;; --- Stringhe ---
   :str             {:tier :clojure-extended :category :strings
                     :template "(str a b c)"
                     :doc {:it "Concatena stringhe" :en "Concatenate strings"}
                     :aliases {:it ["stringa"] :en ["string"]}}
   :subs            {:tier :clojure-extended :category :strings
                     :template "(subs s 0 5)"
                     :doc {:it "Sottostringa" :en "Substring"}
                     :aliases {:it [] :en ["substring"]}}
   :format          {:tier :clojure-extended :category :strings
                     :template "(format \"Ciao %s\" nome)"
                     :doc {:it "Formatta stringa" :en "Format string"}
                     :aliases {:it [] :en []}}

   ;; --- Predicati ---
   :nil?            {:tier :clojure-extended :category :predicates
                     :template "(nil? x)"
                     :doc {:it "È nil?" :en "Is nil?"}
                     :aliases {:it [] :en []}}
   :some?           {:tier :clojure-extended :category :predicates
                     :template "(some? x)"
                     :doc {:it "Non è nil?" :en "Not nil?"}
                     :aliases {:it [] :en []}}
   :number?         {:tier :clojure-extended :category :predicates
                     :template "(number? x)"
                     :doc {:it "È numero?" :en "Is number?"}
                     :aliases {:it [] :en []}}
   :string?         {:tier :clojure-extended :category :predicates
                     :template "(string? x)"
                     :doc {:it "È stringa?" :en "Is string?"}
                     :aliases {:it [] :en []}}
   :keyword?        {:tier :clojure-extended :category :predicates
                     :template "(keyword? x)"
                     :doc {:it "È keyword?" :en "Is keyword?"}
                     :aliases {:it [] :en []}}
   :map?            {:tier :clojure-extended :category :predicates
                     :template "(map? x)"
                     :doc {:it "È mappa?" :en "Is map?"}
                     :aliases {:it [] :en []}}
   :vector?         {:tier :clojure-extended :category :predicates
                     :template "(vector? x)"
                     :doc {:it "È vettore?" :en "Is vector?"}
                     :aliases {:it [] :en []}}
   :even?           {:tier :clojure-extended :category :predicates
                     :template "(even? n)"
                     :doc {:it "È pari?" :en "Is even?"}
                     :aliases {:it ["pari"] :en []}}
   :odd?            {:tier :clojure-extended :category :predicates
                     :template "(odd? n)"
                     :doc {:it "È dispari?" :en "Is odd?"}
                     :aliases {:it ["dispari"] :en []}}
   :pos?            {:tier :clojure-extended :category :predicates
                     :template "(pos? n)"
                     :doc {:it "È positivo?" :en "Is positive?"}
                     :aliases {:it [] :en []}}
   :neg?            {:tier :clojure-extended :category :predicates
                     :template "(neg? n)"
                     :doc {:it "È negativo?" :en "Is negative?"}
                     :aliases {:it [] :en []}}
   :zero?           {:tier :clojure-extended :category :predicates
                     :template "(zero? n)"
                     :doc {:it "È zero?" :en "Is zero?"}
                     :aliases {:it [] :en []}}

   ;; --- Threading ---
   :->              {:tier :clojure-extended :category :threading
                     :template "(-> x (f) (g))"
                     :doc {:it "Thread-first" :en "Thread-first"}
                     :aliases {:it [] :en ["thread first" "thread"]}}
   :->>             {:tier :clojure-extended :category :threading
                     :template "(->> x (f) (g))"
                     :doc {:it "Thread-last" :en "Thread-last"}
                     :aliases {:it [] :en ["thread last"]}}
   :as->            {:tier :clojure-extended :category :threading
                     :template "(as-> x $ (f $) (g $))"
                     :doc {:it "Thread con nome" :en "Thread with name"}
                     :aliases {:it [] :en []}}
   :some->          {:tier :clojure-extended :category :threading
                     :template "(some-> x (f) (g))"
                     :doc {:it "Thread con nil check" :en "Thread with nil check"}
                     :aliases {:it [] :en []}}
   :some->>         {:tier :clojure-extended :category :threading
                     :template "(some->> x (f) (g))"
                     :doc {:it "Thread-last con nil check" :en "Thread-last with nil check"}
                     :aliases {:it [] :en []}}

   ;; --- Varie ---
   :do              {:tier :clojure-extended :category :misc
                     :template "(do\n  expr1\n  expr2)"
                     :doc {:it "Esegue in sequenza" :en "Execute in sequence"}
                     :aliases {:it [] :en []}}
   :some            {:tier :clojure-extended :category :misc
                     :template "(some pred coll)"
                     :doc {:it "Primo truthy" :en "First truthy"}
                     :aliases {:it [] :en []}}
   :every?          {:tier :clojure-extended :category :misc
                     :template "(every? pred coll)"
                     :doc {:it "Tutti true?" :en "All true?"}
                     :aliases {:it [] :en []}}
   :rand            {:tier :clojure-extended :category :misc
                     :template "(rand)"
                     :doc {:it "Numero random" :en "Random number"}
                     :aliases {:it [] :en ["random"]}}
   :rand-int        {:tier :clojure-extended :category :misc
                     :template "(rand-int 10)"
                     :doc {:it "Intero random 0..n-1" :en "Random int 0..n-1"}
                     :aliases {:it [] :en []}}
   :rand-nth        {:tier :clojure-extended :category :misc
                     :template "(rand-nth coll)"
                     :doc {:it "Elemento random" :en "Random element"}
                     :aliases {:it [] :en []}}
   :shuffle         {:tier :clojure-extended :category :misc
                     :template "(shuffle coll)"
                     :doc {:it "Mescola collezione" :en "Shuffle collection"}
                     :aliases {:it ["mescola"] :en []}}})
