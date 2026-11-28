package tn.medimagetrust.dicom;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import tn.medimagetrust.embedding.EmbeddingResult;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Écriture d'une copie marquée (SEC-D07, SEC-M03).
 *
 * <p>Le fichier est <strong>reconstruit</strong> à partir des attributs analysés par {@link DicomReader} et des pixels
 * marqués ; aucun octet brut du fichier de l'utilisateur n'est recopié :</p>
 * <ul>
 *   <li>préambule de 128 octets nuls, puis {@code DICM} et des méta-informations régénérées ;</li>
 *   <li>syntaxe de sortie toujours Explicit VR Little Endian ;</li>
 *   <li>éléments privés (groupe impair) retirés à tous les niveaux, ainsi que les éléments de représentation {@code UN}
 *       (contenu non interprétable, donc non contrôlable) et ceux des groupes {@code 0000} à {@code 0007} ;</li>
 *   <li>{@code Smallest/LargestImagePixelValue} et {@code Smallest/LargestPixelValueInSeries} retirés : les valeurs
 *       extrêmes changent avec le marquage (attributs de type 3) ;</li>
 *   <li>nouveau {@code SOPInstanceUID} {@code 2.25.<entier d'un UUID aléatoire>} ;</li>
 *   <li>rien après les données de pixels : les octets de fin du fichier d'origine ne sont jamais lus.</li>
 * </ul>
 *
 * <p>En mode non signé, tous les attributs d'interprétation (dont {@code BitsAllocated}, {@code BitsStored},
 * {@code HighBit}, {@code PixelRepresentation}, la photométrie, le rescale, le fenêtrage) sont identiques. En mode signé
 * ({@link EmbeddingResult#signedOutput()}), seuls {@code PixelRepresentation = 1}, {@code BitsAllocated = 16},
 * {@code BitsStored = 16} et {@code HighBit = 15} changent (dérogation SEC-M03 c) : un original 8 bits est réécrit sur
 * 2 octets par pixel, et {@code PixelPaddingValue} passe en {@code SS} avec la même valeur.</p>
 *
 * <p>Valeur écrite : valeur de l'embedding moins {@link DicomImage#storedValueOffset()}, soit exactement la valeur
 * stockée d'origine pour tout pixel non modifié.</p>
 */
public final class DicomWriter {
    private static final int CHUNK = 64 * 1024;

    /** Attributs de valeurs extrêmes, retirés de la copie. */
    static final Set<Integer> EXTREME_VALUE_TAGS = Set.of(Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue,
            Tag.SmallestPixelValueInSeries, Tag.LargestPixelValueInSeries);

    /**
     * Écrit la copie marquée.
     *
     * @param original fichier lu par {@link DicomReader}, marquable
     * @param marked   résultat de l'émission sur {@code original.image()}
     * @param out      flux de sortie (non fermé)
     * @return le nouveau {@code SOPInstanceUID} de la copie
     * @throws IllegalArgumentException original non marquable, ou résultat qui ne correspond pas à l'original
     * @throws IOException              erreur d'écriture
     */
    public String writeCopy(DicomImage original, EmbeddingResult marked, OutputStream out) throws IOException {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(marked, "copie marquée");
        Objects.requireNonNull(out, "flux de sortie");
        if (!original.markability().markable()) {
            throw new IllegalArgumentException("original non marquable");
        }
        PixelImage image = marked.image();
        if (image.rows() != original.rows() || image.cols() != original.cols()
                || marked.bitsStored() != original.bitsStored()) {
            throw new IllegalArgumentException("copie marquée sans rapport avec l'original");
        }

        Attributes ds = sanitizedCopy(original.dataset());
        String sopInstanceUid = newUid();
        ds.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid);
        boolean signed = marked.signedOutput();
        int bitsAllocated = original.bitsAllocated();
        boolean outputSigned16 = false;
        if (signed) {
            EmbeddingResult.PixelAttributes a = marked.pixelAttributes().orElseThrow();
            ds.setInt(Tag.PixelRepresentation, VR.US, a.pixelRepresentation());
            ds.setInt(Tag.BitsAllocated, VR.US, a.bitsAllocated());
            ds.setInt(Tag.BitsStored, VR.US, a.bitsStored());
            ds.setInt(Tag.HighBit, VR.US, a.highBit());
            original.pixelPaddingValue().ifPresent(v -> ds.setInt(Tag.PixelPaddingValue, VR.SS, v));
            bitsAllocated = a.bitsAllocated();
            outputSigned16 = true;
        }
        Attributes fmi = ds.createFileMetaInformation(UID.ExplicitVRLittleEndian);

        int offset = original.storedValueOffset();
        int bytesPerPixel = bitsAllocated / 8;
        long count = (long) image.pixelCount() * bytesPerPixel;
        int length = (int) (count + (count & 1));
        try (DicomOutputStream dos = new DicomOutputStream(new NonClosing(out), UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(fmi, ds);
            dos.writeHeader(Tag.PixelData, bytesPerPixel == 2 ? VR.OW : VR.OB, length);
            writePixels(dos, image, offset, bytesPerPixel, outputSigned16);
            if ((count & 1) == 1) {
                dos.write(0);
            }
            dos.finish();
        }
        return sopInstanceUid;
    }

    private static void writePixels(OutputStream dos, PixelImage image, int offset, int bytesPerPixel,
                                    boolean signed16) throws IOException {
        ByteBuffer chunk = ByteBuffer.allocate(CHUNK);
        int n = image.pixelCount();
        for (int i = 0; i < n; i++) {
            int stored = image.pixel(i) - offset;
            if (bytesPerPixel == 2) {
                if (signed16 ? stored < Short.MIN_VALUE || stored > Short.MAX_VALUE : stored < -0x8000 || stored > 0xFFFF) {
                    throw new IllegalStateException("valeur non représentable"); // exclu par le contrôle final
                }
                chunk.put((byte) stored);
                chunk.put((byte) (stored >> 8));
            } else {
                chunk.put((byte) stored);
            }
            if (chunk.remaining() < 2) {
                dos.write(chunk.array(), 0, chunk.position());
                chunk.clear();
            }
        }
        dos.write(chunk.array(), 0, chunk.position());
    }

    /** {@code 2.25.<entier non signé des 128 bits d'un UUID aléatoire>} (PS3.5 B.2). */
    static String newUid() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(uuid.getMostSignificantBits());
        b.putLong(uuid.getLeastSignificantBits());
        return "2.25." + new BigInteger(1, b.array());
    }

    /**
     * Copie filtrée, à tous les niveaux de séquence : ni élément privé, ni {@code UN}, ni groupes {@code 0000} à
     * {@code 0007}, ni données de pixels ou au-delà, ni valeurs extrêmes. L'original n'est pas modifié.
     */
    static Attributes sanitizedCopy(Attributes src) {
        Attributes dst = new Attributes(src.bigEndian(), src.size());
        for (int tag : src.tags()) {
            int group = tag >>> 16;
            VR vr = src.getVR(tag);
            if ((group & 1) == 1 || group < 0x0008 || Integer.compareUnsigned(tag, Tag.PixelData) >= 0
                    || vr == null || vr == VR.UN || EXTREME_VALUE_TAGS.contains(tag)) {
                continue;
            }
            if (vr == VR.SQ) {
                Sequence items = src.getSequence(tag);
                Sequence copy = dst.newSequence(tag, items == null ? 0 : items.size());
                if (items != null) {
                    for (Attributes item : items) {
                        copy.add(sanitizedCopy(item));
                    }
                }
            } else {
                dst.setValue(tag, vr, src.getValue(tag));
            }
        }
        return dst;
    }

    /** Laisse le flux de l'appelant ouvert. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) { super(out); }

        @Override
        public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }

        @Override
        public void close() throws IOException { out.flush(); }
    }
}
