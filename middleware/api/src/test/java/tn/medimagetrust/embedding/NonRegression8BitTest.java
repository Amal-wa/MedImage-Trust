package tn.medimagetrust.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Non-régression 8 bits : pour B = 8, Δ(8) = 12 et aucune sortie ne change. Les empreintes SHA-256 ci-dessous ont été
 * relevées sur le code de {@code main} (commit 5b783b0), avant l'introduction de Δ(B) et du mode signé : pixels marqués
 * en ordre raster, chaque valeur sur 2 octets gros-boutistes.
 */
@DisplayName("Non-régression 8 bits : copies marquées identiques octet par octet à main")
class NonRegression8BitTest {

    private static final long TEST_SEED_NONREG = 0x4E4F_4E52_4547L;

    /** Empreinte des pixels : chaque valeur stockée sur 2 octets, ordre raster. */
    static String sha256(PixelImage img) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(2 * img.pixelCount());
            for (char c : img.pixels()) {
                buf.putChar(c);
            }
            return HexFormat.of().formatHex(md.digest(buf.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String marked(Phantoms.Phantom p, long seed) {
        return sha256(Markings.markRandom(p.image(), new Random(seed)).marked());
    }

    @Test
    @DisplayName("Fantôme normal 8 bits : empreinte de main")
    void normal8() {
        assertEquals("9fbe1b3f08e073f4d2c8f77b6adcbe0c0108e08d0fb731a7c6fb4bf49fdacdc2", marked(Phantoms.normal8(), TEST_SEED_NONREG));
    }

    @Test
    @DisplayName("Fantôme normal 8 bits, autre tirage F/S/W : empreinte de main")
    void normal8OtherDraw() {
        assertEquals("4624535dc0f179eeae0e60e2201e92e6433a7ca1c7f0832f78358f0c2d4b2d7b", marked(Phantoms.normal8(), TEST_SEED_NONREG + 1));
    }

    @Test
    @DisplayName("Fond clair 8 bits (MONOCHROME1) : empreinte de main")
    void lightBackground8() {
        assertEquals("b4f6f0cd9d04c518fc8864dce1483166342b59748aa435904bb6ab3405cb9ed5", marked(Phantoms.lightBackground8(), TEST_SEED_NONREG));
    }
}
