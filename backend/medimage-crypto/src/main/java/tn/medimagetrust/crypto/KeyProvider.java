package tn.medimagetrust.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.SecretKey;

/**
 * Fournisseur de clés versionnées par identifiant {@code kid}.
 * Implémenté en mémoire pour les tests et branché sur WildFly Elytron en production.
 */
public interface KeyProvider {

    /** Retourne l'identifiant (kid) de la clé de signature active de l'établissement. */
    String activeSigningKeyId();

    /** Clé privée Ed25519 associée au kid actif (jamais exportée ni loguée). */
    PrivateKey getSigningPrivateKey(String kid);

    /** Clé publique Ed25519 associée à un kid donné (pour la vérification). */
    PublicKey getSigningPublicKey(String kid);

    /** Clé maître symétrique (HKDF) pour dériver K_tok, K_F et K_log. */
    SecretKey getMasterSecretKey(String kid);
}
