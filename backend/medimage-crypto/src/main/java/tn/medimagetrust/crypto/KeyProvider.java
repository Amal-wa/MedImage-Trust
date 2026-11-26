package tn.medimagetrust.crypto;

import javax.crypto.SecretKey;

/**
 * Source de l'unique secret de l'établissement : la KEK, une par version de clé ({@code kid}).
 *
 * <p>Dossier v2.4, §8.2 et figure 8.1 : la KEK est la <strong>seule</strong> clé stockée. K_tok, K_F, K_store et les
 * graines Ed25519 de SK et de K_log en sont dérivées à la demande par {@code KeyDerivation} (HKDF-SHA-256), et ne sont
 * jamais écrites.</p>
 *
 * <p>Contrat de toute implémentation, dont le futur {@code ElytronKeyProvider} du module web :</p>
 * <ul>
 *   <li>{@link #activeKid()} renvoie le kid des nouvelles émissions ; les anciens kid restent disponibles pour
 *       vérifier les anciennes copies (rotation sans coupure) ;</li>
 *   <li>{@link #kek(int)} renvoie une KEK de 32 octets ; un kid inconnu ou hors de {@code [0, 65 535]} lève une
 *       {@link IllegalArgumentException} au message fixe, qui ne contient ni le kid ni aucun octet de clé ;</li>
 *   <li>en production, la KEK est l'unique entrée par kid du credential store WildFly Elytron, ouvert au démarrage par
 *       un mot de passe masqué ({@code credential-reference}) : jamais en clair, ni en variable d'environnement, ni
 *       dans un journal ;</li>
 *   <li>aucune méthode {@code toString()} n'imprime d'octet de clé.</li>
 * </ul>
 */
public interface KeyProvider {

    /** Kid actif de l'établissement, dans {@code [0, 65 535]}. */
    int activeKid();

    /** KEK de 32 octets de ce kid. */
    SecretKey kek(int kid);
}
