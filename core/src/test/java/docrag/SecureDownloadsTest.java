package docrag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SecureDownloadsTest {
    @TempDir
    Path directory;

    @Test
    void rejectsLocalReservedMappedAndPrivateDestinations() throws Exception {
        for (String ip : java.util.List.of("127.0.0.1", "10.1.2.3", "169.254.169.254", "172.16.0.1", "192.168.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "198.18.0.1", "192.0.2.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::"))
            assertFalse(SecureDownloads.publicAddress(InetAddress.getByName(ip)), ip);
        assertTrue(SecureDownloads.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(SecureDownloads.publicAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void rejectsLoopbackAndUnsafeSchemesBeforeWritingAnything() {
        assertThrows(Exception.class, () -> SecureDownloads.download("https://127.0.0.1/report.pdf", directory.resolve("bad.pdf"), 100));
        assertThrows(IllegalArgumentException.class, () -> SecureDownloads.download("http://example.com/report.pdf", directory.resolve("bad.pdf"), 100));
        assertFalse(Files.exists(directory.resolve("bad.pdf")));
    }
}
