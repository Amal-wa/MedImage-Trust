# Package `dicom` : lecture sûre et écriture de la copie

Package `tn.medimagetrust.dicom` (module api). Seul package autorisé à utiliser dcm4che (règle ArchUnit 12) ; il ne
dépend que d'`embedding` (règle 10). Exigences : SEC-D02, SEC-D07, SEC-F01, SEC-F02, SEC-F04, SEC-F05, SEC-M03,
SEC-M04 (dossier v2.6, §6.1, §6.2 et tableau des décisions DICOM).

## API

| Élément | Rôle |
|---|---|
| `DicomReader.read(InputStream, long declaredSize)` | lecture et contrôle ; renvoie un `DicomImage` ou lève `DicomRejectedException` |
| `DicomReader(DicomAuditSink)`, `DicomReader(DicomAuditSink, ExecutorService, Duration)` | délai de 30 s sur des fils virtuels ; en production, le `ManagedExecutorService` borné du serveur (pool `medimage-imaging`) |
| `DicomImage` | attributs utiles (syntaxe, modalité, profondeurs, représentation, photométrie, `RescaleIntercept`, `PixelPaddingValue`, `PixelPaddingRangeLimit`, `SOPInstanceUID`), statut `Markability`, image `PixelImage` pour l'embedding |
| `Markability` | `MARKABLE`, ou lisible mais non marquable : `BURNED_IN_ANNOTATION`, `OVERLAY_PRESENT`, `CURVE_PRESENT`, `PADDING_RANGE_UNSUPPORTED` |
| `DicomRejectedException` | motif fermé (`reason()`), message fixe sans chiffre ni valeur du fichier, aucune cause, code d'audit `DICOM_REJECTED:<MOTIF>` |
| `DicomAuditSink` | événement `DICOM_SUSPICIOUS_PREAMBLE` ; la couche REST le relie au journal d'audit (dicom ne dépend pas d'audit) |
| `DicomWriter.writeCopy(DicomImage, EmbeddingResult, OutputStream)` | écrit la copie reconstruite ; renvoie le nouveau `SOPInstanceUID` |
| `DicomImage.signedCopyImage(B, PixelRepresentation)` | relecture d'une copie signée dans le domaine de l'émission, pour la vérification |

## Ordre des contrôles

Tout est contrôlé avant l'allocation des pixels, dans cet ordre ; le premier écart rejette le fichier.

