# Corpus d'images réelles

Fantômes et patientes virtuelles, aucune donnée de patient.

Les fichiers sont stockés **hors du dépôt** (dépôt public) : le dépôt ne contient que ce manifeste et le script
[`scripts/corpus/fetch-corpus.sh`](../scripts/corpus/fetch-corpus.sh) (et son équivalent Windows
[`fetch-corpus.ps1`](../scripts/corpus/fetch-corpus.ps1)). Le script lit le tableau ci-dessous, télécharge chaque
fichier par l'API publique NBIA v1 de TCIA (`getSingleImage`) dans `$MEDIMAGE_CORPUS_DIR` et vérifie sa SHA-256 ; une
somme différente supprime le fichier et fait échouer le script. Le tableau est le manifeste : son format (colonnes, UID
numériques, noms `[a-z0-9-]+.dcm`, sommes de 64 hexadécimaux) est validé ligne par ligne, et sa SHA-256 est la clé du
cache du job CI `corpus`.

```bash
MEDIMAGE_CORPUS_DIR=~/medimage-corpus scripts/corpus/fetch-corpus.sh
MEDIMAGE_CORPUS_DIR=~/medimage-corpus mvn -B -f middleware/pom.xml -pl api -am verify -Dgroups=corpus
```

## Fichiers

La colonne « Rang » donne la place de l'instance dans la série, après tri des SOPInstanceUID sur leur dernier
composant (`fetch-corpus.sh --select`) : c'est le numéro d'instance chez ces fabricants, mais la position réelle dans
la pile est celle que les tests de corpus impriment (InstanceNumber). Les six fichiers d'origine (rang « origine »)
ont été choisis au milieu de la liste renvoyée par l'API, non triée.

