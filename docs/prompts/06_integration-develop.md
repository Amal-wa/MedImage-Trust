# Prompt Claude Code 06 — Intégrer `develop` (watermark de Nour) dans la chaîne v2.4

## Pourquoi
Pendant qu'on corrigeait la chaîne (01 à 05 et 02), Nour a fusionné le module **watermark** dans `develop` (PR #3, commit `0765e2e`). Ce module s'appuie sur l'**ancienne** API crypto (kid en `String`, `signBinary` et `verifyBinary`), alors que le prompt 04 l'a remplacée par l'API v2.4 (kid en `int`, `signBytes` et `verifyBytes`). Une pull request `perf/embedding-memoire` → `develop` aurait donc des **conflits** (`backend/pom.xml`, `CryptoService.java`), et watermark **ne compilerait plus**.

**Solution :** fusionner d'abord `develop` **dans** notre branche, en local, et adapter watermark. Ensuite, la pull request vers `develop` passe sans conflit.

## Avant (PowerShell, dans `C:\Users\amelb\Documents\MedImage-Trust`)

```powershell
git fetch --all --prune
git switch perf/embedding-memoire
git status                                         # arbre propre (doc v2.4 et prompts déjà poussés)
git merge --no-ff --no-commit origin/develop       # conflits attendus : backend/pom.xml, CryptoService.java
git status
```

Lance `claude` et colle le bloc ci-dessous.

---

## PROMPT À COLLER

Tu travailles sur le dépôt **MedImage-Trust** (Java 25), branche `perf/embedding-memoire`, qui contient toute la chaîne v2.4 : embedding, harmonisation, crypto v2.4, fingerprint v2.4 et mémoire. Une fusion de `origin/develop` est **en cours** (`--no-commit`). `develop` apporte le module **`backend/medimage-watermark`** de Nour (charge W binaire de 208 octets, `MIT-W-v1`, vérification en échec fermé), écrit pour l'**ancienne** API crypto.

### Règles impératives
1. **Ni `git commit` ni `git push`.** Laisse la fusion en cours, avec les fichiers résolus indexés (`git add`). Je conclurai moi-même.
2. **Ne lis aucun fichier hors du dépôt courant.** Référence : `docs/MedImage-Trust_v2.4_complet.pdf` (§8.3 pour W), `backend/medimage-crypto/README.md` (API v2.4) et `backend/medimage-embedding/README.md` (condensats).
3. **En cas de conflit, notre branche fait foi** pour crypto, embedding, fingerprint, audit, web et la structure Maven. Les ajouts de `develop` sont **gardés**.
4. Dans watermark : **uniquement** l'adaptation à l'API crypto v2.4 et au kid entier. Ne change ni le format des 208 octets, ni l'ordre des vérifications V1 à V6, ni les motifs de `VerificationReason`. Aucun test ne doit être supprimé ni affaibli.
5. Messages et Javadoc en français.

### Tâches
1. **`backend/pom.xml`** : garde notre version. Ajoute `medimage-watermark` aux modules (après fingerprint, avant audit) et à `dependencyManagement`. Retire `medimage-watermark` du commentaire « à venir » (il ne reste que dicom et stego).
2. **`CryptoService.java`** : garde **notre** version (v2.4). N'ajoute pas `signBinary` ni `verifyBinary` en `String`.
3. **Adapter `medimage-watermark`** :
   - `cryptoService.signBinary(String.valueOf(kid), m)` → `signBytes(kid, m)`, et `verifyBinary` → `verifyBytes(kid, m, sig)` ;
   - `computeFCommit(String.valueOf(kid), …)` → `computeFCommit(kid, …)` ;
   - **Vérifie dans le code de crypto** si `signBytes` ajoute lui-même un préfixe de domaine. Le message signé doit être **exactement** `"MIT-W-v1" ‖ 0x00 ‖ en-tête(144)`, avec le préfixe **une seule fois**. Ajoute un test qui le prouve : signer avec watermark, puis vérifier la signature avec `Ed25519` et la clé publique du kid, sur ce message exact ;
   - `PublicKeyStatusRegistry` : le kid devient un `int` (0 à 65 535), comme partout ailleurs. `KeyMetadata.kid` et `findByKid` sont en `int` ; supprime `kidAsString()` s'il ne sert plus ;
   - tests : `InMemoryKeyProvider` s'utilise désormais avec `registerNewKek(kid)`. Adapte la mise en place, sans toucher aux assertions.
4. **Vérifie la cohérence avec embedding**, sans rien modifier, et note-la dans `medimage-watermark/README.md` (à créer s'il n'existe pas) :
   - les 208 octets de W correspondent à `LsbChannel.writeW(byte[208])` ;
   - `roiDigest`, `maskDigest` et `sDigest` doivent venir de `Digests` d'embedding (même encodage) ;
   - le vérificateur devra parcourir `RoniDetector.candidates()` un par un.
   Ce qui manque est listé comme « à brancher » (prochaine étape de Nour).
5. **`backend/README.md`** : ajoute watermark à l'arbre des modules (responsable : Nour) et au graphe de dépendances (crypto ← watermark ; embedding ← watermark, à brancher).

### Pour finir
- `mvn -B clean test` à la racine de `backend/` : les **six** modules passent (crypto, embedding, fingerprint, watermark, audit, web).
- `git status` : la fusion est en cours, tous les conflits sont résolus et indexés.
- Résumé :
  - les conflits et la façon dont tu les as résolus ;
  - les fichiers de watermark modifiés, avec le pourquoi ;
  - la réponse sur le préfixe de domaine (une seule fois ?) ;
  - le nombre de tests par module ;
  - les écarts avec la v2.4 que tu as relevés dans watermark, sans les corriger.
- **Aucun commit, aucun push.**

---

## Après (PowerShell)

```powershell
git status
cd backend; mvn -B clean test; cd ..
git add backend
gitleaks git --pre-commit --staged -v
git commit -m "merge: integre develop (watermark) dans la chaine v2.4 ; watermark adapte a l'API crypto v2.4"
git push
```

Ensuite, sur GitHub : **pull request `perf/embedding-memoire` → `develop`**. Elle ne doit plus avoir de conflit.
