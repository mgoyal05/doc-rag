package docrag;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The connection manager uses the checked addresses themselves; no check-then-JDK-re-resolve gap.
 */
final class SecureDownloads {
    static final DnsResolver DNS = new DnsResolver() {
        public InetAddress[] resolve(String host) throws UnknownHostException {
            var addresses = InetAddress.getAllByName(host);
            for (var address : addresses)
                if (!publicAddress(address)) throw new UnknownHostException("Non-public download destination");
            return addresses;
        }

        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    private SecureDownloads() {
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())
            return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        int c = bytes[2] & 255;
        if (bytes.length == 16)
            return (a & 224) == 32 && !(a == 32 && b == 2) && !(a == 32 && b == 1 && ((c == 13 && (bytes[3] & 255) == 184) || c < 2));
        return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127) && !(a == 192 && b == 0 && (c == 0 || c == 2))
                && !(a == 192 && b == 88 && c == 99) && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113);
    }

    static Path download(String source, Path file, long maxBytes) throws Exception {
        URI uri = URI.create(source);
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(DNS)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10)).setSocketTimeout(Timeout.ofSeconds(60)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(10)).setResponseTimeout(Timeout.ofSeconds(60)).build()).build()) {
            for (int redirect = 0; redirect < 5; redirect++) {
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("Downloads require public HTTPS");
                // Also checks literal IPs, for which a connection manager can skip name resolution.
                DNS.resolve(uri.getHost());
                URI current = uri;
                String location = client.execute(new HttpGet(uri), response -> {
                    int code = response.getCode();
                    if (code >= 300 && code < 400) {
                        var header = response.getFirstHeader("location");
                        if (header == null) throw new IOException("Invalid redirect");
                        return header.getValue();
                    }
                    if (code != 200 || response.getEntity() == null) throw new IOException("PDF download failed");
                    try (var input = response.getEntity().getContent(); var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[8192];
                        long total = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted())
                                throw new InterruptedIOException("Download cancelled");
                            total += count;
                            if (total > maxBytes) throw new IOException("PDF exceeds size limit");
                            output.write(buffer, 0, count);
                        }
                    }
                    return null;
                });
                if (location == null) return file;
                uri = current.resolve(location);
            }
        }
        throw new IOException("Too many redirects");
    }
}
