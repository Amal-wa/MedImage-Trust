# Module `medimage-fingerprint` (F08 / BF07 / BF08 / SEC-D04)

Code de Tardos symétrique, c = 2, 1 024 symboles, ≤ 64 destinataires par image, écrit dans la couche robuste F du
dossier v2.4 (§4.1, §8.2) : QIM sur DCT 8 × 8, 1 bit par coefficient, 8 coefficients par bloc, 128 blocs tirés
secrètement dans une classe F d'au moins 256 blocs.

Dépendances : `medimage-crypto` (clé K_F, engagement `fCommit`) et `medimage-embedding` (canal
[`FBlockChannel`](../medimage-embedding/src/main/java/tn/medimagetrust/embedding/FBlockChannel.java), voir
[`medimage-embedding/README.md`](../medimage-embedding/README.md), section « Interface pour le module fingerprint »).

| Fichier | Rôle |
|---|---|
| `FingerprintParams` | constantes : 1 024 symboles, 1 répétition, 64 destinataires, t = 1/600, étiquettes `MIT-*` ; bits par bloc, blocs utilisés, classe F minimale et Δ repris de `EmbeddingProfile` |
| `Prf`, `CryptoPrf`, `HmacSha256Prf` | port HMAC sous K_F ; adaptateur vers `CryptoService` (production) ; version JCA pour tests unitaires |
| `Messages`, `HmacStream` | messages HMAC non ambigus ; flux HMAC en mode compteur |
| `PositionPermutation` | 128 blocs secrets de la classe F (Fisher-Yates piloté par HMAC, graine = masque) |
| `TardosMath` | loi des biais à coupure, terme de score symétrique |
| `TardosCodeGenerator` | biais, mots de code, bits à écrire (`expand`), engagement `fCommit` |
| `FingerprintEmbedder` | écriture des 128 blocs (8 bits et 8 dithers chacun) et lecture (bloc illisible → 8 effacements) |
| `TardosScorer`, `ThresholdCalibrator` | score ; seuil = z(eps/N) × sqrt(n lisibles) × 1,15 |
| `ColluderAnalyzer`, `AttributionReport` | analyse CU08, verdicts à liste fermée, contrôle W/F |
| `FingerprintService` | façade pour le module `web` (émission F09, analyse CU08) |
| `CollusionSimulator`, `SimulationReport`, `SimulationMain` | Monte-Carlo SEC-D04 et rapport CI |

## Branchement sur le module embedding

```java
// Émission
EmbeddingSession s = EmbeddingSession.open(image);
FingerprintService fp = FingerprintService.forKid(crypto, kid);
var e = fp.prepare(imageId, emissionId, recipientId);        // bits F + fCommit (à signer dans W)
fp.embed(s.fChannel(), s.maskDigest(), e);                    // 128 blocs, avant writeS
// ... writeS, writeW, finish()

// Analyse d'une copie suspecte (service recréé avec le kid enregistré de l'émission)
Partitions p = Partitions.of(RoniDetector.detect(original));  // géométrie de l'émission
fp.analyze(imageId, FBlockReader.of(copie, p), Digests.maskDigest(RoniDetector.detect(original)), candidats, wRecipient);
```

## Correspondance symbole → bloc → coefficient

| Élément | Règle |
|---|---|
| Blocs utilisés | `pos = PositionPermutation.select(K_F, maskDigest, classSize(), 128)` : 128 indices distincts de la classe F, ordre du mélange |
| Symbole `i` (0 à 1 023) | bloc `pos[i / 8]`, coefficient `i % 8` (rang zigzag `6 + i % 8`, voir embedding) |
| Dithers du bloc d'indice `b` | 8 valeurs `HmacStream(K_F, "MIT-FD-v1", maskDigest ‖ b (4 octets, gros-boutiste))`, chacune = 53 bits du flux → réel dans [0, 1) × Δ (Δ = 12) |
| Bloc illisible (`UNREADABLE`) | ses 8 symboles deviennent `ExtractedWord.ERASURE` |
| Classe F < 256 blocs | `FingerprintException.Code.CAPACITY` (message sans taille), rien n'est écrit |
| Blocs non choisis | jamais écrits |

`b` est l'indice du bloc dans la classe F (pas son rang parmi les 128), si bien que les dithers d'un bloc ne
dépendent que de sa place dans la classe.

## Étiquettes utilisées (sous K_F)

| Étiquette | Usage | Origine |
|---|---|---|
| `MIT-F-v1` | mots de code | dossier §8.2 |
| `MIT-FC-v1` | engagement `fCommit` (128 bits, calculé par crypto) | dossier §8.2 |
| `MIT-POS-v1` | graine des blocs secrets (‖ `maskDigest`) | dossier §8.2 |
| `MIT-FB-v1` | biais p_i | **ajout de l'équipe** |
| `MIT-FD-v1` | dithers QIM (‖ `maskDigest` ‖ indice du bloc) | **ajout de l'équipe** (dossier : « dither dérivé de K_F », sans étiquette) |

## Simulation SEC-D04

Collusions de 2 destinataires parmi 64, seuil inchangé (marge 1,15, ε = 10⁻³), longueur du code inchangée (1 024),
graines fixes. Les symboles sont regroupés par blocs de 8 comme dans F.

