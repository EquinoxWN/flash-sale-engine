package portfolio.flashsaleengine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load generator: every buyer is a virtual thread that asks the gateway for a queue token and then
 * tries to buy one unit, retrying with a short random back-off when it is shed (429 or 503).
 */
public final class Burst {
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([0-9a-f]{32})\"");

    private final HttpClient http;
    private final URI base;
    private final String sale;
    private final Semaphore concurrency;
    private final int attempts;
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong won = new AtomicLong();
    private final AtomicLong soldOut = new AtomicLong();
    private final AtomicLong queueFull = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong busy = new AtomicLong();
    private final AtomicLong gaveUp = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    /**
     * Counts from one burst.
     *
     * @param buyers buyers started
     * @param requests HTTP requests sent, retries included
     * @param admitted buyers that got a queue token
     * @param won buyers that bought a unit
     * @param soldOut 410 answers (sold out)
     * @param queueFull 429 answers because the token cap was reached
     * @param rateLimited 429 answers from the rate limit
     * @param busy 503 answers because every in-flight slot was busy
     * @param gaveUp buyers still shed after their last attempt
     * @param errors unexpected answers or I/O failures
     * @param elapsedNanos wall time of the whole burst
     * @param p50Micros median request latency
     * @param p99Micros 99th percentile request latency
     */
    public record Result(
            int buyers,
            long requests,
            long admitted,
            long won,
            long soldOut,
            long queueFull,
            long rateLimited,
            long busy,
            long gaveUp,
            long errors,
            long elapsedNanos,
            long p50Micros,
            long p99Micros) {

        /** Requests per second over the whole burst. */
        public double requestsPerSecond() {
            return elapsedNanos == 0 ? 0 : requests * 1e9 / elapsedNanos;
        }
    }

    private Burst(URI base, String sale, int concurrency, int attempts, HttpClient http) {
        this.base = base;
        this.sale = Ids.requireSaleId(sale);
        this.concurrency = new Semaphore(concurrency);
        this.attempts = attempts;
        this.http = http;
    }

    /** Runs {@code buyers} buyers against the gateway at {@code base} and waits for all of them. */
    public static Result run(URI base, String sale, int buyers, int concurrency, int attempts) {
        if (buyers < 1 || buyers > 1_000_000) {
            throw new IllegalArgumentException("buyers must be between 1 and 1000000");
        }
        if (concurrency < 1 || concurrency > 10_000) {
            throw new IllegalArgumentException("concurrency must be between 1 and 10000");
        }
        if (attempts < 1 || attempts > 100) {
            throw new IllegalArgumentException("attempts must be between 1 and 100");
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                HttpClient http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .executor(pool)
                        .build()) {
            Burst b = new Burst(base, sale, concurrency, attempts, http);
            long start = System.nanoTime();
            try (ExecutorService buyersPool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < buyers; i++) {
                    String user = "buyer-" + i;
                    buyersPool.submit(() -> b.buyer(user));
                }
            }
            return b.result(buyers, System.nanoTime() - start);
        }
    }

    /** One buyer: token, then purchase. */
    private void buyer(String user) {
        Answer t = callWithRetry(user, "/sales/" + sale + "/token", null);
        if (t == null) {
            return;
        }
        if (t.status() != 200) {
            countRefusal(t);
            return;
        }
        Matcher m = TOKEN.matcher(t.body());
        if (!m.find()) {
            errors.incrementAndGet();
            return;
        }
        admitted.incrementAndGet();
        Answer p = callWithRetry(user, "/sales/" + sale + "/buy", m.group(1));
        if (p == null) {
            return;
        }
        if (p.status() == 200) {
            won.incrementAndGet();
        } else {
            countRefusal(p);
        }
    }

    /** Counts a final non-200 answer by its reason. */
    private void countRefusal(Answer a) {
        if (a.status() == 410) {
            soldOut.incrementAndGet();
        } else if (a.status() == 429 && a.body().contains("queue_full")) {
            queueFull.incrementAndGet();
        } else {
            errors.incrementAndGet();
        }
    }

    /** POSTs to the gateway, retrying shed answers; null when the buyer gives up or I/O fails. */
    private Answer callWithRetry(String user, String path, String token) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Answer a = post(user, path, token);
            if (a == null) {
                errors.incrementAndGet();
                return null;
            }
            // Only answers that prove the request never reached Valkey are retried: a 503 for a
            // store failure may come after the script ran, so a retry could buy a second unit.
            boolean rate = a.status() == 429 && a.body().contains("rate_limited");
            boolean full = a.status() == 503 && a.body().contains("\"busy\"");
            if (!rate && !full) {
                return a;
            }
            (rate ? rateLimited : busy).incrementAndGet();
            if (attempt < attempts) {
                sleepMillis(ThreadLocalRandom.current().nextInt(5, 50) * attempt);
            }
        }
        gaveUp.incrementAndGet();
        return null;
    }

    /** One POST inside a concurrency slot; null on I/O failure. */
    private Answer post(String user, String path, String token) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header(Gateway.USER_HEADER, user)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            rb.header(Gateway.TOKEN_HEADER, token);
        }
        HttpRequest req = rb.build();
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            requests.incrementAndGet();
            latencies.add((System.nanoTime() - t0) / 1_000);
            return new Answer(r.statusCode(), r.body());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            concurrency.release();
        }
    }

    /** Collects the counters and latency percentiles. */
    private Result result(int buyers, long elapsed) {
        long[] lat = latencies.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lat);
        return new Result(
                buyers,
                requests.get(),
                admitted.get(),
                won.get(),
                soldOut.get(),
                queueFull.get(),
                rateLimited.get(),
                busy.get(),
                gaveUp.get(),
                errors.get(),
                elapsed,
                percentile(lat, 0.50),
                percentile(lat, 0.99));
    }

    /** Nearest-rank percentile of a sorted array, 0 when empty. */
    static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }

    /** Sleeps, keeping the interrupt flag. */
    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Lines that summarize a burst and its audit, for the CLI and the proof images. */
    public static List<String> describe(Result r, AuditReport audit) {
        List<String> out = new ArrayList<>();
        out.add(String.format(
                "requests: %d in %.2f s (%.0f req/s), latency p50 %.1f ms, p99 %.1f ms",
                r.requests(), r.elapsedNanos() / 1e9, r.requestsPerSecond(), r.p50Micros() / 1e3, r.p99Micros() / 1e3));
        out.add(String.format(
                "shed: rate_limited=%d busy=%d queue_full=%d gave_up=%d errors=%d",
                r.rateLimited(), r.busy(), r.queueFull(), r.gaveUp(), r.errors()));
        out.add(String.format(
                "buyers: %d, tokens issued: %d, won: %d, sold_out answers: %d",
                r.buyers(), r.admitted(), r.won(), r.soldOut()));
        out.add(String.format(
                "audit: units=%d sold=%d in_orders=%d remaining=%d winners=%d oversold=%d -> %s",
                audit.units(),
                audit.sold(),
                audit.unitsInOrders(),
                audit.remaining(),
                audit.winners(),
                audit.oversold(),
                audit.ok() ? "OK" : "FAILED " + audit.violations()));
        return out;
    }

    /** Status and body of one answer. */
    private record Answer(int status, String body) {}
}
