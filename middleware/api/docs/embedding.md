# medimage-embedding — brique commune d'incorporation (profil 3)

> Ancien `README.md` du module Maven `medimage-embedding`, devenu le package `tn.medimagetrust.embedding` du module `api` (voir [`../../README.md`](../../README.md)). Les commandes se lancent depuis la racine du dépôt.


Référence : dossier **v2.4**, `docs/MedImage-Trust_v2.4_complet.pdf` : §4.1 (profil d'incorporation v2), tableau
SEC-M (intégrité diagnostique), conditions G2 à G7 (« conditions pour marquer une image ») et §8.3 (formats binaires).
Le PDF v2.3 est obsolète.

## Rôle

Ce module est la base commune des modules `watermark`, `stego` et `fingerprint`. Il :

1. trouve la zone où l'on a le droit d'écrire (**RONI**), de façon déterministe ;
2. la découpe en partitions **W** (26 blocs), **S** (8 blocs) et **F** (le reste, au moins 256 blocs) ;
3. écrit et relit les bits : QIM sur DCT 8 × 8 pour F, substitution du bit 0 (LSB) pour S et W ;
4. vérifie les invariants avant de rendre une copie marquée (échec fermé).

Il ne connaît **aucune clé** et **aucun format de charge utile**. Il écrit les octets (S, W) ou les bits et dithers
(F) qu'on lui donne. Il ne lit pas le DICOM : `medimage-dicom` lui fournira un `PixelImage`. Aucune dépendance hors
JDK (JUnit en test). Package : `tn.medimagetrust.embedding`.

## Classes

| Classe | Visibilité | Rôle |
|---|---|---|
| `EmbeddingProfile` | publique | Toutes les constantes du profil 3 (une seule classe) ; seul point de calcul de Δ(B), de l'écart F maximal et de la garde. |
| `EmbeddingLimits` | publique | `checkDimensions(rows, cols, bitsStored)` : contrôle O(1), **avant toute allocation**. |
| `PixelImage` | publique (classe finale) | Image immuable en `char[]` : non signée, ou copie signée en complément à deux (`signed()`, `signedFromShorts(...)`) ; seul endroit des conversions (`encode`, `decode`) ; `PixelPaddingValue` facultatif ; copie défensive en entrée et en sortie, validation complète ; `fromInts(...)` pour les tests et les petits appels. |
| `Photometric` | publique (enum) | `MONOCHROME1`, `MONOCHROME2` (information seulement). |
| `RoniDetector` | publique | `detect(image)` avec refus ; `candidates(marked)` pour le vérificateur (T ± 4, sans refus). |
| `RoniProfile` | publique | Seuil T, polarité, blocs RONI en ordre raster, masque par pixel, `profileVersion = 3`. |
| `BackgroundPolarity` | publique (enum) | `DARK` (fond si q ≤ T) ou `LIGHT` (fond si q > T). |
| `Partitions` | publique | Découpage 26 / 8 / reste ; géométrie des blocs. |
| `FBlockChannel` | publique (interface) | Canal F donné au module fingerprint. |
| `FBlockReader` | publique | `FBlockChannel` en lecture seule sur une copie suspecte (recadrage → `UNREADABLE`), au pas Δ(B) de la profondeur B **du registre**. |
| `LsbChannel` | publique | `readS()`, `readW()` ; `reader(image, partitions)` en lecture seule. Écriture seulement via la session. |
| `Digests` | publique | `roiDigest`, `maskDigest`, `sDigest` (SHA-256 de la JCA). |
| `EmbeddingSession` | publique | Orchestration F → S → condensats → W → `finish()` avec contrôle final ; passage en mode signé ou refus si F sort de la plage. |
| `EmbeddingResult` | publique (record) | Résultat de `finish()` : copie, profondeur B à enregistrer dans le registre, `signedOutput`, attributs DICOM à appliquer en mode signé. |
| `EmbeddingException` | publique | `Reason` (liste fermée) et `FinalCheck` (sous-motif du contrôle final), `auditCode()`. |
| `Otsu` | paquet | Seuil d'Otsu déterministe. |
| `QimBlockCodec` | paquet | DCT-II orthonormée 8 × 8, QIM avec dither, écriture et lecture d'un bloc. |
| `Bits` | paquet | Masques compactés, 1 bit par pixel dans un `long[]`. |

## Utilisation

```java
// medimage-dicom, juste après la lecture des dimensions dans l'en-tête, avant de décoder les pixels :
EmbeddingLimits.checkDimensions(rows, cols, bitsStored);

// Émission (module web) :
EmbeddingSession s = EmbeddingSession.open(image);      // RONI + partitions, ou EmbeddingException
FBlockChannel f = s.fChannel();                           // donné au module fingerprint
//   f.write(fIndex, bits8, dither8) pour 128 blocs choisis secrètement dans [0, f.classSize())
s.writeS(token64);                                        // stego : 64 octets
byte[] roi = s.roiDigest(), mask = s.maskDigest(), sd = s.sDigest();
s.writeW(proof208);                                       // watermark : 208 octets, en dernier
EmbeddingResult r = s.finish();                           // contrôle final, ou FINAL_CHECK_FAILED
PixelImage copie = r.image();                             // copie marquée (signée si r.signedOutput())
int b = r.bitsStored();                                   // à enregistrer dans le registre d'émission (fixe Δ(B))
r.pixelAttributes();                                      // mode signé : PixelRepresentation 1, BitsAllocated 16,
                                                          //   BitsStored 16, HighBit 15

// Analyse d'une fuite (module fingerprint) : B vient du registre, jamais de la copie.
FBlockChannel lu = FBlockReader.of(suspect, partitionsDeLEmission, bDuRegistre);

// Vérification (module watermark) :
for (RoniProfile cand : RoniDetector.candidates(suspect)) {
    // Partitions.of(cand) peut lever TOO_FEW_RONI_BLOCKS : candidat à écarter (preuve absente).
    byte[] w = LsbChannel.reader(suspect, Partitions.of(cand)).readW();
    // garder le candidat dont la signature W se vérifie avec Digests.maskDigest(cand) ...
}
```

Les refus sont des constantes d'`enum` stables, à journaliser telles quelles via `medimage-audit` :
`e.auditCode()` vaut par exemple `TOO_FEW_RONI_BLOCKS` ou `FINAL_CHECK_FAILED:ROI_MODIFIED` (format
`[A-Za-z0-9._:@-]{1,64}`). Les messages sont des textes fixes **sans aucun chiffre** (ni dimension, ni valeur de pixel).

| `Reason` | Quand |
|---|---|
| `TOO_FEW_RONI_BLOCKS` | RONI < 290 blocs |
| `PIXEL_PADDING_IN_RONI` | `PixelPaddingValue` déclaré et présent dans le fond : sans ses blocs, la RONI passe sous 290 blocs |
| `GUARD_VIOLATION` | un pixel de la RONI est à moins de `guardQ(B) = 4 × 2^(B − 8)` niveaux de q du seuil |
| `ROI_TOO_SMALL` | ROI < 5 % des pixels |
| `BURNED_IN_ANNOTATION` | `BurnedInAnnotation = YES` |
| `OVERLAY_PRESENT` | overlay présent |
| `IMAGE_TOO_LARGE` | `rows × cols > 36 000 000`, ou un côté > 8 192 |
| `INVALID_INPUT` | entrée malformée (longueurs, valeurs, nuls, index, dithers) |
| `F_DEVIATION_EXCEEDED` | l'insertion F écarterait un pixel de plus de Δ(B) |
| `EMBEDDING_RANGE_UNSUPPORTED` | la couche F sortirait de `[0, 2^B − 1]` et aucune copie sans écrêtage n'est possible : B = 16, ou valeur hors de `[−32 768, 32 767]` |
| `FINAL_CHECK_FAILED` | contrôle final ; sous-motif `FinalCheck` ci-dessous |
| `ILLEGAL_STATE` | ordre non respecté, écriture après `finish()`, session en échec |

`FinalCheck` : `ROI_MODIFIED`, `RANGE_OUTSIDE_RONI`, `UNUSED_BLOCK_MODIFIED`, `DEVIATION_EXCEEDED`, `PSNR_TOO_LOW`,
`S_READBACK_MISMATCH`, `W_READBACK_MISMATCH`, `F_READBACK_MISMATCH`, `MASK_UNSTABLE`.

## Algorithme (profil 3)

1. `q = valeur >> 2`, sur la valeur décodée : pour une copie signée, un pixel négatif a un `q` négatif (décalage
   arithmétique), donc sous tout seuil.
2. Seuil d'Otsu `T` sur l'histogramme de `q`, du plus petit au plus grand `q` représentable : taille
   `(maxValue >> 2) + 1` pour une image non signée (inchangé), `2^14` pour une copie signée ; classe basse `q ≤ T`.
   **Égalités** :
   si plusieurs seuils atteignent exactement le maximum de variance inter-classes (cas d'un trou entre le fond et le
   corps), on prend la partie entière de la moyenne de leurs indices (convention de `graythresh` de MATLAB).
3. Polarité = majorité des pixels du bord (chaque pixel compté une fois ; égalité : fond sombre).
4. Fond connexe au bord, 4-connexité, parcours en largeur itératif (file circulaire qui grandit à la demande).
5. Érosion de 8 px (carré 17 × 17, l'extérieur compte comme du fond), séparable : minimum glissant en ligne puis en
   colonne, O(n).
6. Blocs 8 × 8 alignés sur (0, 0), entièrement dans le masque érodé. Indice de bloc = `ligneBloc × (cols / 8) +
   colonneBloc`. Blocs incomplets ignorés. Si l'image déclare un `PixelPaddingValue`, les blocs qui contiennent un
   pixel de cette valeur sont exclus (ces pixels ne sont jamais modifiés).
7. Garde : chaque pixel de la RONI vérifie `T − q ≥ g` (fond sombre) ou `q − T ≥ g` (fond clair), avec
   `g = guardQ(B) = 4 × 2^(B − 8)` (4 en 8 bits, 64 en 12 bits).
8. Refus, dans cet ordre : `BURNED_IN_ANNOTATION`, `OVERLAY_PRESENT`, `PIXEL_PADDING_IN_RONI` (capacité suffisante
   sans les blocs de remplissage) ou `TOO_FEW_RONI_BLOCKS`, `GUARD_VIOLATION`, `ROI_TOO_SMALL`.

`candidates(marked)` : seuils essayés dans l'ordre `T, T−1, T+1, …, T−4, T+4` (T = Otsu de la copie), seuils hors de
la plage de `q` représentable ignorés, polarité recalculée pour chaque seuil, masques identiques dédoublonnés (le premier est
gardé).

Pourquoi le masque est stable : un pixel F bouge d'au plus Δ(B) = 12 × 2^(B − 8) valeurs stockées, donc de
3 × 2^(B − 8) niveaux de q au plus (3 en 8 bits, 48 en 12 bits) ; un pixel S ou W d'au plus 1. Grâce à la garde de
4 × 2^(B − 8) niveaux, tout pixel modifié reste du côté du fond pour le seuil d'origine, y compris un pixel devenu
négatif ou supérieur à `2^B − 1` dans une copie signée ; les autres pixels ne bougent pas. Le masque calculé avec le seuil d'origine est donc identique, et il est
dans les candidats dès que l'Otsu de la copie reste à ± 4 du seuil d'origine.

## Couche F : QIM sur DCT 8 × 8

- DCT-II orthonormée : `M[u][x] = α(u)·cos((2x+1)uπ/16)`, `α(0) = √(1/8)`, sinon `√(2/8)` ; `C = M·F·Mᵀ`.
- Porteurs, rangs zigzag JPEG 6 à 13, (ligne, colonne) : (0,3) (1,2) (2,1) (3,0) (4,0) (3,1) (2,2) (1,3). Le bit
  `j` de `bits8` va au porteur `j`.
- Pas : `Δ(B) = 12 × 2^(B − 8)` (`EmbeddingProfile.qimDelta`), B = profondeur effective de l'original : 12 en 8 bits,
  48 en 10 bits, 192 en 12 bits, 3 072 en 16 bits.
- Insertion : `c' = Δ·round((c − d − bΔ/2)/Δ) + d + bΔ/2`, avec `round(x) = floor(x + 0,5)`, `d ∈ [0, Δ)`.
- Extraction : réseau le plus proche ; égalité → 0 (marge de décision Δ/4).
- Après DCT inverse : arrondi `floor(x + 0,5)`, **sans bornage**, puis contrôle écart ≤ Δ(B)
  (`F_DEVIATION_EXCEEDED`). Borne théorique : (Δ/2) × (3·√2/8 + 5/4) ≈ 0,89 Δ (10,7 pour Δ = 12, soit ≤ 11 après
  arrondi).
- Plage : si une valeur du bloc sort de `[0, 2^B − 1]`, la session passe en mode signé (B ≤ 15) ou refuse
  (`EMBEDDING_RANGE_UNSUPPORTED`) ; jamais d'écrêtage.
- Bloc écrit d'un coup : en cas de refus, rien n'est écrit (et la session passe en échec).

## Condensats : encodage exact (à reproduire à l'identique dans `watermark`)

**Origine.** Les trois formules viennent du §8.3 de la v2.4, qui précise seulement « version 1, big-endian ». Le reste
de l'encodage **ne vient pas du dossier** mais de la spécification de ce module :

- les tailles des champs : 4 octets pour `rows` et `cols`, 1 pour `bitsStored`, 2 par pixel ;
- l'absence de terminateur et de préfixe de longueur pour les étiquettes ;
- le compactage du masque (bit de poids fort d'abord, dernier octet complété par des zéros).

Ces choix sont figés par les vecteurs fixes ci-dessous. Le module watermark doit les reprendre de ce README ou de la
classe `Digests`, pas du PDF.

```
roiDigest  = SHA-256( "MIT-ROI-v1"  ‖ rows ‖ cols ‖ bitsStored ‖ p(i0) ‖ p(i1) ‖ … )
maskDigest = SHA-256( "MIT-MASK-v1" ‖ rows ‖ cols ‖ m[0] ‖ m[1] ‖ … )
sDigest    = SHA-256( S[0..63] )
```

| Champ | Encodage |
|---|---|
| étiquettes | octets ASCII de la chaîne, **sans** terminateur ni préfixe de longueur (10 et 11 octets) |
| `rows`, `cols` | entier non signé sur 4 octets, gros-boutiste |
| `bitsStored` | 1 octet (8 à 16) |
| pixels hors RONI | chaque pixel `i` tel que `!inRoni(i)`, en ordre raster `i = r × cols + c`, sur 2 octets gros-boutiste (valeur stockée) |
| masque | 1 bit par pixel, `1` = RONI, ordre raster ; 8 pixels par octet, **bit de poids fort d'abord** (pixel `i` → octet `i >> 3`, bit `0x80 >> (i & 7)`) ; dernier octet complété par des zéros ; `⌈rows × cols / 8⌉` octets |
| S | les 64 octets tels qu'écrits |

Le masque est le masque **par pixel de la RONI** (union des blocs RONI), pas le masque érodé. `maskDigest` ne contient
pas `bitsStored` (conforme au §8.3).

Vecteurs fixes (image 16 × 16, 12 bits, `pixel = 17·r + c`, RONI = blocs 0 et 3 ; voir `DigestsTest`, recalculés par
une implémentation Python indépendante) :

| Condensat | Valeur |
|---|---|
| `roiDigest` | `f1512a464cb98064ff8ca59726ee5301211cb94b761dc5893835e9617a079549` |
| `maskDigest` | `1f3b65c09c2938ca9acb4d2cf37601a64b53e455e68300756f4437e690833401` |
| `sDigest(64 × 0x00)` | `f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b` |

## Interface pour le module fingerprint

```java
public interface FBlockChannel {
    int UNREADABLE = -1;
    int classSize();                                         // nombre de blocs de la classe F (≥ 256)
    double qimDelta();                                       // Δ(B) : de l'original à l'émission, du registre à l'analyse
    void write(int fIndex, boolean[] bits8, double[] dither8);
    int[] read(int fIndex, double[] dither8);                // 0, 1 ou UNREADABLE (×8 si le bloc sort de l'image)
}
```

- À l'émission : `session.fChannel()`. Au plus **128 blocs**, chacun écrit **une seule fois**, avant `writeS`.
- À l'analyse d'une fuite : `FBlockReader.of(copieSuspecte, partitionsDeLEmission, bDuRegistre)`. Δ est celui de la
  profondeur B enregistrée à l'émission, jamais lu dans la copie (attributs modifiables, `BitsStored = 16` pour une
  copie signée). Les partitions de l'émission
  doivent donc être recalculables (`Partitions.of(RoniDetector.detect(original))`) ou conservées. Seul un recadrage à
  droite ou en bas est géré (`UNREADABLE`) ; un recadrage à gauche ou en haut décale la grille.
- `dither8` : 8 valeurs finies dans `[0, qimDelta())`, dérivées de K_F par le module fingerprint ; ce module ne voit
  aucune clé. Les tirages ne dépendent pas de Δ : seule l'échelle change (identiques en 8 bits).

**Ce qui change par rapport à l'ancien `FPartition` (v2.3, encore dans `medimage-fingerprint`)** — je n'ai pas touché
au code de ce module, voici seulement les écarts à reprendre :

| | `FPartition` (v2.3) | `FBlockChannel` (v2.4) |
|---|---|---|
| Unité | 1 pixel | 1 bloc 8 × 8 = 8 bits |
| Insertion | LSB du pixel | QIM (Δ(B), 12 en 8 bits) sur les rangs zigzag 6 à 13 de la DCT |
| Classe F | pixels de la RONI de rang modulo 10 dans [4, 9] | blocs de la RONI après les 26 W et les 8 S |
| Taille minimale | 6 144 pixels (`MIN_F_CLASS_PIXELS`) | 256 blocs (`MIN_F_CLASS_BLOCKS`) |
| Capacité utilisée | 3 072 bits (1 024 symboles × 3 répétitions) | 128 blocs × 8 = 1 024 bits : **1 bit par symbole, sans répétition** |
| Paramètre de lecture | rang | indice de bloc + 8 dithers |
| Robustesse | aucune (LSB fragile) | robuste à un bruit faible (voir mesures) |

Conséquences pour `FingerprintParams` : `REPETITIONS = 3`, `CODE_BITS = 3 072` et `MIN_F_CLASS_PIXELS = 6 144` ne
correspondent plus à la v2.4. L'adaptateur devra faire `symbole i → (bloc secret i / 8, bit i % 8)`, tirer les
128 blocs dans `[0, classSize())` (libellé `MIT-POS-v1`) et les dithers par bloc.

## Mesures

Obtenues avec `MeasurementsTest` (lignes `[MESURE]` de la sortie de `mvn -B test`), JDK 25, Windows 11, poste
portable, profil 3. Les temps sont indicatifs et ne sont pas vérifiés par les tests.

| Mesure | Profil 2 (Δ = 12) | Profil 3 (Δ(B)) |
|---|---|---|
| Bits F mal relus sans bruit, **fond à 0**, 12 bits (20 sessions × 128 blocs, 20 480 bits) | **6,34 %** (bornage) | **0 %** (mode signé) |
| Sessions annulées par le contrôle final, fond à 0 | **100 %** (`FINAL_CHECK_FAILED:F_READBACK_MISMATCH`) | **0 %** |
| Bits F mal relus sans bruit, fond vers 200 | 0 % ; 0 session annulée | 0 % ; 0 session annulée |
| PSNR, 12 bits, 512 × 512, 50 marquages | moyen 84,94 dB ; minimal 84,73 dB | moyen 61,45 dB ; minimal 61,10 dB |
| PSNR minimal, 8 bits, 256 × 256 (cas défavorable), 20 marquages | 54,57 dB | 54,57 dB (identique) |
| Bits F relus après bruit gaussien σ = 1 / 1,5 / 2 × 2^(B − 8) (12 bits, 4 096 bits) | 99,39 % / 94,36 % / **86,40 %** (σ absolu, Δ = 12) | 99,66 % / 95,61 % / **86,67 %** (σ × 16, Δ = 192) |

Le PSNR 12 bits baisse d'environ 23 dB : c'est l'effet attendu d'un pas 16 fois plus grand (20 log10 16 ≈ 24 dB), et
il rejoint celui des images 8 bits (≈ 61 dB sur les mêmes fantômes au banc de robustesse), bien au-dessus du critère de
40 dB.
| Temps 512 × 512 : détection seule (`int[]` → `char[]` et masques en bits) | ≈ 3,0 ms → ≈ 5,6 ms |
| Temps 512 × 512 : session complète (F 128 blocs, S, W, contrôle final) | ≈ 30,4 ms → ≈ 14 ms |
| Session complète sur 36 Mpx (6 000 × 6 000, 16 bits), `-Xmx300m` | open ≈ 1,1 s ; F, S, W ≈ 0,3 s ; finish ≈ 1,0 s |
| `mvn -B test` sur le module (85 tests, test mémoire exclu) | ≈ 20 à 30 s |

La détection est un peu plus lente (lecture des masques bit à bit). La session est deux fois plus rapide : le
contrôle final s'arrête au premier masque candidat identique, au lieu de calculer les 9 masques candidats.

## Bilan mémoire (36 Mpx)

Budget du dossier v2.4 (§ « Mémoire ») : original 72 Mo + copie 72 Mo + masque 36 Mo + tampon 36 Mo ≈ 216 Mo par
traitement (4 traitements simultanés, tas de 2 Gio) ; plafond absolu 250 Mio. Pour n = 36 000 000 pixels
(6 000 × 6 000) :

| Tableau | Type | Taille | Durée de vie |
|---|---|---|---|
| Pixels de l'appelant (avant construction) | `char[n]` | 68,7 Mio | jusqu'à la fin du constructeur de `PixelImage`, si l'appelant le libère |
| Original (`PixelImage`, seule copie gardée pour le contrôle « ROI bit à bit ») | `char[n]` | 68,7 Mio | toute la session |
| Copie de travail de la session | `char[n]` | 68,7 Mio | de `open` à `finish` ; devient ensuite l'image marquée, **sans troisième copie** (`PixelImage.adopt`) |
| Fond connexe au bord (parcours) | `long[n/64]` | 4,3 Mio | pendant chaque calcul de masque |
| File du parcours (anneau fixe) | `int[2^20]` | 4,0 Mio | pendant le parcours |
| Masque « en attente » (débordement de la file) | `long[n/64]` | 4,3 Mio | seulement si la frontière dépasse 2^20 pixels |
| Masque temporaire de l'érosion | `long[n/64]` | 4,3 Mio | pendant l'érosion |
| Compteurs de l'érosion, indices du bord | `int[cols]`, `int[2(rows+cols)]` | 0,1 Mio | pendant le calcul |
| Indices des blocs RONI (avant et après réduction) | `int[≤ n/64]` | ≤ 2 × 2,1 Mio | pendant `fullBlocks` |
| Histogramme d'Otsu | `long[≤ 16 384]` | 0,1 Mio | pendant le seuil |
| Profil RONI gardé (masque par pixel + indices de blocs) | `long[n/64]` + `int[]` | 4,3 + 2,1 Mio | toute la session |
| Partitions (blocs W, S, F) | `int[]` | ≤ 2,1 Mio | toute la session |
| Masque compacté pour `maskDigest` | `byte[n/8]` | 4,3 Mio | pendant le calcul du condensat |
| Profil candidat du contrôle final (un seul à la fois) | `long[n/64]` + `int[]` | 6,4 Mio | pendant la comparaison |
| Blocs F, S, W écrits, blocs F utilisés | `int[128]`, `long[classe/64]` | < 0,1 Mio | session / contrôle final |

Pic estimé (pendant `finish`) : original 68,7 + copie 68,7 + profil 6,4 + partitions 2,1 + calcul d'un masque candidat
≤ 12,6 + profil candidat 6,4 ≈ **165 Mio ≈ 173 Mo**, sous les 216 Mo du dossier et le plafond de 250 Mio. Le pic est
estimé sans compter les pixels de l'appelant, qui ne coexistent avec l'original que dans le constructeur, avant la
session (137 Mio au plus à ce moment-là).

**Mesuré** (`MemoryBudgetTest`, `mvn -B -f middleware/pom.xml -pl api test "-Dgroups=memory" "-DargLine=-Xmx300m"`, JDK 25,
G1) :

| Étape | Tas vivant après GC | Pic des pools du tas (borne haute, déchets compris) |
|---|---|---|
| Image construite | 69,0 Mio | — |
| `open` (détection, partitions) | 147,2 Mio | 147,0 Mio |
| F, S, condensats, W | 147,3 Mio | 160,0 Mio |
| `finish` (contrôle final) | 147,3 Mio | 174,3 Mio |

Aucune `OutOfMemoryError` avec un tas de 300 Mio. Pic mesuré : 147,3 Mio retenus, 174,3 Mio au plus en comptant les
déchets pas encore collectés (182,8 Mo), sous les 216 Mo.

Ce qui a changé pour y arriver : pixels en `char[]` (2 octets au lieu de 4) dans `PixelImage` et dans toutes les
copies de travail ; masques compactés d'un bit par pixel ; file de parcours de taille fixe (aucun
`ArrayDeque<Integer>`, aucun tableau `int` de taille n) ; lecteurs `LsbChannel.reader` et `FBlockReader.of` sans copie
de l'image (elle est immuable) ; image marquée construite sans copie à partir de la copie de travail ; contrôle final
qui garde un seul masque candidat à la fois. `RoniDetector.candidates` (côté vérificateur) peut encore garder jusqu'à
9 profils : jusqu'à 58 Mio à 36 Mpx, à itérer un par un dans le module watermark si besoin.

## Exigence → test(s)

Les classes de test portent `@Tag` avec l'identifiant. Pour lancer une exigence : `mvn -B -f middleware/pom.xml -pl api test -Dgroups=SEC-M01`.

| Exigence (v2.4) | Ce qui est prouvé | Test(s) |
|---|---|---|
| SEC-M01 | RONI déterministe du profil 3 ; masque identique avant et après marquage | `OtsuTest`, `RoniDetectorTest`, `ErosionTest`, `PartitionsTest`, `MaskStabilityTest`, `InvariantPropertyTest` |
| SEC-M03 (a) et (b) | contrôle automatique : ROI identique, écart ≤ 1 (S, W) et ≤ Δ(B) (F), PSNR ≥ 40 dB (MAX = 2^B − 1 de l'original) ; mutation d'écriture détectée ; jamais d'écrêtage : mode signé (valeurs hors plage seulement dans la RONI) ou refus explicite ; sorties 8 bits identiques au profil 2. Le point (c), attributs d'interprétation inchangés, **n'est pas couvert ici** (limite 10), sauf la dérogation documentée du mode signé | `FailClosedTest`, `EmbeddingSessionTest`, `LsbChannelTest`, `DctQimTest`, `InvariantPropertyTest`, `DepthAndSignedOutputTest`, `NonRegression8BitTest`, `NonRegression8BitChainTest` |
| SEC-M04, SEC-D08 | refus « non marquable » calculé par le système, sans option pour forcer | `RoniDetectorTest` |
| SEC-M05 | refus si annotation incrustée ou overlay (condition G2) | `RoniDetectorTest` |
| SEC-M06, SEC-D07 | l'original n'est jamais écrit ; la copie ne contient que l'original et les marques | `FailClosedTest`, `InvariantPropertyTest` |
| SEC-D02, SEC-F02 | dimensions contrôlées avant allocation (bombe rejetée < 2 s), entrées malformées rejetées | `EmbeddingLimitsTest` |
| SEC-D02, SEC-F01 | messages d'erreur sans valeur de l'image ; motifs compatibles avec le journal d'audit | `ErrorLeakTest` |
| SEC-D03 | condensats signés par W : encodage figé, sensibles à la ROI et au masque, insensibles à F | `DigestsTest`, `EmbeddingSessionTest`, `LsbChannelTest` |
| SEC-D04 | couche F : 8 bits par bloc par QIM sur DCT au pas Δ(B), 128 blocs, lecture d'une copie recadrée ; Δ fourni par le registre (un mauvais Δ fait échouer l'attribution) ; copie signée relue : mêmes blocs W, S et F | `DctQimTest`, `PartitionsTest`, `EmbeddingSessionTest`, `MeasurementsTest`, `DepthAndSignedOutputTest`, `RegistryDepthTest` |

La correspondance suit les conditions G de la v2.4 (« conditions pour marquer une image ») :

| Condition | Exigences |
|---|---|
| G2 : ni annotation incrustée, ni overlay | SEC-M04, SEC-M05 |
| G3 : RONI du profil 3, ROI ≥ 5 % | SEC-M01, SEC-M04 |
| G4 : au moins 290 blocs, garde de 4 × 2^(B − 8) niveaux | SEC-M04, SEC-D08 |
| G6 : après marquage, ROI identique, écarts ≤ 1 et ≤ Δ(B), PSNR ≥ 40 dB | SEC-M03 |
| G7 : original jamais écrit | SEC-M06 |

## Décisions de conception

Choix retenus là où la v2.4 laisse une marge, avec leur raison.

1. **Garde (étape 7) : lecture littérale.** Un seul pixel de la RONI à moins de `guardQ(B)` niveaux de q du seuil fait
   refuser l'image (`GUARD_VIOLATION`). Raison : le masque RONI ne dépend pas de la garde, et `candidates` le reproduit
   donc à l'identique sans autre calcul. Variante écartée : exclure le bloc fautif de la RONI au lieu de refuser
   l'image (plus d'images marquables, mais un masque qui dépend de la garde et qu'il faudrait reproduire dans
   `candidates`).
