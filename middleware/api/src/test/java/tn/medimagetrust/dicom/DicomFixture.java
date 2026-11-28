package tn.medimagetrust.dicom;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tn.medimagetrust.embedding.PixelImage;

/**
 * Fabrique de fichiers DICOM de test, octet par octet (Explicit ou Implicit VR Little Endian), pour contrôler exactement
 * ce que voit le lecteur : préambule, signature, méta-informations, éléments, longueurs, séquences, données de pixels,
 * octets de fin. Aucune donnée réelle : UID de test sous la racine {@code 2.25}.
 */
final class DicomFixture {
    static final String EXPLICIT_LE = "1.2.840.10008.1.2.1";
    static final String IMPLICIT_LE = "1.2.840.10008.1.2";
    static final String CT_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    static final String TEST_SOP_INSTANCE = "2.25.1234567890123456789";

    /** Représentations à en-tête long (réservé de 2 octets puis longueur sur 4) en Explicit VR. */
    private static final Set<String> LONG_VRS = Set.of("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "SV", "UC", "UN",
            "UR", "UT", "UV");

    byte[] preamble = new byte[128];
    boolean dicm = true;
    String transferSyntax = EXPLICIT_LE;
    /** Syntaxe d'encodage réelle du jeu de données (par défaut, celle annoncée). */
    Boolean explicitOverride;
    final Map<Long, byte[]> elements = new TreeMap<>();
    byte[] pixelData;
    String pixelVr = "OW";
    /** Longueur annoncée de PixelData (par défaut, celle des octets fournis). */
    Long pixelLength;
    boolean pixelDataPresent = true;
    byte[] trailing = new byte[0];
    /** Éléments supplémentaires des méta-informations (groupe 0002), déjà encodés en Explicit VR. */
    byte[] extraFmi = new byte[0];

    /** Fichier valide de base : CT 12 bits non signé, MONOCHROME2. */
    static DicomFixture ct(int rows, int cols) {
        DicomFixture f = new DicomFixture();
        f.str(0x00080016, "UI", CT_IMAGE_STORAGE);
        f.str(0x00080018, "UI", TEST_SOP_INSTANCE);
        f.str(0x00080060, "CS", "CT");
        f.str(0x00080070, "LO", "FANTOME");
        f.str(0x0020000D, "UI", "2.25.11");
        f.str(0x0020000E, "UI", "2.25.12");
        f.us(0x00280002, 1);
        f.str(0x00280004, "CS", "MONOCHROME2");
        f.us(0x00280010, rows);
        f.us(0x00280011, cols);
        f.us(0x00280100, 16);
        f.us(0x00280101, 12);
        f.us(0x00280102, 11);
        f.us(0x00280103, 0);
        f.str(0x00281050, "DS", "40");
        f.str(0x00281051, "DS", "400");
        f.str(0x00281052, "DS", "-1024");
        f.str(0x00281053, "DS", "1");
        f.pixelData = new byte[rows * cols * 2];
        return f;
    }

    /** Fichier valide construit à partir d'une image (valeurs stockées = pixels − décalage). */
    static DicomFixture of(PixelImage image, int bitsAllocated, int pixelRepresentation) {
        DicomFixture f = ct(image.rows(), image.cols());
        int b = image.bitsStored();
        f.us(0x00280100, bitsAllocated);
        f.us(0x00280101, b);
        f.us(0x00280102, b - 1);
        f.us(0x00280103, pixelRepresentation);
        f.str(0x00280004, "CS", image.photometric().name());
        int offset = pixelRepresentation == 1 ? 1 << (b - 1) : 0;
        int n = image.pixelCount();
        byte[] px = new byte[n * (bitsAllocated / 8) + ((n * (bitsAllocated / 8)) & 1)];
        for (int i = 0; i < n; i++) {
            int v = image.pixel(i) - offset;
            if (bitsAllocated == 16) {
                px[2 * i] = (byte) v;
                px[2 * i + 1] = (byte) (v >> 8);
            } else {
                px[i] = (byte) v;
            }
        }
        f.pixelData = px;
        f.pixelVr = bitsAllocated == 8 ? "OB" : "OW";
        if ((n & 1) == 1 && bitsAllocated == 8) {
            f.pixelLength = (long) px.length;
        }
        return f;
    }

    // ---------------------------------------------------------------------------------------------- éléments

    private static long key(int tag) { return tag & 0xFFFFFFFFL; }

    DicomFixture str(int tag, String vr, String value) {
        byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
        if ((v.length & 1) == 1) {
            byte[] p = new byte[v.length + 1];
            System.arraycopy(v, 0, p, 0, v.length);
            p[v.length] = (byte) (vr.equals("UI") ? 0 : ' ');
            v = p;
        }
        return raw(tag, vr, v);
    }

