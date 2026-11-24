package tn.medimagetrust.fingerprint;

import tn.medimagetrust.crypto.CryptoService;

import java.util.List;
import java.util.Optional;

/**
 * Façade du module, appelée par le module web (F09 émission, F08/CU08 analyse).
 * <p>
 * Un service est lié à UN kid (version de K_F). À l'émission on utilise le kid actif et on le
 * <b>enregistre avec l'émission</b> (registre SEC06) ; pour analyser une ancienne copie on recrée le
 * service avec le kid enregistré : {@code FingerprintService.forKid(crypto, kidDeLEmission)}.
 * <p>
 * Le module web doit aussi refuser le 65e destinataire d'une image et réserver l'analyse au
 * responsable sécurité (annotation, journalisation, aucune PII dans les logs).
 */
public final class FingerprintService {
    /** Données à confier à l'émission : kid à enregistrer, bits à écrire dans F, engagement à signer dans W. */
    public record EmissionFingerprint(String kid, boolean[] bits, byte[] fCommit) {}

    private final String kid;
    private final TardosCodeGenerator generator;
    private final FingerprintEmbedder embedder;
    private final ColluderAnalyzer analyzer;

    /** Branchement de production : K_F issue de CryptoService pour ce kid. */
    public static FingerprintService forKid(CryptoService crypto, String kid) {
        return new FingerprintService(new CryptoPrf(crypto, kid), kid);
    }

    /** Branchement générique (tests unitaires avec {@link HmacSha256Prf}). */
    public FingerprintService(Prf prf) {
        this(prf, "test");
    }

    private FingerprintService(Prf prf, String kid) {
        this.kid = kid;
        this.generator = new TardosCodeGenerator(prf);
        this.embedder = new FingerprintEmbedder(prf);
        this.analyzer = new ColluderAnalyzer(generator);
    }

    public String kid() { return kid; }

    /** CU07 étape 1 : générer le code du destinataire et l'engagement. */
    public EmissionFingerprint prepare(byte[] imageId, byte[] emissionId, String recipientId) {
        byte[] symbols = generator.symbols(imageId, recipientId);
        return new EmissionFingerprint(kid, TardosCodeGenerator.expand(symbols),
                generator.commit(emissionId, recipientId));
    }

    /** CU07 étape 2 : insérer dans la partition F. Échec : exception, émission annulée. */
    public void embed(FPartition f, byte[] maskDigest, EmissionFingerprint fp) {
        if (!fp.kid().equals(kid)) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "kid de l'émission différent du service");
        }
        embedder.embed(f, maskDigest, fp.bits());
    }

    /** CU08 : analyser la partition F d'une copie suspecte (service créé avec le kid de l'émission). */
    public AttributionReport analyze(byte[] imageId, FPartition suspectF, byte[] maskDigest,
                                     List<String> candidates, Optional<String> wRecipient) {
        ExtractedWord w = embedder.extract(suspectF, maskDigest, FingerprintEmbedder.VotePolicy.MAJORITY);
        return analyzer.analyze(imageId, w, candidates, wRecipient);
    }
}
