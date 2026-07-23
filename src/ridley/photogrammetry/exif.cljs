(ns ridley.photogrammetry.exif
  "Minimal, dependency-free EXIF reader for the acquisition flow: pulls the
   35mm-equivalent focal length (FocalLengthIn35mmFilm, tag 0xA405) out of a
   JPEG's APP1/TIFF block so the camera FOV comes from the photo itself rather
   than a hand-entered number (see ridley.photogrammetry.camera/
   equiv-focal->hfov-deg for what the number then means).

   Deliberately tiny and defensive: it walks only as far as the one tag it
   needs and returns nil on ANY structural surprise (not a JPEG, no APP1, no
   Exif marker, tag absent, unexpected type) — the caller falls back to the
   manual focal slider rather than seeing an exception. Operates on a
   js/DataView over the file's ArrayBuffer, so it works the same in the
   browser (blob.arrayBuffer()) and in a Node test (Buffer → ArrayBuffer).")

(defn- u8 [^js dv off] (.getUint8 dv off))
(defn- u16 [^js dv off little?] (.getUint16 dv off little?))
(defn- u32 [^js dv off little?] (.getUint32 dv off little?))

(def ^:private tag-exif-ifd 0x8769)             ; pointer to the Exif sub-IFD
(def ^:private tag-focal-35 0xA405)             ; FocalLengthIn35mmFilm (SHORT)
(def ^:private type-short 3)
(def ^:private type-long 4)

(defn- exif-tiff-offset
  "Byte offset of the TIFF header (right after the 'Exif\\0\\0' magic) inside a
   JPEG DataView, or nil. Walks the marker segments from SOI until it finds an
   APP1 whose payload starts with 'Exif\\0\\0', or hits image data / a
   malformed marker."
  [^js dv]
  (let [len (.-byteLength dv)]
    (when (and (>= len 4) (= 0xFFD8 (u16 dv 0 false)))          ; SOI
      (loop [off 2]
        (when (< (+ off 4) len)
          (if (not= 0xFF (u8 dv off))
            nil                                                  ; not on a marker
            (let [marker (u8 dv (inc off))]
              (cond
                (or (= marker 0xD9) (= marker 0xDA)) nil         ; EOI / SOS
                (= marker 0xE1)                                  ; APP1
                (let [seg-len (u16 dv (+ off 2) false)
                      data (+ off 4)]
                  (if (and (>= seg-len 8) (< (+ data 6) len)
                           (= 0x45786966 (u32 dv data false))    ; "Exif"
                           (zero? (u16 dv (+ data 4) false)))    ; \0\0
                    (+ data 6)
                    (recur (+ off 2 seg-len))))
                :else
                (let [seg-len (u16 dv (+ off 2) false)]
                  (if (< seg-len 2) nil (recur (+ off 2 seg-len))))))))))))

(defn- ifd-tag-value
  "Value of tag `tag` in the IFD at absolute offset `ifd` (= tiff + relative),
   for SHORT/LONG single values held inline in the 12-byte entry. nil if the
   tag is absent or of an unexpected type."
  [^js dv ifd little? tag]
  (let [n (u16 dv ifd little?)]
    (loop [i 0]
      (when (< i n)
        (let [entry (+ ifd 2 (* i 12))
              tg (u16 dv entry little?)]
          (if (= tg tag)
            (let [typ (u16 dv (+ entry 2) little?)
                  val-off (+ entry 8)]
              (cond
                (= typ type-short) (u16 dv val-off little?)
                (= typ type-long) (u32 dv val-off little?)
                :else nil))
            (recur (inc i))))))))

(defn focal-35mm-from-dataview
  "35mm-equivalent focal length (a positive number of mm) from a JPEG
   DataView, or nil when absent/unreadable. Checks the Exif sub-IFD first (its
   standard home) and IFD0 as a fallback."
  [^js dv]
  (try
    (when-let [tiff (exif-tiff-offset dv)]
      (let [bo (u16 dv tiff false)
            little? (= bo 0x4949)]                               ; 'II' vs 'MM'
        (when (and (or little? (= bo 0x4D4D))
                   (= 0x002A (u16 dv (+ tiff 2) little?)))
          (let [ifd0 (+ tiff (u32 dv (+ tiff 4) little?))
                from-exif (when-let [rel (ifd-tag-value dv ifd0 little? tag-exif-ifd)]
                            (ifd-tag-value dv (+ tiff rel) little? tag-focal-35))
                focal (or from-exif
                          (ifd-tag-value dv ifd0 little? tag-focal-35))]
            (when (and focal (pos? focal)) focal)))))
    (catch :default _ nil)))

(defn focal-35mm-from-arraybuffer
  "35mm-equivalent focal length from a JPEG ArrayBuffer, or nil."
  [^js array-buffer]
  (when array-buffer
    (focal-35mm-from-dataview (js/DataView. array-buffer))))
