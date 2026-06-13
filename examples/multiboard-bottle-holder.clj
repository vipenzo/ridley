;; Multiboard bottle / can holder — a clip that hangs on a Multiboard tile,
;; plus a cradle sized to hold a bottle or a can.
;;
;; NOTE: the clip ("hook") is an imported STL embedded via decode-mesh and
;; recentered on the origin with mesh-translate. It would be better rebuilt
;; natively in Ridley — it's essentially an extruded hexagon with a little
;; fillet. TODO: reproduce the clip as Ridley code and drop the embedded STL.
(def hook
  (-> (decode-mesh
       "ykRIxevKOcS2CwRDykRIxevKOcRf6PxCykRIxeuKM8RQ6PxCykRIxeuKM8SuCwRDht1IxevKOcTy/wdDDoRIxevKOcTz/wdDDoRIxdyNMsTr/wdDht1IxdyNMsTr/wdDDoRIxevKOcTl//RCht1IxevKOcTW//RCht1IxdyNMsTW//RCDoRIxdyNMsTW//RCyvRHxesqMMSuCwRDt/hIxesqMMSuCwRDVLlIxfonMcTr/wdDDoRIxfonMcTr/wdDyvRHxfonMcTr/wdDyhxJxTi7MMSuCwRDyhxJxevKOcSuCwRDht1IxcK4McTr/wdDDoRIxfonMcTW//RCVLlIxfonMcTW//RCt/hIxesqMMRQ6PxCyvRHxesqMMRQ6PxCyvRHxfonMcTW//RCyhxJxevqOcRf6PxCyhxJxevqOcS1CwRDyhxJxevKOcRQ6PxCykRIxevqOcS2CwRDykRIxevqOcRf6PxCDoRIxevqOcTl//RCht1IxevqOcTl//RCDoRIxevqOcTz/wdDht1IxevqOcTy/wdD9oRIxe4qO8QgugdD+UZIxe4qO8RX2gND9YRIxe4qO8Tk//RCn9xIxe4qO8Tk//RC+UZIxe4qO8R4v/xCnBpJxe4qO8R4v/xCnBpJxe4qO8RW2gNDn9xIxe4qO8QgugdDXYhIxetKO8TEBvZCONlIxetKO8TEBvZCZBJJxetKO8RMLP1CZBJJxetKO8TSowNDONlIxetKO8SVNgdDXYhIxetKO8SVNgdDMU9IxetKO8TSowNDMU9IxetKO8RMLP1CyvRHxdyNMsTX//RCyvRHxeuKM8RQ6PxCyvRHxdyNMsTr/wdDyvRHxeuKM8SuCwRDyhxJxTi7MMRQ6PxCht1IxcK4McTW//RCysxHxevKMMSVAgNDysxHxUBqMcTrfwVDysxHxZVLMsTrfwVDysxHxevqMsSVAgNDysxHxevqMsSC+v5CysxHxZVLMsTW//lCysxHxUBqMcTW//lCysxHxevKMMSC+v5C"
       "AAAAAAEAAAACAAAAAAAAAAIAAAADAAAABAAAAAUAAAAGAAAABAAAAAYAAAAHAAAACAAAAAkAAAAKAAAACAAAAAoAAAALAAAADAAAAA0AAAAOAAAADwAAABAAAAAMAAAADgAAAA8AAAAMAAAACAAAAAsAAAACAAAACAAAAAIAAAABAAAAEQAAABIAAAAEAAAABAAAAAcAAAATAAAABAAAABMAAAARAAAAFAAAABUAAAAWAAAAFgAAABcAAAAYAAAAFAAAABYAAAAYAAAABQAAAAAAAAADAAAABQAAAAMAAAAGAAAAGQAAABoAAAASAAAAGQAAABIAAAAbAAAAHAAAAB0AAAABAAAAHAAAAAEAAAAAAAAAHgAAAB8AAAAJAAAAHgAAAAkAAAAIAAAAIAAAABwAAAAAAAAAIAAAAAAAAAAFAAAAHQAAAB4AAAAIAAAAHQAAAAgAAAABAAAAHwAAABkAAAAbAAAAHwAAABsAAAAJAAAAGgAAACEAAAAEAAAAGgAAAAQAAAASAAAAHAAAACAAAAAiAAAAHAAAACIAAAAjAAAAIQAAACAAAAAFAAAAIQAAAAUAAAAEAAAAHwAAAB4AAAAkAAAAHwAAACQAAAAlAAAAHAAAACMAAAAmAAAAHAAAACYAAAAdAAAAGQAAAB8AAAAlAAAAGQAAACUAAAAnAAAAGQAAACcAAAAoAAAAGQAAACgAAAAaAAAAIQAAABoAAAAoAAAAIQAAACgAAAApAAAAIQAAACkAAAAiAAAAIQAAACIAAAAgAAAAHgAAAB0AAAAmAAAAHgAAACYAAAAkAAAAKgAAACsAAAAlAAAAKgAAACUAAAAkAAAAKwAAACwAAAAnAAAAKwAAACcAAAAlAAAAKAAAACcAAAAsAAAAKAAAACwAAAAtAAAALQAAAC4AAAApAAAALQAAACkAAAAoAAAAIgAAACkAAAAuAAAAIgAAAC4AAAAvAAAALwAAADAAAAAjAAAALwAAACMAAAAiAAAAJgAAACMAAAAwAAAAJgAAADAAAAAxAAAAMQAAACoAAAAkAAAAMQAAACQAAAAmAAAALAAAACsAAAAqAAAAKgAAADEAAAAwAAAAMAAAAC8AAAAuAAAALgAAAC0AAAAsAAAALAAAACoAAAAwAAAAMAAAAC4AAAAsAAAAAgAAAAsAAAAyAAAAAgAAADIAAAAzAAAABgAAAA8AAAAOAAAADgAAABMAAAAHAAAADgAAAAcAAAAGAAAAEAAAAA8AAAAGAAAAEAAAAAYAAAA0AAAAMgAAAAsAAAAUAAAAMgAAABQAAAAYAAAAAwAAAAIAAAAzAAAAAwAAADMAAAA1AAAACgAAAAkAAAAbAAAAGwAAADYAAAA3AAAAGwAAADcAAAAKAAAANQAAADQAAAAGAAAANQAAAAYAAAADAAAAFAAAAAsAAAAKAAAACgAAADcAAAAVAAAACgAAABUAAAAUAAAAGwAAABIAAAARAAAAGwAAABEAAAA2AAAAOAAAADkAAAA6AAAAOgAAADsAAAA8AAAAPAAAAD0AAAA+AAAAPgAAAD8AAAA4AAAAOAAAADoAAAA8AAAAPAAAAD4AAAA4AAAAPgAAAD0AAAAyAAAAPgAAADIAAAAYAAAAPwAAAD4AAAAYAAAAPwAAABgAAAAXAAAAPQAAADwAAAAzAAAAPQAAADMAAAAyAAAAPAAAADsAAAA1AAAAPAAAADUAAAAzAAAAOwAAADoAAAA0AAAAOwAAADQAAAA1AAAAOgAAADkAAAAQAAAAOgAAABAAAAA0AAAAOQAAADgAAAAMAAAAOQAAAAwAAAAQAAAAOAAAAD8AAAAXAAAAOAAAABcAAAAMAAAANgAAABEAAAANAAAANgAAAA0AAAAWAAAAEQAAABMAAAAOAAAAEQAAAA4AAAANAAAAFgAAABUAAAA3AAAAFgAAADcAAAA2AAAAFgAAAA0AAAAMAAAAFgAAAAwAAAAXAAAA")
      (mesh-translate [3207.299 726.921 -129.250])))

