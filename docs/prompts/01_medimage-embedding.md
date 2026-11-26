# Prompt Claude Code — Module `medimage-embedding` (MedImage-Trust)

## Avant de lancer Claude Code (PowerShell, à la racine du dépôt)

```powershell
git fetch --all --prune
git switch -c feat/embedding origin/features/crypto-fingerprint   # dernier commit attendu : 20161cc « feat: add audit module »
# si la branche feat/embedding existe déjà en local :
#   git switch feat/embedding ; git reset --hard origin/features/crypto-fingerprint
git log -1 --oneline
java -version    # 25
mvn -v           # « Java version: 25 »
```

Copie aussi le dossier v2.4 dans le dépôt local, **sans le commiter dans cette branche** (tu le pousseras à part) :

```powershell
Copy-Item "$HOME\Downloads\MedImage-Trust_v2.4_complet.pdf" .\docs\
```

Puis lance `claude` dans ce dossier et colle tout le bloc ci-dessous.

---

## PROMPT À COLLER

Tu travailles sur le dépôt **MedImage-Trust** (projet de Sécurité Applicative, Sup'COM) : une plateforme Jakarta EE 11 / WildFly / Java 25 qui protège des images médicales DICOM par trois marques : **W** (watermarking, preuve d'origine signée Ed25519), **S** (stéganographie, jeton patient chiffré) et **F** (fingerprinting, code de Tardos pour tracer une fuite).

**État actuel du dépôt (branche de départ `features/crypto-fingerprint`) :**
- `backend/pom.xml` : pom parent ; modules déclarés : `medimage-crypto` et `medimage-fingerprint`.
- `backend/medimage-audit` : journal chaîné (Nourhen). Module **autonome**, pas encore déclaré dans le parent.
- `medimage-web` existe sur une autre branche, pas ici.
- **Référence fonctionnelle : le dossier v2.4** (`docs/MedImage-Trust_v2.4_complet.pdf`, sections 4.1 et 8.3). Le PDF v2.3 éventuellement présent dans `docs/` est **obsolète**. Si le code des autres modules contredit la v2.4 (par exemple `FPartition` ou `FingerprintParams`, encore en LSB v2.3), **la v2.4 et ce prompt font foi**. Ne corrige pas leur code : note seulement l'écart dans le README.

Ta tâche : créer le module Maven **`backend/medimage-embedding`**. C'est la **brique commune** dont dépendent les modules `watermark`, `stego` et `fingerprint`. Elle trouve la zone où l'on a le droit d'écrire (la RONI), la découpe en partitions W, S et F, écrit et relit les bits, et vérifie les invariants. Elle ne connaît **aucune clé** et **aucun format de charge utile** : elle écrit les octets ou les bits qu'on lui donne.

### Règles impératives

1. **Ne fais ni `git commit` ni `git push`.** Laisse tous les fichiers modifiés non commités : je relis et je pousse moi-même.
2. **Ne modifie aucun autre module** (`medimage-crypto`, `medimage-fingerprint`, `medimage-audit`), ni les workflows dans `.github/`, ni `docs/`. La seule modification autorisée hors du nouveau module : ajouter `<module>medimage-embedding</module>` et l'entrée `dependencyManagement` correspondante dans `backend/pom.xml`. **N'ajoute pas** `medimage-audit` au parent : c'est le travail de sa responsable.
3. Respecte les conventions existantes :
   - `groupId` : `tn.medimage` ;
   - parent : `tn.medimage:medimage-backend:0.1.0-SNAPSHOT` ;
   - package : `tn.medimagetrust.embedding` ;
   - Java 25 ; JUnit 5 (version héritée du parent) ;
   - Javadoc et messages d'erreur **en français**.
4. **Aucune dépendance externe** au-delà du JDK et de JUnit. Pas de lecture DICOM ici : un autre module (`medimage-dicom`) fournira les pixels.
5. **Échec fermé** : toute anomalie lève une exception à motif fermé (un `enum`). On ne renvoie jamais une image à moitié marquée. On travaille toujours sur une **copie** des pixels.
6. Aucun `System.out` dans le code de production. Aucune donnée patient dans les messages. Le module `web` journalisera les refus via `medimage-audit`, dont les champs n'acceptent que des identifiants opaques (`[A-Za-z0-9._:@-]{1,64}`). Les motifs de refus doivent donc être des constantes d'`enum` stables (par exemple `TOO_FEW_RONI_BLOCKS`), réutilisables telles quelles, et les messages d'exception ne doivent contenir ni valeur de pixel, ni dimension, ni nom de fichier.
7. Si un point de la spécification est ambigu ou impossible à tenir, **ne l'invente pas** : choisis l'option la plus prudente, et écris-la dans `backend/medimage-embedding/README.md`, section « Décisions à valider ».

