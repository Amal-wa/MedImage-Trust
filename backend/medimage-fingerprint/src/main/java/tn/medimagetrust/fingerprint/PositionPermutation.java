package tn.medimagetrust.fingerprint;

import java.util.HashMap;
import java.util.Map;

/**
 * Positions secrètes de F : mélange de Fisher-Yates de la classe F, piloté par
 * HMAC-SHA-256 en mode compteur, graine = MIT-POS-v1 ‖ condensat du masque RONI (spec §8.2).
 * Le masque se relit sur la copie (pixel >> 2), donc les mêmes positions se retrouvent à l'analyse,
 * mais seul le détenteur de K_F peut les calculer.
 */
public final class PositionPermutation {
    private PositionPermutation() {}

    /**
     * @param classSize nombre de pixels de la classe F
     * @param count     nombre de positions à utiliser (3 072)
     * @return count rangs distincts dans [0, classSize), dans l'ordre du mélange
     */
    public static int[] select(Prf prf, byte[] maskDigest, int classSize, int count) {
        if (count > classSize) {
            throw new FingerprintException(FingerprintException.Code.CAPACITY,
                    "classe F trop petite : " + classSize + " < " + count);
        }
        HmacStream stream = new HmacStream(prf, FingerprintParams.LABEL_POS, maskDigest);
        // Fisher-Yates « partiel » creux : évite d'allouer un tableau de la taille de la classe.
        Map<Integer, Integer> swapped = new HashMap<>();
        int[] out = new int[count];
        for (int k = 0; k < count; k++) {
            int j = k + stream.nextInt(classSize - k);
            int vj = swapped.getOrDefault(j, j);
            int vk = swapped.getOrDefault(k, k);
            out[k] = vj;
            swapped.put(j, vk);
        }
        return out;
    }
}
