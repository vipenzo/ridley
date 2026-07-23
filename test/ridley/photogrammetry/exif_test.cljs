(ns ridley.photogrammetry.exif-test
  "EXIF focal read + the 35mm-equivalent → horizontal-FOV conversion.

   The conversion bug this guards against (2026-07-23): the '48mm-equivalent'
   in EXIF is referred to the 43.27mm full-frame DIAGONAL, so on a 4:3 photo
   the horizontal FOV is ~39.7°, not the 41.1° the old sensor-width=36 (3:2)
   formula returned — a ~4% focal-scale error that is nearly invisible in a
   single-photo fit (absorbed into camera distance) but corrupts the multi-
   photo turntable geometry and the per-photo seeds edge-snap starts from."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.exif :as exif]))

;; ---------------------------------------------------------------------------
;; A hand-built EXIF/JPEG, little-endian ('II'), carrying exactly one useful
;; tag: FocalLengthIn35mmFilm (0xA405) = 48, in the Exif sub-IFD (0x8769). All
;; TIFF offsets are relative to the TIFF header start, per the spec. Kept as a
;; literal so the test documents the precise binary layout the parser walks.

(def ^:private tiff-bytes
  [0x49 0x49  0x2A 0x00  0x08 0x00 0x00 0x00           ; 'II', 42, IFD0 @ 8
   ;; IFD0
   0x01 0x00                                            ; 1 entry
   0x69 0x87  0x04 0x00  0x01 0x00 0x00 0x00  0x1A 0x00 0x00 0x00 ; 0x8769 LONG → 26
   0x00 0x00 0x00 0x00                                  ; next-IFD = 0
   ;; Exif sub-IFD @ 26
   0x01 0x00                                            ; 1 entry
   0x05 0xA4  0x03 0x00  0x01 0x00 0x00 0x00  0x30 0x00 0x00 0x00 ; 0xA405 SHORT = 48
   0x00 0x00 0x00 0x00])                                ; next-IFD = 0

(defn- synthetic-jpeg []
  (let [app1-payload (concat [0x45 0x78 0x69 0x66 0x00 0x00] tiff-bytes) ; "Exif\0\0" + TIFF
        app1-len (+ 2 (count app1-payload))            ; length field counts itself
        bytes (concat [0xFF 0xD8                        ; SOI
                       0xFF 0xE1                        ; APP1
                       (bit-and (bit-shift-right app1-len 8) 0xFF)
                       (bit-and app1-len 0xFF)]
                      app1-payload)]
    (.-buffer (js/Uint8Array. (clj->js bytes)))))

(deftest reads-focal-35mm-from-a-synthetic-jpeg
  (is (= 48 (exif/focal-35mm-from-arraybuffer (synthetic-jpeg)))
      "must recover FocalLengthIn35mmFilm from the Exif sub-IFD"))

(deftest returns-nil-on-non-jpeg-or-missing-tag
  (testing "not a JPEG"
    (is (nil? (exif/focal-35mm-from-arraybuffer
               (.-buffer (js/Uint8Array. (clj->js [0x00 0x01 0x02 0x03])))))))
  (testing "empty buffer"
    (is (nil? (exif/focal-35mm-from-arraybuffer (js/ArrayBuffer. 0))))))

;; The real iPhone photo, if the asset is present (guarded so a checkout
;; without the JPEGs still passes). Proves the parser works on a genuine file,
;; not just the hand-built one.
(deftest reads-focal-from-the-real-taped-block-photo
  (let [fs (js/require "fs")
        path "test-assets/param-acq-box-tape/IMG_8908.jpeg"]
    (if-not (.existsSync fs path)
      (println "  (IMG_8908.jpeg assente — salto la verifica sul file reale)")
      (let [buf (.readFileSync fs path)
            ab (.slice (.-buffer buf) (.-byteOffset buf) (+ (.-byteOffset buf) (.-byteLength buf)))]
        (is (= 48 (exif/focal-35mm-from-arraybuffer ab))
            "iPhone 15 Pro Max 2x reports FocalLengthIn35mmFilm = 48")))))

;; ---------------------------------------------------------------------------
;; The conversion.

(defn- close? [a b tol] (< (Math/abs (- a b)) tol))

(deftest equiv-focal-hfov-is-the-diagonal-convention
  (testing "4:3 (iPhone) at 48mm-eq gives ~39.7° horizontal, not 41.1°"
    (is (close? (cam/equiv-focal->hfov-deg 48.0 (/ 4.0 3.0)) 39.67 0.05))
    ;; the WRONG width-based value, kept here as the thing we moved away from
    (is (close? (cam/focal-mm->fov-deg 48.0 36.0) 41.11 0.05)))
  (testing "3:2 reduces exactly to the width=36 formula (the two agree there)"
    (is (close? (cam/equiv-focal->hfov-deg 48.0 (/ 3.0 2.0))
                (cam/focal-mm->fov-deg 48.0 36.0)
                1e-9)))
  (testing "the intrinsic focal in px differs by ~4% (the actual scale bug)"
    (let [W 4032 H 3024
          fx-correct (:fx (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ W H)) W H))
          fx-old (* W (/ 48.0 36.0))]              ; cli.cljs:25's frozen value
      (is (close? fx-correct 5591 5) (str "correct fx ≈ 5591, got " fx-correct))
      (is (close? fx-old 5376 1))
      (is (> (/ fx-correct fx-old) 1.035) "correct focal is ~4% longer than the width-based one"))))
