package tn.medimagetrust.dicom;

import static tn.medimagetrust.dicom.DicomRejectedException.Reason.FILE_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.HEADER_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.IMAGE_TOO_LARGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.MALFORMED;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.NOT_DICOM;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_LENGTH_MISMATCH;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_MISSING;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_DATA_TRUNCATED;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.PIXEL_VALUE_OUT_OF_RANGE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SEQUENCE_TOO_DEEP;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SIZE_MISMATCH;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SUSPICIOUS_PREAMBLE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.SUSPICIOUS_TEXT;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.TIMEOUT;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.TOO_MANY_ELEMENTS;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_MODALITY;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_PROFILE;
import static tn.medimagetrust.dicom.DicomRejectedException.Reason.UNSUPPORTED_TRANSFER_SYNTAX;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputHandler;
import org.dcm4che3.io.DicomInputStream;
import tn.medimagetrust.embedding.EmbeddingException;
import tn.medimagetrust.embedding.Photometric;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Lecture sûre d'un fichier DICOM : point d'entrée unique, {@link #read(InputStream, long)}.
 *
 * <p>Contrôles, dans cet ordre, avant toute allocation des pixels (SEC-D02, SEC-F01, SEC-F02, SEC-F04) :</p>
 * <ol>
 *   <li>taille annoncée ≤ 80 Mio, et jamais plus d'octets lus que la taille annoncée ;</li>
 *   <li>préambule de 128 octets : rejet s'il commence par une signature d'exécutable PE ({@code MZ}), ELF ou Mach-O,
 *       avec un événement d'audit ({@link DicomAuditSink.Event#DICOM_SUSPICIOUS_PREAMBLE}) ;</li>
 *   <li>{@code DICM} à l'offset 128 ;</li>
 *   <li>en-tête lu en flux par dcm4che et arrêté avant PixelData, sous un garde qui voit chaque élément avant son
 *       allocation : en-tête ≤ 1 Mio, ≤ 5 000 éléments, profondeur de séquence ≤ 8, longueur paire et compatible avec
 *       la représentation, longueur indéfinie réservée aux séquences ; textes sans balise ni caractère de contrôle ;</li>
 *   <li>profil : Explicit ou Implicit VR Little Endian, image unique, un échantillon, MONOCHROME1 ou MONOCHROME2,
 *       8 ou 16 bits alloués, {@code BitsStored} de 8 à {@code BitsAllocated} avec {@code HighBit = BitsStored − 1},
 *       {@code Rows} et {@code Columns} ≤ 8 192, produit ≤ 36 000 000, modalité CR, DX, CT, MR ou MG ;</li>
 *   <li>longueur de PixelData égale à {@code Rows × Columns × octets par pixel} (plus un octet de bourrage si ce
 *       nombre est impair), puis lecture des pixels directement dans le tableau de {@link PixelImage}, chaque valeur
 *       dans la profondeur déclarée ;</li>
 *   <li>annotation incrustée, overlay, courbe ou plage de remplissage : image lisible mais non marquable
 *       ({@link Markability}) ;</li>
 *   <li>{@code PixelPaddingValue} transmis à l'embedding, qui exclut ces pixels de la RONI.</li>
 * </ol>
 *
 * <p>Toute la lecture tourne sous un délai (30 s par défaut, SEC-F04) : au-delà, le flux est fermé et le fichier rejeté
 * ({@code TIMEOUT}). Les octets qui suivent les données de pixels ne sont pas lus. Toute anomalie produit un rejet à
 * motif fermé ; aucune lecture « au mieux », aucune valeur du fichier dans un message.</p>
 */
public final class DicomReader {
    /** Plafond de fichier : 80 Mio (dossier v2.6, §6.2). */
    public static final long MAX_FILE_BYTES = 80L * 1024 * 1024;
    /** Plafond de l'en-tête, hors données de pixels : 1 Mio. */
    public static final int MAX_HEADER_BYTES = 1024 * 1024;
    /** Nombre maximal d'éléments (méta-informations, jeu de données et séquences). */
    public static final int MAX_ELEMENTS = 5_000;
    /** Profondeur maximale de séquences imbriquées. */
    public static final int MAX_SEQUENCE_DEPTH = 8;
    /** Côté maximal ({@code Rows}, {@code Columns}). */
    public static final int MAX_DIMENSION = 8_192;
    /** Nombre maximal de pixels. */
    public static final long MAX_PIXELS = 36_000_000L;
    /** Délai de lecture par défaut (SEC-F04). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Syntaxes acceptées en entrée (décision : voir api/docs/dicom.md). */
    static final Set<String> ACCEPTED_TRANSFER_SYNTAXES = Set.of(UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian);
    /** Modalités acceptées (SEC-F01, plus MG : voir api/docs/dicom.md). */
    static final Set<String> ACCEPTED_MODALITIES = Set.of("CR", "DX", "CT", "MR", "MG");

    private static final int PREAMBLE = 128;
    private static final int PREFIX = PREAMBLE + 4;
    private static final ExecutorService DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final DicomAuditSink audit;
    private final ExecutorService executor;
    private final Duration timeout;

    /** Lecteur avec le délai par défaut, sur des fils virtuels. */
    public DicomReader(DicomAuditSink audit) {
        this(audit, DEFAULT_EXECUTOR, DEFAULT_TIMEOUT);
    }

    /**
     * Lecteur sur un exécuteur fourni (en production : le {@code ManagedExecutorService} borné du serveur) et un délai
     * donné.
     */
    public DicomReader(DicomAuditSink audit, ExecutorService executor, Duration timeout) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.executor = Objects.requireNonNull(executor, "exécuteur");
        this.timeout = Objects.requireNonNull(timeout, "délai");
    }

    /**
     * Lit et contrôle un fichier DICOM.
     *
     * @param in           flux du fichier (non fermé, sauf dépassement du délai)
     * @param declaredSize taille annoncée par le client, en octets
     * @throws DicomRejectedException rejet à motif fermé
     */
    public DicomImage read(InputStream in, long declaredSize) throws DicomRejectedException {
        if (in == null) {
            throw new DicomRejectedException(MALFORMED);
        }
        if (declaredSize > MAX_FILE_BYTES) {
            throw new DicomRejectedException(FILE_TOO_LARGE);
        }
        if (declaredSize < PREFIX) {
            throw new DicomRejectedException(NOT_DICOM);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Future<DicomImage> task = executor.submit(() -> readChecked(in, declaredSize, deadline));
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            closeQuietly(in); // débloque une lecture en attente sur un flux lent
            throw new DicomRejectedException(TIMEOUT);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new DicomRejectedException(TIMEOUT);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DicomRejectedException rejected) {
                throw new DicomRejectedException(rejected.reason());
            }
            throw new DicomRejectedException(MALFORMED);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // rejet déjà décidé : l'erreur de fermeture n'apporte rien
        }
    }

    // ---------------------------------------------------------------------------------------------- lecture

    private DicomImage readChecked(InputStream in, long declaredSize, long deadline) throws DicomRejectedException {
        LimitedInputStream limited = new LimitedInputStream(in, declaredSize, deadline);
        try {
            return readStream(new BufferedInputStream(limited, 64 * 1024), deadline);
        } catch (DicomRejectedException e) {
            throw e;
        } catch (LimitExceededException e) {
            throw new DicomRejectedException(SIZE_MISMATCH);
        } catch (DeadlineExceededException e) {
            throw new DicomRejectedException(TIMEOUT);
        } catch (EmbeddingException e) {
            throw new DicomRejectedException(e.reason() == EmbeddingException.Reason.IMAGE_TOO_LARGE
                    ? IMAGE_TOO_LARGE : MALFORMED);
        } catch (IOException | RuntimeException e) {
            // Exception de dcm4che ou de lecture : on ne garde ni le type ni le message (contenu possible du fichier).
            if (hasCause(e, TruncatedException.class)) throw new DicomRejectedException(PIXEL_DATA_TRUNCATED);
            if (hasCause(e, LimitExceededException.class)) throw new DicomRejectedException(SIZE_MISMATCH);
            if (hasCause(e, DeadlineExceededException.class)) throw new DicomRejectedException(TIMEOUT);
            if (hasCause(e, GuardRejection.class)) throw new DicomRejectedException(guardReason(e));
            throw new DicomRejectedException(MALFORMED);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private static DicomRejectedException.Reason guardReason(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof GuardRejection g) return g.reason;
        }
        return MALFORMED;
    }

    private DicomImage readStream(BufferedInputStream buf, long deadline) throws IOException, DicomRejectedException {
        // 2-3. Préambule et signature, lus avant dcm4che, puis rendus au flux.
        buf.mark(PREFIX);
        byte[] prefix = buf.readNBytes(PREFIX);
        if (prefix.length < PREFIX) {
            throw new DicomRejectedException(NOT_DICOM);
        }
        if (isExecutablePreamble(prefix)) {
            audit.record(DicomAuditSink.Event.DICOM_SUSPICIOUS_PREAMBLE);
            throw new DicomRejectedException(SUSPICIOUS_PREAMBLE);
        }
        if (prefix[PREAMBLE] != 'D' || prefix[PREAMBLE + 1] != 'I' || prefix[PREAMBLE + 2] != 'C'
                || prefix[PREAMBLE + 3] != 'M') {
            throw new DicomRejectedException(NOT_DICOM);
        }
        buf.reset();

        // 4. En-tête en flux, sous garde, arrêté avant PixelData.
        DicomInputStream dis = new DicomInputStream(buf);
        dis.setAllocateLimit(MAX_HEADER_BYTES);
        HeaderGuard guard = new HeaderGuard(dis, deadline);
        dis.setDicomInputHandler(guard);
        Attributes fmi = dis.getFileMetaInformation();
        if (fmi == null) {
            throw new DicomRejectedException(NOT_DICOM);
        }
        String ts = dis.getTransferSyntax();
        if (ts == null || !ACCEPTED_TRANSFER_SYNTAXES.contains(ts)
                || !ts.equals(fmi.getString(Tag.TransferSyntaxUID))) {
            throw new DicomRejectedException(UNSUPPORTED_TRANSFER_SYNTAX);
        }
        Attributes ds = dis.readDatasetUntilPixelData();
        if (dis.tag() != Tag.PixelData || dis.level() != 0) {
            throw new DicomRejectedException(PIXEL_DATA_MISSING);
        }
        if (dis.getPosition() - PREFIX > MAX_HEADER_BYTES) {
            throw new DicomRejectedException(HEADER_TOO_LARGE);
        }
        checkText(fmi);
        checkText(ds);

        // 5. Profil accepté.
        Profile p = profile(ds);

        // 6. Longueur de PixelData, puis lecture dans le tableau de l'image.
        int bytesPerPixel = p.bitsAllocated / 8;
        long expected = (long) p.rows * p.cols * bytesPerPixel;
        int length = dis.length();
        boolean oddPad = (expected & 1) == 1 && length == expected + 1;
        if (length < 0) {
            throw new DicomRejectedException(MALFORMED); // données encapsulées dans une syntaxe non compressée
        }
        if (length != expected && !oddPad) {
            throw new DicomRejectedException(PIXEL_DATA_LENGTH_MISMATCH);
        }
        VR vr = dis.vr();
        if (dis.explicitVR() && vr != VR.OW && !(vr == VR.OB && p.bitsAllocated == 8)) {
            throw new DicomRejectedException(MALFORMED);
        }
        Markability markability = markability(ds);
        int offset = p.pixelRepresentation == 1 ? 1 << (p.bitsStored - 1) : 0;
        PixelImage image = readPixels(dis, p, offset, markability, deadline);

        // 8. Valeur de remplissage : même décalage que les pixels, dans la profondeur déclarée.
        Integer padding = storedPadding(ds, Tag.PixelPaddingValue, p);
        Integer paddingLimit = storedPadding(ds, Tag.PixelPaddingRangeLimit, p);
        if (padding != null) {
            image = image.withPixelPaddingValue(padding + offset);
        }
        return new DicomImage(fmi, ds, ts, p.modality, p.bitsAllocated, p.highBit, p.pixelRepresentation, padding,
                paddingLimit, markability, image);
    }

    /** Signatures d'exécutables en tête de préambule : PE ({@code MZ}), ELF, Mach-O (32 et 64 bits, deux ordres). */
    static boolean isExecutablePreamble(byte[] p) {
        int b0 = p[0] & 0xFF;
        int b1 = p[1] & 0xFF;
        int b2 = p[2] & 0xFF;
        int b3 = p[3] & 0xFF;
        if (b0 == 0x4D && b1 == 0x5A) return true;                                  // MZ (PE)
        if (b0 == 0x7F && b1 == 'E' && b2 == 'L' && b3 == 'F') return true;         // ELF
        if (b0 == 0xFE && b1 == 0xED && b2 == 0xFA && (b3 == 0xCE || b3 == 0xCF)) return true; // Mach-O gros-boutiste
        return (b0 == 0xCE || b0 == 0xCF) && b1 == 0xFA && b2 == 0xED && b3 == 0xFE;  // Mach-O petit-boutiste
    }

    // ---------------------------------------------------------------------------------------------- profil

    private record Profile(String modality, int rows, int cols, int bitsAllocated, int bitsStored, int highBit,
                           int pixelRepresentation, Photometric photometric) {}

    private static Profile profile(Attributes ds) throws DicomRejectedException {
        if (ds.getString(Tag.SOPClassUID) == null || ds.getString(Tag.SOPInstanceUID) == null) {
            throw new DicomRejectedException(MALFORMED);
        }
        String frames = ds.getString(Tag.NumberOfFrames);
        if (frames != null && !frames.trim().equals("1")) {
            throw new DicomRejectedException(UNSUPPORTED_PROFILE);
        }
        if (ds.getInt(Tag.SamplesPerPixel, -1) != 1) {
            throw new DicomRejectedException(UNSUPPORTED_PROFILE);
        }
        String pi = ds.getString(Tag.PhotometricInterpretation);
        Photometric photometric;
        if ("MONOCHROME1".equals(pi)) {
            photometric = Photometric.MONOCHROME1;
        } else if ("MONOCHROME2".equals(pi)) {
            photometric = Photometric.MONOCHROME2;
        } else {
            throw new DicomRejectedException(UNSUPPORTED_PROFILE);
        }
        int allocated = ds.getInt(Tag.BitsAllocated, -1);
        int stored = ds.getInt(Tag.BitsStored, -1);
        int high = ds.getInt(Tag.HighBit, -1);
        int representation = ds.getInt(Tag.PixelRepresentation, -1);
        if ((allocated != 8 && allocated != 16) || stored < 8 || stored > allocated || high != stored - 1
                || (representation != 0 && representation != 1)) {
            throw new DicomRejectedException(UNSUPPORTED_PROFILE);
        }
        int rows = ds.getInt(Tag.Rows, -1);
        int cols = ds.getInt(Tag.Columns, -1);
        if (rows < 1 || cols < 1) {
            throw new DicomRejectedException(MALFORMED);
        }
        if (rows > MAX_DIMENSION || cols > MAX_DIMENSION || (long) rows * cols > MAX_PIXELS) {
            throw new DicomRejectedException(IMAGE_TOO_LARGE);
        }
        String modality = ds.getString(Tag.Modality);
        if (modality == null || !ACCEPTED_MODALITIES.contains(modality)) {
            throw new DicomRejectedException(UNSUPPORTED_MODALITY);
        }
        return new Profile(modality, rows, cols, allocated, stored, high, representation, photometric);
    }

    /** Annotation incrustée, overlay (60xx), courbe (50xx), plage de remplissage : lisible mais non marquable. */
    private static Markability markability(Attributes ds) {
        if ("YES".equals(ds.getString(Tag.BurnedInAnnotation))) {
            return Markability.BURNED_IN_ANNOTATION;
        }
        boolean curve = false;
        for (int tag : ds.tags()) {
            int group = tag >>> 16;
            if (group >= 0x6000 && group <= 0x601E && (group & 1) == 0) {
                return Markability.OVERLAY_PRESENT;
            }
            if (group >= 0x5000 && group <= 0x501E && (group & 1) == 0) {
                curve = true;
            }
        }
        if (curve) {
            return Markability.CURVE_PRESENT;
        }
        if (ds.contains(Tag.PixelPaddingRangeLimit)) {
            return Markability.PADDING_RANGE_UNSUPPORTED;
        }
        return Markability.MARKABLE;
    }

    /**
     * Valeur de remplissage en valeur stockée : signée sur {@code BitsAllocated} si {@code PixelRepresentation = 1},
     * puis dans la profondeur déclarée ; sinon rejet ({@code PIXEL_VALUE_OUT_OF_RANGE}).
     */
    private static Integer storedPadding(Attributes ds, int tag, Profile p) throws DicomRejectedException {
        if (!ds.containsValue(tag)) {
            return null;
        }
        int raw = ds.getInt(tag, Integer.MIN_VALUE);
        if (raw == Integer.MIN_VALUE) {
            throw new DicomRejectedException(MALFORMED);
        }
        int word = p.bitsAllocated == 8 ? raw & 0xFF : raw & 0xFFFF;
        int value = p.pixelRepresentation == 1 ? (p.bitsAllocated == 8 ? (byte) word : (short) word) : word;
        if (!inStoredRange(value, p)) {
            throw new DicomRejectedException(PIXEL_VALUE_OUT_OF_RANGE);
        }
        return value;
    }

    private static boolean inStoredRange(int value, Profile p) {
        if (p.pixelRepresentation == 1) {
            int half = 1 << (p.bitsStored - 1);
            return value >= -half && value < half;
        }
        return value >= 0 && value <= (1 << p.bitsStored) - 1;
    }

    // ---------------------------------------------------------------------------------------------- pixels

    private static PixelImage readPixels(DicomInputStream dis, Profile p, int offset, Markability markability,
                                         long deadline) throws IOException, DicomRejectedException {
        boolean burnedIn = markability == Markability.BURNED_IN_ANNOTATION;
        boolean overlay = markability == Markability.OVERLAY_PRESENT || markability == Markability.CURVE_PRESENT;
        int bytesPerPixel = p.bitsAllocated / 8;
        int[] rejected = new int[1];
        PixelImage image = PixelImage.filled(p.rows, p.cols, p.bitsStored, p.photometric, false, burnedIn, overlay,
                (PixelImage.PixelFiller<IOException>) w -> {
                    byte[] chunk = new byte[64 * 1024];
                    int total = w.length();
                    int index = 0;
                    while (index < total) {
                        if (System.nanoTime() > deadline) throw new DeadlineExceededException();
                        int pixels = Math.min(chunk.length / bytesPerPixel, total - index);
                        try {
                            dis.readFully(chunk, 0, pixels * bytesPerPixel);
                        } catch (EOFException e) {
                            throw new TruncatedException();
                        }
                        for (int k = 0; k < pixels; k++) {
                            int value;
                            if (bytesPerPixel == 2) {
                                int word = (chunk[2 * k] & 0xFF) | (chunk[2 * k + 1] & 0xFF) << 8;
                                value = p.pixelRepresentation == 1 ? (short) word : word;
                            } else {
                                value = p.pixelRepresentation == 1 ? chunk[k] : chunk[k] & 0xFF;
                            }
                            if (!inStoredRange(value, p)) {
                                rejected[0] = 1;
                                return;
                            }
                            w.put(index + k, value + offset);
                        }
                        index += pixels;
                    }
                });
        if (rejected[0] != 0) {
            throw new DicomRejectedException(PIXEL_VALUE_OUT_OF_RANGE);
        }
        return image;
    }

    // ---------------------------------------------------------------------------------------------- textes

    /** Représentations textuelles dont les octets sont contrôlés. */
    private static final Set<VR> TEXT_VRS = Set.of(VR.AE, VR.AS, VR.CS, VR.DA, VR.DS, VR.DT, VR.IS, VR.LO, VR.LT,
            VR.PN, VR.SH, VR.ST, VR.TM, VR.UC, VR.UI, VR.UR, VR.UT);
    /** Textes longs : sauts de ligne et tabulations admis. */
    private static final Set<VR> MULTILINE_VRS = Set.of(VR.LT, VR.ST, VR.UT);

    /**
     * Aucun caractère de contrôle (sauf tabulation et sauts de ligne dans LT, ST, UT, et le NUL de bourrage final des
     * UI), aucun {@code <} ni {@code >} : un attribut texte ne peut pas transporter de balise vers un visualiseur.
     */
    private static void checkText(Attributes attrs) throws DicomRejectedException {
        boolean[] suspicious = new boolean[1];
        try {
            attrs.accept((item, tag, vr, value) -> {
                if (TEXT_VRS.contains(vr) && !textAllowed(vr, value)) {
                    suspicious[0] = true;
                    return false;
                }
                return true;
            }, true);
        } catch (Exception e) {
            throw new DicomRejectedException(MALFORMED);
        }
        if (suspicious[0]) {
            throw new DicomRejectedException(SUSPICIOUS_TEXT);
        }
    }

    private static boolean textAllowed(VR vr, Object value) {
        if (value instanceof byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                if (!charAllowed(vr, bytes[i] & 0xFF, i == bytes.length - 1)) return false;
            }
            return true;
        }
        if (value instanceof String s) {
            return stringAllowed(vr, s);
        }
        if (value instanceof String[] values) {
            for (String s : values) {
                if (s != null && !stringAllowed(vr, s)) return false;
            }
            return true;
        }
        return value == null || value == Value.NULL;
    }


    private static boolean stringAllowed(VR vr, String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!charAllowed(vr, s.charAt(i), i == s.length() - 1)) return false;
        }
        return true;
    }

    private static boolean charAllowed(VR vr, int c, boolean last) {
        if (c == '<' || c == '>' || c == 0x7F) return false;
        if (c >= 0x20) return true;
        if (c == 0x00) return vr == VR.UI && last;
        return MULTILINE_VRS.contains(vr) && (c == '\t' || c == '\n' || c == '\r' || c == '\f');
    }

    /** Valeur vide de dcm4che (alias local pour la lisibilité). */
    private static final class Value {
        static final Object NULL = org.dcm4che3.data.Value.NULL;
    }

    // ---------------------------------------------------------------------------------------------- garde d'en-tête

    /**
     * Garde de l'en-tête : voit chaque élément après la lecture de son en-tête et avant celle de sa valeur (donc avant
     * toute allocation), et délègue ensuite à dcm4che.
     */
    private static final class HeaderGuard implements DicomInputHandler {
        private final DicomInputStream dis;
        private final long deadline;
        private int elements;

        HeaderGuard(DicomInputStream dis, long deadline) {
            this.dis = dis;
            this.deadline = deadline;
        }

        /**
         * @param item {@code true} pour un item de séquence (seul l'en-tête {@code (FFFE,E000)} est admis, longueur
         *             indéfinie permise), {@code false} pour un élément de données
         */
        private void check(boolean item) throws GuardRejection, DeadlineExceededException {
            if (System.nanoTime() > deadline) throw new DeadlineExceededException();
            if (++elements > MAX_ELEMENTS) throw new GuardRejection(TOO_MANY_ELEMENTS);
            if (dis.level() > MAX_SEQUENCE_DEPTH) throw new GuardRejection(SEQUENCE_TOO_DEEP);
            int length = dis.length();
            VR vr = dis.vr();
            if (item && dis.tag() != Tag.Item) throw new GuardRejection(MALFORMED);
            if (length == -1) {
                if (!item && vr != VR.SQ && vr != VR.UN) throw new GuardRejection(MALFORMED);
                return;
            }
            if (item) {
                if (length < 0 || dis.getPosition() - PREFIX + length > MAX_HEADER_BYTES) {
                    throw new GuardRejection(HEADER_TOO_LARGE);
                }
                return;
            }
            if (length < 0 || (length & 1) != 0) throw new GuardRejection(MALFORMED);
            if (dis.getPosition() - PREFIX + length > MAX_HEADER_BYTES) throw new GuardRejection(HEADER_TOO_LARGE);
            int unit = switch (vr) {
                case US, SS, OW -> 2;
                case UL, SL, FL, AT, OL, OF -> 4;
                case FD, SV, UV, OD, OV -> 8;
                default -> 1;
            };
            if (length % unit != 0) throw new GuardRejection(MALFORMED);
        }

        @Override
        public void readValue(DicomInputStream in, Attributes attrs) throws IOException {
            check(false);
            in.readValue(in, attrs);
        }

        @Override
        public void readValue(DicomInputStream in, Sequence seq) throws IOException {
            check(true);
            in.readValue(in, seq);
        }

        @Override
        public void readValue(DicomInputStream in, Fragments frags) throws IOException {
            throw new GuardRejection(MALFORMED); // fragments : données encapsulées, jamais dans l'en-tête accepté
        }

        @Override
        public void startDataset(DicomInputStream in) throws IOException {
            in.startDataset(in);
        }

        @Override
        public void endDataset(DicomInputStream in) throws IOException {
            in.endDataset(in);
        }
    }

    // ---------------------------------------------------------------------------------------------- flux borné

    /** Le flux dépasse la taille annoncée. */
    private static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        LimitExceededException() { super(null, null); }
    }

    /** Délai de lecture dépassé. */
    private static final class DeadlineExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        DeadlineExceededException() { super(null, null); }
    }

    /** Données de pixels plus courtes que leur longueur. */
    private static final class TruncatedException extends IOException {
        private static final long serialVersionUID = 1L;

        TruncatedException() { super(null, null); }
    }

    /** Rejet décidé par le garde, transporté à travers dcm4che. */
    private static final class GuardRejection extends IOException {
        private static final long serialVersionUID = 1L;
        private final DicomRejectedException.Reason reason;

        GuardRejection(DicomRejectedException.Reason reason) {
            super(null, null);
            this.reason = reason;
        }
    }

    /**
     * Flux qui n'autorise jamais plus de {@code limit} octets (la taille annoncée) et vérifie l'échéance à chaque
     * lecture.
     */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private final long deadline;
        private long count;

        LimitedInputStream(InputStream in, long limit, long deadline) {
            super(in);
            this.limit = limit;
            this.deadline = deadline;
        }

        private void before() throws IOException {
            if (System.nanoTime() > deadline) throw new DeadlineExceededException();
            if (Thread.currentThread().isInterrupted()) throw new DeadlineExceededException();
        }

        @Override
        public int read() throws IOException {
            before();
            int b = super.read();
            if (b >= 0 && ++count > limit) throw new LimitExceededException();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            before();
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) throw new LimitExceededException();
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            before();
            long s = super.skip(n);
            count += s;
            if (count > limit) throw new LimitExceededException();
            return s;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

}
