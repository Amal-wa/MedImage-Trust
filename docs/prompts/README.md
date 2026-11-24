# Prompts utilisés pour générer le code (Claude Code)

Ce dossier rassemble, dans l'ordre, les prompts donnés à **Claude Code** pour produire ou corriger le code du dépôt. Comme le demande l'encadrant, ils sont versionnés avec le code qu'ils ont produit.

## Méthode
1. Le prompt est rédigé et vérifié par rapport au **dossier v2.4** et aux **consignes du professeur** (email de la semaine 4, triptyques C-I-D et Authentification / Autorisation / Données privées).
2. Claude Code travaille sur **une branche dédiée**, sans commit ni push.
3. Un membre de l'équipe relit le diff, lance `mvn -B clean test` et `gitleaks`, puis **commite et pousse lui-même**.
4. Les écarts et les décisions à valider signalés par Claude Code sont notés dans le README du module concerné.

## Historique

| N° | Fichier | Branche produite | Objet | Statut |
|---|---|---|---|---|
| 01 | `01_medimage-embedding.md` | `feat/embedding` | Brique RONI : profil v2, partitions W/S/F, DCT 8×8/QIM, LSB, contrôle final, échec fermé | Fait (83 tests) |
| 02 | `02_embedding-memoire.md` | `perf/embedding-memoire` (lancé après le 05) | Budget mémoire : 36 Mpx ≈ 216 Mo (`char[]`, masques compactés) | À faire |
| 03 | `03_harmonisation-structure.md` | `chore/harmonisation` | Pom parent, audit et web rattachés, health sans version, rôles, README | Fait (252 tests, 5 modules) |
| 04 | `04_crypto-v2.4.md` | `fix/crypto-v2.4` | KEK seule + dérivation HKDF, kid sur 16 bits, jeton S et fichiers au repos conformes v2.4, RFC 8032 | Fait (276 tests ; RFC 8032 OK ; 10^5 nonces sans doublon) |
| 05 | `05_fingerprint-v2.4.md` | `fix/fingerprint-v2.4` | Fingerprint sur DCT/QIM via `FBlockChannel`, simulation SEC-D04 | Fait (284 tests ; 0 fausse accusation) |
| 06 | — | — | CI/CD : build, tests, gitleaks, test d'architecture, banc d'attaques | À rédiger |

**Règle :** chaque fichier contient **exactement** le texte donné à Claude Code. Si un prompt est corrigé après usage, la version utilisée est conservée et la correction est ajoutée en fin de fichier, dans une section « Révision ».
