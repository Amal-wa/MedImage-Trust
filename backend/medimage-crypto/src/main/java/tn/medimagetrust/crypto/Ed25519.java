package tn.medimagetrust.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

/** Signature et vérification Ed25519 avec l'API JCA native. */
public final class Ed25519 {

    private static final String ALGORITHM = "Ed25519";

    private Ed25519() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponible", e);
        }
    }

    /** Signe des octets (en pratique : la sortie de Jcs.canonicalize). */
    public static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Clé privée invalide", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature impossible", e);
        }
    }

    /** Renvoie true si la signature est valide, false dans tous les autres cas (jamais d'exception). */
    public static boolean verify(PublicKey publicKey, byte[] message, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (SignatureException e) {
            return false; // signature malformée (mauvaise taille, point invalide...)
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Clé publique invalide", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Vérification impossible", e);
        }
    }
}
