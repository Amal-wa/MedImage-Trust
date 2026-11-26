package tn.medimagetrust.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

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

    /**
     * Paire Ed25519 déterministe à partir d'une graine de 32 octets (RFC 8032 §5.1.5), pour les clés dérivées de la
     * KEK. La clé privée vient de {@link EdECPrivateKeySpec} ; la clé publique correspondante est calculée par le
     * {@link KeyPairGenerator} du JDK, initialisé avec {@link SeedRandom}, qui lui fournit exactement la graine.
     */
    public static KeyPair keyPairFromSeed(byte[] seed32) {
        if (seed32 == null || seed32.length != 32) {
            throw new IllegalArgumentException("Graine de 32 octets requise");
        }
        try {
            PrivateKey privateKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed32.clone()));
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed32));
            PublicKey publicKey = generator.generateKeyPair().getPublic();
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponible", e);
        }
    }

    /**
     * Source « aléatoire » déterministe, utilisée <strong>uniquement</strong> par {@link #keyPairFromSeed(byte[])} :
     * elle renvoie la graine une seule fois, puis refuse tout autre tirage (si le JDK en demandait davantage, la
     * dérivation échouerait au lieu de produire une clé fausse). Jamais utilisée pour un nonce ni pour une clé
     * aléatoire.
     */
    private static final class SeedRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final byte[] seed;
        private boolean used;

        SeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public synchronized void nextBytes(byte[] bytes) {
            if (used || bytes.length != seed.length) {
                throw new IllegalStateException("Tirage inattendu");
            }
            System.arraycopy(seed, 0, bytes, 0, seed.length);
            Arrays.fill(seed, (byte) 0);
            used = true;
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
