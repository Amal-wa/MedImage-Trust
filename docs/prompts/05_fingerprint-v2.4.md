# Prompt Claude Code 05 — `medimage-fingerprint` sur la couche DCT/QIM (v2.4)

## Avant (PowerShell, dans `C:\Users\amelb\Documents\MedImage-Trust`)

```powershell
git fetch --all --prune
git status                                       # arbre propre ; fix/crypto-v2.4 déjà poussée
git switch -c fix/fingerprint-v2.4 origin/fix/crypto-v2.4
Test-Path .\docs\MedImage-Trust_v2.4_complet.pdf  # True
```

Lance `claude` et colle le bloc ci-dessous.

---

## PROMPT À COLLER

Tu travailles sur le dépôt **MedImage-Trust** (Java 25), branche `fix/fingerprint-v2.4`. Le module `backend/medimage-fingerprint` (code de Tardos, ≤ 64 destinataires, 2 complices) est solide, mais il écrit encore F selon l'**ancienne spécification v2.3** :
- LSB pixel par pixel, via le port `FPartition` (« rangs modulo 10 ») ;
- 1 024 symboles × **3 répétitions** = 3 072 bits ;
- classe F ≥ 6 144 pixels.

Le dossier **v2.4** (`docs/MedImage-Trust_v2.4_complet.pdf`, §4.1 et §8.2) et la consigne du professeur imposent une couche **robuste en DCT 8×8 / QIM** :
- 1 024 symboles, **1 bit par coefficient** ;
- 8 coefficients par bloc, donc **128 blocs** ;
- blocs tirés secrètement dans une classe F d'**au moins 256 blocs** (marge ×2).

Cette couche est déjà implémentée par le module **`medimage-embedding`** (interface `FBlockChannel`). L'équipe m'a autorisée à brancher fingerprint dessus.

### Règles impératives
1. **Ni `git commit` ni `git push`.**
2. **Ne lis aucun fichier hors du dépôt courant.** Si le PDF v2.4 est absent de `docs/`, arrête-toi et signale-le. Lis `backend/medimage-embedding/README.md` (section `FBlockChannel`) avant de commencer.
3. Ne modifie **pas** `medimage-embedding`, `medimage-crypto`, `medimage-audit` ni `medimage-web`. Si l'interface `FBlockChannel` ne suffit pas, **arrête-toi et explique-moi pourquoi** au lieu de la changer.
4. **Garde intacts** :
   - la génération du code (`TardosCodeGenerator`, biais, `MIT-F-v1`, `MIT-FB-v1`) ;
   - le score (`TardosScorer`, `TardosMath`) ;
   - le seuil (`ThresholdCalibrator`) ;
   - l'analyse et les verdicts (`ColluderAnalyzer`, `AttributionReport`, `W_F_INCONSISTENT`, `TOO_MANY_ERASURES`) ;
   - l'engagement `fCommit` (`MIT-FC-v1`).

   Seule la **couche d'écriture/lecture** change.
5. Messages et Javadoc en français. Les messages d'erreur ne contiennent ni taille, ni position, ni valeur (positions secrètes, SEC-D04).

### Ce qu'il faut changer
1. **Dépendance** : `medimage-fingerprint` dépend de `medimage-embedding` (pom du module ; version gérée par le parent).
2. **`FingerprintParams`** :
   - `REPETITIONS = 1`, `CODE_BITS = SYMBOLS = 1 024` ;
   - `BITS_PER_BLOCK = 8` et `USED_BLOCKS = 128`, repris de `EmbeddingProfile` (ne pas dupliquer les valeurs) ;
   - minimum de la classe F : `EmbeddingProfile.MIN_F_CLASS_BLOCKS` (256) ; supprime `MIN_F_CLASS_PIXELS`.
3. **`PositionPermutation`** : même mélange de Fisher-Yates piloté par HMAC, même graine `MIT-POS-v1 ‖ maskDigest`, mais on tire **128 indices de blocs** parmi `FBlockChannel.classSize()`. Le symbole `i` va dans le bloc `pos[i / 8]`, au coefficient `i % 8`.
4. **Dither QIM** : 8 valeurs par bloc utilisé, dérivées de K_F, uniformes dans `[0, Δ)` avec `Δ = EmbeddingProfile.QIM_DELTA` :
   - flux `HmacStream` avec l'étiquette **`MIT-FD-v1`** ‖ maskDigest ‖ indice du bloc (4 octets, gros-boutiste) ;
   - conversion : 53 bits du flux → double dans [0, 1), multiplié par Δ.

   Ajoute `LABEL_DITHER = "MIT-FD-v1"`, marquée « AJOUT de l'équipe (dossier : « dither dérivé de K_F », sans étiquette) », comme `MIT-FB-v1`.