| # | Contrôle | Motif |
|---|---|---|
| 1 | taille annoncée ≤ 80 Mio ; jamais plus d'octets lus que la taille annoncée | `FILE_TOO_LARGE`, `SIZE_MISMATCH` |
| 2 | préambule : `MZ` (PE), `7F 45 4C 46` (ELF), `FE ED FA CE`, `FE ED FA CF`, `CE FA ED FE`, `CF FA ED FE` (Mach-O) ; événement d'audit | `SUSPICIOUS_PREAMBLE` |
| 3 | `DICM` à l'offset 128 | `NOT_DICOM` |
| 4 | en-tête lu en flux par dcm4che, arrêté avant PixelData, sous un garde qui voit chaque élément **avant** la lecture de sa valeur : en-tête ≤ 1 Mio (méta-informations comprises), ≤ 5 000 éléments (items compris), profondeur de séquence ≤ 8, longueur paire et multiple de la taille de la représentation (2 pour US, SS, OW ; 4 pour UL, SL, FL, AT, OL, OF ; 8 pour FD, SV, UV, OD, OV), longueur indéfinie réservée à SQ, UN et aux items, fragments refusés ; textes sans `<`, `>` ni caractère de contrôle | `HEADER_TOO_LARGE`, `TOO_MANY_ELEMENTS`, `SEQUENCE_TOO_DEEP`, `MALFORMED`, `SUSPICIOUS_TEXT` |
| 4 bis | syntaxe de transfert : Explicit VR LE ou Implicit VR LE, identique dans les méta-informations et le flux | `UNSUPPORTED_TRANSFER_SYNTAX` |
| 5 | profil : `NumberOfFrames` absent ou 1, `SamplesPerPixel` 1, MONOCHROME1 ou MONOCHROME2, `BitsAllocated` 8 ou 16, 8 ≤ `BitsStored` ≤ `BitsAllocated`, `HighBit = BitsStored − 1`, `PixelRepresentation` 0 ou 1 ; `Rows`, `Columns` ≥ 1 et ≤ 8 192, produit ≤ 36 000 000 ; modalité CR, DX, CT, MR ou MG ; `SOPClassUID`, `SOPInstanceUID` présents | `UNSUPPORTED_PROFILE`, `IMAGE_TOO_LARGE`, `MALFORMED`, `UNSUPPORTED_MODALITY` |
| 6 | PixelData présent au niveau 0, longueur définie = `Rows × Columns × octets` (plus 1 octet de bourrage si ce nombre est impair), VR OW (ou OB en 8 bits) ; lecture par blocs de 64 Kio directement dans le tableau `char[]` de `PixelImage` (`PixelImage.filled`), sans copie intermédiaire de la taille de l'image ; chaque valeur dans la profondeur déclarée | `PIXEL_DATA_MISSING`, `PIXEL_DATA_LENGTH_MISMATCH`, `PIXEL_DATA_TRUNCATED`, `PIXEL_VALUE_OUT_OF_RANGE` |
| 7 | `BurnedInAnnotation = YES`, groupe 60xx (overlay), groupe 50xx (courbe), `PixelPaddingRangeLimit` : lisible, non marquable | statut `Markability` |
| 8 | `PixelPaddingValue` (et `PixelPaddingRangeLimit`) exposés en valeur stockée ; `PixelPaddingValue` transmis à l'embedding, qui exclut ces pixels de la RONI | `PIXEL_VALUE_OUT_OF_RANGE` si hors profondeur |

Délai : toute la lecture tourne dans une tâche soumise à l'exécuteur, sous un délai de 30 s (SEC-F04). Le flux borné
vérifie l'échéance à chaque lecture ; à l'expiration, la tâche est interrompue, le flux fermé et le fichier rejeté
(`TIMEOUT`), sans bloquer l'appelant. Les octets qui suivent PixelData ne sont jamais interprétés.

Toute exception de dcm4che ou d'entrée-sortie devient un rejet à motif fermé (`MALFORMED`, ou le motif du garde) :
ni le type ni le message de l'exception d'origine ne sont conservés (ils pourraient citer le fichier).

## Écriture de la copie (SEC-D07)

La copie est **reconstruite** : attributs analysés plus pixels marqués, jamais d'octet brut du fichier reçu.

- préambule de 128 octets nuls, `DICM`, méta-informations régénérées (`Attributes.createFileMetaInformation`) ;
- syntaxe de sortie : toujours Explicit VR Little Endian ;
- retirés à tous les niveaux de séquence : éléments privés (groupe impair, créateurs compris), éléments `UN`, groupes
  `0000` à `0007`, tout ce qui suit PixelData ;
- retirés : `SmallestImagePixelValue`, `LargestImagePixelValue`, `SmallestPixelValueInSeries`,
  `LargestPixelValueInSeries` (type 3 ; les valeurs extrêmes changent avec le marquage) ;
- nouveau `SOPInstanceUID` `2.25.<entier non signé des 128 bits d'un UUID aléatoire>` (PS3.5 B.2), repris dans
  `MediaStorageSOPInstanceUID` ;
- rien après le dataset.

Valeur écrite : valeur de l'embedding moins `storedValueOffset()`, donc exactement la valeur stockée d'origine pour tout
pixel non modifié. En mode non signé, tous les attributs d'interprétation sont identiques. En mode signé, seuls
`PixelRepresentation = 1`, `BitsAllocated = 16`, `BitsStored = 16`, `HighBit = 15` changent (dérogation SEC-M03 c,
voir `embedding.md`) ; un original 8 bits est réécrit sur 2 octets par pixel ; `PixelPaddingValue` passe en `SS` avec
la même valeur.

