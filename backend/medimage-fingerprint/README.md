# Module `medimage-fingerprint` (F08 / BF07 / BF08 / SEC-D04)

Code de Tardos symétrique, c = 2, 1 024 symboles x 3 répétitions, <= 64 destinataires par image.
Dépend de `medimage-crypto` (clé K_F et engagement fCommit) ; la brique RONI d'Amal reste derrière le port `FPartition`.

| Fichier | Rôle |
|---|---|
| `FingerprintParams` | constantes de la spec (1 024, 3, 64, t = 1/600, 6 144 pixels, étiquettes MIT-*) |
| `Prf`, `CryptoPrf`, `HmacSha256Prf` | port HMAC sous K_F ; adaptateur vers `CryptoService` (production) ; version JCA pour tests unitaires |
| `Messages`, `HmacStream` | messages HMAC non ambigus ; flux HMAC en mode compteur |
| `PositionPermutation` | positions secrètes de F (Fisher-Yates piloté par HMAC, graine = masque) |
| `TardosMath` | loi des biais à coupure, terme de score symétrique |
| `TardosCodeGenerator` | biais, mots de code, expansion x3, engagement `fCommit` |
| `FPartition`, `InMemoryFPartition` | port vers la brique RONI d'Amal + version de test |
| `FingerprintEmbedder` | insertion (3 072 bits) et extraction par vote (effacements) |
| `TardosScorer`, `ThresholdCalibrator` | score ; seuil = z(eps/N) x sqrt(n lisibles) x 1,15 |
| `ColluderAnalyzer`, `AttributionReport` | analyse CU08, verdicts à liste fermée, contrôle W/F |
| `FingerprintService` | façade pour le module `web` (émission F09, analyse CU08) |
| `CollusionSimulator`, `SimulationReport`, `SimulationMain` | Monte-Carlo SEC-D04 et rapport CI |

## Reste à brancher
1. **Amal (embedding)** : adaptateur `FPartition` sur la classe F (rangs modulo 10 dans [4, 9], bit 0 du pixel) et
   `maskDigest` = SHA-256(MIT-MASK-v1 ‖ rows ‖ cols ‖ masque RONI) ; ajouter la dépendance dans le `pom.xml`.
2. **Module web** : refuser le 65e destinataire d'une image ; enregistrer le `kid` de chaque émission ; analyse réservée
   au responsable sécurité, annotée et journalisée, sans PII dans les logs ; image « non marquable » : mode dégradé (F09).
3. **Watermark (W)** : signer `fCommit` (retourné par `FingerprintService.prepare`) dans la preuve W.

## Décisions de conception à valider avec l'équipe (non fixées par le dossier)
- Étiquette `MIT-FB-v1` pour les biais p_i (ajout : la spec ne décrit que MIT-F-v1 pour les mots de code).
- Vote par symbole : majorité ; effacement seulement si aucune majorité (pixels illisibles). Le mode `UNANIMOUS`
  efface les votes 2-1, ce qui efface justement les symboles informatifs en cas de collusion au pixel : déconseillé.
- Seuil : écart-type théorique sqrt(n) (variance exacte 1 par symbole), marge 1,15 mesurée sur 3 millions d'innocents.
  La longueur 1 024 est inférieure à la longueur théorique de Tardos (~4 400 pour N = 64, eps = 10^-3) :
  le seuil est donc validé empiriquement, pas par la borne de preuve.
- Moins de 50 % de symboles lisibles : `TOO_MANY_ERASURES`, aucune accusation.
- Cohérence W/F : si W (valide) désigne R et que R n'est pas parmi les accusés : `W_F_INCONSISTENT`, aucune accusation.

## Commandes
```
mvn -B test
mvn -B -pl medimage-fingerprint exec:java -Dexec.args="3000 target/reports"   # après mvn -DskipTests install
```
