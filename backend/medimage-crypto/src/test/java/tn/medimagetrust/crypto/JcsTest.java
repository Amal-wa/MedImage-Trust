package tn.medimagetrust.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JcsTest {

    private static String c(String json) {
        return new String(Jcs.canonicalize(json), StandardCharsets.UTF_8);
    }

    @Test
    void clesTrieesParOrdreAlphabetique() {
        assertEquals("{\"a\":2,\"b\":1}", c("{\"b\":1,\"a\":2}"));
    }

    @Test
    void ordreParUnitesUtf16MajusculesAvantMinuscules() {
        assertEquals("{\"B\":2,\"a\":3,\"b\":1}", c("{\"b\":1,\"B\":2,\"a\":3}"));
    }

    @Test
    void espacesSupprimes() {
        assertEquals("{\"a\":1,\"b\":[1,2]}", c("{ \"a\" : 1 ,\n \"b\" : [ 1 , 2 ] }"));
    }

    @Test
    void objetsImbriquesTriesEtTableauxConservesDansLOrdre() {
        assertEquals(
            "{\"a\":{\"x\":null,\"y\":true},\"z\":[3,1,2]}",
            c("{\"z\":[3,1,2],\"a\":{\"y\":true,\"x\":null}}"));
    }

    @Test
    void caracteresNonAsciiRestentEnUtf8() {
        byte[] attendu = "{\"nom\":\"é\"}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(attendu, Jcs.canonicalize("{\"nom\":\"\\u00e9\"}"));
        assertArrayEquals(attendu, Jcs.canonicalize("{\"nom\":\"é\"}"));
    }

    @Test
    void caracteresDeControleEchappes() {
        assertEquals("{\"a\":\"\\u0001\"}", c("{\"a\":\"\\u0001\"}"));
        assertEquals("{\"a\":\"\\n\"}", c("{\"a\":\"\\u000a\"}"));
    }

    @Test
    void zeroNegatifDevientZero() {
        assertEquals("{\"a\":0}", c("{\"a\":-0}"));
    }

    @Test
    void objetEtTableauVidesConserves() {
        assertEquals("{\"a\":{},\"b\":[]}", c("{\"b\":[],\"a\":{}}"));
    }

    @Test
    void cleDupliqueeRejetee() {
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("{\"a\":1,\"a\":2}"));
    }

    @Test
    void nombreDecimalRejete() {
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("{\"a\":1.5}"));
    }

    @Test
    void jsonInvalideRejete() {
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("{\"a\":1} x"));
    }
}
