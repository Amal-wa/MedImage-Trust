package tn.medimagetrust.dicom;

import tn.medimagetrust.crypto.CryptoService;

/** Fixture du test d'architecture : dépendance de dicom vers crypto (doit violer la règle 10). */
public final class ArchFixtureDicomToCrypto {
    private ArchFixtureDicomToCrypto() {}

    static byte[] digest(CryptoService crypto, byte[] data) {
        return crypto.digest(data);
    }
}
