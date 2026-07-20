; === Bersaglio di calibrazione per l'acquisizione parametrica ===
;
; Il pezzo per chiudere il gate del metodo: una scatola a spigoli vivi, cioè
; un oggetto che il modello del solver sa descrivere ESATTAMENTE. Il lettore
; SD della prima sessione non lo era (raccordi ~1.7 mm, bordo rialzato,
; connettore sporgente), e questo confondeva "il metodo non funziona" con
; "il pezzo non è una scatola".
;
; Due obiettivi, e la geometria serve a entrambi:
;
; 1. QUOTE — spigoli vivi. Su un pezzo stampato FDM il raggio di spigolo è
;    ~0.1-0.3 mm, ben sotto il ~1.16 mm che il gate tollera (EXP 10), quindi
;    il bias da raccordo scende sotto i 0.05-0.12 mm e smette di dominare.
;
; 2. REGISTRAZIONE — le CINQUE tacche. Sono pozzetti quadrati a coordinate
;    note esattamente, che NON entrano nel fit: servono come verità a terra.
;    Riproiettandole attraverso le camere stimate e confrontandole con dove
;    l'utente le clicca si ottiene l'errore di registrazione in pixel, che è
;    una misura indipendente dalle quote. È il numero che decide se il fit
;    valga come registratore di camere anche quando le quote non tornano.
;
; Le tacche sono in posizione DIVERSA su ogni faccia, quindi vederne una dice
; quale faccia stai guardando: l'orientamento non è mai ambiguo, e cade anche
; l'ambiguità a 180 gradi che una scatola nuda ha per simmetria.

; --- Quote nominali (mm) ------------------------------------------------
; Tutte e tre distinte e senza rapporti quasi-uguali, così nessuna coppia di
; ipotesi di orientamento può confondersi. Nessuna dimensione troppo sottile:
; è la più sottile che paga di più qualunque residuo di arrotondamento.

(def BX 60)   ; lungo l'asse X del mondo
(def BY 40)   ; lungo Y
(def BZ 50)   ; lungo Z — verticale sul giradischi, la quota che il calibro fissa

; --- Tacche -------------------------------------------------------------
(def PW 10)   ; lato del pozzetto
(def PD 2)    ; profondità

; Centri delle tacche, in coordinate del pezzo con origine al centro della
; scatola. QUESTE COORDINATE VANNO NEL NOTE.md DELLA SESSIONE: sono la verità
; a terra della metrica di registrazione.
;
;   faccia +X  ( 30,   0,  15)   in alto
;   faccia -X  (-30,   0, -15)   in basso
;   faccia +Y  (-18,  20,   0)   spostata verso -X
;   faccia -Y  ( 18, -20,   0)   spostata verso +X
;   faccia +Z  ( 18,  10,  25)   fuori centro sul piano superiore

; Un tagliente che affonda PD nella faccia perpendicolare all'asse dato.
; Il taglierino è lungo 2*PD e centrato SUL piano della faccia, così metà
; entra e metà resta fuori: niente facce complanari, che sono la ricetta
; nota per gli artefatti di CSG.
(defn pocket-x [sx pz]
  (-> (box PW PW (* 2 PD))            ; rt=Y, u=Z, f=X
      (mesh-translate [(* sx (/ BX 2)) 0 pz])))

(defn pocket-y [sy px]
  (-> (box (* 2 PD) PW PW)            ; rt=Y sottile
      (mesh-translate [px (* sy (/ BY 2)) 0])))

(defn pocket-z [px py]
  (-> (box PW (* 2 PD) PW)            ; u=Z sottile
      (mesh-translate [px py (/ BZ 2)])))

; --- Il pezzo -----------------------------------------------------------

(def target
  (-> (box BY BZ BX)                  ; rt=Y=40, u=Z=50, f=X=60
      (mesh-difference (pocket-x  1  15))
      (mesh-difference (pocket-x -1 -15))
      (mesh-difference (pocket-y  1 -18))
      (mesh-difference (pocket-y -1  18))
      (mesh-difference (pocket-z 18 10))))

(register bersaglio target)

; --- Stampa -------------------------------------------------------------
;
; Orientamento consigliato: appoggiato sulla faccia -Z (quella senza tacca),
; così l'"elephant foot" resta sulla base, che è l'unica faccia mai
; fotografata. Niente supporti: i pozzetti sono poco profondi e a parete
; verticale o rivolti verso l'alto.
;
; Layer sottili (0.12-0.16 mm) sugli spigoli verticali: sono quelli che il
; solver usa di più. Nessuna smussatura, nessun raccordo, niente brim se
; evitabile — il brim lascia una sbavatura proprio sullo spigolo inferiore.
;
; Dopo la stampa: MISURA COL CALIBRO tutte e tre le quote e scrivile nel
; NOTE.md. Il nominale non è il vero — il ritiro del materiale vale
; facilmente qualche decimo, ed è proprio quello che stiamo misurando.
;
; Per esportare:  (save-stl target "param-acq-target.stl")