## Décisions de conception

1. **Syntaxes de transfert.** Entrée : Explicit VR Little Endian (1.2.840.10008.1.2.1) **et** Implicit VR Little Endian
   (1.2.840.10008.1.2) ; sortie : toujours Explicit VR Little Endian ; tout le reste est rejeté
   (`UNSUPPORTED_TRANSFER_SYNTAX`). Implicit VR LE est la syntaxe par défaut de DICOM, non compressée, et la seule de
   la mammographie VICTRE du corpus ; les représentations viennent alors du dictionnaire de dcm4che, et le garde
   applique les mêmes bornes. **Écart avec le dossier v2.6 §6.1** (Explicit VR LE seulement).
2. **Modalité MG.** Acceptée en plus de CR, DX, CT, MR : mammographie 2D, image unique monochrome, même profil de
   pixels. **Écart avec le dossier v2.6 §6.1 et SEC-F01** (CR, DX, CT, MR).
3. **Représentation signée.** Pour `PixelRepresentation = 1`, l'image donnée à l'embedding vaut
   `valeur stockée + 2^(B − 1)` : décalage exact et réversible, qui garde tous les rapports de valeurs (Otsu, garde,
   condensats, Δ(B)) ; `DicomWriter` le retire. Le `PixelPaddingValue` subit le même décalage.
4. **Relecture d'une copie signée.** `signedCopyImage(B, PixelRepresentation)` a besoin de B **et** de la
   représentation de l'original : pour un original signé, la valeur stockée de la copie vaut valeur de l'embedding
   moins 2^(B − 1). Les deux valeurs viennent du registre d'émission, jamais de la copie : le registre doit donc aussi
   enregistrer `PixelRepresentation` de l'original (ajout au registre SEC06, à prévoir dans la couche REST).
5. **Plage de remplissage.** `PixelPaddingRangeLimit` rend l'image non marquable (`PADDING_RANGE_UNSUPPORTED`) :
   l'embedding n'exclut qu'une valeur, pas une plage, et les pixels de la plage pourraient être modifiés.
