package com.foobnix.webdav;

import com.burgstaller.okhttp.AuthenticationCacheInterceptor;
import com.burgstaller.okhttp.CachingAuthenticatorDecorator;
import com.burgstaller.okhttp.DispatchingAuthenticator;
import com.burgstaller.okhttp.basic.BasicAuthenticator;
import com.burgstaller.okhttp.digest.CachingAuthenticator;
import com.burgstaller.okhttp.digest.Credentials;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Thin wrapper around the Sardine-Android WebDAV client (com.thegrizzlylabs:
 * sardine-android 0.9). Listings and downloads only - the module is read-only.
 * No OPDS classes are used here.
 *
 * Connection hardening (home NAS servers): sardine 0.9 ships only a Basic
 * authenticator, so credentials are wired through okhttp-digest's
 * DispatchingAuthenticator (Basic + Digest, chosen by the server's
 * WWW-Authenticate challenge). Self-signed HTTPS servers can be accepted per
 * server with the trustAll flag.
 */
public class WebDavClient {

    /** Error kind of the last failed request: "", "auth", "ssl", "network", "other". */
    public static volatile String lastError = "";

    /**
     * Kept for existing callers: true when the last failure looked like an
     * HTTP 401/403 auth rejection.
     */
    public static volatile boolean lastErrorWasAuth = false;

    /** Reusable clients keyed by credential set: every periodic/debounced
     * sync used to build a fresh OkHttpSardine (own connection pool +
     * dispatcher threads) and never shut it down. */
    private static final Map<String, Sardine> CLIENTS = new ConcurrentHashMap<String, Sardine>();

    public static Sardine sardine(String login, String password) {
        return sardine(login, password, false);
    }

