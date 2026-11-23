# MedImage-Trust

Plateforme de protection et de vérification d'images médicales.

## Structure
- `backend/` : API Java (Maven multi-modules)
- `frontend/` : application PWA
- `docs/` : cahier conceptuel
- `scripts/` : scripts de déploiement WildFly
- `.github/workflows/` : pipelines CI/CD

## Branches
- `main` : code stable, protégé
- `develop` : intégration
- `feature/*` : une branche par fonctionnalité

## Pipeline CI
À chaque push et Pull Request : compilation Maven, tests unitaires, scan Gitleaks.

## Pipeline CD
Déploiement manuel (dev / prod) via GitHub Actions et `scripts/deploy.sh`.