6. **Annotation, overlay, courbe** : statuts distincts d'un rejet (image valide, aucune copie marquée), SEC-M04.
7. **Textes.** Un `<`, un `>` ou un caractère de contrôle (sauf tabulation et sauts de ligne en LT, ST, UT, et le NUL
   final d'un UI) rejette le fichier (`SUSPICIOUS_TEXT`) : pas de neutralisation, qui serait une lecture « au mieux ».
   Les représentations `UN` ne sont pas contrôlées à la lecture et sont retirées de la copie.
8. **Comptage.** Les bornes d'en-tête portent sur méta-informations, jeu de données et séquences ; un item de
   séquence compte pour un élément.
9. **Délai.** Exécuteur à fils virtuels par défaut, injectable (`ManagedExecutorService` en production) ; échéance
   vérifiée à chaque lecture et entre deux blocs de pixels.
10. **dcm4che.** `dcm4che-core` **5.35.2**, absent de Maven Central (recherche Central : aucun résultat ; repo1 : 404).
    Dépôt officiel `https://www.dcm4che.org/maven2/` (HTTPS), déclaré dans `middleware/pom.xml` et limité au groupe
    `org.dcm4che` par le filtre de dépôts de Maven Resolver (`middleware/.mvn/maven.config`,
    `middleware/.mvn/rrf/groupId-dcm4che.txt`) ; `--strict-checksums` et `checksumPolicy` `fail`. La 5.36.0 publie
    `dcm4che-core` mais pas son POM parent `dcm4che-parent:5.36.0` (404 sur le dépôt) : elle n'est pas résolvable.
    `slf4j-api` 2.0.18 (version du parent 5.35.2) en `provided`, fourni par WildFly.

## Écarts avec le dossier v2.6

| Sujet | Dossier v2.6 | Implémentation | Raison |
|---|---|---|---|
| Syntaxes d'entrée | Explicit VR LE seulement (§6.1) | Explicit VR LE et Implicit VR LE ; sortie Explicit | décision 1 |
| Modalités | CR, DX, CT, MR (§6.1, SEC-F01) | CR, DX, CT, MR, MG | décision 2 |
| Registre d'émission | B enregistré | B et `PixelRepresentation` de l'original | décision 4 |

## Tests

| Test | Contenu |
|---|---|
| `DicomTrapTest` | 47 fichiers piégés générés octet par octet (PE, ELF, 4 Mach-O, DICM absent, tailles, longueurs impaires ou aberrantes, longueur indéfinie hors séquence, profondeur 9, plus de 5 000 éléments dans le jeu de données et dans les méta-informations, en-tête et méta-informations au-delà de 1 Mio, JPEG, Big Endian, Deflate, Rows × Columns, multi-image, RGB, PALETTE COLOR, 32 bits, `<script>`, caractère de contrôle, PixelData absent, tronqué, trop long, trop court, encapsulé, valeurs hors profondeur…) : motif attendu, message fixe sans valeur du fichier, événement d'audit pour les préambules |
| `DicomFuzzTest` | 200 mutations à graine fixe : 0 plantage non maîtrisé, 0 dépassement du délai |
| `DicomReaderTest` | Explicit et Implicit VR LE, 8 bits à nombre de pixels impair, représentation signée, `PixelPaddingValue`, MG, statuts non marquables, délai (flux bloqué, flux lent) |
| `DicomWriterTest` | SEC-D07 (élément privé, séquence privée, octets de fin, valeurs extrêmes : absents de la copie ; copie identique hors RONI, `SOPInstanceUID` et attributs signés) ; mode signé 12 bits et 8 bits à fond nul, original signé : attributs 1 / 16 / 16 / 15, valeurs négatives relues, mêmes blocs W, S, F |
| `DicomCorpusTest` (`@Tag("corpus")`) | corpus réel de `docs/CORPUS.md` (voir ci-dessous) |

## Corpus réel

26 fichiers (`docs/CORPUS.md`), tous lus sans rejet. Résultat des tests de corpus :

| Fichiers | Syntaxe | Modalité | BitsAllocated / B | PixelRepresentation | PixelPaddingValue | Statut |
|---|---|---|---|---|---|---|
| ct-01-ge (5) | Explicit VR LE | CT | 16 / 16 | 1 | −2000 | non marquable : `PIXEL_PADDING_IN_RONI` (le fond hors du champ de reconstruction est du remplissage) |
| ct-02-philips (5) | Explicit VR LE | CT | 16 / 12 | 0 | — | marquable, copie signée, PSNR ≥ 61,3 dB |
| ct-03-siemens (5) | Explicit VR LE | CT | 16 / 12 | 0 | — | marquable, copie signée, PSNR ≥ 61,2 dB |
| mr-01-ge (5) | Explicit VR LE | MR | 16 / 16 | 1 | 0 | non marquable : `GUARD_VIOLATION` |
| mr-02-siemens (5) | Explicit VR LE | MR | 16 / 12 | 0 | — | non marquable : `GUARD_VIOLATION` |
| mg-01-victre (1) | Implicit VR LE | MG | 16 / 16 | 0 | — | non marquable : `GUARD_VIOLATION` |

## Limites connues

- Corpus sans radiographie (CR, DX) ; une seule mammographie.
- 16 fichiers du corpus sur 26 ne sont pas marquables avec le profil RONI 3 (garde du seuil de fond sur le bruit
  d'IRM et de la mammographie simulée ; remplissage GE hors du champ). Ce sont des refus de l'embedding, en échec fermé,
  pas des rejets de lecture.
- Le contrôle des textes rejette un fichier légitime dont une description contiendrait `<` ou `>` (aucun cas dans le
  corpus).