### Spécification (dossier v2.4, « Profil d'incorporation v2 »)

#### Entrée : `PixelImage` (record immuable)
- `rows`, `cols` (int > 0) ; `rows × cols ≤ 36 000 000`, sinon refus.
- **Contrôle des dimensions avant toute allocation** : méthode statique `EmbeddingLimits.checkDimensions(rows, cols, bitsStored)`. Le module `medimage-dicom` l'appellera juste après avoir lu les dimensions dans l'en-tête (magic bytes, puis `ImageReader.getWidth/getHeight`, consigne du prof), **avant** de décoder les pixels. Le constructeur de `PixelImage` l'appelle aussi en premier.
- `bitsStored` (8 à 16) ; `maxValue = 2^bitsStored − 1`.
- `photometric` : `MONOCHROME1` ou `MONOCHROME2` (enum).
- `int[] pixels` : valeurs stockées non signées, en ordre raster, de longueur `rows × cols`. Copie défensive.
- `burnedInAnnotation` (boolean), `overlayPresent` (boolean).

#### Étape 1 — Détection de la RONI (`RoniDetector`), déterministe
1. `q = pixel >> 2` (constante `Q_SHIFT = 2`).
2. **Seuil d'Otsu** `T` sur l'histogramme de `q` (taille `(maxValue >> 2) + 1`). Convention : la classe basse est `q ≤ T`.
3. **Polarité du fond** = celle de la majorité des pixels du **bord** de l'image (première et dernière lignes, première et dernière colonnes) :
   - fond sombre : un pixel est « fond » si `q ≤ T` ;
   - fond clair : si `q > T` ;
   - en cas d'égalité, fond sombre.
4. Garder les **composantes connexes du fond, en 4-connexité, qui touchent le bord**. Fais un parcours en largeur (BFS) itératif depuis les pixels de fond du bord ; **pas de récursion**, car les images peuvent faire 36 Mpx.
5. **Érosion de 8 pixels** (élément structurant carré 17 × 17). Les pixels hors de l'image comptent comme du fond. Implémentation séparable en O(n) : un minimum glissant en ligne, puis en colonne.
6. Découper en **blocs 8 × 8** alignés sur (0, 0) ; les blocs incomplets au bord droit ou bas sont ignorés. Un bloc est dans la RONI si **ses 64 pixels** sont dans le masque érodé.
7. **Garde** : chaque pixel de la RONI doit être à **au moins 4 niveaux de q** du seuil, du côté du fond :
   - fond sombre : `T − q ≥ 4` ;
   - fond clair : `q − T ≥ 4`.

   Si un pixel de la RONI viole la garde, refus `GUARD_VIOLATION` (lecture littérale du dossier). Écris dans le README la variante possible : exclure le bloc au lieu de refuser l'image.