| Fichier | Collection | Fabricant | SeriesInstanceUID | SOPInstanceUID | Licence | SHA-256 | Rang |
|---|---|---|---|---|---|---|---|
| ct-01-ge.dcm | CT4Harmonization-Multicentric | GE MEDICAL SYSTEMS | 1.2.840.113619.2.55.3.313318667.350.1615792090.123 | 1.2.840.113619.2.55.3.313318667.350.1615792090.124.113 | CC BY 4.0 | 38347094b1996b89a80c0e718f1ac43c93906b2da0d07ddc593500cca9d56b6b | origine |
| ct-01-ge-p20.dcm | CT4Harmonization-Multicentric | GE MEDICAL SYSTEMS | 1.2.840.113619.2.55.3.313318667.350.1615792090.123 | 1.2.840.113619.2.55.3.313318667.350.1615792090.124.55 | CC BY 4.0 | e2606b371cc45ac2ac679356c79ae72c67e469912caf6415a3c5955c1baceb43 | 55/275 (20 %) |
| ct-01-ge-p35.dcm | CT4Harmonization-Multicentric | GE MEDICAL SYSTEMS | 1.2.840.113619.2.55.3.313318667.350.1615792090.123 | 1.2.840.113619.2.55.3.313318667.350.1615792090.124.96 | CC BY 4.0 | 84dfaca14ef55b7ed5205607eab26310693c42fe637918730eacdaeef7334ee2 | 96/275 (35 %) |
| ct-01-ge-p65.dcm | CT4Harmonization-Multicentric | GE MEDICAL SYSTEMS | 1.2.840.113619.2.55.3.313318667.350.1615792090.123 | 1.2.840.113619.2.55.3.313318667.350.1615792090.124.179 | CC BY 4.0 | 53f3e13fce6403af5faeab60c858ebb40f612178bd35a714450ae5c6f131dd12 | 179/275 (65 %) |
| ct-01-ge-p80.dcm | CT4Harmonization-Multicentric | GE MEDICAL SYSTEMS | 1.2.840.113619.2.55.3.313318667.350.1615792090.123 | 1.2.840.113619.2.55.3.313318667.350.1615792090.124.220 | CC BY 4.0 | 45cc520470dc646aff2d5f6ad0833e885bfab042d5d129f450f9b9f5cc509b79 | 220/275 (80 %) |
| ct-02-philips.dcm | CT4Harmonization-Multicentric | Philips | 1.2.840.113704.1.111.10184.1615482333.109 | 1.2.840.113704.1.111.4216.1615482685.46487 | CC BY 4.0 | b7a27a876e5a162125ceeb658f73a09bbc1b72491f1c18112eb68bbe78ab9372 | origine |
| ct-02-philips-p20.dcm | CT4Harmonization-Multicentric | Philips | 1.2.840.113704.1.111.10184.1615482333.109 | 1.2.840.113704.1.111.4216.1615482672.46346 | CC BY 4.0 | 065f4db99a7d777202e981c1cb3d95227ddc8dc6a5fc806b90f71779cdf4f6ae | 69/343 (20 %) |
| ct-02-philips-p35.dcm | CT4Harmonization-Multicentric | Philips | 1.2.840.113704.1.111.10184.1615482333.109 | 1.2.840.113704.1.111.4216.1615482676.46397 | CC BY 4.0 | 9c93689f76cd06c2f7c22365461734de2689cdb5105127cd6c3db3daf5f61936 | 120/343 (35 %) |
| ct-02-philips-p65.dcm | CT4Harmonization-Multicentric | Philips | 1.2.840.113704.1.111.10184.1615482333.109 | 1.2.840.113704.1.111.4216.1615482685.46500 | CC BY 4.0 | 20a1a9c3b5e37a6750ac958143cfcfb708fcd84505cbfabcc8f65404bc388cf6 | 223/343 (65 %) |
| ct-02-philips-p80.dcm | CT4Harmonization-Multicentric | Philips | 1.2.840.113704.1.111.10184.1615482333.109 | 1.2.840.113704.1.111.4216.1615482690.46551 | CC BY 4.0 | fea51f285d4dc35bbb840e14e1b61bd370e747585df914a927335ff9966ac1b8 | 274/343 (80 %) |
| ct-03-siemens.dcm | CT4Harmonization-Multicentric | SIEMENS | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043502 | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043555 | CC BY 4.0 | ab914ebad0e786e9c43c346cdd06eb598a248abc4098b00bc1debb3c6c49fe85 | origine |
| ct-03-siemens-p20.dcm | CT4Harmonization-Multicentric | SIEMENS | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043502 | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043571 | CC BY 4.0 | 59f1eddd5a692e8eb7a8521925e5e0aab0f78f8c6ddd40181addffde2593f8ff | 69/343 (20 %) |
| ct-03-siemens-p35.dcm | CT4Harmonization-Multicentric | SIEMENS | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043502 | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043622 | CC BY 4.0 | cbe40bbb9287eeb077258251b555338733d246694225bb3e4cee9eab1bc77988 | 120/343 (35 %) |
| ct-03-siemens-p65.dcm | CT4Harmonization-Multicentric | SIEMENS | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043502 | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043725 | CC BY 4.0 | a56086410fe4cb7ce24fd9d73c578ffdee5fc6d0786864bb29dcd5545cb559f2 | 223/343 (65 %) |
| ct-03-siemens-p80.dcm | CT4Harmonization-Multicentric | SIEMENS | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043502 | 1.3.12.2.1107.5.1.4.83100.30000021022306474301500043776 | CC BY 4.0 | 21c6f4f23b5a46e22aefe7cdc45f21d338e3d47a1208675c81dd714ac1d89e25 | 274/343 (80 %) |
| mr-01-ge.dcm | RIDER PHANTOM MRI | GE MEDICAL SYSTEMS | 1.2.840.113619.2.176.3596.3311940.6866.1220205031.849 | 1.2.840.113619.2.176.3596.3311940.6797.1220205362.777 | CC BY 3.0 | cd227adce77aeeadbfc5da097c849c0db0e4e0334321ad78a8d16dc8ee3d8185 | origine |
| mr-01-ge-p20.dcm | RIDER PHANTOM MRI | GE MEDICAL SYSTEMS | 1.2.840.113619.2.176.3596.3311940.6866.1220205031.849 | 1.2.840.113619.2.176.3596.3311940.6797.1220205362.729 | CC BY 3.0 | 704bf9684d95d5674aaa931bcd748726abd31b37c908beb586ab712a770db204 | 14/70 (20 %) |
| mr-01-ge-p35.dcm | RIDER PHANTOM MRI | GE MEDICAL SYSTEMS | 1.2.840.113619.2.176.3596.3311940.6866.1220205031.849 | 1.2.840.113619.2.176.3596.3311940.6797.1220205362.740 | CC BY 3.0 | 2d7585fe9ab0eeb138e1c5c7ef28d2f49089089707f8b3521f8789e29c7fa865 | 25/70 (35 %) |
| mr-01-ge-p65.dcm | RIDER PHANTOM MRI | GE MEDICAL SYSTEMS | 1.2.840.113619.2.176.3596.3311940.6866.1220205031.849 | 1.2.840.113619.2.176.3596.3311940.6797.1220205362.760 | CC BY 3.0 | 99233638ff0c9dd92fc38958935413f9e269717886b82d083919fc04a8d25bcc | 45/70 (65 %) |
| mr-01-ge-p80.dcm | RIDER PHANTOM MRI | GE MEDICAL SYSTEMS | 1.2.840.113619.2.176.3596.3311940.6866.1220205031.849 | 1.2.840.113619.2.176.3596.3311940.6797.1220205362.771 | CC BY 3.0 | cdd5a6b538078e8809ae6a2386fc579d8c73d2544f6d9be660f142d6b7f5d944 | 56/70 (80 %) |
| mr-02-siemens.dcm | RIDER PHANTOM MRI | SIEMENS | 1.3.12.2.1107.5.2.31.30165.2008083111550627984708535.0.0.0 | 1.3.12.2.1107.5.2.31.30165.2008083111550729543708547 | CC BY 3.0 | 5fba6a81eeabe16466d6c34848c6bf536923d5b7ff2058c5e85af6a7bf402001 | origine |
| mr-02-siemens-p20.dcm | RIDER PHANTOM MRI | SIEMENS | 1.3.12.2.1107.5.2.31.30165.2008083111550627984708535.0.0.0 | 1.3.12.2.1107.5.2.31.30165.2008083111550635376608536 | CC BY 3.0 | 48f898d60f4f1e9faabd102631dffeec826ca0e2330ab53c6061e2ac3ca193a3 | 3/12 (20 %) |
| mr-02-siemens-p35.dcm | RIDER PHANTOM MRI | SIEMENS | 1.3.12.2.1107.5.2.31.30165.2008083111550627984708535.0.0.0 | 1.3.12.2.1107.5.2.31.30165.2008083111550666683408540 | CC BY 3.0 | bffa3e30be599e468f8757fcc19ce3e5661a0ea7aa645aa1ca0e3cdaa358cd2c | 4/12 (35 %) |
| mr-02-siemens-p65.dcm | RIDER PHANTOM MRI | SIEMENS | 1.3.12.2.1107.5.2.31.30165.2008083111550627984708535.0.0.0 | 1.3.12.2.1107.5.2.31.30165.2008083111550777549808553 | CC BY 3.0 | 429c56b295824dbd3c7814070f879c824fea9511c16cfcd52649000aaf4b484d | 8/12 (65 %) |
| mr-02-siemens-p80.dcm | RIDER PHANTOM MRI | SIEMENS | 1.3.12.2.1107.5.2.31.30165.2008083111550627984708535.0.0.0 | 1.3.12.2.1107.5.2.31.30165.2008083111550810071508556 | CC BY 3.0 | 74596ba7e093857701b7c24ba6bd01dc6b5a5a97a558c8e57d50f100747c4f94 | 9/12 (80 %) |
| mg-01-victre.dcm | VICTRE | VICTRE | 1.3.6.1.4.1.14519.5.2.1.2135.6389.493833262754258181014605596053 | 1.3.6.1.4.1.14519.5.2.1.2135.6389.182727984284711478592722556001 | CC BY 3.0 | 1f654139c66453f85629f7fff0161ff50355ab2c9699edfcf7f5daf244c069a1 | série à 1 image |