2. **Dimensions : limite de 8 192 par côté** (`MAX_DIMENSION`), en plus de `rows × cols ≤ 36 000 000`, parce
   que la v2.4 (SEC-F02) l'exige. `rows` ou `cols` ≤ 0 → `INVALID_INPUT` ; dimensions trop grandes ou produit qui
   déborde d'un `int` → `IMAGE_TOO_LARGE` (calcul en `long`). `bitsStored` hors de [8, 16] → `INVALID_INPUT`.
3. **Égalités d'Otsu** : moyenne entière des seuils ex aequo (voir Algorithme). Avec la règle « premier maximum »
   (celle de nombreuses bibliothèques), le seuil tombe au sommet du bruit du fond : les pixels de fond les plus clairs
   sont alors à moins de la garde du seuil, et une image à fond bien séparé (comme le fantôme normal) serait refusée
   pour `GUARD_VIOLATION`. C'est un choix d'implémentation que la v2.4 ne fixe pas ; le vérificateur utilise le même
   code, donc il reste cohérent. L'histogramme d'une copie signée couvre toute la plage d'un entier signé 16 bits :
   un décalage d'indices ne change ni la variance inter-classes ni la moyenne des seuils ex aequo.
   Histogramme à une seule valeur : `T` = cette valeur.
