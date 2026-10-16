package fr.enimaloc.blinder.link.preview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewFetcherTest {

    static final String PAGE = """
            <html><head><meta property="og:title" content="Fake site">
            <meta property="og:description" content="Fake description"></head></html>""";

    HttpServer server;
    String base;
    int port;

    final PreviewFetcher permissive = new PreviewFetcher(address -> true);
    final PreviewFetcher only127001 = new PreviewFetcher(address -> address.getHostAddress().equals("127.0.0.1"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        server.createContext("/page", ex -> respond(ex, 200, "text/html; charset=utf-8", PAGE));
        server.createContext("/plain", ex -> respond(ex, 200, "text/plain", "hello"));
        server.createContext("/missing", ex -> respond(ex, 404, "text/html", "nope"));
        server.createContext("/redirect-ok", ex -> redirect(ex, "/page"));
        server.createContext("/redirect-refused", ex -> redirect(ex, "http://127.0.0.2:" + port + "/page"));
        server.createContext("/loop", ex -> redirect(ex, "/loop"));
        server.createContext("/big", ex -> respond(ex, 200, "text/html",
                "<html><head><meta property=\"og:title\" content=\"Big\"></head><body>" + "x".repeat(1_000_000)));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "text/html", PAGE);
        });
        server.createContext("/endless", ex -> endless(ex, 200, "text/html",
                "<html><head><meta property=\"og:title\" content=\"Endless\"></head><body>", null));
        server.createContext("/redirect-endless", ex -> endless(ex, 302, "text/html", "x", "/page"));
        server.createContext("/plain-endless", ex -> endless(ex, 200, "text/plain", "x", null));
        server.createContext("/drip", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write("<html><head>".getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (true) {
                    Thread.sleep(2_000);
                    out.write('x');
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // client gone
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @Test
    void fetchAfterCloseReturnsEmptyWithoutThrowing() {
        PreviewFetcher closed = new PreviewFetcher(address -> true);
        closed.close();
        assertThat(closed.fetch(base + "/page")).isEmpty();
    }

    @AfterEach
    void stop() {
        permissive.close();
        only127001.close();
        server.stop(0);
    }

    static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // client stopped reading (body cap)
        }
    }

    static void endless(HttpExchange ex, int status, String type, String prefix, String location)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (location != null) {
            ex.getResponseHeaders().set("Location", location);
        }
        ex.sendResponseHeaders(status, 0);
        byte[] chunk = "x".repeat(16384).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(prefix.getBytes(StandardCharsets.UTF_8));
            while (true) {
                out.write(chunk);
                out.flush();
            }
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    @Test
    void extractsFromHtml() {
        assertThat(permissive.fetch(base + "/page"))
                .contains(new PreviewData("Fake site", "Fake description", null));
    }

    @Test
    void publicPolicyRefusesLoopback() {
        assertThat(new PreviewFetcher(new PublicAddressPolicy()).fetch(base + "/page")).isEmpty();
    }

    @Test
    void followsAllowedRedirect() {
        assertThat(permissive.fetch(base + "/redirect-ok")).isPresent();
    }

    @Test
    void revalidatesEveryRedirect() {
        assertThat(only127001.fetch(base + "/page")).isPresent();
        assertThat(only127001.fetch(base + "/redirect-refused")).isEmpty();
    }

    @Test
    void stopsAfterThreeRedirects() {
        assertThat(permissive.fetch(base + "/loop")).isEmpty();
    }

    @Test
    void ignoresNonHtmlAndErrors() {
        assertThat(permissive.fetch(base + "/plain")).isEmpty();
        assertThat(permissive.fetch(base + "/missing")).isEmpty();
    }

    @Test
    void readsOnlyTheBeginningOfLargeBodies() {
        assertThat(permissive.fetch(base + "/big")).get()
                .satisfies(data -> assertThat(data.title()).isEqualTo("Big"));
    }

    @Test
    void timesOut() {
        Instant start = Instant.now();
        assertThat(permissive.fetch(base + "/slow")).isEmpty();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void refusesOtherSchemes() {
        assertThat(permissive.fetch("file:///etc/passwd")).isEmpty();
        assertThat(permissive.fetch("ftp://127.0.0.1/")).isEmpty();
        assertThat(permissive.fetch("not a url")).isEmpty();
    }

    @Test
    void endlessHtmlBodyIsCutByBodyCapOrDeadline() {
        Instant start = Instant.now();
        assertThat(permissive.fetch(base + "/endless"))
                .contains(new PreviewData("Endless", null, null));
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void redirectWithEndlessBodyDoesNotHang() {
        Instant start = Instant.now();
        assertThat(permissive.fetch(base + "/redirect-endless")).isPresent();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void nonHtmlEndlessBodyDoesNotHang() {
        Instant start = Instant.now();
        assertThat(permissive.fetch(base + "/plain-endless")).isEmpty();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void dripBodyIsCutByDeadline() {
        Instant start = Instant.now();
        assertThat(permissive.fetch(base + "/drip")).isEmpty();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }
}
