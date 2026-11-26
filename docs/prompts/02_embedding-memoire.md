# Prompt Claude Code — `medimage-embedding` : budget mémoire (36 Mpx ≈ 216 Mo)

## Avant (PowerShell, à la racine du dépôt)

```powershell
git fetch --all --prune
git status                                            # arbre propre ; fix/fingerprint-v2.4 déjà poussée
git switch -c perf/embedding-memoire origin/fix/fingerprint-v2.4
Test-Path .\docs\MedImage-Trust_v2.4_complet.pdf      # True
```

Ce prompt est lancé **après** les prompts 03 à 05 : il part donc de la dernière branche de la chaîne, `fix/fingerprint-v2.4`, où fingerprint utilise déjà embedding. Lance `claude` et colle le bloc ci-dessous.

---

## PROMPT À COLLER

Contexte : le module `backend/medimage-embedding` existe (dépôt MedImage-Trust, branche `perf/embedding-memoire`, créée depuis `fix/fingerprint-v2.4`). Le module `medimage-fingerprint` l'utilise déjà (`FBlockChannel`, et `EmbeddingSession` dans `EmbeddingIntegrationTest`). Le README du module signale un dépassement (section « Décisions à valider ») : avec des `int[]`, une image de 36 Mpx dépasse le budget mémoire du dossier v2.4. Le dossier (§ « Mémoire », limites du prototype) compte, pour 36 Mpx, **original 72 Mo + copie 72 Mo + masque 36 Mo + tampon 36 Mo ≈ 216 Mo par traitement** (4 traitements simultanés, tas JVM de 2 Gio). Cible : **≤ 216 Mo**, avec un plafond absolu de 250 Mio. Corrige ce point, **et seulement celui-ci**.

### Règles
1. **Ni commit ni push.** Je relis et je pousse moi-même.
2. **Ne lis aucun fichier hors du dépôt courant.**
3. Ne modifie **que** `backend/medimage-embedding/`. Seule exception : si le passage à `char[]` casse la compilation d'un **test** de `medimage-fingerprint` (par exemple `EmbeddingIntegrationTest`), adapte **uniquement l'appel** (par `PixelImage.fromInts(...)`), sans rien changer à ce qu'il vérifie. Aucun autre module, ni `backend/pom.xml`, ni `.github/`, ni `docs/`.
4. **Aucun changement de comportement** : mêmes constantes du profil v2, mêmes motifs d'erreur, mêmes condensats (les vecteurs fixes de `DigestsTest` doivent rester identiques), mêmes résultats de détection et de marquage. Tous les tests existants doivent passer **sans être affaiblis**.
5. Toujours aucune dépendance externe en dehors du JDK et de JUnit.

### Ce qu'il faut changer
1. **Pixels en `char[]`** (16 bits non signés : couvre `bitsStored` de 8 à 16), à la place de `int[]`, dans `PixelImage` et dans **toutes** les copies de travail (session, canal LSB, canal F, contrôle final).
   - Le constructeur principal de `PixelImage` reçoit un `char[]`. Garde une fabrique `PixelImage.fromInts(...)` pour les tests et les petits appels ; elle valide chaque valeur (`0 … maxValue`), sinon `INVALID_INPUT`.
   - `EmbeddingLimits.checkDimensions` reste appelé **en premier**, avant toute allocation.
   - Aucun accesseur ne doit exposer le tableau interne : copies défensives, comme avant.
2. **Masques compactés** : 1 bit par pixel (`long[]` ou `java.util.BitSet`) à la place de `boolean[]` ou `int[]`, pour le masque de fond, le masque érodé et le masque de la RONI.
3. **Érosion et parcours en largeur** :
   - pas de tableau `int` de taille `rows × cols` ;
   - tampons de taille ligne ou colonne pour le minimum glissant ;
   - file de parcours compacte (pas de `ArrayDeque<Integer>`, qui encapsule chaque entier dans un objet).
4. **Ne garder que le nécessaire** : au plus **une copie de l'original** (pour le contrôle « ROI bit à bit ») et **une copie de travail** pendant la session. Les tableaux temporaires sont libérés (référence à `null`) dès qu'ils ne servent plus.

### Preuve attendue
1. **Bilan mémoire dans le README** : un tableau qui donne, pour 36 Mpx, chaque tableau alloué, son type, sa taille en Mio et sa durée de vie, puis le **pic total** estimé. Le pic doit être **≤ 216 Mo** (plafond absolu : 250 Mio).
2. **Test de mémoire réel** : un test `@Tag("memory")`, exclu de `mvn -B test` par défaut, qui fait une session complète sur une image **6 000 × 6 000** (36 Mpx, 16 bits, fond bruité). Il doit être lançable avec :
   ```
   mvn -B -pl medimage-embedding test -Dgroups=memory -DargLine=-Xmx300m
   ```
   et passer **sans `OutOfMemoryError`**. Configure Surefire **dans le pom du module seulement** pour exclure ce tag par défaut.
3. Mets à jour la ligne « Mémoire » de la section « Décisions à valider » du README avec le pic mesuré.

### Pour finir
- `mvn -B clean test` à la racine de `backend/` : les **cinq** modules passent.
- Le test mémoire avec `-Xmx300m` : il passe.
- Résumé : fichiers modifiés, pic mémoire estimé et mesuré, temps sur 512 × 512 avant et après. **Aucun commit, aucun push.**

---

## Après (PowerShell)

```powershell
git status                                   # backend/medimage-embedding/ (+ éventuellement un test de fingerprint)
cd backend
mvn -B clean test
mvn -B -pl medimage-embedding test "-Dgroups=memory" "-DargLine=-Xmx300m"
cd ..
git add backend
gitleaks git --pre-commit --staged -v
git commit -m "perf(embedding): pixels en char[], masques compactes, pic memoire <= 216 Mo a 36 Mpx"
git push -u origin perf/embedding-memoire
```