| Stratégie | Modèle | Sens en v2.4 |
|---|---|---|
| `DRAW` | chaque symbole pris chez l'un ou l'autre complice | gardée : mélange au niveau des coefficients |
| `MIN_MAX` | minimum ou maximum de chaque symbole | gardée comme **cas pessimiste** : suppose que les complices lisent les bits, impossible sans K_F (dither secret) |
| `BLOCK_DRAW` | chaque bloc de 8 symboles pris en entier chez l'un ou l'autre | **nouvelle** : l'attaque réaliste (les complices voient quels blocs diffèrent entre leurs copies) |
| ~~`INTERLEAVE`~~ | séries de 64 symboles alternées | **supprimée** : l'ordre des symboles est caché derrière des blocs secrets ; le découpage par zones de l'image que peuvent faire les complices revient à `BLOCK_DRAW` |

Résultats (`CollusionSimulationTest`) :

| Condition | Essais / stratégie | Stratégie | Fausses accusations | Détection ≥ 1 complice | Les 2 complices |
|---|---|---|---|---|---|
| sans dégradation | 3 000 | DRAW | 0 | 100,00 % | 100,00 % |
| | 3 000 | MIN_MAX | 0 | 100,00 % | 100,00 % |
| | 3 000 | BLOCK_DRAW | 0 | 100,00 % | 100,00 % |
| 30 % de blocs effacés | 500 | les 3 | 0 | 100,00 % | 100,00 % |
| erreurs QIM 0,6 % (σ = 1) | 500 | les 3 | 0 | 100,00 % | 100,00 % |
| erreurs QIM 5,6 % (σ = 1,5) | 500 | les 3 | 0 | 100,00 % | 100,00 % |
| erreurs QIM 13,6 % (σ = 2) | 500 | DRAW / MIN_MAX / BLOCK_DRAW | 0 | 100,00 % | 99,80 % / 100,00 % / 98,20 % |

Objectif tenu : 0 fausse accusation (borne haute à 95 % : 0,1 % par la règle des trois) et ≥ 99 % de détection. La
perte des 3 répétitions ne coûte rien sans dégradation : elles ne servaient qu'à voter contre les effacements et les
erreurs de pixel, ce que la v2.4 confie à la QIM. Les taux d'erreur QIM viennent des mesures du module embedding
(bruit gaussien sur les pixels) ; la simulation les suppose indépendants d'un symbole à l'autre.

Bout en bout avec la vraie PRF : `EndToEndFingerprintTest` (100 collusions au bloc près sur le canal de test,
16 destinataires) et `EmbeddingIntegrationTest` (vraie session `EmbeddingSession` sur un fantôme 512 × 512 :
`finish()` réussi, bon destinataire accusé seul ; deux vraies copies mélangées bloc par bloc : aucun innocent ;
recadrage de 75 % des lignes : `TOO_MANY_ERASURES`). Sur ce fantôme, perdre 60 % des lignes laisse encore 45 à 50 %
des blocs F lisibles, car le fond est plus présent en haut et en bas de l'image : le cas « 60 % de blocs illisibles »
est donc testé sur le canal de test (`ColluderAnalyzerTest`). L'intégration utilise une KEK de test fixe pour être
reproductible.

## Reste à faire

1. **Module web** : refuser le 65e destinataire d'une image ; enregistrer le `kid` de chaque émission ; analyse
   réservée au responsable sécurité, annotée et journalisée, sans PII dans les logs ; image « non marquable » : mode
   dégradé (F09). Conserver ou recalculer les partitions de l'émission pour l'analyse.
2. **Watermark (W)** : signer `fCommit` (retourné par `FingerprintService.prepare`) dans la preuve W.
3. **Banc d'attaques en CI** : JPEG Q75 et redimensionnement à 75 % sur de vraies copies (SEC-D04 demande ≥ 95 % des
   symboles récupérés) ; la simulation ci-dessus ne couvre que des erreurs indépendantes.
4. Recadrage à gauche ou en haut : la grille de blocs se décale, `FBlockReader` ne le compense pas (voir embedding).

## Décisions de conception à valider avec l'équipe (non fixées par le dossier)
- Étiquettes `MIT-FB-v1` (biais) et `MIT-FD-v1` (dithers) : ajouts de l'équipe.
- Dithers indexés par l'indice du bloc dans la classe F (pas par son rang 0 à 127).
- Plus de vote : 1 répétition, un symbole illisible est un effacement, sans majorité possible.
- `MIN_MAX` gardée comme borne pessimiste bien qu'irréalisable sans K_F ; `INTERLEAVE` supprimée.
- Seuil : écart-type théorique sqrt(n) (variance exacte 1 par symbole), marge 1,15 mesurée sur 3 millions d'innocents.
  La longueur 1 024 est inférieure à la longueur théorique de Tardos (~4 400 pour N = 64, eps = 10^-3) :
  le seuil est donc validé empiriquement, pas par la borne de preuve.
- Moins de 50 % de symboles lisibles : `TOO_MANY_ERASURES`, aucune accusation. Sans répétition, cela correspond à
  plus de 50 % des 128 blocs illisibles.
- Cohérence W/F : si W (valide) désigne R et que R n'est pas parmi les accusés : `W_F_INCONSISTENT`, aucune accusation.
- Une erreur du canal (`EmbeddingException`, par exemple `F_DEVIATION_EXCEEDED`) n'est pas convertie : elle remonte
  telle quelle avec son motif fermé, et l'émission est annulée.

## Commandes
```
mvn -B -pl medimage-fingerprint -am test
mvn -B -pl medimage-fingerprint -am install -DskipTests
mvn -B -pl medimage-fingerprint exec:java -Dexec.args="3000 target/reports"
```
