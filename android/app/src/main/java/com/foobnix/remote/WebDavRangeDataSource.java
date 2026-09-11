package com.foobnix.remote;

import com.foobnix.android.utils.LOG;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.burgstaller.okhttp.AuthenticationCacheInterceptor;
import com.burgstaller.okhttp.CachingAuthenticatorDecorator;
import com.burgstaller.okhttp.DispatchingAuthenticator;
import com.burgstaller.okhttp.basic.BasicAuthenticator;
import com.burgstaller.okhttp.digest.CachingAuthenticator;
import com.burgstaller.okhttp.digest.Credentials;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebDAV random-access data source built on plain HTTP Range requests
 * (OkHttp). Kept deliberately separate from the sardine browsing client:
 * range reads need precise control over status codes and Content-Range
 * validation.
 */
public class WebDavRangeDataSource implements RemoteDataSource {

    /** Shared clients keyed by credential set (connection pool reuse). */
    private static final Map<String, OkHttpClient> CLIENTS = new ConcurrentHashMap<String, OkHttpClient>();

    private final String url;
    private final String login;
    private final String password;
    private final boolean trustAll;

    private OkHttpClient client;
    private long size = -1;
    private String etag = "";
    private String lastModified = "";
    /** Set by the open() probe: 206 = real range support, 200 = ignored. */
    private boolean rangeSupported = true;

    public WebDavRangeDataSource(String url, String login, String password, boolean trustAll) {
        this.url = url;
        this.login = login;
        this.password = password;
        this.trustAll = trustAll;
    }

    private static OkHttpClient client(String login, String password, boolean trustAll) {
        final String key = login + "|" + password + "|" + trustAll;
        OkHttpClient cached = CLIENTS.get(key);
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
        if (login != null && !login.isEmpty()) {
            Credentials credentials = new Credentials(login, password);
            DispatchingAuthenticator authenticator = new DispatchingAuthenticator.Builder()
                    .with("digest", new DigestAuthenticator(credentials))
                    .with("basic", new BasicAuthenticator(credentials))
                    .build();
            Map<String, CachingAuthenticator> authCache = new ConcurrentHashMap<String, CachingAuthenticator>();
            builder.authenticator(new CachingAuthenticatorDecorator(authenticator, authCache));
            builder.addInterceptor(new AuthenticationCacheInterceptor(authCache));
        }
        OkHttpClient created = builder.build();
        CLIENTS.put(key, created);
        return created;
    }

    @SuppressWarnings("deprecation")
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

    @Override
    public void open() throws IOException {
        client = client(login, password, trustAll);
        // Size first via a 1-byte range request: HEAD is often disabled and
        // the Content-Range total doubles as proof of range support.
        Response resp = null;
        try {
            Request req = new Request.Builder().url(url).header("Range", "bytes=0-0").build();
            resp = client.newCall(req).execute();
            if (resp.code() == 206) {
                String cr = resp.header("Content-Range");
                size = parseTotal(cr);
                rangeSupported = true;
                if (size <= 0) {
                    throw new IOException("WebDAV: bad Content-Range: " + cr);
                }
            } else if (resp.code() == 200) {
                // Server ignored Range — full body. Range reads are NOT
                // possible; the opener must degrade to a full fetch
                // (tech-spec §6.5: never fake streaming by skipping).
                size = resp.body().contentLength();
                rangeSupported = false;
                if (size <= 0) {
                    throw new IOException("WebDAV: no size, code " + resp.code());
                }
                android.util.Log.i("REMOTE", "webdav: server ignores Range, degrading to full fetch: " + url);
            } else {
                throw new IOException("WebDAV open failed: HTTP " + resp.code() + " " + url);
            }
            etag = resp.header("ETag", "");
            lastModified = resp.header("Last-Modified", "");
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    private static long parseTotal(String contentRange) {
        // "bytes 0-0/12345"
        if (contentRange == null) {
            return -1;
        }
        int i = contentRange.lastIndexOf('/');
        if (i < 0) {
            return -1;
        }
        try {
            String total = contentRange.substring(i + 1).trim();
            if ("*".equals(total)) {
                return -1;
            }
            return Long.parseLong(total);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int readAt(long offset, byte[] buffer, int off, int len) throws IOException {
        if (offset >= size) {
            return 0;
        }
        long end = Math.min(offset + len, size) - 1;
        Request.Builder rb = new Request.Builder().url(url);
        if (offset > 0 || end < size - 1) {
            rb.header("Range", "bytes=" + offset + "-" + end);
        }
        Response resp = null;
        try {
            resp = client.newCall(rb.build()).execute();
            if (resp.code() == 416) {
                return 0; // range past EOF
            }
            if (resp.code() != 206 && resp.code() != 200) {
                throw new IOException("WebDAV read failed: HTTP " + resp.code());
            }
            InputStream in = resp.body().byteStream();
            long skip = resp.code() == 200 ? offset : 0; // 200 = full body
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) {
                    break;
                }
                skip -= s;
            }
            int total = 0;
            while (total < len) {
                int n = in.read(buffer, off + total, len - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            return total;
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    @Override
    public String versionTag() {
        if (!etag.isEmpty()) {
            return etag;
        }
        return size + "-" + lastModified;
    }

    @Override
    public String name() {
        return "webdav";
    }

    @Override
    public boolean supportsRange() {
        return rangeSupported;
    }

    @Override
    public void close() {
        // The shared OkHttpClient / connection pool stays alive for reuse.
    }
}
