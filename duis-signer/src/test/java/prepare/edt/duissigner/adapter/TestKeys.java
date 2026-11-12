package prepare.edt.duissigner.adapter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

/**
 * Creates a PKCS#12 keystore with one EC P-256 key pair and self-signed certificate per User Role,
 * using keytool from the JDK that runs the tests. TEST keys only.
 */
final class TestKeys {

    static final char[] PASSWORD = "test-only".toCharArray();
    static final String OTHER_USER = "other-user";
    static final String REGISTERED_SUPPLIER_AGENT = "registered-supplier-agent";

    private TestKeys() {
    }

    static KeyStore create(Path directory) throws Exception {
        Path file = directory.resolve("test-roles.p12");
        generate(file, OTHER_USER, "CN=EDT Other User (TEST)");
        generate(file, REGISTERED_SUPPLIER_AGENT, "CN=EDT Registered Supplier Agent (TEST)");

        // JCA: ask for a KeyStore of type PKCS12, then load the file into it.
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }

    private static void generate(Path file, String alias, String subject) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(List.of(
                keytool.toString(), "-genkeypair",
                "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",   // NIST P-256, the curve DUIS uses
                "-sigalg", "SHA256withECDSA",
                "-dname", subject,
                "-validity", "30",
                "-storetype", "PKCS12",
                "-keystore", file.toString(),
                "-storepass", new String(PASSWORD),
                "-keypass", new String(PASSWORD)))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }
}