8. **Refus** (`EmbeddingException`, `Reason` fermé ; liste complète ci-dessous, aucun autre motif) :
   - `TOO_FEW_RONI_BLOCKS` : RONI < 290 blocs ;
   - `GUARD_VIOLATION` ;
   - `ROI_TOO_SMALL` : la ROI (tout ce qui n'est pas RONI) couvre moins de 5 % des pixels ;
   - `BURNED_IN_ANNOTATION` ;
   - `OVERLAY_PRESENT` ;
   - `IMAGE_TOO_LARGE` ;
   - `INVALID_INPUT` ;
   - et, pour les étapes suivantes : `F_DEVIATION_EXCEEDED` (étape 3), `FINAL_CHECK_FAILED` (étape 5), `ILLEGAL_STATE` (écriture après `finish()`, ou W écrit avant S).
9. Résultat : `RoniProfile`, avec :
   - le seuil `T` et la polarité ;
   - le masque par bloc : liste ordonnée, en raster, des indices de blocs de la RONI ;
   - le masque binaire par pixel ;
   - `profileVersion = 2`.

Pour le vérificateur : `RoniDetector.candidates(PixelImage marked)` renvoie les masques **distincts** obtenus avec les seuils `T−4 … T+4`, où `T` est l'Otsu de la copie. Le module watermark gardera celui dont la preuve W se vérifie. Ces masques sont calculés **sans** appliquer les refus, sauf `INVALID_INPUT`.

#### Étape 2 — Partitions (`Partitions`)
Blocs de la RONI pris en ordre raster :
- les **26 premiers → W** (1 664 bits = 208 octets) ;
- les **8 suivants → S** (512 bits = 64 octets) ;
- **tous les autres → classe F**, qui doit compter **au moins 256 blocs** (seuil 26 + 8 + 256 = 290).

Ordre des bits dans W et S :
- les blocs se suivent dans l'ordre de la partition ;
- dans un bloc, les pixels vont en raster (ligne par ligne) ;
- dans un octet, le bit de poids fort est écrit en premier.

#### Étape 3 — Couche robuste F (`QimBlockCodec`, `FBlockChannel`)
- DCT 8 × 8 **orthonormée** (type II) et son inverse, en `double`.
- Coefficients porteurs : **rangs zigzag 6 à 13** (ordre JPEG, rang 0 = DC). Positions (ligne, colonne) :
  - 6 → (0,3) ;
  - 7 → (1,2) ;
  - 8 → (2,1) ;
  - 9 → (3,0) ;
  - 10 → (4,0) ;
  - 11 → (3,1) ;
  - 12 → (2,2) ;
  - 13 → (1,3).
- **QIM avec dither**, pas `Δ = 12`. Pour un bit `b` et un dither `d ∈ [0, Δ)` :
  - insertion : `c' = Δ·round((c − d − bΔ/2) / Δ) + d + bΔ/2` ;
  - extraction : le `b ∈ {0,1}` qui minimise la distance de `c` au réseau correspondant.
- Après DCT inverse : arrondi à l'entier, puis bornage à `[0, maxValue]`. **Écart maximal par pixel : 12 valeurs stockées.** Vérifie-le et lève `F_DEVIATION_EXCEEDED` sinon.
- Les dithers sont **fournis par l'appelant** (8 valeurs par bloc, dérivées de K_F dans le module fingerprint). Ce module ne connaît pas de clé.
- Interface publique exposée au module fingerprint :
  ```java
  public interface FBlockChannel {
      int UNREADABLE = -1;
      int classSize();                                         // nombre de blocs de la classe F
      void write(int fIndex, boolean[] bits8, double[] dither8);
      int[] read(int fIndex, double[] dither8);                // 0, 1 ou UNREADABLE
  }
  ```
  - `read` renvoie `UNREADABLE` pour les 8 bits si le bloc sort de l'image (copie recadrée).
  - Les **128 blocs utilisés** sont choisis par l'appelant parmi la classe F (positions secrètes). Les blocs non utilisés **ne sont pas modifiés**.

**Point à mesurer, sans le corriger en douce** : quand le fond vaut presque 0 (fréquent en scanner et en IRM), le bornage à 0 peut casser certains bits QIM. Mesure le taux de bits mal relus sur deux fantômes : un fond à 0 et un fond vers 200. Mets les chiffres dans le README, section « Décisions à valider ». Ne change pas Δ ni les rangs zigzag sans le signaler.

#### Étape 4 — Scellement fragile S et W (`LsbChannel`)
- Substitution du **bit 0** : `p = (p & ~1) | bit`. Écart de 1 au maximum.
- Méthodes :
  - `writeS(byte[64])`, `readS() → byte[64]` ;
  - `writeW(byte[208])`, `readW() → byte[208]`.
- Taille différente de 64 ou 208 octets : `INVALID_INPUT`.

#### Étape 5 — Orchestration et contrôle final (`EmbeddingSession`)
Ordre imposé, sur une copie de l'image :
1. `detect` : RONI et partitions, ou refus.
2. Écriture **F**, par l'appelant via `FBlockChannel`.
3. Écriture **S**.
4. Calcul des condensats pour W. L'écriture de W est faite **en dernier**, avec la charge fournie par l'appelant.
5. `finish()` : **contrôle final**, sinon `FINAL_CHECK_FAILED` avec un sous-motif :
   - tous les pixels hors RONI sont **identiques bit à bit** à l'original ;
   - **PSNR ≥ 40 dB** sur l'image entière, avec `MAX = maxValue` ;
   - S et W relus identiques à ce qui a été écrit, et les bits F écrits relus identiques ;
   - le masque recalculé sur la copie (`candidates`) **contient** le masque d'origine.
   - Une session ne peut pas être finie deux fois ; aucune écriture n'est possible après `finish()`.

#### Condensats (`Digests`, SHA-256 de la JCA)
- `roiDigest = SHA-256("MIT-ROI-v1" ‖ rows ‖ cols ‖ bitsStored ‖ pixels hors RONI en raster)`
- `maskDigest = SHA-256("MIT-MASK-v1" ‖ rows ‖ cols ‖ masque binaire par pixel)`
- `sDigest = SHA-256(64 octets de S)`

Encodage :
- étiquettes en ASCII ;
- `rows`, `cols` : entiers de 4 octets, gros-boutiste ;
- `bitsStored` : 1 octet ;
- chaque pixel sur 2 octets gros-boutiste ;
- masque compacté 8 pixels par octet, bit de poids fort d'abord, dernier octet complété par des zéros.

Documente exactement cet encodage dans le README : le module watermark devra le reproduire à l'identique.

#### Constantes (`EmbeddingProfile`, une seule classe)
| Constante | Valeur |
|---|---|
| `PROFILE_VERSION` | 2 |
| `Q_SHIFT` | 2 |
| `EROSION_PX` | 8 |
| `BLOCK` | 8 |
| `GUARD_Q` | 4 |
| `T_SEARCH` | 4 |
| `W_BLOCKS` | 26 |
| `S_BLOCKS` | 8 |
| `F_USED_BLOCKS` | 128 |
| `MIN_F_CLASS_BLOCKS` | 256 |
| `MIN_RONI_BLOCKS` | 290 |
| `QIM_DELTA` | 12 |
| `ZIGZAG_FIRST`, `ZIGZAG_LAST` | 6, 13 |
| `MAX_F_DEVIATION` | 12 |
| `MIN_PSNR_DB` | 40.0 |
| `MIN_ROI_RATIO` | 0.05 |
| `MAX_PIXELS` | 36 000 000 |

### Tests JUnit 5 à écrire (tous doivent passer avec `mvn -B test`)
Crée un générateur de **fantômes synthétiques** déterministes (graine fixe) dans `src/test`, par exemple une image 512 × 512, 12 bits, avec un fond sombre bruité et une ellipse claire bruitée au centre. Prévois aussi les variantes : fond à 0, fond clair (MONOCHROME1), corps qui touche les quatre bords, image 64 × 64.

| Test | Ce qu'il vérifie |
|---|---|
| `OtsuTest` | seuil correct sur un histogramme bimodal connu |
| `RoniDetectorTest` | fantôme normal : ≥ 290 blocs, aucun bloc RONI qui touche l'ellipse ; refus `TOO_FEW_RONI_BLOCKS` (64 × 64 et corps sur les bords) ; refus `BURNED_IN_ANNOTATION` et `OVERLAY_PRESENT` ; polarité correcte en MONOCHROME1 |
| `ErosionTest` | un carré de fond isolé de 30 px est réduit de 8 px de chaque côté ; le masque érodé ne déborde jamais du masque d'origine |
| `PartitionsTest` | 26 / 8 / reste, en ordre raster, sans chevauchement |
| `DctQimTest` | DCT puis DCT inverse = identité (à 1e-9) ; QIM aller-retour 100 % sans bruit ; bruit gaussien σ = 2 sur les pixels → ≥ 95 % des bits relus ; écart max par pixel ≤ 12 |
| `LsbChannelTest` | aller-retour S et W ; écart ≤ 1 ; mauvaise taille → `INVALID_INPUT` |
| `EmbeddingSessionTest` | session complète (F sur 128 blocs pris au hasard avec une graine, S aléatoire, W aléatoire) : ROI identique bit à bit, PSNR ≥ 40 dB, tout relu |
| `MaskStabilityTest` (**SEC-M01**) | sur 50 marquages aléatoires, `candidates(copieMarquée)` contient le masque d'origine dans 100 % des cas |
| `FailClosedTest` | ROI modifiée par erreur (injecte un défaut) → `FINAL_CHECK_FAILED` ; écriture après `finish()` → exception ; l'image d'origine n'est jamais modifiée |
| `DigestsTest` | vecteurs fixes : même entrée → même condensat ; un pixel de ROI modifié → `roiDigest` différent ; un pixel F modifié → `roiDigest` identique |

### SecDevOps : la sécurité dans chaque étape, pas à la fin
1. **Traçabilité exigence → test.** Chaque classe de test porte `@Tag` avec l'identifiant de l'exigence couverte (`SEC-M01`, `SEC-D02`, `SEC-D04`…) et un `@DisplayName` en français qui dit ce qui est prouvé. Le README contient un tableau « Exigence → test(s) ».
2. **Tests négatifs (cas d'abus), au même niveau que les tests positifs :**
   - **image géante** : `EmbeddingLimits.checkDimensions(20000, 20000, 16)` → `IMAGE_TOO_LARGE` en **moins de 2 s**, sans aucune allocation de pixels (consigne du prof : bombe de décodage rejetée < 2 s, sans `OutOfMemoryError`) ; idem pour `rows` ou `cols` ≤ 0, et pour un produit `rows × cols` qui déborde d'un `int` ;
   - **entrées malformées** : `pixels.length ≠ rows × cols`, `bitsStored` hors [8, 16], valeur de pixel > `maxValue`, tableau `null` → `INVALID_INPUT` ;
   - **un seul bit modifié** dans un bloc S ou W de la copie → la relecture diffère (le sceau est fragile) ;
   - **un pixel de ROI modifié** pendant la session → `FINAL_CHECK_FAILED` ;
   - **index F hors limites** ou tableaux de dither ou de bits de longueur ≠ 8 → `INVALID_INPUT`.
3. **Tests de propriétés** : sur 200 images générées aléatoirement (graine fixe), vérifier que les invariants tiennent toujours : ROI intacte, écart F ≤ 12, écart S/W ≤ 1, masque stable. Pas de bibliothèque : une boucle JUnit avec `Random(graine)` suffit.
4. **Aucun secret dans le code** : ni clé, ni mot de passe, ni dither codé en dur hors des tests. Les graines des tests sont des constantes explicitement nommées `TEST_SEED_*`.
5. **Pas de fuite par les erreurs** : un test vérifie que `getMessage()` de chaque `EmbeddingException` ne contient aucun chiffre venant de l'image (ni dimension, ni valeur de pixel).
6. **Code défensif** : copies défensives en entrée et en sortie (aucun tableau interne exposé), champs `final`, classes `final`, aucune réflexion, aucune désérialisation Java.
7. **Prêt pour la CI** : `mvn -B test` doit passer **hors ligne** une fois les dépendances téléchargées, en moins de 2 minutes, sans test qui dépende de l'horloge ou de l'ordre d'exécution. Ne modifie pas `.github/` : la CI est gérée par sa responsable.

### Hors périmètre de ce module (ne pas faire ici)
Lecture DICOM, construction du jeton S, signature de W, code de Tardos et choix des 128 blocs secrets, banc d'attaques JPEG Q75 et redimensionnement (il viendra dans la CI), endpoints REST.

### Livrables
1. `backend/medimage-embedding/pom.xml`, rattaché au parent.
2. Le code dans `src/main/java/tn/medimagetrust/embedding/`.
3. Les tests dans `src/test/java/tn/medimagetrust/embedding/`.
4. `backend/medimage-embedding/README.md`, avec :
   - le rôle du module ;
   - un tableau des classes ;
   - l'encodage exact des condensats ;
   - l'interface `FBlockChannel` à donner au module fingerprint, en indiquant ce qui change par rapport à l'ancien `FPartition` (LSB pixel par pixel, rangs modulo 10, 6 144 px) ;
   - les mesures (taux de bits F cassés par le bornage, PSNR moyen, temps sur 512 × 512) ;
   - le tableau « Exigence → test(s) » ;
   - la section « Décisions à valider ».
5. `backend/pom.xml` : ajout du module et de son entrée `dependencyManagement`. Rien d'autre.

### Pour finir
- Lance `mvn -B test` à la racine de `backend/` : les modules déclarés dans le parent (crypto, fingerprint, embedding) doivent tous passer. Si crypto ou fingerprint échouent **avant** ton travail, ne les corrige pas : signale-le-moi.
- Donne-moi un résumé :
  - les fichiers créés ;
  - les fichiers modifiés (seulement `backend/pom.xml`) ;
  - le résultat des tests ;
  - les mesures ;
  - les décisions à valider.
- Rappel : **aucun commit, aucun push**.

---

## Après Claude Code (PowerShell)

```powershell
git status                       # seuls backend/pom.xml et backend/medimage-embedding/ doivent apparaître (+ docs/ v2.4, non suivi)
cd backend; mvn -B test; cd ..
git diff backend/pom.xml         # vérifier qu'il n'y a que l'ajout du module
# contrôle des secrets avant tout push (gitleaks installé une fois : winget install Gitleaks.Gitleaks)
gitleaks detect --source . --no-banner -v
# puis, quand tu as relu :
git add backend/pom.xml backend/medimage-embedding
git commit -m "feat(embedding): RONI, partitions W/S/F, QIM et LSB, contrôle final"
git push -u origin feat/embedding
```