(def perno (mesh-intersection hook (attach (box 20) (path (rt 5) (f -5) (stretch-rt 1.4641000000000006)
                                                          (rt 5)))))
(def l10 (mesh-intersection perno (attach (box 10) (path (rt 5) (f -5)
                                                         (stretch-f 1.610510000000001)
                                                         (f -5) (rt 5) (f 10)
                                                         (stretch-f 1.4641000000000006)
                                                         (stretch-u 1.6105100000000008)
                                                         (f -5)))))
(def P40
  (mesh-union perno
              (attach l10 (rt -19))
              (attach l10 (rt -29))
              (attach (box 20) (path (rt -15)
                                     (stretch-f 1.2)
                                     (stretch-rt 0.13)
                                     (stretch-u 2.5)
                                     (f -5)
                                     (rt -9)
                                     (u 18)))))

(def P20
  (mesh-union perno
              (attach (box 20) (path (rt -15)
                                     (stretch-f 1.2)
                                     (stretch-rt 0.13)
                                     (stretch-u 2.5)
                                     (f -5)
                                     (rt 9)
                                     (u 18)))))

(resolution :n 64)

(defn reggibottiglia [diam H spessore p-pos]
  (mesh-difference
   (mesh-union
    (box (+ diam 15) (+ diam 15) H)
    (concat-meshes (for [x p-pos]
                     (attach perno (path (tr 90) (rt (/ diam 2)) (u (* x 25)))))))

   (attach (cyl (/ diam 2) (* diam 3)) (tv -30))))
(register bottle-holder (attach (reggibottiglia 91 40 3 [-1.5 1.5]) (f 60)))
(register can-holder (reggibottiglia 35 20 3 [0]))
