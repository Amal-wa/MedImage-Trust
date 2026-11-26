# Prompt Claude Code 03 — Harmonisation de la structure (tous les modules)

> Ordre des prompts : **02 mémoire embedding** (sur `feat/embedding`), puis **03 structure**, **04 crypto v2.4**, **05 fingerprint v2.4**, puis **06 CI**. Chaque prompt part du résultat **poussé** du précédent : ne lance pas le suivant avant d'avoir poussé le précédent.

## Avant (PowerShell, dans `C:\Users\amelb\Documents\MedImage-Trust`)

```powershell
git fetch --all --prune
git status                                            # arbre propre ; feat/embedding poussée, prompt 02 inclus
git switch -c chore/harmonisation origin/feat/embedding
git merge --no-ff --no-commit origin/feat/web-health   # ajoute le module web ; des conflits sont possibles (.gitignore)
git status
Copy-Item "C:\Users\amelb\Documents\CySeD\MedImage-Trust\docs\MedImage-Trust_v2.4_complet.pdf" .\docs\ -Force   # version corrigée du §8.4
```

Lance `claude` dans ce dossier et colle le bloc ci-dessous.

---

## PROMPT À COLLER

Tu travailles sur le dépôt **MedImage-Trust** (Sup'COM, Sécurité Applicative) : une plateforme Jakarta EE 11 / WildFly / Java 25 qui protège des images DICOM par trois marques (W watermarking, S stéganographie, F fingerprinting). L'équipe m'a autorisée à corriger les incohérences de **structure** entre les modules. Cette tâche-ci ne touche **aucun** algorithme : c'est de l'organisation Maven, de la documentation et deux petites corrections de sécurité. Les changements d'algorithme viendront dans des tâches séparées, après celle-ci : crypto (sous-clés, jeton S) puis fingerprint (passage du LSB v2.3 à la DCT/QIM v2.4). Ici, tu ne fais que **les lister** dans le README du backend.

**État de départ** (branche `chore/harmonisation`, créée depuis `origin/feat/embedding`) :
- `backend/pom.xml` : parent `tn.medimage:medimage-backend:0.1.0-SNAPSHOT` ; modules déclarés : crypto, fingerprint, embedding.
- `backend/medimage-audit` : pom **autonome** (pas de parent, versions écrites en dur), non déclaré dans le parent.
- `backend/medimage-web` : arrive par une fusion **en cours** (`git merge --no-commit origin/feat/web-health`) ; `groupId tn.medimagetrust`, pom autonome.
- **Référence fonctionnelle** : `docs/MedImage-Trust_v2.4_complet.pdf`. Le PDF v2.3 de `docs/` est obsolète.

### Règles impératives
1. **Ni `git commit` ni `git push`.** La fusion reste « en cours » : je la conclurai moi-même après relecture. Tu peux résoudre les conflits et faire `git add` des fichiers résolus, rien de plus.
2. **Ne lis aucun fichier hors du dossier du dépôt courant.** Si `docs/MedImage-Trust_v2.4_complet.pdf` est absent, **arrête-toi et signale-le** ; ne cherche pas ailleurs sur le disque.
3. **Aucun changement de comportement** dans le code Java, sauf les deux corrections de sécurité de `medimage-web` décrites ci-dessous. Aucun test existant ne doit être supprimé ni affaibli.
4. Ne touche pas à `.github/` (prompt 06) ni au contenu des classes de crypto, fingerprint, embedding et audit (prompts 04 et 05).
5. Messages, Javadoc et README **en français**.

### Tâches

**1. Résoudre la fusion `feat/web-health`.** Garde **l'union** des deux `.gitignore` (ou de tout autre fichier en conflit), sans doublon. Ne supprime aucun fichier venu de l'une ou l'autre branche.

**2. Pom parent (`backend/pom.xml`)** :
- modules, dans l'ordre de dépendance : `medimage-crypto`, `medimage-embedding`, `medimage-fingerprint`, `medimage-audit`, `medimage-web` ; mets à jour le commentaire « à venir » (il ne reste que `medimage-dicom`, `medimage-stego` et `medimage-watermark`) ;
- `dependencyManagement` : les cinq modules internes (`${project.version}`), `junit-jupiter` (existant) et `jakarta.platform:jakarta.jakartaee-api:11.0.0` en `provided` ;
- `pluginManagement` : `maven-surefire-plugin` 3.5.2 (existant), `maven-war-plugin` 3.4.0, `exec-maven-plugin` 3.5.0 ;
- propriétés inchangées (Java 25, UTF-8).

**3. `medimage-audit/pom.xml`** : rattache-le au parent, supprime `groupId`/`version`/`properties` redondants et les versions écrites en dur (héritées du parent). Dépendances inchangées (crypto + JUnit). Ses tests doivent passer à l'identique.

**4. `medimage-web/pom.xml`** :
- `groupId tn.medimage`, rattaché au parent, versions héritées ;
- WAR compatible avec le script du professeur `add-api.cli`, qui déploie `${api.name}-${api.version}.war` : `finalName` = `medimage-api-1.0` (donc `api.name=medimage-api`, `api.version=1.0`). Note ces deux valeurs dans le README du backend, pour les futurs fichiers `.properties` dev/prod.

**5. Corrections de sécurité dans `medimage-web`** :
- `HealthResource` : **ne plus exposer la version de Java ni du serveur** (fuite d'information utile à un attaquant, OWASP A05 ; un endpoint public ne doit rien révéler). Réponse : `{"status":"UP"}` uniquement, avec l'en-tête `Cache-Control: no-store`. Garde `@PermitAll` explicite sur la classe et la méthode.
- Ajoute un test JUnit simple (sans serveur) qui appelle `health()` et vérifie que le corps ne contient ni « java », ni « wildfly », ni chiffre de version.

**6. `ATTRIBUTION.md`, section 1 seulement** : les rôles sont inversés par rapport au dossier v2.4 (§10.1). Corrige les trois lignes « Rôle principal » :
- BEN ABDELJELIL Nour : axe watermarking (et module crypto, registre de clés, politique d'autorisation) ;
- TOUMI Nourhen : axe fingerprinting (et PWA, audit, CI/CD) ;
- BEN KHALIFA Amel : axe stéganographie (et brique RONI / embedding, contrôle DICOM).

Ne touche à rien d'autre dans ce fichier (déclarations, signatures, tableau IA).

**7. `medimage-audit/README.md`, deux corrections pour suivre la v2.4** (texte seulement, pas de code) :
- remplace « `LogKeyProvider` adossé au keystore PKCS#12 du service d'audit (passphrase en Docker secret) » par : « `LogKeyProvider` en production : graine K_log = HKDF(KEK, « MIT-LOG-v1 », kid), puis clé Ed25519 ; la KEK est la seule entrée du **credential store WildFly Elytron**, ouvert par un mot de passe masqué (credential-reference). Pas de keystore PKCS#12, pas de Docker (dossier v2.4, §8.2) » ;
- remplace « le volume `medimage-anchors` » par « un dossier dédié du système de fichiers du serveur (écriture réservée au service d'audit) ».

**8. `backend/README.md`** : réécris-le pour l'état réel :
- arbre des modules avec leur responsable et leur rôle en une ligne ;
- graphe de dépendances (crypto ← embedding ← fingerprint ; crypto ← audit ; web dépendra de tous) ;
- commandes (`mvn -B test`, test d'un module avec `-pl`) ;
- « Référence : dossier v2.4 » ;
- **liste des écarts connus avec la v2.4, à traiter par les prompts suivants** : jeton S (AAD et sous-clé par émission), dérivation des clés depuis la seule KEK, format du kid sur 2 octets, fingerprint encore en LSB v2.3 (`FPartition`, 3 072 bits) au lieu de `FBlockChannel`, CI vide.

Garde la section « Points à trancher en équipe » existante en la mettant à jour : marque comme **tranchés** l'insertion (DCT/QIM puis LSB, consigne du professeur), l'AAD du jeton S (v2.4 §8.2), le format de W (charge binaire fixe `MIT-W-v1`, v2.4 §8.3) et les rôles d'`ATTRIBUTION.md`.

**9. `README.md` à la racine** (vide aujourd'hui) : présentation courte du projet, structure du dépôt (`backend/`, `frontend/`, `docs/`, `scripts/`), prérequis (JDK 25, Maven 3.9), commande de build, convention de branches (`feat/<module>`, `fix/<sujet>`, `chore/<sujet>` ; intégration sur `develop`, `main` = versions livrées), et un lien vers le dossier v2.4 dans `docs/`.

### Pour finir
- `mvn -B clean test` à la racine de `backend/` : les **cinq** modules compilent et tous les tests passent.
- `git status` : montre l'état de la fusion.
- Donne-moi un résumé : conflits résolus et comment, fichiers modifiés (un par ligne, avec le pourquoi), nombre de tests par module, et tout ce que tu n'as pas pu faire. **Aucun commit, aucun push.**

---

## Après (PowerShell)

```powershell
git status
git diff --staged --stat
cd backend; mvn -B clean test; cd ..
gitleaks detect --source . --no-banner -v
git add -A backend ATTRIBUTION.md README.md .gitignore
git status                                         # vérifier : rien dans docs/ ni ailleurs d'inattendu
git commit -m "chore: harmonisation des modules (pom parent, audit et web rattaches, roles, README, health sans version)"
git push -u origin chore/harmonisation
```