    public static Sardine sardine(String login, String password, boolean trustAll) {
        final String key = login + "|" + password + "|" + trustAll;
        final Sardine cached = CLIENTS.get(key);
        if (cached != null) {
            return cached;
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        if (trustAll) {
            applyTrustAll(builder);
        }

        if (TxtUtils.isNotEmpty(login)) {
            // sardine 0.9 only adds a Basic header via setCredentials; a
            // Digest-only server answers 401 forever. Route auth through
            // okhttp-digest which speaks both, selected by the challenge.
            Credentials credentials = new Credentials(login, password);
            DispatchingAuthenticator authenticator = new DispatchingAuthenticator.Builder()
                    .with("digest", new DigestAuthenticator(credentials))
                    .with("basic", new BasicAuthenticator(credentials))
                    .build();
            Map<String, CachingAuthenticator> authCache = new ConcurrentHashMap<String, CachingAuthenticator>();
            builder.authenticator(new CachingAuthenticatorDecorator(authenticator, authCache));
            builder.addInterceptor(new AuthenticationCacheInterceptor(authCache));
            // Preemptive Basic: a lenient NAS may answer an anonymous request
            // with a SUCCESS-but-permission-filtered (empty) listing instead
            // of a 401 challenge, so challenge-driven auth never transmits
            // the stored credentials. Servers that reject Basic still fall
            // back to the normal challenge flow above.
            final String preemptive = okhttp3.Credentials.basic(login, password,
                    java.nio.charset.StandardCharsets.UTF_8);
            builder.addInterceptor(chain -> {
                okhttp3.Request req = chain.request();
                if (req.header("Authorization") == null) {
                    req = req.newBuilder().header("Authorization", preemptive).build();
                }
                return chain.proceed(req);
            });
        }

        final Sardine created = new OkHttpSardine(builder.build());
        CLIENTS.put(key, created);
        return created;
    }

    /** Accept any certificate / hostname (opt-in per server, LAN self-signed). */
    private static void applyTrustAll(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * PROPFIND depth 1 listing of {@code url}.
     *
     * @return items (directories first, then files, alphabetical), or
     * {@code null} when the request failed (network / auth). Check
     * {@link #lastError} for the failure kind right after a null return.
     */
    public static List<WebDavItem> list(String url, String login, String password, boolean trustAll) {
        // device-visible one-liner (LOG.d is emulator-gated): empty-listing
        // reports become diagnosable via "adb logcat -s WEBDAV"
        android.util.Log.i("WEBDAV", "list " + url + " auth=" + TxtUtils.isNotEmpty(login)
                + " trustAll=" + trustAll);
        url = encodeIfNeeded(url);
        try {
            List<DavResource> resources = sardine(login, password, trustAll).list(url);
            List<WebDavItem> items = new ArrayList<WebDavItem>();
            for (DavResource r : resources) {
                URI href = r.getHref();
                if (href == null) {
                    continue;
                }
                String h = resolve(url, href.toString());
                if (isSelf(url, h)) {
                    continue;
                }
                WebDavItem item = new WebDavItem();
                item.href = h;
                item.isDir = r.isDirectory();
                item.name = r.getName();
                if (TxtUtils.isEmpty(item.name)) {
                    item.name = lastName(h);
                }
                Long len = r.getContentLength();
                item.size = len == null ? -1 : len;
                items.add(item);
            }
            sort(items);
            lastError = "";
            lastErrorWasAuth = false;
            android.util.Log.i("WEBDAV", "list ok: " + items.size() + " item(s)");
            if (items.isEmpty()) {
                logRawPropfind(url, login, password);
            }
            return items;
        } catch (Exception e) {
            LOG.e(e);
            lastError = classifyError(e);
            lastErrorWasAuth = "auth".equals(lastError);
            android.util.Log.i("WEBDAV", "list FAIL kind=" + lastError + ": " + e);
            return null;
        }
    }


    /**
     * Empty-listing diagnostic: replay the PROPFIND once outside sardine with
     * an RFC-encoded request target and log code + body sample. Distinguishes
     * a genuinely empty folder from rows dropped by strict URI/namespace
     * parsing and from odd server answers for unencoded (Chinese/space)
     * paths. Runs only on the empty path, so the cost is one extra request.
     */
    private static void logRawPropfind(String url, String login, String password) {
        try {
            java.net.URL u = new java.net.URL(url);
            // re-encode an already-decoded path (raw spaces / Chinese in a
            // saved startDir would otherwise form an illegal request target)
            java.net.URI uri = new java.net.URI(u.getProtocol(), u.getUserInfo(),
                    u.getHost(), u.getPort(), u.getPath(), u.getQuery(), u.getRef());
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(uri.toString())
                    .header("Depth", "1");
            if (TxtUtils.isNotEmpty(login)) {
                String b64 = android.util.Base64.encodeToString(
                        (login + ":" + password).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                rb.header("Authorization", "Basic " + b64);
            }
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            okhttp3.Response resp = client.newCall(rb.method("PROPFIND", null).build()).execute();
            String sample = resp.body() == null ? "<no body>" : resp.peekBody(1600).string();
            android.util.Log.i("WEBDAV", "raw probe " + uri + " code=" + resp.code()
                    + " body: " + sample.replaceAll("\\s+", " "));
            resp.close();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        } catch (Exception e) {
            android.util.Log.i("WEBDAV", "raw probe FAIL " + url + ": " + e);
        }
    }

    /**
     * Percent-encode the request target when it still contains raw decoded
     * characters (a startDir saved from display names can carry spaces /
     * Chinese): a NAS answers such targets with a SUCCESS-but-empty listing
     * instead of 404. Strict-parse first so already-encoded server hrefs
     * pass through untouched — no double encoding.
     */
    public static String encodeIfNeeded(String url) {
        try {
            new java.net.URI(url);
            return url;
        } catch (Exception notStrictlyValid) {
            try {
                java.net.URL u = new java.net.URL(url);
                return new java.net.URI(u.getProtocol(), u.getUserInfo(), u.getHost(),
                        u.getPort(), u.getPath(), u.getQuery(), u.getRef()).toASCIIString();
            } catch (Exception e) {
                LOG.e(e);
                return url;
            }
        }
    }

    /** Categorize a failure so the UI can suggest the right remedy. */
    private static String classifyError(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return "ssl";
            }
            if (c instanceof UnknownHostException) {
                return "network";
            }
            String msg = c.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403"))) {
                return "auth";
            }
            String simple = c.getClass().getSimpleName();
            if (simple.contains("Unauthorized") || simple.contains("Forbidden")) {
                return "auth";
            }
            if (simple.contains("Timeout") || simple.contains("Connect") || simple.contains("Socket")) {
                return "network";
            }
        }
        return "other";
    }

    public static InputStream openStream(String url, String login, String password, boolean trustAll) throws IOException {
        return sardine(login, password, trustAll).get(encodeIfNeeded(url));
    }

    static String resolve(String base, String href) {
        if (href == null || href.isEmpty()) {
            return base;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            // Ensure the base path ends with '/' so URI.resolve treats the last
            // segment as a directory (RFC 3986 §5.3): without it,
            // "http://h/dav".resolve("sub/") wrongly drops "dav".
            String b = base.endsWith("/") ? base : base + "/";
            return URI.create(b).resolve(href).toString();
        } catch (Exception e) {
            return base;
        }
    }

    private static boolean isSelf(String url, String href) {
        return WebDavStore.trimSlash(url).equals(WebDavStore.trimSlash(href));
    }

    public static String lastName(String href) {
        String p = href;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static void sort(List<WebDavItem> items) {
        Collections.sort(items, new Comparator<WebDavItem>() {
            @Override
            public int compare(WebDavItem lhs, WebDavItem rhs) {
                if (lhs.isDir != rhs.isDir) {
                    return lhs.isDir ? -1 : 1;
                }
                String a = lhs.name == null ? "" : lhs.name.toLowerCase();
                String b = rhs.name == null ? "" : rhs.name.toLowerCase();
                return a.compareTo(b);
            }
        });
    }
}