5. **`FingerprintEmbedder`** :
   - `embed(FBlockChannel, maskDigest, boolean[1024])` : écrit les 128 blocs, chacun une seule fois, avec ses 8 dithers ;
   - `extract(FBlockChannel, maskDigest)` : lit les 128 blocs ; `UNREADABLE` → `ExtractedWord.ERASURE` ;
   - plus de vote : supprime `VotePolicy` (sans objet avec 1 répétition) et adapte les appelants ;
   - capacité < 256 blocs → `FingerprintException.Code.CAPACITY`.
6. **Supprime** `FPartition` et `InMemoryFPartition`. Pour les tests, crée dans `src/test` un `InMemoryFBlockChannel` qui reproduit exactement le contrat de `FBlockChannel` :
   - QIM réelle sur des blocs synthétiques, en utilisant `QimBlockCodec` d'embedding si c'est public ; sinon une copie de test documentée ;
   - `UNREADABLE` simulable (recadrage).
7. **`TardosCodeGenerator.expand`** : avec 1 répétition, renvoie les 1 024 bits du mot de code. Adapte `TardosCodeGeneratorTest` en conséquence, sans perdre ce qu'il vérifie.
8. **`FingerprintService`** : même façade. `embed(FBlockChannel, maskDigest, EmissionFingerprint)`, `analyze(imageId, FBlockChannel suspect, maskDigest, candidates, wRecipient)`. Le `kid` est un `int` (déjà fait au prompt 04).
9. **Collusion** : `CollusionSimulator` simule maintenant le mélange **au niveau des symboles et des blocs** :
   - un complice prend un bloc entier de l'une ou l'autre copie ;
   - ou mélange symbole par symbole ;
   - plus le bruit QIM et les effacements (recadrage).

   Garde les stratégies existantes qui ont encore un sens et documente celles qui n'en ont plus.

### Tests à faire passer (sans en affaiblir aucun)
- Tous les tests existants qui ont encore un sens, adaptés. Supprime seulement ceux qui testaient les 3 répétitions ou le vote, et **explique chaque suppression** dans le résumé.
- Nouveaux :
  - aller-retour du code F sur `InMemoryFBlockChannel` à 100 % ;
  - les blocs non choisis ne sont jamais écrits ;
  - mêmes `maskDigest` et K_F → mêmes 128 blocs et mêmes dithers ; une autre K_F → d'autres blocs ;
  - classe F de 255 blocs → `CAPACITY` ;
  - 60 % de blocs illisibles → `TOO_MANY_ERASURES`, aucune accusation.
- **Test d'intégration avec le vrai module embedding** : sur un fantôme synthétique 512 × 512 (réutilise ou recopie le générateur de test d'embedding), session complète `EmbeddingSession` (F par fingerprint, S et W aléatoires), `finish()` réussi, puis analyse de la copie : le bon destinataire est accusé, aucun innocent.
- **Simulation Monte-Carlo SEC-D04** : 3 000 collusions par stratégie. Objectif : **0 fausse accusation** et au moins un complice détecté dans ≥ 99 % des cas. Si l'objectif n'est pas tenu à cause de la perte des 3 répétitions, **ne change ni la longueur du code ni le seuil en douce** : donne les chiffres et propose des options dans le README.

### Documentation
- `medimage-fingerprint/README.md` : remplace les tableaux et la section « Reste à brancher » par l'état v2.4 :
  - lien vers `FBlockChannel` ;
  - correspondance symbole → bloc → coefficient ;
  - étiquettes utilisées ;
  - résultats de la simulation.
- `backend/README.md` : retire « fingerprint encore en LSB v2.3 » des écarts connus.

### Pour finir
- `mvn -B clean test` à la racine de `backend/` : tous les modules passent.
- Résumé :
  - les fichiers modifiés et supprimés, avec le pourquoi ;
  - les tests supprimés et la raison de chaque suppression ;
  - le nombre de tests ;
  - les chiffres de la simulation (fausses accusations, taux de détection, par stratégie) ;
  - les décisions à valider.
- **Aucun commit, aucun push.**

---

## Après (PowerShell)

```powershell
git status
cd backend; mvn -B clean test; cd ..
gitleaks detect --source . --no-banner -v
git add -A backend
git commit -m "fix(fingerprint): couche DCT/QIM v2.4 via FBlockChannel (128 blocs, dither K_F), simulation SEC-D04"
git push -u origin fix/fingerprint-v2.4
```
