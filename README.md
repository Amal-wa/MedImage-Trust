# MedImage-Trust

Plateforme de protection et de vérification d'images médicales DICOM (projet de Sécurité Applicative, Sup'COM).
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

`main` est la branche de travail suivie par l'enseignant. Toute modification passe par une branche, puis par une pull
request vers `main` avec la CI au vert.

| Branche | Usage |
|---|---|
| `main` | branche de travail suivie par l'enseignant, protégée |
| `feat/<sujet>` | nouvelle fonctionnalité (ex. `feat/embedding`) |
| `fix/<sujet>` | correction |
| `chore/<sujet>` | organisation, outillage, documentation (ex. `chore/middleware-structure`) |

## CI/CD

- CI : compilation Maven, tests unitaires et scan Gitleaks (`.github/workflows/ci.yml`).
- CD : déploiement manuel (dev ou prod) via GitHub Actions (`.github/workflows/cd.yml`).
- Les déclencheurs et le script de déploiement sont encore à aligner sur la convention de branches ci-dessus : voir
  les écarts connus dans [`middleware/README.md`](middleware/README.md).
