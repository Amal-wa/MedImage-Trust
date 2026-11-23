# medimage-audit

Journal d'audit chaîné, ancrage signé et alertes A1–A7. Dépend uniquement de `medimage-crypto`
(SHA-256, JCS, Ed25519). Exigences : SEC07, SEC09, SEC-R04, SEC02 (A1B), MT10/11/15/16/28.

## Utilisation (module `web`)

```java
AuditLog log = new AuditLog(store, new AlertEngine(), alertSink);          // écriture
log.append(new AuditEvent(Instant.now(), EventType.COPY_ISSUED, actorId, "emission:" + uuid,
                          Result.SUCCESS, correlationId));                  // lève AuditException => refuser l'opération

// Service d'audit (tâches planifiées, identité de service distincte)
new AnchorService(store, anchorsMain, anchorsCopy, logKeys, alertSink, clock).anchorToday();   // 1 fois par jour
new AuditVerifier(store, anchorsMain, anchorsCopy, logKeys, alertSink, clock).verify();        // 1 fois par jour + à la demande
verifier.checkFreshness();                                                                      // toutes les heures (SEC-R04)
new RetentionService(store, purgeStore, anchorsMain, verifier, clock).purgeExpired();           // mensuel
```

Règle de journalisation : `actor`, `resource`, `correlationId` sont des **identifiants opaques**
(`[A-Za-z0-9._:@-]{1,64}`). Passer l'identifiant du compte, jamais le nom saisi, un nom de fichier ou un attribut DICOM
(l'événement est rejeté à la construction).

## Format

```
canonique   = JCS({actor, corr, digest, resource, result, seq, ts(ms), type})
entry_hash  = SHA-256("MIT-LOG-v1" ‖ 0x00 ‖ prev_hash ‖ canonique)      prev_hash(1) = 32 octets à 0
ancrage     = "MIT-LOG-v1" ‖ 0x00 ‖ 0x01 ‖ kid(2) ‖ jour(4) ‖ lastSeq(8) ‖ headHash(32) ‖ Ed25519(64)   (122 octets)
```

## À implémenter côté infrastructure

* `AuditStore` / `AuditPurgeStore` sur PostgreSQL. Squelette conseillé :

```sql
CREATE TABLE audit_log (
  seq BIGINT PRIMARY KEY,                       -- l'unicité fait échouer les écritures concurrentes
  ts_ms BIGINT NOT NULL, type TEXT NOT NULL, actor TEXT NOT NULL, resource TEXT NOT NULL,
  result TEXT NOT NULL, corr TEXT NOT NULL, digest TEXT NOT NULL DEFAULT '',
  prev_hash BYTEA NOT NULL CHECK (length(prev_hash) = 32),
  entry_hash BYTEA NOT NULL CHECK (length(entry_hash) = 32)
);
REVOKE ALL ON audit_log FROM app_role;  GRANT INSERT, SELECT ON audit_log TO app_role;   -- SEC09 : ni UPDATE ni DELETE
-- DELETE (purge) réservé à audit_maint_role, jamais au rôle applicatif.
```
  `append` doit lever `AuditConflictException` sur violation de `seq` (SQLState 23505).
* `LogKeyProvider` adossé au keystore PKCS#12 du service d'audit (passphrase en Docker secret).
* `AnchorStore` : `FileAnchorStore` sur le volume `medimage-anchors` (écriture réservée au service d'audit) et une
  seconde instance sur la copie séparée.

## Limites assumées

* Suppression des **dernières** lignes après le dernier ancrage : non détectable par la chaîne ; fenêtre bornée à un jour.
* Un attaquant qui obtient K_log **et** l'accès aux deux volumes peut forger un historique : K_log doit rester hors de portée
  de l'administrateur (SEC10).
