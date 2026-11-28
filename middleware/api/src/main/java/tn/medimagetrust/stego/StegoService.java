package tn.medimagetrust.stego;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.crypto.Kid;
import tn.medimagetrust.embedding.EmbeddingProfile;
import tn.medimagetrust.embedding.EmbeddingSession;
import tn.medimagetrust.embedding.LsbChannel;
import tn.medimagetrust.embedding.Partitions;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Jeton S : référence de dossier chiffrée dans la partition S (SEC-D01, dossier v2.6 §4.2 et §8.3).
 *
 * <p>Charge S de 64 octets :</p>
 * <pre>
 *   version(1) = 0x01 ‖ kid(2, gros-boutiste) ‖ nonce(12) ‖ chiffré(32) ‖ étiquette GCM(16) ‖ bourrage(1, aléatoire)
 * </pre>
 * <p>Texte clair de 32 octets, scellé par {@link CryptoService#sealTokenS} (format inchangé ; AAD liée à l'émission, à
 * l'émetteur et au kid) :</p>
 * <pre>
 *   référence de dossier(16) ‖ issuerId(4, gros-boutiste) ‖ indicateur de protocole(1) ‖ zéros(11)
 * </pre>
 *
 * <p>Flux recherche sans autorisation : S = 64 octets aléatoires ({@link #embedWithoutToken}). La lecture
 * ({@link #extract}) se fait avec l'identifiant d'émission et l'émetteur fournis par l'appelant (registre d'émission) ;
 * elle ne dépend pas de la charge W. Toute anomalie lève la même {@link StegoException}.</p>
 *
 * <p>La référence de dossier n'apparaît jamais en clair dans les pixels ni dans un message ou un journal ;
 * {@link Token#toString()} la masque.</p>
 */
public final class StegoService {
    /** Version du format de la charge S. */
    public static final int VERSION = 0x01;
    /** Taille de la référence de dossier. */
    public static final int FOLDER_REFERENCE_BYTES = 16;

    private static final int PLAINTEXT_ISSUER = FOLDER_REFERENCE_BYTES;
    private static final int PLAINTEXT_PROTOCOL = PLAINTEXT_ISSUER + 4;
    private static final int PLAINTEXT_ZEROS = PLAINTEXT_PROTOCOL + 1;
    private static final int SEALED_OFFSET = 1 + Kid.BYTES;

    /** Indicateur de protocole (même codage que l'octet de flux de W). */
    public enum Protocol {
        /** Flux clinique. */
        CLINICAL(0x01),
        /** Flux recherche avec autorisation. */
        RESEARCH(0x02);

        private final int code;

        Protocol(int code) { this.code = code; }

        /** Code sur un octet. */
        public int code() { return code; }

        static Protocol of(int code) throws StegoException {
            for (Protocol p : values()) {
                if (p.code == code) return p;
            }
            throw new StegoException();
        }
    }

    /**
     * Contenu d'un jeton S ouvert.
     *
     * @param folderReference référence opaque de dossier (16 octets, copie)
     * @param protocol        indicateur de protocole
     * @param kid             version de clé du jeton
     */
    public record Token(byte[] folderReference, Protocol protocol, int kid) {
        public Token {
            folderReference = folderReference.clone();
        }

        /** Copie de la référence. */
        @Override
        public byte[] folderReference() { return folderReference.clone(); }

        /** La référence n'est jamais imprimée. */
        @Override
        public String toString() { return "Token[référence masquée]"; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Token t && Arrays.equals(folderReference, t.folderReference) && protocol == t.protocol
                    && kid == t.kid;
        }

        @Override
        public int hashCode() { return Objects.hash(Arrays.hashCode(folderReference), protocol, kid); }
    }

    private final CryptoService crypto;
    private final SecureRandom random;

    /** Service sur un {@link SecureRandom} par défaut. */
    public StegoService(CryptoService crypto) {
        this(crypto, new SecureRandom());
    }

    /** Service sur une source d'aléa donnée (qui doit être cryptographique). */
    public StegoService(CryptoService crypto, SecureRandom random) {
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.random = Objects.requireNonNull(random, "aléa");
    }

    /**
     * Construit le jeton S et l'écrit dans la partition S de la session.
     *
     * @param session         session d'embedding ouverte, avant l'écriture de S
     * @param folderReference référence opaque de dossier, 16 octets
     * @param emissionId      identifiant d'émission, 16 octets
     * @param issuerId        identifiant de l'émetteur
     * @param kid             version de la clé de jeton
     * @param protocol        indicateur de protocole
     * @throws IllegalArgumentException taille invalide ou kid inconnu (message fixe)
     */
    public void embed(EmbeddingSession session, byte[] folderReference, byte[] emissionId, int issuerId, int kid,
                      Protocol protocol) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(protocol, "protocole");
        if (folderReference == null || folderReference.length != FOLDER_REFERENCE_BYTES) {
            throw new IllegalArgumentException("référence de 16 octets requise");
        }
        if (emissionId == null || emissionId.length != CryptoService.ID_BYTES) {
            throw new IllegalArgumentException("identifiant d'émission de 16 octets requis");
        }
        byte[] plaintext = new byte[CryptoService.TOKEN_PLAINTEXT_BYTES];
        byte[] s = new byte[EmbeddingProfile.S_BYTES];
        try {
            System.arraycopy(folderReference, 0, plaintext, 0, FOLDER_REFERENCE_BYTES);
            putInt(plaintext, PLAINTEXT_ISSUER, issuerId);
            plaintext[PLAINTEXT_PROTOCOL] = (byte) protocol.code();
            byte[] sealed = crypto.sealTokenS(Kid.check(kid), emissionId, issuerId, plaintext);
            s[0] = (byte) VERSION;
            System.arraycopy(Kid.toBytes(kid), 0, s, 1, Kid.BYTES);
            System.arraycopy(sealed, 0, s, SEALED_OFFSET, sealed.length);
            byte[] pad = new byte[1];
            random.nextBytes(pad);
            s[SEALED_OFFSET + sealed.length] = pad[0];
            session.writeS(s);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(s, (byte) 0);
        }
    }

    /** Flux recherche sans autorisation : 64 octets aléatoires dans la partition S. */
    public void embedWithoutToken(EmbeddingSession session) {
        Objects.requireNonNull(session, "session");
        byte[] s = new byte[EmbeddingProfile.S_BYTES];
        random.nextBytes(s);
        session.writeS(s);
    }

    /**
     * Lit la partition S d'une copie et ouvre le jeton.
     *
     * @param copy       copie analysée (dans le domaine de valeurs de l'émission)
     * @param partitions partitions de la copie
     * @param emissionId identifiant d'émission fourni par l'appelant (registre), 16 octets
     * @param issuerId   émetteur de l'émission, fourni par l'appelant
     * @throws StegoException pour toute anomalie, sans détail
     */
    public Token extract(PixelImage copy, Partitions partitions, byte[] emissionId, int issuerId)
            throws StegoException {
        byte[] s = null;
        byte[] plaintext = null;
        try {
            if (emissionId == null || emissionId.length != CryptoService.ID_BYTES) {
                throw new StegoException();
            }
            s = LsbChannel.reader(copy, partitions).readS();
            if (s.length != EmbeddingProfile.S_BYTES || (s[0] & 0xFF) != VERSION) {
                throw new StegoException();
            }
            int kid = Kid.fromBytes(s, 1);
            byte[] sealed = Arrays.copyOfRange(s, SEALED_OFFSET, SEALED_OFFSET + CryptoService.TOKEN_SEALED_BYTES);
            plaintext = crypto.openTokenS(kid, emissionId, issuerId, sealed);
            if (plaintext.length != CryptoService.TOKEN_PLAINTEXT_BYTES || getInt(plaintext, PLAINTEXT_ISSUER) != issuerId) {
                throw new StegoException();
            }
            for (int i = PLAINTEXT_ZEROS; i < plaintext.length; i++) {
                if (plaintext[i] != 0) throw new StegoException();
            }
            Protocol protocol = Protocol.of(plaintext[PLAINTEXT_PROTOCOL] & 0xFF);
            return new Token(Arrays.copyOf(plaintext, FOLDER_REFERENCE_BYTES), protocol, kid);
        } catch (StegoException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StegoException(); // ni type ni message de la cause
        } finally {
            if (s != null) Arrays.fill(s, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }
}
