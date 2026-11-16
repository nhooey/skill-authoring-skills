;; Unit and offline end-to-end tests of scripts/skill-citations.clj, run by
;; the dev shell's `skill-citations-tests` and by `nix flake check`.
;;
;; The script is loaded into this namespace for its functions. The end-to-end
;; tests build a skill and an upstream tree in a temporary directory and run
;; `lock` and `verify` with --source, so they need no network or git.

(ns skill-citations-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def script
  (str (fs/normalize (fs/path (fs/parent (fs/absolutize *file*)) ".." ".." "scripts" "skill-citations.clj"))))

(load-file script)

(defn- lines [& ls] (str (str/join "\n" ls) "\n"))

(defn- thrown-message [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- includes-some? [problems needle]
  (boolean (some #(str/includes? % needle) problems)))

;; --- resolve-span ------------------------------------------------------------

(def guide
  (lines "# Guide"                  ; 1
         ""                         ; 2
         "## Install"               ; 3
         ""                         ; 4
         "Run the installer."       ; 5
         ""                         ; 6
         "### From source"          ; 7
         ""                         ; 8
         "```sh"                    ; 9
         "# not a heading"          ; 10
         "make install"             ; 11
         "```"                      ; 12
         ""                         ; 13
         "## Usage"                 ; 14
         ""                         ; 15
         "Call `run`."))            ; 16

(def core
  (lines "(ns core)"                ; 1
         ""                         ; 2
         "(defn run"                ; 3
         "  [x]"                    ; 4
         "  x)"                     ; 5
         ""                         ; 6
         "(defn stop [] nil)"))     ; 7

(deftest resolve-span-heading
  (testing "runs to the next heading at the same level, over subheadings and fenced #-lines, trimming blank lines"
    (is (= [3 12] (resolve-span guide {:id "i" :path "g.md" :heading "Install"}))))
  (testing ":intro stops at the next heading of any level"
    (is (= [3 5] (resolve-span guide {:id "i" :path "g.md" :heading "Install" :intro true}))))
  (testing "the last section runs to the end of the file"
    (is (= [14 16] (resolve-span guide {:id "u" :path "g.md" :heading "Usage"}))))
  (testing "a top-level heading spans the whole file"
    (is (= [1 16] (resolve-span guide {:id "g" :path "g.md" :heading "Guide"})))))

(deftest resolve-span-form-and-line
  (testing ":form runs to the next top-level form"
    (is (= [3 5] (resolve-span core {:id "r" :path "c.clj" :form "(defn run"}))))
  (testing ":form at the end of the file"
    (is (= [7 7] (resolve-span core {:id "s" :path "c.clj" :form "(defn stop"}))))
  (testing ":line is the one line holding the text"
    (is (= [4 4] (resolve-span core {:id "x" :path "c.clj" :line "[x]"})))))

(deftest resolve-span-failures
  (is (= "[a] heading \"Missing\" not found in g.md"
         (thrown-message #(resolve-span guide {:id "a" :path "g.md" :heading "Missing"}))))
  (is (= "[b] line \"defn\" matches 2 places in c.clj"
         (thrown-message #(resolve-span core {:id "b" :path "c.clj" :line "defn"}))))
  (is (= "[c] needs a :heading, :form or :line anchor"
         (thrown-message #(resolve-span core {:id "c" :path "c.clj"})))))

(deftest span-hash-normalizes-whitespace-only
  (let [a (lines "(defn run" "  [x]" "  x)")
        spaced (lines "(defn   run" "" "\t[x]  " "  x)")
        changed (lines "(defn run" "  [y]" "  y)")]
    (is (= (span-hash a [1 3]) (span-hash spaced [1 4])))
    (is (not= (span-hash a [1 3]) (span-hash changed [1 3])))
    (is (not= (span-hash a [1 3]) (span-hash a [1 2])))))

;; --- tags --------------------------------------------------------------------

(deftest tag-key-orders-releases-after-their-prereleases
  (is (= ["v0.9.10" "1.0.0-beta2" "v1.0.0-beta10" "v1.0.0-rc1" "v1.0.0" "v1.0.1"]
         (sort-by tag-key ["v1.0.0" "v1.0.0-rc1" "v1.0.1" "1.0.0-beta2" "v0.9.10" "v1.0.0-beta10"])))
  (is (nil? (tag-key "d414b12")))
  (is (nil? (tag-key "v1.0"))))

(deftest latest-tag-picks-the-newest
  (testing "semver: the highest tag above the current one, ignoring non-semver tags"
    (is (= "v2.0.0" (latest-tag {:tags (constantly ["v1.0.0" "v2.0.0-rc1" "v2.0.0" "nightly" "v1.9.9"])} "v1.0.0")))
    (is (= "v3.0.0" (latest-tag {:tags (constantly ["v1.0.0" "v2.0.0"])} "v3.0.0"))))
  (testing "GitHub releases: the last one, whatever its name"
    (is (= "abc1234" (latest-tag {:releases (constantly ["d414b12" "abc1234"])} "d414b12")))
    (is (= "d414b12" (latest-tag {:releases (constantly [])} "d414b12")))))

;; --- footnote text -----------------------------------------------------------

(deftest history-note-cases
  (is (= "" (history-note "v2" nil [] "since " "differs in ")))
  (is (= ", new or changed in v2" (history-note "v2" "v2" [] "since " "differs in ")))
  (is (= ", since v1; differs in v1.1, v1.2" (history-note "v2" "v1" ["v1.1" "v1.2"] "since " "differs in "))))

(deftest permalink-marks-markdown-plain
  (is (= "https://github.com/o/r/blob/v1/docs/a.md?plain=1#L3-L9"
         (permalink "o/r" "v1" {:path "docs/a.md" :lines [3 9]})))
  (is (= "https://github.com/o/r/blob/v1/src/a.clj#L3-L9"
         (permalink "o/r" "v1" {:path "src/a.clj" :lines [3 9]}))))

(deftest expected-footnotes-format
  (let [cfg {:source {:repo "acme/widget" :tag "v1.2.0"}
             :cite [{:id "install" :path "docs/guide.md" :heading "Install"}
                    {:id "run" :path "src/core.clj" :form "(defn run"}
                    {:id "stop" :path "src/core.clj" :line "(defn stop"}
                    {:id "other" :path "README.md" :line "Other things" :source {:repo "acme/other" :tag "v0.1.0"}}]
             :observed [{:id "seen" :example "ex-1"} {:id "fresh" :example "ex-2"}]
             :observed-on "Widget"}
        lock {"citations" {"install" {"lines" [3 12] "since" "v1.0.0" "differs" ["v1.1.1"]}
                           "run" {"lines" [3 5] "since" "v1.2.0" "differs" []}
                           "stop" {"lines" [7 7]}
                           "other" {"lines" [2 2]}}
              "observed" {"seen" {"since" "v1.1.0" "fails" ["v1.1.1"]}}}]
    (is (= {"install" "[^install]: [guide.md § Install, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/docs/guide.md?plain=1#L3-L12), unchanged since v1.0.0; differs in v1.1.1"
            "run" "[^run]: [core.clj `(defn run`, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/src/core.clj#L3-L5), new or changed in v1.2.0"
            "stop" "[^stop]: [core.clj line 7, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/src/core.clj#L7-L7)"
            "other" "[^other]: [README.md line 2, acme/other v0.1.0](https://github.com/acme/other/blob/v0.1.0/README.md?plain=1#L2-L2)"
            "seen" "[^seen]: Observed on Widget v1.2.0, holds on every tag the eval ran since v1.1.0; fails on v1.1.1."
            "fresh" "[^fresh]: Observed on Widget v1.2.0."}
           (expected-footnotes cfg lock)))))

(deftest unified-diff-keeps-only-the-changed-middle
  (is (= ["-b" "+B" "+C"] (unified-diff ["a" "b" "d"] ["a" "B" "C" "d"])))
  (is (= [] (unified-diff ["a"] ["a"]))))

(deftest parse-args-cases
  (is (= {:skill "dir" :source "tree" :to "latest" :json true}
         (parse-args ["dir" "--source" "tree" "--to" "latest" "--json"])))
  (is (= {:skill "dir"} (parse-args ["dir"])))
  (is (= {} (parse-args [])))
  (is (= {:skill "dir" :help true} (parse-args ["dir" "-h"])))
  (testing "an unknown option, a missing value or a second directory is an error"
    (is (= {:error "unknown option --bogus"} (parse-args ["dir" "--bogus"])))
    (is (= {:error "--to needs a value"} (parse-args ["dir" "--to"])))
    (is (= {:error "--source needs a value"} (parse-args ["--source" "--json" "dir"])))
    (is (= {:error "one SKILL-DIR only, got a and b"} (parse-args ["a" "b"])))))

(deftest cli-action-cases
  (testing "--help at the top lists every command and option"
    (doseq [args [["--help"] ["-h"] ["help"]]]
      (let [{:keys [help]} (cli-action args)]
        (is (every? #(str/includes? help %)
                    ["lock" "verify" "drift" "bump" "pairs" "--source" "--to" "--json" "--help"])))))
  (testing "COMMAND --help gives that command's usage, before any other check"
    (is (str/starts-with? (:help (cli-action ["drift" "--help"])) "usage: skill-citations drift SKILL-DIR --to"))
    (is (contains? (cli-action ["bump" "-h"]) :help)))
  (testing "a runnable command line"
    (is (= {:run "drift" :opts {:skill "d" :to "latest"}} (cli-action ["drift" "d" "--to" "latest"]))))
  (testing "usage errors"
    (doseq [args [[] ["nonsense" "d"] ["verify"] ["drift" "d"] ["lock" "d" "--bogus"]]]
      (is (contains? (cli-action args) :usage-error) (pr-str args)))))

;; --- history -----------------------------------------------------------------

(deftest with-history-finds-since-and-differs
  (let [skill (str (fs/create-temp-dir {:prefix "citations-history"}))
        _ (fs/create-dirs (fs/path skill "evals"))
        _ (spit (str (fs/path skill "evals" "releases.edn"))
                (pr-str {:tags {"v1.0.0" :tested "v1.1.0" :tested "v1.1.1" :tested "v1.2.0" :tested}
                         :results {"ex-1" {"v1.0.0" :fail "v1.1.0" :pass "v1.1.1" :fail "v1.2.0" :pass}}}))
        old-run (lines "(defn run" "  [x] (inc x))")
        new-run (lines "(defn run" "  [x] x)")
        text {"v1.0.0" old-run "v1.1.0" new-run "v1.1.1" old-run "v1.2.0" new-run}
        up {:commit identity
            :read (fn [ref _] (text ref))
            ;; v1.1.1 is a patch release of the older line, off the pinned tag's history.
            :history (constantly [{:tag "v1.0.0" :on-line? true} {:tag "v1.1.0" :on-line? true}
                                  {:tag "v1.1.1" :on-line? false} {:tag "v1.2.0" :on-line? true}])}
        cfg {:source {:repo "acme/widget" :tag "v1.2.0"}
             :cite [{:id "run" :path "src/core.clj" :form "(defn run"}]
             :observed [{:id "seen" :example "ex-1"}]
             :observations "evals/examples.edn"}
        ups (constantly up)
        lock (with-history skill cfg ups (build-lock cfg ups) nil)]
    (is (= {"path" "src/core.clj" "lines" [1 2] "sha256" (span-hash new-run [1 2])
            "since" "v1.1.0" "differs" ["v1.1.1"]}
           (get-in lock ["citations" "run"])))
    (is (= {"seen" {"since" "v1.1.0" "fails" ["v1.1.1"]}} (get lock "observed")))))

(deftest with-history-without-git-keeps-the-old-lock
  (let [cfg {:source {:repo "acme/widget" :tag "v1.2.0"} :cite []}
        old {"citations" {"run" {"since" "v1.0.0" "differs" ["v1.1.1"] "lines" [9 9]}}
             "observed" {"seen" {"since" "v1.1.0" "fails" []}}}
        lock {"citations" {"run" {"lines" [1 2]}}}]
    (is (= {"citations" {"run" {"lines" [1 2] "since" "v1.0.0" "differs" ["v1.1.1"]}}
            "observed" {"seen" {"since" "v1.1.0" "fails" []}}}
           (with-history "/nonexistent" cfg (constantly {:read (constantly nil)}) lock old)))))

;; --- lock and verify against a local tree ------------------------------------

(def own-commit "1111111111111111111111111111111111111111")
(def other-commit "2222222222222222222222222222222222222222")

(def skill-md
  (lines "# Widget"
         ""
         "Install it.[^install] Call run.[^run]"
         ""
         "## Stopping"
         ""
         "Stop it.[^stop] The other repo agrees.[^other] Seen in an eval.[^seen]"
         ""
         "```md"
         "A fenced [^fake] reference is not a citation."
         "```"))

(def citations-edn
  {:source {:repo "acme/widget" :tag "v1.2.0"}
   :observed-on "Widget"
   :observations "evals/examples.edn"
   :cite [{:id "install" :path "docs/guide.md" :heading "Install"}
          {:id "run" :path "src/core.clj" :form "(defn run"}
          {:id "stop" :path "src/core.clj" :line "(defn stop"}
          {:id "other" :path "README.md" :line "Other things" :source {:repo "acme/other" :tag "v0.1.0"}}]
   :observed [{:id "seen" :example "ex-1"}]})


(defn- quietly
  "Call f, dropping what it prints, and return its result."
  [f]
  (let [result (atom nil)]
    (with-out-str (reset! result (f)))
    @result))

(defn- put! [root path text]
  (let [f (fs/path root path)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- fixture!
  "A skill locked against an upstream tree, both in a fresh temporary directory."
  []
  (let [dir (fs/create-temp-dir {:prefix "citations-e2e"})
        skill (str (fs/path dir "skill"))
        tree (str (fs/path dir "tree"))]
    (put! tree "docs/guide.md" guide)
    (put! tree "src/core.clj" core)
    (put! tree "_sources/acme/other@v0.1.0/README.md" (lines "# Other" "Other things."))
    (put! skill "SKILL.md" skill-md)
    (put! skill "citations.edn" (pr-str citations-edn))
    (put! skill "evals/examples.edn" (pr-str {:example [{:id "ex-1"}]}))
    ;; --source needs a lock naming the commits the tree stands for.
    (put! skill "citations.lock.json"
          (json/generate-string {"repo" "acme/widget" "tag" "v1.2.0" "commit" own-commit
                                 "sources" {"acme/other@v0.1.0" {"commit" other-commit}}}))
    (let [problems (quietly #(cmd-lock skill {:source tree}))]
      (when (seq problems) (throw (ex-info "fixture failed to lock" {:problems problems}))))
    {:skill skill :tree tree}))

(defn- verify! [{:keys [skill tree]}]
  (quietly #(cmd-verify skill {:source tree})))

(defn- edit! [root path f]
  (let [file (str (fs/path root path))] (spit file (f (slurp file)))))

(defn- edit-config! [{:keys [skill]} f]
  (edit! skill "citations.edn" #(pr-str (f (clojure.edn/read-string %)))))

(deftest lock-writes-spans-hashes-and-footnotes
  (let [{:keys [skill tree]} (fixture!)
        lock (json/parse-string (slurp (str (fs/path skill "citations.lock.json"))))
        md (slurp (str (fs/path skill "SKILL.md")))]
    (is (= own-commit (get lock "commit")))
    (is (= {"docs/guide.md" (sha256 guide) "src/core.clj" (sha256 core)} (get lock "files")))
    (is (= {"repo" "acme/other" "tag" "v0.1.0" "commit" other-commit
            "files" {"README.md" (sha256 (lines "# Other" "Other things."))}}
           (get-in lock ["sources" "acme/other@v0.1.0"])))
    (is (= {"install" [3 12] "run" [3 5] "stop" [7 7] "other" [2 2]}
           (update-vals (get lock "citations") #(get % "lines"))))
    (is (= "acme/other@v0.1.0" (get-in lock ["citations" "other" "source"])))
    (is (= (span-hash guide [3 12]) (get-in lock ["citations" "install" "sha256"])))
    (testing "each new footnote goes at the end of the file citing it"
      (is (str/ends-with? md (str "[^install]: [guide.md § Install, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/docs/guide.md?plain=1#L3-L12)\n"
                                  "[^other]: [README.md line 2, acme/other v0.1.0](https://github.com/acme/other/blob/v0.1.0/README.md?plain=1#L2-L2)\n"
                                  "[^run]: [core.clj `(defn run`, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/src/core.clj#L3-L5)\n"
                                  "[^seen]: Observed on Widget v1.2.0.\n"
                                  "[^stop]: [core.clj line 7, v1.2.0](https://github.com/acme/widget/blob/v1.2.0/src/core.clj#L7-L7)\n"))))
    (testing "a second lock changes nothing"
      (let [before (map #(slurp (str (fs/path skill %))) ["SKILL.md" "citations.lock.json"])]
        (is (empty? (quietly #(cmd-lock skill {:source tree}))))
        (is (= before (map #(slurp (str (fs/path skill %))) ["SKILL.md" "citations.lock.json"])))))))

(deftest references-skip-fences-and-definitions
  (let [{:keys [skill]} (fixture!)]
    (is (= {"install" [["SKILL.md" 3 "Widget"]]
            "run" [["SKILL.md" 3 "Widget"]]
            "stop" [["SKILL.md" 7 "Stopping"]]
            "other" [["SKILL.md" 7 "Stopping"]]
            "seen" [["SKILL.md" 7 "Stopping"]]}
           (references skill)))))

(deftest verify-passes-on-a-fresh-lock
  (is (= [] (vec (verify! (fixture!))))))

(deftest verify-catches-each-disagreement
  (testing "an upstream file that no longer matches its locked hash"
    (let [fx (fixture!)]
      (edit! (:tree fx) "src/core.clj" #(str/replace % "  x)" "  (inc x))"))
      (is (= ["[run] src/core.clj does not match the locked file hash"
              "[stop] src/core.clj does not match the locked file hash"]
             (vec (verify! fx))))))
  (testing "a second source's file that changed"
    (let [fx (fixture!)]
      (edit! (:tree fx) "_sources/acme/other@v0.1.0/README.md" #(str % "More.\n"))
      (is (= ["[other] README.md does not match the locked file hash"] (vec (verify! fx))))))
  (testing "a cited file missing from the tree"
    (let [fx (fixture!)]
      (fs/delete (fs/path (:tree fx) "docs/guide.md"))
      (is (= ["[install] docs/guide.md missing at the locked commit"] (vec (verify! fx))))))
  (testing "a footnote edited by hand"
    (let [fx (fixture!)]
      (edit! (:skill fx) "SKILL.md" #(str/replace % "line 7, v1.2.0" "line 8, v1.2.0"))
      (is (= ["footnote [^stop] is stale or missing; run `lock`"] (vec (verify! fx))))))
  (testing "a passage citing an id citations.edn lacks"
    (let [fx (fixture!)]
      (edit! (:skill fx) "SKILL.md" #(str/replace % "Install it." "Install it.[^nope]"))
      (is (= ["SKILL.md:3 cites [^nope], which citations.edn does not define"] (vec (verify! fx))))))
  (testing "a footnote no passage cites"
    (let [fx (fixture!)]
      (edit! (:skill fx) "SKILL.md" #(str/replace % "Stop it.[^stop]" "Stop it."))
      (is (= ["[^stop] is defined but no passage cites it"] (vec (verify! fx))))))
  (testing "a lock pinning another tag than citations.edn"
    (let [fx (fixture!)]
      (edit-config! fx #(assoc-in % [:source :tag] "v1.3.0"))
      ;; With --source the tree can't stand for the new tag, so verify stops early.
      (is (= "the lock has no commit for acme/widget@v1.2.0; run `lock`" (thrown-message #(verify! fx))))
      (let [skill (:skill fx)]
        (is (includes-some? (verify skill (load-config skill) (read-lock skill) nil)
                            "lock pins acme/widget@v1.2.0, citations.edn says acme/widget@v1.3.0")))))
  (testing "a citation added to citations.edn but not locked"
    (let [fx (fixture!)]
      (edit-config! fx #(update % :cite conj {:id "usage" :path "docs/guide.md" :heading "Usage"}))
      (is (includes-some? (verify! fx) "lock and citations.edn disagree on ids; run `lock`"))))
  (testing "a citation moved to another source but not locked"
    (let [fx (fixture!)]
      (edit-config! fx (fn [cfg] (update cfg :cite (fn [cs] (mapv #(if (= "other" (:id %)) (assoc-in % [:source :tag] "v0.2.0") %) cs)))))
      (is (includes-some? (verify! fx) "[other] cites acme/other@v0.2.0, the lock says acme/other@v0.1.0; run `lock`"))))
  (testing "an anchor changed in citations.edn but not locked"
    (let [fx (fixture!)]
      (edit-config! fx (fn [cfg] (update cfg :cite (fn [cs] (mapv #(if (= "install" (:id %)) (assoc % :heading "Usage") %) cs)))))
      (is (= ["footnote [^install] is stale or missing; run `lock`"
              "[install] resolves to lines 14-16, not the locked span; run `lock`"]
             (vec (verify! fx))))))
  (testing "an observation of an example the eval lacks"
    (let [fx (fixture!)]
      (edit! (:skill fx) "evals/examples.edn" (constantly (pr-str {:example [{:id "ex-2"}]})))
      (is (= ["[^seen] observes example `ex-1`, which evals/examples.edn lacks"] (vec (verify! fx)))))))

(deftest load-config-rejects-duplicate-ids
  (let [fx (fixture!)]
    (edit-config! fx #(update % :observed conj {:id "run" :example "ex-1"}))
    (is (= "duplicate citation ids: run" (thrown-message #(load-config (:skill fx)))))))

;; --- the command line ----------------------------------------------------------

(defn- cli [& args]
  (apply p/shell {:out :string :err :string :continue true} "bb" script args))

(deftest cli-exit-codes-and-messages
  (testing "verify succeeds only when everything agrees"
    (let [{:keys [skill tree] :as fx} (fixture!)
          ok (cli "verify" skill "--source" tree)]
      (is (= [0 "citations verified\n" ""] [(:exit ok) (:out ok) (:err ok)]))
      (edit! (:tree fx) "docs/guide.md" #(str/replace % "Run the installer." "Run the setup."))
      (let [bad (cli "verify" skill "--source" tree)]
        (is (= [1 "" "problem: [install] docs/guide.md does not match the locked file hash\n"]
               [(:exit bad) (:out bad) (:err bad)])))))
  (testing "a missing lock is an error, exit 2"
    (let [{:keys [skill tree]} (fixture!)]
      (fs/delete (fs/path skill "citations.lock.json"))
      (let [r (cli "verify" skill "--source" tree)]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "citations.lock.json is missing; run `lock` first")))))
  (testing "--help prints to stdout, exit 0"
    (let [r (cli "--help")]
      (is (= [0 ""] [(:exit r) (:err r)]))
      (is (str/starts-with? (:out r) "usage: skill-citations")))
    (is (= 0 (:exit (cli "verify" "--help")))))
  (testing "usage errors, exit 2"
    (is (= 2 (:exit (cli))))
    (is (= 2 (:exit (cli "nonsense" "dir"))))
    (is (= 2 (:exit (cli "verify"))))
    (let [r (cli "drift" "dir")]
      (is (= [2 "drift needs --to\n"] [(:exit r) (:err r)])))))

(def usage
  (str "usage: skill-citations-tests [--help]\n\n"
       "Run the unit and offline end-to-end tests of skill-citations.\n\n"
       "options:\n"
       "  -h, --help  print this help\n\n"
       "exit status: 0 all passed, 1 a test failed, 2 bad usage"))

(let [args *command-line-args*]
  (cond (some #{"-h" "--help"} args) (do (println usage) (System/exit 0))
        (seq args) (do (binding [*out* *err*] (println usage)) (System/exit 2))
        :else (let [{:keys [fail error]} (t/run-tests 'skill-citations-test)]
                (System/exit (if (zero? (+ fail error)) 0 1)))))
