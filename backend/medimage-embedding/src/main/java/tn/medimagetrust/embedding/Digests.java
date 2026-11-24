package tn.medimagetrust.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static tn.medimagetrust.embedding.EmbeddingException.Reason.ILLEGAL_STATE;
import static tn.medimagetrust.embedding.EmbeddingException.Reason.INVALID_INPUT;

/**
 * Condensats signés par W (dossier v2.4, §8.3), SHA-256 de la JCA.
 *
 * <pre>
 * roiDigest  = SHA-256("MIT-ROI-v1"  ‖ rows ‖ cols ‖ bitsStored ‖ pixels hors RONI en ordre raster)
 * maskDigest = SHA-256("MIT-MASK-v1" ‖ rows ‖ cols ‖ masque binaire par pixel)
 * sDigest    = SHA-256(64 octets de S)
 * </pre>
 *
 * <p>Encodage : étiquettes en ASCII sans terminateur ni longueur ; {@code rows}, {@code cols} sur 4 octets
 * gros-boutiste ; {@code bitsStored} sur 1 octet ; chaque pixel sur 2 octets gros-boutiste ; masque compacté
 * 8 pixels par octet en ordre raster, bit de poids fort d'abord, dernier octet complété par des zéros.</p>
 */
public final class Digests {
    private Digests() {}

    /** Étiquette du condensat de la ROI. */
    public static final String ROI_LABEL = "MIT-ROI-v1";
    /** Étiquette du condensat du masque. */
    public static final String MASK_LABEL = "MIT-MASK-v1";

    /**
     * Condensat de la ROI : les pixels hors RONI de {@code image}, selon le masque de {@code profile}.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si un argument est nul ou si les dimensions diffèrent
     */
    public static byte[] roiDigest(PixelImage image, RoniProfile profile) {
        if (image == null || profile == null || image.rows() != profile.rows() || image.cols() != profile.cols()) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        MessageDigest md = sha256();
        md.update(ROI_LABEL.getBytes(StandardCharsets.US_ASCII));
        md.update(int32(image.rows()));
        md.update(int32(image.cols()));
        md.update((byte) image.bitsStored());
        char[] px = image.view();
        byte[] buf = new byte[8192];
        int pos = 0;
        for (int i = 0; i < px.length; i++) {
            if (!profile.inRoniUnchecked(i)) {
                buf[pos++] = (byte) (px[i] >>> 8);
                buf[pos++] = (byte) px[i];
                if (pos == buf.length) {
                    md.update(buf, 0, pos);
                    pos = 0;
                }
            }
        }
        md.update(buf, 0, pos);
        return md.digest();
    }

    /**
     * Condensat du masque RONI par pixel.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si le profil est nul
     */
    public static byte[] maskDigest(RoniProfile profile) {
        if (profile == null) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        MessageDigest md = sha256();
        md.update(MASK_LABEL.getBytes(StandardCharsets.US_ASCII));
        md.update(int32(profile.rows()));
        md.update(int32(profile.cols()));
        md.update(profile.packedMask());
        return md.digest();
    }

    /**
     * Condensat de la partition S.
     *
     * @throws EmbeddingException {@code INVALID_INPUT} si {@code s} est nul ou ne fait pas 64 octets
     */
    public static byte[] sDigest(byte[] s) {
        if (s == null || s.length != EmbeddingProfile.S_BYTES) {
            throw new EmbeddingException(INVALID_INPUT);
        }
        return sha256().digest(s.clone());
    }

    private static byte[] int32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est obligatoire dans toute JVM conforme : échec fermé si l'environnement est altéré.
            throw new EmbeddingException(ILLEGAL_STATE);
        }
    }
}
