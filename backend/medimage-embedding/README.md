# medimage-embedding — brique commune d'incorporation (profil v2)

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
| `EmbeddingProfile` | publique | Toutes les constantes du profil v2 (une seule classe). |
| `EmbeddingLimits` | publique | `checkDimensions(rows, cols, bitsStored)` : contrôle O(1), **avant toute allocation**. |
| `PixelImage` | publique (record) | Image immuable : copie défensive en entrée et en sortie, validation complète. |
| `Photometric` | publique (enum) | `MONOCHROME1`, `MONOCHROME2` (information seulement). |
| `RoniDetector` | publique | `detect(image)` avec refus ; `candidates(marked)` pour le vérificateur (T ± 4, sans refus). |
| `RoniProfile` | publique | Seuil T, polarité, blocs RONI en ordre raster, masque par pixel, `profileVersion = 2`. |
| `BackgroundPolarity` | publique (enum) | `DARK` (fond si q ≤ T) ou `LIGHT` (fond si q > T). |
| `Partitions` | publique | Découpage 26 / 8 / reste ; géométrie des blocs. |
| `FBlockChannel` | publique (interface) | Canal F donné au module fingerprint. |
| `FBlockReader` | publique | `FBlockChannel` en lecture seule sur une copie suspecte (recadrage → `UNREADABLE`). |
| `LsbChannel` | publique | `readS()`, `readW()` ; `reader(image, partitions)` en lecture seule. Écriture seulement via la session. |
| `Digests` | publique | `roiDigest`, `maskDigest`, `sDigest` (SHA-256 de la JCA). |
| `EmbeddingSession` | publique | Orchestration F → S → condensats → W → `finish()` avec contrôle final. |
| `EmbeddingException` | publique | `Reason` (liste fermée) et `FinalCheck` (sous-motif du contrôle final), `auditCode()`. |
| `Otsu` | paquet | Seuil d'Otsu déterministe. |
| `QimBlockCodec` | paquet | DCT-II orthonormée 8 × 8, QIM avec dither, écriture et lecture d'un bloc. |

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
PixelImage copie = s.finish();                            // contrôle final, ou FINAL_CHECK_FAILED

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
| `GUARD_VIOLATION` | un pixel de la RONI est à moins de 4 niveaux de q du seuil |
| `ROI_TOO_SMALL` | ROI < 5 % des pixels |
| `BURNED_IN_ANNOTATION` | `BurnedInAnnotation = YES` |
| `OVERLAY_PRESENT` | overlay présent |
| `IMAGE_TOO_LARGE` | `rows × cols > 36 000 000`, ou un côté > 8 192 |
| `INVALID_INPUT` | entrée malformée (longueurs, valeurs, nuls, index, dithers) |
| `F_DEVIATION_EXCEEDED` | l'insertion F écarterait un pixel de plus de 12 |
| `FINAL_CHECK_FAILED` | contrôle final ; sous-motif `FinalCheck` ci-dessous |
| `ILLEGAL_STATE` | ordre non respecté, écriture après `finish()`, session en échec |

`FinalCheck` : `ROI_MODIFIED`, `UNUSED_BLOCK_MODIFIED`, `DEVIATION_EXCEEDED`, `PSNR_TOO_LOW`,
`S_READBACK_MISMATCH`, `W_READBACK_MISMATCH`, `F_READBACK_MISMATCH`, `MASK_UNSTABLE`.

## Algorithme (profil v2)

1. `q = pixel >> 2`.
2. Seuil d'Otsu `T` sur l'histogramme de `q` (taille `(maxValue >> 2) + 1`), classe basse `q ≤ T`. **Égalités** :
   si plusieurs seuils atteignent exactement le maximum de variance inter-classes (cas d'un trou entre le fond et le
   corps), on prend la partie entière de la moyenne de leurs indices (convention de `graythresh` de MATLAB).