    DicomFixture us(int tag, int value) {
        return raw(tag, "US", new byte[] {(byte) value, (byte) (value >> 8)});
    }

    DicomFixture ss(int tag, int value) {
        return raw(tag, "SS", new byte[] {(byte) value, (byte) (value >> 8)});
    }

    DicomFixture raw(int tag, String vr, byte[] value) {
        elements.put(key(tag), element(tag, vr, value, value.length));
        return this;
    }

    /** Élément dont la longueur annoncée diffère de sa valeur. */
    DicomFixture rawWithLength(int tag, String vr, byte[] value, long declaredLength) {
        elements.put(key(tag), element(tag, vr, value, declaredLength));
        return this;
    }

    /** Élément déjà encodé (séquences, structures piégées). */
    DicomFixture encoded(int tag, byte[] bytes) {
        elements.put(key(tag), bytes);
        return this;
    }

    DicomFixture remove(int tag) {
        elements.remove(key(tag));
        return this;
    }

    boolean explicit() {
        return explicitOverride != null ? explicitOverride : !IMPLICIT_LE.equals(transferSyntax);
    }

    byte[] element(int tag, String vr, byte[] value, long length) {
        return element(tag, vr, value, length, explicit());
    }

    static byte[] element(int tag, String vr, byte[] value, long length, boolean explicit) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        header(o, tag, vr, length, explicit);
        o.writeBytes(value);
        return o.toByteArray();
    }

    static void header(ByteArrayOutputStream o, int tag, String vr, long length, boolean explicit) {
        le16(o, tag >>> 16);
        le16(o, tag & 0xFFFF);
        if (explicit && vr != null) {
            o.writeBytes(vr.getBytes(StandardCharsets.US_ASCII));
            if (LONG_VRS.contains(vr)) {
                le16(o, 0);
                le32(o, length);
            } else {
                le16(o, (int) length);
            }
        } else {
            le32(o, length);
        }
    }

    /**
     * Séquence de longueur indéfinie dont chaque item (longueur indéfinie) contient {@code inner} ; les séquences
     * imbriquées s'obtiennent en passant une séquence comme contenu.
     */
    byte[] sequence(int tag, byte[]... items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        header(o, tag, "SQ", 0xFFFFFFFFL, explicit());
        for (byte[] item : items) {
            le16(o, 0xFFFE);
            le16(o, 0xE000);
            le32(o, 0xFFFFFFFFL);
            o.writeBytes(item);
            le16(o, 0xFFFE);
            le16(o, 0xE00D);
            le32(o, 0);
        }
        le16(o, 0xFFFE);
        le16(o, 0xE0DD);
        le32(o, 0);
        return o.toByteArray();
    }

    /** {@code depth} séquences imbriquées (profondeur 1 : une séquence au niveau du jeu de données). */
    byte[] nested(int depth) {
        byte[] inner = element(0x00080100, "SH", "T1".getBytes(StandardCharsets.US_ASCII), 2);
        for (int d = 0; d < depth; d++) {
            inner = sequence(0x00081110, inner);
        }
        return inner;
    }

    // ---------------------------------------------------------------------------------------------- fichier

    byte[] fileMetaInformation() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(element(0x00020001, "OB", new byte[] {0, 1}, 2, true));
        body.writeBytes(uiExplicit(0x00020002, CT_IMAGE_STORAGE));
        body.writeBytes(uiExplicit(0x00020003, TEST_SOP_INSTANCE));
        body.writeBytes(uiExplicit(0x00020010, transferSyntax));
        body.writeBytes(uiExplicit(0x00020012, "2.25.999"));
        body.writeBytes(extraFmi);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] len = new byte[4];
        int n = body.size();
        len[0] = (byte) n;
        len[1] = (byte) (n >> 8);
        len[2] = (byte) (n >> 16);
        len[3] = (byte) (n >> 24);
        o.writeBytes(element(0x00020000, "UL", len, 4, true));
        o.writeBytes(body.toByteArray());
        return o.toByteArray();
    }

    private static byte[] uiExplicit(int tag, String uid) {
        byte[] v = uid.getBytes(StandardCharsets.US_ASCII);
        if ((v.length & 1) == 1) {
            byte[] p = new byte[v.length + 1];
            System.arraycopy(v, 0, p, 0, v.length);
            v = p;
        }
        return element(tag, "UI", v, v.length, true);
    }

    byte[] bytes() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(preamble);
        if (dicm) o.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(fileMetaInformation());
        for (byte[] e : elements.values()) o.writeBytes(e);
        if (pixelDataPresent) {
            long len = pixelLength != null ? pixelLength : pixelData.length;
            header(o, 0x7FE00010, pixelVr, len, explicit());
            o.writeBytes(pixelData);
        }
        o.writeBytes(trailing);
        return o.toByteArray();
    }

    static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    static void le32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 24) & 0xFF));
    }
}
