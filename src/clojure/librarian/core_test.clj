(ns librarian.core-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [librarian.core :refer [extract-valid-isbn parse-safe-int]]))

(def scanned-ocr-text
  "ББК 81.2УКР-93
Б78

Допущено Міністерством освіти України
(протокол № 4/1-18 від 25.03.98)

Художники С. М. Железняк,
О. В. Кузнєцова, Л. Г. Орлюк

Бондаренко Н. В.
Б78 Мальва: Післябукварна читанка.— Київ; Ірпінь: ВТФ
«Перун», 1999.— 216 с. : іл.
ISBN 966-569-026-4

ББК 81.2УКР-93

ISBN 966-569-026-4

© ВТФ «Перун», 1998
© Н. В. Бондаренко, 1998")

(deftest test-isbn-extraction
  (testing "Extraction of typical Ukrainian publisher ISBN and OCR variations"
    (let [result (extract-valid-isbn scanned-ocr-text)]
      (println "🔎 [Clojure Scanner] Extracted result:" result)
      (is (= result "9665690264"))))
  (testing "Extraction of spaced-out ISBN-13"
    (let [spaced-text "Something before... ІSВN 978 - 966 - 2449 - 01 - 3 ...something after"
          result (extract-valid-isbn spaced-text)]
      (println "🔎 [Clojure Scanner] Extracted spaced result:" result)
      (is (= result "9789662449013")))))

(deftest test-parse-safe-int
  (testing "Integer parsing and floating rates conversion to percentages"
    (is (= (parse-safe-int 95 0) 95))
    (is (= (parse-safe-int 0.95 0) 95))
    (is (= (parse-safe-int 0.8 0) 80))
    (is (= (parse-safe-int "0.9" 0) 90))
    (is (= (parse-safe-int "85%" 0) 85))
    (is (= (parse-safe-int nil 10) 10))
    (is (= (parse-safe-int "1999" 0) 1999))))

(defn -main []
  (println "🧪 [Clojure test runner] Executing metadata scanning assertions...")
  (let [report (run-tests 'librarian.core-test)]
    (if (and (zero? (:fail report)) (zero? (:error report)))
      (do
        (println "✅ [Clojure test runner] SUCCESS! All Librarian assertions passed green!")
        (System/exit 0))
      (do
        (println "❌ [Clojure test runner] FAILURE: Assertions missed baseline parameters.")
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
