# 📑 Fiche d'Attribution, d'Intégrité Académique et d'Usage de l'IA

## 👥 1. Identification de l'Équipe & Rôles

* **Établissement : École Supérieure des Communications de Tunis (SUP'COM)
* **Année universitaire : 2026–2027
* **Groupe / Trinôme N° : Trinôme 1
* **Nom de l'Application Métier : MedImage-Trust

### Membres de l'équipe :
1. BEN ABDELJELIL Nour - Email : nour.benabdeljelil@supcom.tn - Rôle principal : Axe watermarking (et module crypto, registre de clés, politique d'autorisation)
2. TOUMI Nourhen - Email : nourhen.toumi@supcom.tn - Rôle principal : Axe fingerprinting (et PWA, audit, CI/CD)
3. BEN KHALIFA Amel - Email : amel.benkhalifa@supcom.tn - Rôle principal : Axe stéganographie (et brique RONI / embedding, contrôle DICOM)

## 📜 2. Déclaration d'Intégrité et d'Anti-Plagiat

Par la présente, nous certifions sur l'honneur que :
* L'architecture, la conception logicielle et l'implémentation métier de cette application sont le fruit de notre propre travail d'équipe.
* Aucun copier-coller de code, de diagrammes ou de documentations provenant d'un autre groupe de l'atelier n'a été effectué.
* Toute source externe a été correctement citée dans la documentation technique.

## 🤖 3. Déclaration de l'Usage des Intelligences Artificielles (IA)

| Composant / Phase | IA utilisée (Nom/Version) | Pourcentage estimé de contenu généré | Rôle précis de l'IA & Prompts clés utilisés |
| :--- | :--- | :--- | :--- |
| **Phase 1 : Exigences (SRE)** | Claude Sonnet 5.5 | 80% | Brainstorming sur les cas d’abus et les exigences de sécurité d’une plateforme d’imagerie DICOM (fichiers DICOM piégés, collusion, recadrage, fausse marque, échange de jetons), puis relecture de la cohérence et de l’exhaustivité du document. Les exigences retenues ont été choisies, reformulées et validées par l’équipe. |
| **Phase 2 : Threat Modelling** | Claude (assistant claude.ai) | 80 % | Aide à la modélisation des menaces (STRIDE par élément du DFD, LINDDUN, OWASP Risk Rating, liens CAPEC / ATT&CK) et à la mise en cohérence avec le cahier des charges (dossier v2.4). Menaces, scores et contre-mesures relus, choisis et validés par l'équipe. |
| **Développement Backend** | Claude Code (rédaction des prompts assistée par Claude) | 85 % | Génération du code des modules Maven à partir de prompts rédigés et vérifiés par l'équipe contre le dossier v2.4 et les consignes de l'enseignant. Prompts versionnés dans `docs/prompts/` (01 à 06). Claude Code travaille sur une branche dédiée, sans commit ni push ; relecture du diff, tests (`mvn -B clean test`) et `gitleaks` par l'équipe, qui commite et pousse. |
| **Moteur de Dissimulation** | Claude Code (rédaction des prompts assistée par Claude) | 85 % | Module `medimage-embedding` (RONI, partitions W/S/F, QIM sur DCT 8×8, LSB, contrôle final) et adaptation de fingerprint et watermark : prompts 01, 02, 05 et 06 de `docs/prompts/`. Spécification (profil d'incorporation v2) rédigée par l'équipe dans le dossier v2.4 ; mesures et décisions incertaines consignées dans les README (« Décisions à valider »). |

### 🛠️ Exemple de Prompt Clé :
> Prompts complets utilisés pour le code : voir [`docs/prompts/`](docs/prompts/README.md).
> “Quelles sont les menaces principales pour une application qui stocke des images médicales ?”
> “Comment structurer un cahier des charges pour un projet de sécurité ?”
> “Comment protéger des données de santé (sensibles) dans une application web ?”
> “Comment est structuré un fichier DICOM (préambule de 128 octets, en-tête, pixels) ?”
> “Explique-moi la méthode STRIDE avec un exemple appliqué à une application d’imagerie médicale.”

## ✍️ 4. Signatures des membres
* BEN ABDELJELIL Nour : Engagée / 11/11/2026
* TOUMI Nourhen : Engagée / 11/11/2026
* BEN KHALIFA Amel : Engagée / 11/11/2026