## Sources

Toutes les collections viennent de The Cancer Imaging Archive (TCIA), API publique NBIA v1
(`https://services.cancerimagingarchive.net/nbia-api/services/v1`) : `getSeries` (appartenance de chaque série à sa
collection, vérifiée par le script), `getSOPInstanceUIDs` (choix des coupes), `getSeriesMetaData` (DOI),
`getSingleImage` (téléchargement).

| Collection | Contenu | Licence | DOI |
|---|---|---|---|
| CT4Harmonization-Multicentric | fantôme physique anthropomorphe, 13 scanners, 4 fabricants | CC BY 4.0 | [10.7937/M0PB-BH69](https://doi.org/10.7937/M0PB-BH69) |
| RIDER PHANTOM MRI | fantôme EuroSpin II Test Object 5, IRM GE et Siemens | CC BY 3.0 | [10.7937/K9/TCIA.2015.MI4QDDHU](https://doi.org/10.7937/K9/TCIA.2015.MI4QDDHU) |
| VICTRE | mammographies simulées (patientes virtuelles) ; série à une image (mammographie 2D, 8,6 Mio) | CC BY 3.0 | [10.7937/TCIA.2019.ho23nxaw](https://doi.org/10.7937/TCIA.2019.ho23nxaw) |

Les DOI sont ceux que renvoie `getSeriesMetaData` (« Data Description URI ») pour les séries du tableau
(`fetch-corpus.sh --doi <SeriesInstanceUID>`). Citation demandée par TCIA : citer la collection (DOI ci-dessus) et
TCIA (Clark K. et al., *J Digit Imaging* 26(6), 2013, doi:10.1007/s10278-013-9622-7).

Écartée : CPTAC-GBM (licence NIH Controlled Data Access Policy, accès contrôlé).

## Limites

- Pas de radiographie (CR, DX) dans le corpus.
- Une seule mammographie, et la seule image en Implicit VR Little Endian.
- Les coupes d'une même série partagent fabricant et protocole : 26 fichiers, mais 6 séries indépendantes.
