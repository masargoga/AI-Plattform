package com.agenticpilot.source.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Fetches one URL for the website connector and refuses to touch internal addresses. The block happens in the DNS resolver
 * the HTTP client connects with, so the address that was checked is the address that is used (no gap for DNS rebinding).
 * Redirects are not followed here, the caller decides. Time and size are limited.
 */
public final class SafeFetcher implements AutoCloseable {

    /** A fetched page. For redirects (3xx) the location is the absolute target. */
    public record Response(int status, String contentType, byte[] body, URI location) {
    }

    private final CloseableHttpClient client;
    private final int maxBytes;
    private final Timeout timeout;

    public SafeFetcher(WebsiteProperties props) {
        this.maxBytes = props.maxPageBytes();
        this.timeout = Timeout.ofMilliseconds(props.timeout().toMillis());
        boolean allowPrivate = props.allowPrivateAddresses();
        SystemDefaultDnsResolver resolver = new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] all = super.resolve(host);
                for (InetAddress a : all) {
                    if (!allowPrivate && !AddressPolicy.isPublic(a)) {
                        throw new UnknownHostException("address not allowed");
                    }
                }
                return all;
            }
        };
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(manager)
                .setUserAgent(props.userAgent())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build())
                .build();
    }

    public Response fetch(URI uri) {
        HttpGet get = new HttpGet(uri);
        get.setHeader("Accept", "text/html,text/plain;q=0.9");
        try {
            return client.execute(get, response -> {
                int status = response.getCode();
                Header type = response.getFirstHeader("Content-Type");
                String contentType = type == null ? "" : type.getValue();
                URI location = null;
                Header loc = response.getFirstHeader("Location");
                if (status / 100 == 3 && loc != null) {
                    try {
                        location = uri.resolve(loc.getValue());
                    } catch (IllegalArgumentException e) {
                        throw new FetchException("A redirect pointed to an invalid address");
                    }
                }
                HttpEntity entity = response.getEntity();
                byte[] body = new byte[0];
                if (entity != null && status / 100 == 2) {
                    body = read(entity);
                }
                return new Response(status, contentType, body, location);
            });
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            if (root instanceof UnknownHostException u && "address not allowed".equals(u.getMessage())) {
                throw new FetchException("The address of this site is not allowed (internal or reserved address)", e);
            }
            if (root instanceof java.net.SocketTimeoutException || root instanceof org.apache.hc.core5.http.ConnectionRequestTimeoutException) {
                throw new FetchException("The site did not answer in time", e);
            }
            throw new FetchException("The site could not be reached", e);
        }
    }

    private byte[] read(HttpEntity entity) throws IOException {
        if (entity.getContentLength() > maxBytes) {
            throw new FetchException("A page is larger than " + (maxBytes / 1024) + " KB");
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > maxBytes) {
                    throw new FetchException("A page is larger than " + (maxBytes / 1024) + " KB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Override
    public void close() throws IOException {
        client.close(org.apache.hc.core5.io.CloseMode.GRACEFUL);
    }
}
