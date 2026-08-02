(ns ridley.manual.structure-test
  "Regression tests for the two ways a manual page can come back empty.

   Both were live bugs on 2026-08-02 (dev-docs/HANDOVER-manual-pages-blank.md):
   a card whose symbol name carries a `?` was fetched with the `?` unescaped —
   so the server saw a query string and answered 404 — and a build shipped
   without `npm run sync-manual` got the app's index.html back with HTTP 200,
   which the Markdown renderer turned into a blank page instead of an error."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.manual.structure :as structure]))

(deftest card-url-strips-the-docs-prefix
  (testing "an index :path becomes a URL relative to the public web root"
    (is (= "manual/reference/en/box.md"
           (structure/card-url "docs/manual/reference/en/box.md"))))
  (testing "no card file (a Clojure core entry) yields no URL"
    (is (nil? (structure/card-url nil)))))

(deftest card-url-encodes-url-structural-characters
  (testing "`sdf-node?` — the `?` must not open a query string, or the server
            is asked for `sdf-node` and answers 404 (the card opened blank
            online AND in dev until this was fixed)"
    (is (= "manual/reference/en/sdf-node%3F.md"
           (structure/card-url "docs/manual/reference/en/sdf-node?.md"))))
  (testing "the slashes stay slashes: only the filename is encoded"
    (is (= 3 (count (re-seq #"/" (structure/card-url
                                  "docs/manual/reference/en/sdf-node?.md")))))))

(deftest card-file-inverts-card-url
  (testing "round-trip: the on-disk name is recoverable from the served URL,
            so a card still matches its own index entry after encoding"
    (doseq [p ["docs/manual/reference/en/box.md"
               "docs/manual/reference/en/sdf-node?.md"
               "docs/manual/reference/en/extrude+.md"]]
      (is (= (last (.split p "/"))
             (structure/card-file (structure/card-url p)))))))

(deftest chapter-url-resolves-language
  (let [chap (structure/chapter-by-id :ch-01)]
    (testing "a translated chapter is served from the requested language"
      (is (= "manual/guides/it/01-getting-started.md"
             (structure/chapter-url chap :it)))
      (is (= "manual/guides/en/01-getting-started.md"
             (structure/chapter-url chap :en)))))
  (testing "an untranslated chapter falls back to the source language rather
            than fetching a URL that would 404"
    (is (= "manual/guides/it/99-solo-in-italiano.md"
           (structure/chapter-url {:file "99-solo-in-italiano.md" :langs #{:it}}
                                  :en)))))

(deftest shell-html?-catches-the-served-app-page
  (testing "a host that answers a missing file with index.html and HTTP 200 is
            detected, so the manual reports the failure instead of rendering
            the shell as an empty page"
    (is (structure/shell-html? "<!DOCTYPE html>\n<html lang=\"en\">\n<head>"))
    (is (structure/shell-html? "\n  <!doctype HTML><html>"))
    (is (structure/shell-html? "<html><body>Ridley</body></html>")))
  (testing "real Markdown is not mistaken for it — guides open with an HTML
            comment block of author notes, which must NOT trip the check"
    (is (not (structure/shell-html? "<!--\nNOTE INTERNE\n-->\n# 1. Per iniziare")))
    (is (not (structure/shell-html? "---\nname: box\n---\n\n# box")))
    (is (not (structure/shell-html? "# box\n\nCreate a rectangular box.")))
    (is (not (structure/shell-html? "")))))
