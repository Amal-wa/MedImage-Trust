# MedImage-Trust

Plateforme de protection et de vérification d'images médicales DICOM.
Chaque copie émise porte trois marques, écrites seulement dans la zone sans intérêt diagnostique (RONI) :

- **W, watermarking** : preuve d'origine signée Ed25519 par l'établissement émetteur ;
- **S, stéganographie** : jeton chiffré qui relie la copie au dossier du patient ;
- **F, fingerprinting** : code de Tardos propre à chaque destinataire, pour retrouver l'origine d'une fuite.

Pile : Jakarta EE 11 sur WildFly, Java 25.

Référence fonctionnelle : dossier v2.4, [`docs/MedImage-Trust_v2.4_complet.pdf`](docs/MedImage-Trust_v2.4_complet.pdf).
Le PDF v2.3 est obsolète.

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `middleware/` | serveur Java, trois modules Maven : `core` (commun), `api` (tout le code développé, `api-1.0.war`), `iam` (identités et accès, `iam-1.0.war`) ; voir [`middleware/README.md`](middleware/README.md) |
| `frontend/` | application PWA (à venir) |
| `docs/` | dossier de conception (v2.4) et diagrammes UML |
| `scripts/` | scripts de déploiement WildFly (à venir) |
| `.github/` | pipelines CI/CD et `CODEOWNERS` |

## Prérequis

- JDK 25 (`JAVA_HOME` doit pointer vers lui) ;
- Maven 3.9.

## Construire et tester

```
cd middleware
mvn -B clean verify          # compile, teste (tests ArchUnit compris) et produit les deux WAR
mvn -B -DskipTests package   # produit api/target/api-1.0.war et iam/target/iam-1.0.war
```

## Branches

`main` est la branche de référence, protégée. Toute modification passe par une branche, puis par une pull
request vers `main` avec la CI au vert.

| Branche | Usage |
|---|---|
| `main` | branche de référence, protégée |
| `feat/<sujet>` | nouvelle fonctionnalité (ex. `feat/embedding`) |
| `fix/<sujet>` | correction |
| `chore/<sujet>` | organisation, outillage, documentation (ex. `chore/middleware-structure`) |

## CI/CD

Chaîne SecDevOps complète, détaillée dans [`docs/ci-cd.md`](docs/ci-cd.md) (schéma, correspondance exigence → job,
protection de `main`, secrets de prod) :

- CI (`.github/workflows/ci.yml`, sur toute branche et toute PR vers `main`) : compilation, tests (ArchUnit, vecteurs
  NIST AES-GCM, budget mémoire), Gitleaks, CodeQL, revue des dépendances, SBOM CycloneDX, ShellCheck, intégrité des
  scripts de base fournis, banc d'attaques non bloquant ;
- CD (`.github/workflows/cd.yml`) : après une CI verte sur `main`, WildFly 42 est installé dans le runner par
  [`scripts/`](scripts/README.md), puis déploiement, test de fumée, banc de charge et ZAP baseline ; la prod passe par
  SSH après approbation manuelle ;
- Dependabot (`.github/dependabot.yml`) : dépendances Maven et actions GitHub, chaque semaine.
