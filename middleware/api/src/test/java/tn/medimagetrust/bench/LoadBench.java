package tn.medimagetrust.bench;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Banc de charge du job CD dev (objectif SRE : p95 &lt; 2 s, SEC-R02 ; aucune requête suspendue plus de 10 s).
 *
 * <p>Contre le WildFly déployé par {@code deploy.sh dev} : 500 requêtes {@code GET /api/health} et 200
 * {@code GET /api/whoami} sans jeton, 20 en parallèle (latences p50, p95, p99) ; puis une rafale de 200 requêtes
 * simultanées (fils virtuels, délai de 10 s chacune), où 429 et 503 sont des refus acceptés (pool borné), suivie d'un
 * contrôle de santé. Mémoire : la mémoire de ce processus client est indiquée pour information ; celle du serveur est
 * lue par le job CD sur l'interface de gestion de WildFly.</p>
 *
 * <p>Propriétés : {@code -Dload.api} (défaut {@code https://api.medimage.localhost}), {@code -Dload.truststore}
 * (défaut {@code /opt/medimage/tls/truststore.p12}, mot de passe {@code -Dload.truststore.password}, défaut
 * {@code changeit} : magasin de confiance public du certificat autosigné de dev, aucun secret). Rapport
 * {@code load.md} dans le dossier passé en argument. Code de sortie 1 si une réponse 5xx (hors 503 de la rafale), un
 * délai dépassé, un p95 ≥ 2 s, une santé dégradée après la rafale, ou une réponse de {@code /api/whoami} autre que
 * 401 en {@code application/problem+json} ; un 404 sur {@code /api/whoami} (endpoint du prompt 12 pas encore livré)
 * n'est qu'un avertissement.</p>
 */
public final class LoadBench {

    static final int HEALTH_REQUESTS = 500;
    static final int WHOAMI_REQUESTS = 200;
    static final int CONCURRENCY = 20;
    static final int BURST = 200;
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final long P95_LIMIT_MS = 2000;

    private LoadBench() {}

    /** Résultat d'une série de requêtes. */
    record Series(String name, List<Long> latenciesMs, int count2xx, int count4xx, int count5xx, int timeouts,
                  int errors, List<Integer> statuses) {

        long percentile(double p) {
            if (latenciesMs.isEmpty()) return -1;
            List<Long> sorted = new ArrayList<>(latenciesMs);
            Collections.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "target/bench");
        String api = System.getProperty("load.api", "https://api.medimage.localhost");
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .sslContext(sslContext())
                .build();

        Series health = run(client, "GET /api/health", URI.create(api + "/api/health"), HEALTH_REQUESTS, CONCURRENCY);
        Series whoami = run(client, "GET /api/whoami (sans jeton)", URI.create(api + "/api/whoami"),
                WHOAMI_REQUESTS, CONCURRENCY);
        Series burst = run(client, "rafale de " + BURST + " requêtes simultanées", URI.create(api + "/api/health"),
                BURST, BURST);
        HttpResponse<String> after = client.send(get(URI.create(api + "/api/health")),
                HttpResponse.BodyHandlers.ofString());
        boolean healthyAfter = after.statusCode() == 200 && after.body().replace(" ", "").contains("\"status\":\"UP\"");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Series s : List.of(health, whoami)) {
            if (s.count5xx() > 0) errors.add(s.name() + " : " + s.count5xx() + " réponse(s) 5xx");
            if (s.timeouts() > 0) errors.add(s.name() + " : " + s.timeouts() + " requête(s) au-delà de 10 s");
            if (s.errors() > 0) errors.add(s.name() + " : " + s.errors() + " erreur(s) de connexion");
            if (s.percentile(95) >= P95_LIMIT_MS) errors.add(s.name() + " : p95 = " + s.percentile(95) + " ms ≥ 2 s");
        }
        long burst5xxOther = burst.statuses().stream().filter(c -> c >= 500 && c != 503).count();
        if (burst5xxOther > 0) errors.add("rafale : " + burst5xxOther + " réponse(s) 5xx autres que 503");
        if (burst.timeouts() > 0) errors.add("rafale : " + burst.timeouts() + " requête(s) suspendue(s) plus de 10 s");
        if (burst.errors() > 0) errors.add("rafale : " + burst.errors() + " erreur(s) de connexion");
        if (!healthyAfter) errors.add("santé après la rafale : HTTP " + after.statusCode() + " " + after.body());
        // /api/whoami sans jeton : 401 en application/problem+json attendu. Un 404 (endpoint pas encore livré) n'est
        // qu'un avertissement ; toute autre réponse est un échec.
        // TODO(prompt-12) : rendre ce contrôle strict (supprimer la tolérance du 404 une fois /api/whoami livré).
        long whoami401 = whoami.statuses().stream().filter(c -> c == 401).count();
        long whoami404 = whoami.statuses().stream().filter(c -> c == 404).count();
        long whoamiOther = whoami.statuses().size() - whoami401 - whoami404;
        if (whoami404 > 0) {
            warnings.add("endpoint whoami absent (prompt 12) : " + whoami404 + " / " + whoami.statuses().size()
                    + " réponses 404");
        }
        if (whoamiOther > 0) {
            errors.add("/api/whoami sans jeton : " + whoamiOther + " réponse(s) ni 401 ni 404");
        }
        if (whoami401 > 0) {
            HttpResponse<String> probe = client.send(get(URI.create(api + "/api/whoami")),
                    HttpResponse.BodyHandlers.ofString());
            String type = probe.headers().firstValue("Content-Type").orElse("");
            if (probe.statusCode() == 401 && !type.startsWith("application/problem+json")) {
                errors.add("/api/whoami sans jeton : 401 en « " + type + " » au lieu de application/problem+json");
            }
        }

        String md = report(api, List.of(health, whoami, burst), healthyAfter, errors, warnings);
        Files.createDirectories(out);
        Files.writeString(out.resolve("load.md"), md, StandardCharsets.UTF_8);
        System.out.println(md);
        warnings.forEach(w -> System.out.println("::warning::" + w));
        errors.forEach(e -> System.out.println("::error::" + e));
        System.out.println("Rapport : " + out.resolve("load.md").toAbsolutePath());
        if (!errors.isEmpty()) System.exit(1);
    }

    static Series run(HttpClient client, String name, URI uri, int total, int concurrency) throws Exception {
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger c2 = new AtomicInteger();
        AtomicInteger c4 = new AtomicInteger();
        AtomicInteger c5 = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Semaphore permits = new Semaphore(concurrency);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                futures.add(pool.submit(() -> {
                    permits.acquireUninterruptibly();
                    long start = System.nanoTime();
                    try {
                        HttpResponse<Void> r = client.send(get(uri), HttpResponse.BodyHandlers.discarding());
                        latencies.add((System.nanoTime() - start) / 1_000_000);
                        statuses.add(r.statusCode());
                        int family = r.statusCode() / 100;
                        if (family == 2) c2.incrementAndGet();
                        else if (family == 4) c4.incrementAndGet();
                        else if (family == 5) c5.incrementAndGet();
                    } catch (java.net.http.HttpTimeoutException e) {
                        timeouts.incrementAndGet();
                    } catch (IOException e) {
                        errors.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        }
        return new Series(name, List.copyOf(latencies), c2.get(), c4.get(), c5.get(), timeouts.get(), errors.get(),
                List.copyOf(statuses));
    }

    static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
    }

    static SSLContext sslContext() throws IOException, GeneralSecurityException {
        String path = System.getProperty("load.truststore", "/opt/medimage/tls/truststore.p12");
        Path p = Path.of(path);
        if (!Files.exists(p)) return SSLContext.getDefault(); // prod : autorités de confiance du JDK
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ts.load(in, System.getProperty("load.truststore.password", "changeit").toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    static String report(String api, List<Series> series, boolean healthyAfter, List<String> errors,
                         List<String> warnings) {
        StringBuilder sb = new StringBuilder("# Banc de charge (CD dev)\n\n");
        sb.append("Cible : `").append(api).append("` ; objectif SRE : p95 < 2 s (SEC-R02), aucune requête ")
                .append("suspendue plus de 10 s.\n\n");
        sb.append("| Série | Requêtes | 2xx | 4xx | 5xx | > 10 s | Erreurs | p50 | p95 | p99 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Series s : series) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %d | %d ms | %d ms | %d ms |%n",
                    s.name(), s.statuses().size() + s.timeouts() + s.errors(), s.count2xx(), s.count4xx(),
                    s.count5xx(), s.timeouts(), s.errors(), s.percentile(50), s.percentile(95), s.percentile(99)));
        }
        sb.append("\nSanté après la rafale : ").append(healthyAfter ? "UP" : "**dégradée**").append("\n");
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        sb.append(String.format(Locale.ROOT, "%nMémoire du client (information) : %.1f Mio de tas utilisés.%n",
                used / 1048576.0));
        sb.append("\n## Verdict\n\n");
        if (errors.isEmpty()) sb.append("OK\n");
        errors.forEach(e -> sb.append("- ÉCHEC : ").append(e).append('\n'));
        warnings.forEach(w -> sb.append("- avertissement : ").append(w).append('\n'));
        return sb.toString();
    }
}