4. **Ordre et forme des candidats** : `T, T−1, T+1, …` (le plus probable d'abord), polarité recalculée pour chaque
   seuil, seuils hors limites ignorés, doublons supprimés. Le résultat est une liste de `RoniProfile`.
5. **Session plus stricte que la spécification du module** : au plus 128 blocs F, chacun écrit une seule fois ; F
   interdit après S ; S et W écrits une seule fois ; `finish()` exige S et W. F n'est pas obligatoire (0 à 128 blocs) :
   le nombre de blocs F écrits relève de la couche fingerprint, pas de la session. **Toute** exception (même une entrée
   invalide) place la session en échec définitif.
6. **Sous-motifs ajoutés au contrôle final** : `DEVIATION_EXCEEDED` (écarts ≤ 1 et ≤ Δ(B), SEC-M03 b),
   `UNUSED_BLOCK_MODIFIED` (« les blocs réservés mais non utilisés ne sont pas modifiés », §4.1) et
   `RANGE_OUTSIDE_RONI` (valeur hors de `[0, 2^B − 1]` ailleurs que dans la RONI d'une copie signée). Ordre des
   contrôles : ROI et plage, écarts, PSNR, relectures S, W, F, masque.
7. **Arrondis** : `floor(x + 0,5)` pour la QIM et la DCT inverse ; égalité de distance à l'extraction → bit 0.
8. **`Photometric` n'intervient pas dans la détection** : la polarité vient du bord, comme le dit la v2.4.
9. **Mémoire à 36 Mpx.** `PixelImage` stocke des `char[]`, les masques sont compactés à 1 bit par pixel, et l'image
   marquée reprend la copie de travail sans copie. Pic estimé ≈ 165 Mio (173 Mo). Le mode signé n'ajoute aucun
   tableau : la copie signée occupe le même `char[]`.
   **Mesuré** sur 6 000 × 6 000 à 16 bits avec `-Xmx300m` (profil 3) : 147,2 Mio vivants, 174,2 Mio au plus en
   comptant les déchets (182,7 Mo), sous le budget de 216 Mo du dossier et le plafond de 250 Mio (voir « Bilan
   mémoire »).
   Conséquences sur l'interface : le constructeur de `PixelImage` reçoit un `char[]`, et `pixels()` renvoie un
   `char[]` ; `PixelImage.fromInts(...)` garde l'entrée en `int[]`. `PixelImage` est une classe finale et non plus un
   `record` (mêmes accesseurs), pour pouvoir reprendre la copie de travail sans troisième copie.
10. **SHA-256 indisponible** (impossible dans une JVM conforme) → `ILLEGAL_STATE`, pour rester dans la liste fermée
    des motifs.
11. **Pas QIM proportionnel à la profondeur : Δ(B) = 12 × 2^(B − 8)** (profil 3). B est la profondeur effective de
    l'original (`BitsStored`, de 8 à 16). Raison : avec un pas fixe de 12 valeurs stockées, une image 12 bits recevait
    un pas 16 fois plus petit, relativement à sa dynamique, qu'une image 8 bits ; JPEG Q75 (passage en 8 bits) et le
    redimensionnement à 75 % effaçaient F (≈ 50 % des bits, aucune attribution au banc de robustesse). Avec Δ(B), le
    pas relatif est le même pour toutes les profondeurs, et le PSNR (MAX = 2^B − 1) reste le même (≈ 61 dB sur les
    fantômes 512 × 512, en 8 comme en 12 bits). Pour B = 8, Δ reste 12 et toutes les sorties sont identiques au
    profil 2 (empreintes SHA-256 relevées sur `main`, `NonRegression8BitTest`, `NonRegression8BitChainTest`).
    Un seul point de calcul, `EmbeddingProfile` ; ce qui en dépend :
    - le pas de la QIM et la plage des dithers `[0, Δ)` (`QimBlockCodec`, `FBlockChannel.qimDelta()`, dithers tirés par
      `FingerprintEmbedder` à l'échelle du canal) ;
    - l'écart maximal d'un pixel F, `maxFDeviation(B) = Δ(B)` (`F_DEVIATION_EXCEEDED`, contrôle final) ;
    - la garde, `guardQ(B) = 4 × 2^(B − 8)` niveaux de q, qui reste plus large que l'écart F exprimé en q
      (`3 × 2^(B − 8)`) : c'est elle qui garantit la stabilité du masque ;
    - le seuil de décision à l'extraction, Δ/4, qui découle du réseau.
    La fenêtre de recherche du seuil des candidats (`T_SEARCH`) n'est pas mise à l'échelle (limite 5).
12. **Δ fourni par le registre, jamais lu dans l'image analysée.** `finish()` renvoie un `EmbeddingResult` dont
    `bitsStored()` est la profondeur B à enregistrer dans le registre d'émission ; à l'analyse,
    `FBlockReader.of(copie, partitions, B)` reçoit cette valeur. Raison : un attaquant peut modifier les attributs de
    la copie, et une copie signée porte `BitsStored = 16`. Un Δ de 8 bits appliqué à une copie 12 bits rend les bits
    F aléatoires (≈ 50 %) et l'attribution échoue (`RegistryDepthTest`).
13. **Copie en entier signé 16 bits quand F sortirait de la plage.** Chaque bloc F est calculé sans bornage. Si une
    valeur sort de `[0, 2^B − 1]` (fond nul, fond saturé) :
    - B ≤ 15 et toutes les valeurs dans `[−32 768, 32 767]` : la copie passe en **mode signé**. Les valeurs sont
      écrites telles quelles dans le même `char[]` (complément à deux, conversions dans `PixelImage` seulement) ; les
      pixels de la ROI gardent exactement leur valeur ; seuls des pixels de la RONI peuvent devenir négatifs ou
      dépasser `2^B − 1`, ce que le contrôle final vérifie (`RANGE_OUTSIDE_RONI`). `EmbeddingResult.signedOutput()`
      est vrai et `pixelAttributes()` donne les attributs à appliquer par le module dicom : `PixelRepresentation = 1`,
      `BitsAllocated = 16`, `BitsStored = 16`, `HighBit = 15`. `BitsAllocated` passe à 16 pour un original 8 bits
      stocké sur 8 bits (un échantillon de 8 bits ne peut pas porter un entier signé 16 bits) ; il vaut déjà 16 pour
      un original de 9 à 15 bits. Tous les autres attributs d'interprétation restent inchangés : c'est la seule
      dérogation documentée à SEC-M03 (c), et elle porte sur ces quatre attributs seulement.
    - B = 16, ou valeur hors de `[−32 768, 32 767]` : refus `EMBEDDING_RANGE_UNSUPPORTED`, aucune copie.
    `PixelImage.bitsStored()` reste B pour une copie signée : `roiDigest` inclut `bitsStored` et doit rester celui que
    W signe. Raison : échec fermé sans écrêtage. Au profil 2, le bornage silencieux à 0 cassait 6,34 % des bits F sur
    un fond nul, et le contrôle final annulait 100 % des copies : ces images (scanner, IRM) étaient non marquables.
    La règle vaut aussi en 8 bits : une image 8 bits à fond nul est désormais marquée en mode signé, alors que le
    profil 2 l'annulait ; une copie 8 bits produite sans sortie de plage est identique au profil 2.
14. **Détection de la RONI sur une copie signée.** `q` est calculé sur la valeur décodée (décalage arithmétique) : un
    pixel négatif a un `q` négatif et tombe dans le fond sombre ; une valeur supérieure à `2^B − 1` reste du côté du
    fond clair. Vérifié sur une copie signée relue avec `BitsStored = 16` et avec `BitsStored = B` : mêmes blocs W, S
    et F que l'original.
15. **`PixelPaddingValue`.** Si l'original déclare une valeur de remplissage, les blocs qui contiennent un pixel de
    cette valeur sont exclus de la RONI : ces pixels ne sont jamais modifiés. Si la RONI passe alors sous 290 blocs
    alors qu'elle suffisait sans cette exclusion, refus `PIXEL_PADDING_IN_RONI` (cas du fond nul déclaré comme
    remplissage). Le vérificateur applique la même exclusion si la copie déclare la même valeur.

## Limites connues

1. **Bruit gaussien et JPEG Q75 : objectif « ≥ 95 % des bits » non atteint.** À bruit relatif égal (σ/Δ), le taux ne
   dépend pas de B : pour σ = 2 × 2^(B − 8), la marge de décision Δ/4 vaut 1,5 σ, théorie 1 − 2Q(1,5) ≈ 86,6 % (le
   test l'encadre entre 83 % et 90 %) ; les 95 % sont atteints vers σ ≈ 1,45 × 2^(B − 8). Au banc de robustesse
   (`AttackBench`, voir [`docs/ci-cd.md`](../../../docs/ci-cd.md)), JPEG Q75 laisse ≈ 70 % des bits F en 8 comme en
   12 bits, mais le vrai destinataire est accusé dans 20 copies sur 20, sans faux positif ; le redimensionnement à
   75 % dépasse 95 % des bits dans les deux profondeurs. Augmenter encore Δ relativement à la dynamique n'est pas
   possible sans casser la garde (donc SEC-M01) et l'imperceptibilité.
2. **B = 16 : aucune copie si F sort de la plage.** Il n'existe pas de représentation sur 16 bits plus large : une
   image 16 bits dont le fond est à moins de ≈ 0,89 × Δ(16) ≈ 2 740 valeurs de 0 ou de 65 535 peut être refusée
   (`EMBEDDING_RANGE_UNSUPPORTED`), selon les bits écrits. Les images médicales en `BitsStored = 12` (cas courant du
   scanner et de l'IRM) passent en mode signé.
3. **Mode signé, côté module dicom (non testé sans lui).** L'écriture des attributs `PixelRepresentation = 1`,
   `BitsAllocated = 16`, `BitsStored = 16`, `HighBit = 15` (pour un original 8 bits : réécriture des pixels sur
   2 octets) et la relecture d'une copie signée relèvent du module dicom (prompt 10). Une copie
   relue doit être construite par `PixelImage.signedFromShorts(…)` avec la profondeur B **du registre** : `roiDigest`
   inclut `bitsStored`, et un `BitsStored = 16` lu dans la copie donnerait un autre condensat que celui signé par W.
   La détection de la RONI, elle, ne dépend pas de cette valeur (testé avec 16 et avec B).
4. **`PixelPaddingValue` : testé sur des images synthétiques seulement.** L'exclusion et le refus sont testés avec
   `PixelImage.withPixelPaddingValue` ; la lecture réelle de l'attribut, sa conservation dans la copie et sa transmission
   au vérificateur ne peuvent pas être testées sans le module dicom. Si un pixel de la RONI prenait, après marquage,
   la valeur de remplissage (fond proche de cette valeur), le vérificateur exclurait son bloc sur la copie : le contrôle
   final refuserait alors l'émission (`MASK_UNSTABLE`, échec fermé). Ce cas n'a pas été observé sur les fantômes.
5. **Fenêtre de recherche du seuil non mise à l'échelle.** Les candidats restent `T − 4 … T + 4` quel que soit B. Avec
   Δ(B), l'Otsu de la copie peut s'écarter davantage du seuil d'origine en niveaux de q ; sur une image dont le fond et
   le corps sont nettement séparés, le masque ne dépend pas du seuil choisi dans l'intervalle (tests sur fond normal,
   nul et saturé). Sinon, le contrôle final refuse (`MASK_UNSTABLE`, échec fermé). Élargir la fenêtre multiplierait
   les candidats, et la mémoire du vérificateur.
6. **Corps qui couvre la majorité du bord (profil §4.1).** La polarité s'inverse et le corps est pris pour du fond ;
   l'image est **acceptée** avec une « RONI » dans le corps (`RoniDetectorTest.knownLimitation…`, disque de rayon 300
   dans 512 × 512). Seule la validation humaine obligatoire (SEC-M02) rattrape ce cas. Un disque un peu plus petit
   (rayon 285), qui touche les quatre bords sans en couvrir la majorité, est refusé (`TOO_FEW_RONI_BLOCKS`).
7. **Point d'injection de défaut** : `EmbeddingSession.injectFault` (visibilité paquet) sert au test de mutation
   SEC-M03. Pour qu'aucun autre code ne puisse l'appeler, il faudrait sceller le paquet (`module-info.java`) au moment
   de l'intégration dans `medimage-web`.
8. **Incohérence relevée dans la v2.4** (non corrigée ici : ce module ne modifie pas le dossier de conception) : §8.4
   V3 dit encore « lire W dans la classe W (rangs 0 à 2 modulo 10) », un reste de la v2.3. Ce module lit W dans les
   26 blocs W.
9. **Écarts avec `medimage-fingerprint`** (v2.3, non modifié) : `FPartition` et `FingerprintParams` (voir le tableau de
   l'interface). Le commentaire du `pom.xml` parent « à venir : … medimage-embedding … » n'a pas été retouché : la
   seule modification du parent est l'ajout du module et de son entrée `dependencyManagement`.
10. **SEC-M03 (c) : aucun module ne le couvre pour l'instant.** Le point (c) exige des attributs d'interprétation
    inchangés : Rows, Columns, BitsAllocated, BitsStored, HighBit, PixelRepresentation, PhotometricInterpretation,
    PixelSpacing, RescaleSlope et Intercept, WindowCenter et Width, VOI LUT, PixelPaddingValue, orientation et
    position. Ce module ne voit que les pixels et deux indicateurs (annotation incrustée, overlay) : il ne peut pas faire
    ce contrôle. Il faut l'attribuer à `medimage-dicom` ou à `medimage-web`, par une comparaison de l'en-tête de
    l'original et de la copie avant l'émission, en tolérant seulement la dérogation du mode signé (décision 13 :
    PixelRepresentation, BitsAllocated, BitsStored et HighBit). Sinon
    SEC-M03 reste partiellement couvert.
