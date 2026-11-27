package tn.medimagetrust.fingerprint;

import tn.medimagetrust.crypto.CryptoService;
import tn.medimagetrust.embedding.FBlockChannel;

import java.util.List;
import java.util.Optional;

/**
 * Façade du module, appelée par le module web (F09 émission, F08/CU08 analyse).
 * <p>
 * Un service est lié à UN kid (version de K_F). À l'émission on utilise le kid actif et on le
 * <b>enregistre avec l'émission</b> (registre SEC06) ; pour analyser une ancienne copie on recrée le
 * service avec le kid enregistré : {@code FingerprintService.forKid(crypto, kidDeLEmission)}.
 * <p>
 * Le canal F vient du module embedding : à l'émission {@code EmbeddingSession.fChannel()}, à l'analyse
 * {@code FBlockReader.of(copieSuspecte, partitionsDeLEmission)}. Le {@code maskDigest} est celui de
 * {@code tn.medimagetrust.embedding.Digests.maskDigest}.
 * <p>
 * Le module web doit aussi refuser le 65e destinataire d'une image et réserver l'analyse au
 * responsable sécurité (annotation, journalisation, aucune PII dans les logs).
 */
public final class FingerprintService {
    /** Données à confier à l'émission : kid à enregistrer, bits à écrire dans F, engagement à signer dans W. */
    public record EmissionFingerprint(int kid, boolean[] bits, byte[] fCommit) {}

    /** Kid du branchement générique (PRF de test, sans module crypto) : hors de [0, 65 535], jamais un vrai kid. */
    public static final int NO_KID = -1;

    private final int kid;
    private final TardosCodeGenerator generator;
    private final FingerprintEmbedder embedder;
    private final ColluderAnalyzer analyzer;

    /** Branchement de production : K_F issue de CryptoService pour ce kid (entier sur 16 bits). */
    public static FingerprintService forKid(CryptoService crypto, int kid) {
        return new FingerprintService(new CryptoPrf(crypto, kid), kid);
    }

    /** Branchement générique (tests unitaires avec {@link HmacSha256Prf}). */
    public FingerprintService(Prf prf) {
        this(prf, NO_KID);
    }

    private FingerprintService(Prf prf, int kid) {
        this.kid = kid;
        this.generator = new TardosCodeGenerator(prf);
        this.embedder = new FingerprintEmbedder(prf);
        this.analyzer = new ColluderAnalyzer(generator);
    }

    public int kid() { return kid; }

    /** CU07 étape 1 : générer le code du destinataire et l'engagement. */
    public EmissionFingerprint prepare(byte[] imageId, byte[] emissionId, String recipientId) {
        byte[] symbols = generator.symbols(imageId, recipientId);
        return new EmissionFingerprint(kid, TardosCodeGenerator.expand(symbols),
                generator.commit(emissionId, recipientId));
    }

    /** CU07 étape 2 : insérer dans la classe F (128 blocs secrets). Échec : exception, émission annulée. */
    public void embed(FBlockChannel f, byte[] maskDigest, EmissionFingerprint fp) {
        if (fp.kid() != kid) {
            throw new FingerprintException(FingerprintException.Code.INVALID_INPUT, "kid de l'émission différent du service");
        }
        embedder.embed(f, maskDigest, fp.bits());
    }

    /** CU08 : analyser la classe F d'une copie suspecte (service créé avec le kid de l'émission). */
    public AttributionReport analyze(byte[] imageId, FBlockChannel suspect, byte[] maskDigest,
                                     List<String> candidates, Optional<String> wRecipient) {
        ExtractedWord w = embedder.extract(suspect, maskDigest);
        return analyzer.analyze(imageId, w, candidates, wRecipient);
    }
}
