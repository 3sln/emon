;; A new Maven Central signing key, end to end, run by the maintainer on their own machine:
;;   bb tools/release/gpg_key.clj --name "3sln emon release signing" --email you@example.com [--out DIR] [--repo 3sln/emon] [--yes]
;;
;; 1. generates an RSA-4096 signing key (valid 3 years) in your normal GnuPG keyring; the passphrase is read
;;    from the terminal without echo (empty = no passphrase);
;; 2. writes to DIR (default ~/emon-release-key, mode 700): the armored PRIVATE key (mode 600), the public key
;;    and the revocation certificate GnuPG made;
;; 3. publishes the public key to keyserver.ubuntu.com and keys.openpgp.org (Central checks either; the second
;;    emails you a verification link that must be clicked);
;; 4. sets the GitHub secrets MAVEN_GPG_PRIVATE_KEY (and MAVEN_GPG_PASSPHRASE, or deletes it for an empty one)
;;    on the repository through `gh`, reading them from the files/stdin, never from the command line.
;; Each network or GitHub step asks first unless --yes. Nothing secret is printed.
;; Afterwards: put DIR's three files and the passphrase in your password manager, then delete DIR.
(require '[babashka.process :as p] '[babashka.fs :as fs] '[clojure.string :as str])

(defn die [& m] (binding [*out* *err*] (println (apply str "gpg_key: " m))) (System/exit 1))
(defn arg [k] (second (drop-while #(not= % k) *command-line-args*)))
(def yes? (some #{"--yes"} *command-line-args*))
(def name* (or (arg "--name") (die "--name is required, e.g. --name \"3sln emon release signing\"")))
(def email (or (arg "--email") (die "--email is required")))
(def out (str (fs/expand-home (or (arg "--out") "~/emon-release-key"))))
(def repo (or (arg "--repo") "3sln/emon"))

(defn sh! [opts & cmd] (let [r (apply p/shell (merge {:out :string :err :string :continue true} opts) cmd)]
                         (when-not (zero? (:exit r)) (die (str/join " " cmd) " failed: " (str/trim (:err r))))
                         r))
(defn ask [q] (or yes? (do (print (str q " [y/N] ")) (flush) (contains? #{"y" "yes"} (str/lower-case (str/trim (or (read-line) "")))))))
(defn secret-line [q] (let [c (System/console)] (when-not c (die "no terminal: run this in an interactive shell"))
                        (String. (.readPassword c "%s" (into-array Object [q])))))

(when-not (fs/which "gpg") (die "gpg not found (brew install gnupg)"))
(when-not (fs/which "gh") (die "gh not found (brew install gh), and `gh auth login` first"))
(when (and (fs/exists? out) (seq (fs/list-dir out))) (die out " exists and is not empty; pass --out to choose another directory"))

;; 1. generate
(def pass (let [a (secret-line "Passphrase for the new key (empty = none): ")
                b (secret-line "Repeat it: ")]
            (when (not= a b) (die "the passphrases differ"))
            a))
(def uid (str name* " <" email ">"))
(println "generating an RSA-4096 signing key for" uid "...")
(sh! {:in pass} "gpg" "--batch" "--pinentry-mode" "loopback" "--passphrase-fd" "0"
     "--quick-generate-key" uid "rsa4096" "sign" "3y")
(def fpr (let [lines (str/split-lines (:out (sh! {} "gpg" "--with-colons" "--list-secret-keys" "--" uid)))]
           (->> lines (filter #(str/starts-with? % "fpr:")) last (#(nth (str/split % #":") 9)))))
(when (str/blank? fpr) (die "could not read the new key's fingerprint"))
(println "fingerprint:" fpr)

;; 2. export
(fs/create-dirs out)
(sh! {} "chmod" "700" out)
(def secret-file (str out "/emon-signing-" fpr ".secret.asc"))
(def public-file (str out "/emon-signing-" fpr ".public.asc"))
(def rev-file (str out "/emon-signing-" fpr ".revocation.asc"))
(spit secret-file "")
(sh! {} "chmod" "600" secret-file)
(sh! {:in pass} "gpg" "--batch" "--pinentry-mode" "loopback" "--passphrase-fd" "0"
     "--armor" "--output" secret-file "--yes" "--export-secret-keys" fpr)
(sh! {} "gpg" "--armor" "--output" public-file "--yes" "--export" fpr)
(let [rev (fs/path (fs/expand-home "~/.gnupg/openpgp-revocs.d") (str fpr ".rev"))]
  (if (fs/exists? rev) (fs/copy rev rev-file {:replace-existing true}) (println "note: no revocation certificate found at" (str rev))))
(println "wrote" out "(private key, public key, revocation certificate)")

;; 3. publish the public key
(when (ask "Publish the public key to keyserver.ubuntu.com and keys.openpgp.org?")
  (sh! {} "gpg" "--keyserver" "hkps://keyserver.ubuntu.com" "--send-keys" fpr)
  (println "  sent to keyserver.ubuntu.com")
  (let [r (sh! {:in (slurp public-file)} "curl" "-sS" "--fail" "-T" "-" "https://keys.openpgp.org")]
    (println "  keys.openpgp.org answered:" (str/trim (:out r)))
    (println "  keys.openpgp.org also needs you to verify the email: open the token link it shows or request it with")
    (println "    gpg --export" fpr "| curl -T - https://keys.openpgp.org   then follow the link it prints")))

;; 4. GitHub secrets
(when (ask (str "Set MAVEN_GPG_PRIVATE_KEY" (if (str/blank? pass) " (and delete MAVEN_GPG_PASSPHRASE)" " and MAVEN_GPG_PASSPHRASE") " on " repo "?"))
  (sh! {:in (slurp secret-file)} "gh" "secret" "set" "MAVEN_GPG_PRIVATE_KEY" "--repo" repo)
  (if (str/blank? pass)
    (p/shell {:continue true :out :string :err :string} "gh" "secret" "delete" "MAVEN_GPG_PASSPHRASE" "--repo" repo)
    (sh! {:in pass} "gh" "secret" "set" "MAVEN_GPG_PASSPHRASE" "--repo" repo))
  (println "  GitHub secrets updated on" repo))

(println)
(println "Next:")
(println " 1. Store" out "(all three files) and the passphrase in your password manager; then delete" out ".")
(println " 2. Click the keys.openpgp.org verification email (Central accepts keyserver.ubuntu.com alone, but both is safer).")
(println " 3. Check Central can see it:  gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys" fpr)
(println " 4. The old key is lost: it cannot be revoked; it simply stops being used. Nothing else references it.")
