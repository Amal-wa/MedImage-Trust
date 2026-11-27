package tn.medimagetrust.embedding;

/**
 * Seuil d'Otsu sur un histogramme d'entiers, déterministe.
 *
 * <p>Convention : la classe basse est {@code q ≤ T}. On maximise la variance inter-classes
 * {@code w0 · w1 · (μ0 − μ1)²} sur tous les seuils qui laissent deux classes non vides. Quand plusieurs seuils
 * atteignent exactement le maximum (cas typique : un trou entre le fond et le corps), on retient la partie entière de
 * la moyenne de leurs indices (même convention que {@code graythresh} de MATLAB) : le seuil tombe au milieu du trou,
 * loin des deux modes. Histogramme à une seule valeur occupée : {@code T} = cette valeur (tout est dans la classe
 * basse).</p>
 */
final class Otsu {
    private Otsu() {}

    /**
     * @param histogram effectifs par niveau (au moins un effectif non nul)
     * @return le seuil {@code T} (indice dans l'histogramme)
     */
    static int threshold(long[] histogram) {
        if (histogram == null || histogram.length == 0) {
            throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        }
        long total = 0;
        double sumAll = 0;
        int lastNonEmpty = -1;
        for (int i = 0; i < histogram.length; i++) {
            long h = histogram[i];
            if (h < 0) {
                throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
            }
            if (h > 0) {
                lastNonEmpty = i;
            }
            total += h;
            sumAll += (double) i * h;
        }
        if (total == 0) {
            throw new EmbeddingException(EmbeddingException.Reason.INVALID_INPUT);
        }

        long w0 = 0;
        double sum0 = 0;
        double best = -1;
        long argSum = 0;
        long argCount = 0;
        for (int t = 0; t < lastNonEmpty; t++) {
            long h = histogram[t];
            w0 += h;
            sum0 += (double) t * h;
            if (w0 == 0) {
                continue;
            }
            long w1 = total - w0;
            double mu0 = sum0 / w0;
            double mu1 = (sumAll - sum0) / w1;
            double diff = mu0 - mu1;
            double between = (double) w0 * (double) w1 * diff * diff;
            if (between > best) {
                best = between;
                argSum = t;
                argCount = 1;
            } else if (between == best) {
                argSum += t;
                argCount++;
            }
        }
        if (argCount == 0) {
            return lastNonEmpty; // une seule valeur occupée
        }
        return (int) (argSum / argCount);
    }
}
