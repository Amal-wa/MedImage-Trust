package tn.medimagetrust.archfixture;

import org.dcm4che3.data.Attributes;

/** Fixture du test d'architecture : usage de dcm4che hors du package dicom (doit violer la règle 12). */
public final class Dcm4cheOutsideDicom {
    private Dcm4cheOutsideDicom() {}

    static int size(Attributes attributes) {
        return attributes.size();
    }
}
