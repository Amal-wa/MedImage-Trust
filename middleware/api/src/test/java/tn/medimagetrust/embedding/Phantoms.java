package tn.medimagetrust.embedding;

import java.util.Random;

/**
 * Fantômes synthétiques déterministes (graine fixe) : un fond bruité et un « corps » elliptique bruité.
 * Bruit uniforme borné, pour que les marges de garde soient connues exactement.
 *
 * <p>Public (classe, {@link Spec}, {@link Phantom}, {@link #build}, {@link #normalSpec}) pour être réutilisé tel quel par
 * le banc d'attaques ({@code tn.medimagetrust.bench.AttackBench}), sans copie.</p>
 */
public final class Phantoms {
    private Phantoms() {}

    static final long TEST_SEED_PHANTOM = 0x4D49_5450_4831L;

    /** Paramètres d'un fantôme. */
    public record Spec(int rows, int cols, int bitsStored, Photometric photometric,
                int bgLevel, int bgNoise, int bodyLevel, int bodyNoise,
                double centerRow, double centerCol, double semiRows, double semiCols) {}

    /** Image et masque du corps (vérité terrain). */
    public record Phantom(PixelImage image, boolean[] body) {}

    public static Phantom build(Spec s, long seed) {
        Random rnd = new Random(seed);
        int max = (1 << s.bitsStored()) - 1;
        int n = s.rows() * s.cols();
        int[] px = new int[n];
        boolean[] body = new boolean[n];
        for (int r = 0; r < s.rows(); r++) {
            for (int c = 0; c < s.cols(); c++) {
                double dr = (r - s.centerRow()) / s.semiRows();
                double dc = (c - s.centerCol()) / s.semiCols();
                boolean in = dr * dr + dc * dc <= 1.0;
                int level = in ? s.bodyLevel() : s.bgLevel();
                int noise = in ? s.bodyNoise() : s.bgNoise();
                int v = level + (noise == 0 ? 0 : rnd.nextInt(2 * noise + 1) - noise);
                px[r * s.cols() + c] = Math.max(0, Math.min(max, v));
                body[r * s.cols() + c] = in;
            }
        }
        return new Phantom(PixelImage.fromInts(s.rows(), s.cols(), s.bitsStored(), s.photometric(), px, false, false),
                body);
    }

    /** 512 × 512, 12 bits, fond sombre vers 200 (q de 40 à 60), ellipse claire vers 2000 (q de 450 à 550). */
    public static Spec normalSpec() {
        return new Spec(512, 512, 12, Photometric.MONOCHROME2, 200, 40, 2000, 200, 256, 256, 150, 190);
    }

    static Phantom normal() { return build(normalSpec(), TEST_SEED_PHANTOM); }

    static Phantom normal(long seed) { return build(normalSpec(), seed); }

    /** Fond exactement à 0 (scanner, IRM). */
    static Phantom zeroBackground() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME2, 0, 0, 2000, 200, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Fond clair (MONOCHROME1, air en valeurs hautes), corps sombre. */
    static Phantom lightBackground() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME1, 3900, 40, 1500, 200, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Disque qui touche les quatre bords : il ne reste du fond que dans les coins (moins de 290 blocs). */
    static Phantom bodyTouchingEdges() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME2, 200, 40, 2000, 200, 256, 256, 285, 285),
                TEST_SEED_PHANTOM);
    }

    /**
     * Disque qui couvre la majorité du bord : la polarité s'inverse et le corps est pris pour du fond. Échec attendu
     * du profil (§4.1), rattrapé seulement par la validation humaine.
     */
    static Phantom bodyCoveringBorder() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME2, 200, 40, 2000, 200, 256, 256, 300, 300),
                TEST_SEED_PHANTOM);
    }

    /** Image 64 × 64 : 64 blocs au plus. */
    static Phantom small64() {
        return build(new Spec(64, 64, 12, Photometric.MONOCHROME2, 200, 40, 2000, 200, 32, 32, 12, 12),
                TEST_SEED_PHANTOM);
    }

    /** Contraste faible : fond q de 40 à 60, corps q de 66 à 86 ; le seuil tombe à moins de 4 niveaux du fond. */
    static Phantom lowContrast() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME2, 200, 40, 304, 40, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Corps minuscule : la ROI couvre moins de 5 % de l'image. */
    static Phantom tinyBody() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME2, 200, 40, 2000, 200, 256, 256, 20, 20),
                TEST_SEED_PHANTOM);
    }

    /** Variante 8 bits du fantôme normal (fond vers 30, corps vers 153) : même géométrie, dynamique 8 bits. */
    static Phantom normal8() {
        return build(new Spec(512, 512, 8, Photometric.MONOCHROME2, 30, 3, 153, 12, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Variante 8 bits du fond clair (MONOCHROME1, fond vers 230, corps sombre vers 100). */
    static Phantom lightBackground8() {
        return build(new Spec(512, 512, 8, Photometric.MONOCHROME1, 230, 3, 100, 12, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Fond exactement à 0 en 8 bits (original stocké sur 8 bits), corps clair vers 153. */
    static Phantom zeroBackground8() {
        return build(new Spec(512, 512, 8, Photometric.MONOCHROME2, 0, 0, 153, 12, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Fond exactement à {@code 2^B − 1} (fond saturé, polarité claire), corps sombre, en 12 bits. */
    static Phantom saturatedBackground() {
        return build(new Spec(512, 512, 12, Photometric.MONOCHROME1, 4095, 0, 1500, 200, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Fond exactement à 0, en 16 bits : la QIM sortirait de la plage, que le mode signé ne peut pas couvrir. */
    static Phantom zeroBackground16() {
        return build(new Spec(512, 512, 16, Photometric.MONOCHROME2, 0, 0, 32000, 3200, 256, 256, 150, 190),
                TEST_SEED_PHANTOM);
    }

    /** Fantôme aléatoire marquable (taille, profondeur, polarité, position du corps tirées de {@code rnd}). */
    static Spec randomMarkableSpec(Random rnd) {
        int rows = 256 + rnd.nextInt(129);
        int cols = 256 + rnd.nextInt(129);
        int bits = 8 + rnd.nextInt(9);
        int max = (1 << bits) - 1;
        boolean light = rnd.nextBoolean();
        // Fond à 12 % de la dynamique (loin de 0 et du maximum), corps à 60 % ; bruits bornés.
        int bg = (int) Math.round(max * (light ? 0.88 : 0.12));
        int body = (int) Math.round(max * (light ? 0.40 : 0.60));
        int bgNoise = Math.max(1, max / 64);
        int bodyNoise = Math.max(1, max / 20);
        double cr = rows / 2.0 + (rnd.nextDouble() - 0.5) * rows * 0.1;
        double cc = cols / 2.0 + (rnd.nextDouble() - 0.5) * cols * 0.1;
        double sr = rows * (0.22 + rnd.nextDouble() * 0.1);
        double sc = cols * (0.22 + rnd.nextDouble() * 0.1);
        return new Spec(rows, cols, bits, light ? Photometric.MONOCHROME1 : Photometric.MONOCHROME2,
                bg, bgNoise, body, bodyNoise, cr, cc, sr, sc);
    }

    /** Copie de l'image avec d'autres attributs d'en-tête. */
    static PixelImage withFlags(PixelImage img, boolean burnedIn, boolean overlay) {
        return new PixelImage(img.rows(), img.cols(), img.bitsStored(), img.photometric(), img.pixels(), burnedIn,
                overlay);
    }
}