3. Polarité = majorité des pixels du bord (chaque pixel compté une fois ; égalité : fond sombre).
4. Fond connexe au bord, 4-connexité, parcours en largeur itératif (file circulaire qui grandit à la demande).
5. Érosion de 8 px (carré 17 × 17, l'extérieur compte comme du fond), séparable : minimum glissant en ligne puis en
   colonne, O(n).
6. Blocs 8 × 8 alignés sur (0, 0), entièrement dans le masque érodé. Indice de bloc = `ligneBloc × (cols / 8) +
   colonneBloc`. Blocs incomplets ignorés.
7. Garde : chaque pixel de la RONI vérifie `T − q ≥ 4` (fond sombre) ou `q − T ≥ 4` (fond clair).
8. Refus, dans cet ordre : `BURNED_IN_ANNOTATION`, `OVERLAY_PRESENT`, `TOO_FEW_RONI_BLOCKS`, `GUARD_VIOLATION`,
   `ROI_TOO_SMALL`.

`candidates(marked)` : seuils essayés dans l'ordre `T, T−1, T+1, …, T−4, T+4` (T = Otsu de la copie), seuils hors de
`[0, maxValue >> 2]` ignorés, polarité recalculée pour chaque seuil, masques identiques dédoublonnés (le premier est
gardé).

Pourquoi le masque est stable : un pixel F bouge d'au plus 11 valeurs stockées, donc de 3 niveaux de q au plus ; un
pixel S ou W d'au plus 1. Grâce à la garde de 4 niveaux, tout pixel modifié reste du côté du fond pour le seuil
d'origine ; les autres pixels ne bougent pas. Le masque calculé avec le seuil d'origine est donc identique, et il est
dans les candidats dès que l'Otsu de la copie reste à ± 4 du seuil d'origine.

## Couche F : QIM sur DCT 8 × 8

- DCT-II orthonormée : `M[u][x] = α(u)·cos((2x+1)uπ/16)`, `α(0) = √(1/8)`, sinon `√(2/8)` ; `C = M·F·Mᵀ`.
- Porteurs, rangs zigzag JPEG 6 à 13, (ligne, colonne) : (0,3) (1,2) (2,1) (3,0) (4,0) (3,1) (2,2) (1,3). Le bit
  `j` de `bits8` va au porteur `j`.
- Insertion : `c' = Δ·round((c − d − bΔ/2)/Δ) + d + bΔ/2`, avec `round(x) = floor(x + 0,5)`, Δ = 12, `d ∈ [0, 12)`.
- Extraction : réseau le plus proche ; égalité → 0.
- Après DCT inverse : arrondi `floor(x + 0,5)`, bornage `[0, maxValue]`, puis contrôle écart ≤ 12
  (`F_DEVIATION_EXCEEDED`). Borne théorique : 6 × (3·√2/8 + 5/4) ≈ 10,7, soit ≤ 11 après arrondi.
- Bloc écrit d'un coup : en cas de refus, rien n'est écrit (et la session passe en échec).

## Condensats : encodage exact (à reproduire à l'identique dans `watermark`)

**Origine.** Les trois formules viennent du §8.3 de la v2.4, qui précise seulement « version 1, big-endian ». Le reste
de l'encodage **ne vient pas du dossier** mais de la consigne de ce module :

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
    void write(int fIndex, boolean[] bits8, double[] dither8);
    int[] read(int fIndex, double[] dither8);                // 0, 1 ou UNREADABLE (×8 si le bloc sort de l'image)
}
```

- À l'émission : `session.fChannel()`. Au plus **128 blocs**, chacun écrit **une seule fois**, avant `writeS`.
- À l'analyse d'une fuite : `FBlockReader.of(copieSuspecte, partitionsDeLEmission)`. Les partitions de l'émission
  doivent donc être recalculables (`Partitions.of(RoniDetector.detect(original))`) ou conservées. Seul un recadrage à
  droite ou en bas est géré (`UNREADABLE`) ; un recadrage à gauche ou en haut décale la grille.
- `dither8` : 8 valeurs finies dans `[0, 12)`, dérivées de K_F par le module fingerprint ; ce module ne voit aucune clé.

**Ce qui change par rapport à l'ancien `FPartition` (v2.3, encore dans `medimage-fingerprint`)** — je n'ai pas touché
au code de ce module, voici seulement les écarts à reprendre :

| | `FPartition` (v2.3) | `FBlockChannel` (v2.4) |
|---|---|---|
| Unité | 1 pixel | 1 bloc 8 × 8 = 8 bits |
| Insertion | LSB du pixel | QIM (Δ = 12) sur les rangs zigzag 6 à 13 de la DCT |
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
portable. Les temps sont indicatifs et ne sont pas vérifiés par les tests.

| Mesure | Résultat |
|---|---|
| Bits F mal relus sans bruit, **fond à 0** (20 sessions × 128 blocs, 20 480 bits) | **6,34 %** |
| Sessions annulées par le contrôle final, fond à 0 | **100 %** (`FINAL_CHECK_FAILED:F_READBACK_MISMATCH`) |
| Bits F mal relus sans bruit, fond vers 200 | 0 % ; 0 session annulée |
| PSNR, 12 bits, 512 × 512, 50 marquages | moyen 84,94 dB ; minimal 84,73 dB |
| PSNR minimal, 8 bits, 256 × 256 (cas défavorable), 20 marquages | 54,57 dB |
| Bits F relus après bruit gaussien σ = 1 / 1,5 / 2 (4 096 bits) | 99,39 % / 94,36 % / **86,40 %** |
| Temps 512 × 512 : détection seule | ≈ 7 ms |
| Temps 512 × 512 : session complète (F 128 blocs, S, W, contrôle final avec 9 masques candidats) | ≈ 66 ms |
| `mvn -B test` sur le module (83 tests) | ≈ 42 s ; backend complet ≈ 1 min 02 s |

## Exigence → test(s)

Les classes de test portent `@Tag` avec l'identifiant. Pour lancer une exigence : `mvn -B test -Dgroups=SEC-M01`.

| Exigence (v2.4) | Ce qui est prouvé | Test(s) |
|---|---|---|
| SEC-M01 | RONI déterministe du profil v2 ; masque identique avant et après marquage | `OtsuTest`, `RoniDetectorTest`, `ErosionTest`, `PartitionsTest`, `MaskStabilityTest`, `InvariantPropertyTest` |
| SEC-M03 (a) et (b) | contrôle automatique : ROI identique, écart ≤ 1 (S, W) et ≤ 12 (F), PSNR ≥ 40 dB ; mutation d'écriture détectée. Le point (c), attributs d'interprétation inchangés, **n'est pas couvert ici** (décision 17) | `FailClosedTest`, `EmbeddingSessionTest`, `LsbChannelTest`, `DctQimTest`, `InvariantPropertyTest` |
| SEC-M04, SEC-D08 | refus « non marquable » calculé par le système, sans option pour forcer | `RoniDetectorTest` |
| SEC-M05 | refus si annotation incrustée ou overlay (condition G2) | `RoniDetectorTest` |
| SEC-M06, SEC-D07 | l'original n'est jamais écrit ; la copie ne contient que l'original et les marques | `FailClosedTest`, `InvariantPropertyTest` |
| SEC-D02, SEC-F02 | dimensions contrôlées avant allocation (bombe rejetée < 2 s), entrées malformées rejetées | `EmbeddingLimitsTest` |
| SEC-D02, SEC-F01 | messages d'erreur sans valeur de l'image ; motifs compatibles avec le journal d'audit | `ErrorLeakTest` |
| SEC-D03 | condensats signés par W : encodage figé, sensibles à la ROI et au masque, insensibles à F | `DigestsTest`, `EmbeddingSessionTest`, `LsbChannelTest` |
| SEC-D04 | couche F : 8 bits par bloc par QIM sur DCT, 128 blocs, lecture d'une copie recadrée | `DctQimTest`, `PartitionsTest`, `EmbeddingSessionTest`, `MeasurementsTest` |

La correspondance suit les conditions G de la v2.4 (« conditions pour marquer une image ») :

| Condition | Exigences |
|---|---|
| G2 : ni annotation incrustée, ni overlay | SEC-M04, SEC-M05 |
| G3 : RONI du profil v2, ROI ≥ 5 % | SEC-M01, SEC-M04 |
| G4 : au moins 290 blocs, garde de 4 niveaux | SEC-M04, SEC-D08 |
| G6 : après marquage, ROI identique, écarts ≤ 1 et ≤ 12, PSNR ≥ 40 dB | SEC-M03 |
| G7 : original jamais écrit | SEC-M06 |

## Décisions à valider

1. **Garde (étape 7) — lecture littérale.** Un seul pixel de la RONI à moins de 4 niveaux de q du seuil fait refuser
   l'image (`GUARD_VIOLATION`). Variante possible : exclure le bloc fautif de la RONI au lieu de refuser l'image (plus
   d'images marquables, mais le masque dépend alors de la garde, qu'il faudra reproduire à l'identique dans
   `candidates`).
2. **Fond à 0 : la couche F casse au bornage (mesuré, non corrigé).** Sur un fond à 0 (scanner, IRM), le bornage à 0
   après la DCT inverse casse 6,34 % des bits F ; le contrôle final, qui exige que les bits F écrits soient relus
   identiques, annule **100 %** des copies (`FINAL_CHECK_FAILED:F_READBACK_MISMATCH`). Ces images sont donc de fait
   non marquables, par échec fermé. Je n'ai changé ni Δ, ni les rangs, ni le contrôle. Pistes à trancher en équipe :
   (a) garder ce comportement ; (b) ne plus exiger la relecture exacte de F au contrôle final et laisser Tardos
   absorber les erreurs (affaiblit SEC-M03) ; (c) rendre inéligibles à F les blocs trop proches de 0 ou du maximum,
   avec un critère qui donne le même résultat sur l'original et sur la copie (délicat : les indices F du vérificateur
   doivent rester identiques).
3. **Robustesse σ = 2 : l'objectif « ≥ 95 % » est impossible avec Δ = 12.** La marge de décision de la QIM vaut
   Δ/4 = 3, soit 1,5 σ pour σ = 2 : théorie 1 − 2Q(1,5) ≈ 86,6 %, mesuré 86,40 %. Les 95 % sont atteints vers
   σ ≈ 1,45 (99,39 % à σ = 1, 94,36 % à σ = 1,5). Le test écrit vérifie donc : σ = 1 → ≥ 95 %, et σ = 2 → taux
   conforme à la théorie (entre 83 % et 90 %). Augmenter Δ n'est pas possible sans casser le profil : au-delà de
   Δ ≈ 13, l'écart par pixel dépasse 12 et la variation de q atteint 4 niveaux, ce qui casse la garde et donc SEC-M01.
   L'objectif SEC-D04 (≥ 95 % des symboles après JPEG Q75 ou redimensionnement à 75 %) est à confirmer par le banc
   d'attaques de la CI : il est à risque.
4. **Limite connue du profil (§4.1) : corps qui couvre la majorité du bord.** La polarité s'inverse et le corps est
   pris pour du fond ; l'image est **acceptée** avec une « RONI » dans le corps (`RoniDetectorTest.knownLimitation…`,
   disque de rayon 300 dans 512 × 512). Seule la validation humaine obligatoire (SEC-M02) rattrape ce cas. Un disque
   un peu plus petit (rayon 285), qui touche les quatre bords sans en couvrir la majorité, est refusé
   (`TOO_FEW_RONI_BLOCKS`).
5. **Dimensions : limite de 8 192 par côté ajoutée** (`MAX_DIMENSION`), en plus de `rows × cols ≤ 36 000 000`, parce
   que la v2.4 (SEC-F02) l'exige. `rows` ou `cols` ≤ 0 → `INVALID_INPUT` ; dimensions trop grandes ou produit qui
   déborde d'un `int` → `IMAGE_TOO_LARGE` (calcul en `long`). `bitsStored` hors de [8, 16] → `INVALID_INPUT`.
6. **Égalités d'Otsu** : moyenne entière des seuils ex aequo (voir Algorithme). Avec la règle « premier maximum »
   (celle de nombreuses bibliothèques), le seuil tombe au sommet du bruit du fond : les pixels de fond les plus clairs
   sont alors à moins de 4 niveaux du seuil, et une image à fond bien séparé (comme le fantôme normal) serait refusée
   pour `GUARD_VIOLATION`. C'est un choix d'implémentation que la v2.4 ne fixe pas ; le vérificateur utilise le même
   code, donc il reste cohérent.
   Histogramme à une seule valeur : `T` = cette valeur.
7. **Ordre et forme des candidats** : `T, T−1, T+1, …` (le plus probable d'abord), polarité recalculée pour chaque
   seuil, seuils hors limites ignorés, doublons supprimés. Le résultat est une liste de `RoniProfile`.
8. **Session plus stricte que la consigne** : au plus 128 blocs F, chacun écrit une seule fois ; F interdit après S ;
   S et W écrits une seule fois ; `finish()` exige S et W ; F n'est pas obligatoire (0 à 128 blocs) — faut-il exiger
   exactement 128 blocs ? **Toute** exception (même une entrée invalide) place la session en échec définitif.
9. **Sous-motifs ajoutés au contrôle final** : `DEVIATION_EXCEEDED` (écarts ≤ 1 et ≤ 12, SEC-M03 b) et
   `UNUSED_BLOCK_MODIFIED` (« les blocs réservés mais non utilisés ne sont pas modifiés », §4.1). Ordre des contrôles :
   ROI, écarts, PSNR, relectures S, W, F, masque.
10. **Arrondis** : `floor(x + 0,5)` pour la QIM et la DCT inverse ; égalité de distance à l'extraction → bit 0.
11. **`Photometric` n'intervient pas dans la détection** : la polarité vient du bord, comme le dit la v2.4.
12. **Mémoire à 36 Mpx** : `PixelImage` impose `int[]` (4 octets par pixel). Original 144 Mo + copie de travail
    144 Mo + deux masques `boolean[]` de 36 Mo pendant la détection : au-delà du budget de 250 Mio par traitement de
    SEC-F02. Il faudrait un stockage `short[]`/`char[]`, ce qui change l'interface demandée : à décider.
13. **Point d'injection de défaut** : `EmbeddingSession.injectFault` (visibilité paquet) sert au test de mutation
    SEC-M03. Pour qu'aucun autre code ne puisse l'appeler, il faudrait sceller le paquet (`module-info.java`) au moment
    de l'intégration dans `medimage-web`.
14. **SHA-256 indisponible** (impossible dans une JVM conforme) → `ILLEGAL_STATE`, pour rester dans la liste fermée
    des motifs.
15. **Incohérence relevée dans la v2.4** (non corrigée, `docs/` n'est pas à moi) : §8.4 V3 dit encore « lire W dans
    la classe W (rangs 0 à 2 modulo 10) », un reste de la v2.3. Ce module lit W dans les 26 blocs W.
16. **Écarts avec `medimage-fingerprint`** (v2.3, non modifié) : `FPartition` et `FingerprintParams` (voir le tableau de
    l'interface). Le commentaire du `pom.xml` parent « à venir : … medimage-embedding … » n'a pas été retouché : la
    seule modification du parent est l'ajout du module et de son entrée `dependencyManagement`.
17. **SEC-M03 (c) : aucun module ne le couvre pour l'instant.** Le point (c) exige des attributs d'interprétation
    inchangés : Rows, Columns, BitsAllocated, BitsStored, HighBit, PixelRepresentation, PhotometricInterpretation,
    PixelSpacing, RescaleSlope et Intercept, WindowCenter et Width, VOI LUT, PixelPaddingValue, orientation et position.
    Ce module ne voit que les pixels et deux indicateurs (annotation incrustée, overlay) : il ne peut pas faire ce
    contrôle. Il faut l'attribuer à `medimage-dicom` ou à `medimage-web`, par une comparaison de l'en-tête de l'original
    et de la copie avant l'émission. Sinon SEC-M03 reste partiellement couvert.
