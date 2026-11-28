# Package `stego` : jeton S

Package `tn.medimagetrust.stego` (module api). Dépend de `crypto` et d'`embedding` seulement (règle ArchUnit 11) :
le jeton S se lit sans la charge W. Exigence SEC-D01 (dossier v2.6, §4.2 et §8.3).

## Format

Charge S de 64 octets, écrite dans la partition S par `EmbeddingSession.writeS` :

```
version(1) = 0x01 ‖ kid(2, gros-boutiste) ‖ nonce(12) ‖ chiffré(32) ‖ étiquette GCM(16) ‖ bourrage(1, aléatoire)
```

Texte clair de 32 octets, scellé par `CryptoService.sealTokenS` (format inchangé) :

```
référence de dossier(16) ‖ issuerId(4, gros-boutiste) ‖ indicateur de protocole(1) ‖ zéros(11)
```

Sous-clé par émission `K_S = HKDF(K_tok, "MIT-S-v1" ‖ 0x00 ‖ emissionId)` ; AAD
`"MIT-S-v1" ‖ emissionId(16) ‖ issuerId(4) ‖ kid(2)` (voir `crypto.md`). Indicateur de protocole : `0x01` clinique,
`0x02` recherche avec autorisation (même codage que l'octet de flux de W).

## API

| Méthode | Rôle |
|---|---|
| `embed(session, folderReference16, emissionId16, issuerId, kid, protocol)` | construit, scelle et écrit le jeton |
| `embedWithoutToken(session)` | flux recherche sans autorisation : S = 64 octets de `SecureRandom` |
| `extract(copy, partitions, emissionId16, issuerId)` | lit S et ouvre le jeton avec l'identifiant d'émission et l'émetteur fournis par l'appelant (registre) ; renvoie `Token(folderReference, protocol, kid)` |

Échec fermé : clé absente ou fausse, autre émission ou autre émetteur (AAD), jeton transplanté sur une autre image,
S aléatoire, version, émetteur ou zéros du texte clair invalides : une seule `StegoException`, message fixe, aucune
cause, aucune donnée partielle. Les tableaux intermédiaires (texte clair, charge) sont mis à zéro après usage ;
`Token.toString()` masque la référence.

Lecture sur une copie analysée : les partitions sont recalculées sur la copie (`RoniDetector.candidates`, essai de
chaque candidat), comme pour W.

## Tests (`StegoServiceTest`)

- 20 copies (fantômes 512 × 512, F, S et W écrits) : la bonne clé redonne la référence attendue, avec des partitions
  recalculées sur la copie.
- Kid inconnu, même kid avec une autre clé : échec.
- Autre identifiant d'émission, autre émetteur, identifiant absent : échec.
- Jeton de l'image A recopié tel quel dans la partition S de l'image B (émission B) : échec (AAD).
- S aléatoire (flux recherche, et 20 S aléatoires dont 10 avec l'octet de version correct) : échec.
- La référence n'apparaît en clair ni dans la charge S, ni dans les octets des pixels, ni dans les sorties standard,
  les journaux (`java.util.logging`) ou `Token.toString()`.

## Limite connue

La version et le kid sont en clair : une partition S portant un jeton se distingue de 64 octets aléatoires (premier
octet `0x01`, kid plausible). Le flux recherche sans autorisation reste donc reconnaissable comme tel. Proposition, non
appliquée (le format demandé est « 64 octets aléatoires ») : tirer aussi le premier octet à `0x01` et un kid actif
dans le S aléatoire, ou chiffrer l'en-tête, pour que les deux flux soient indistinguables.
