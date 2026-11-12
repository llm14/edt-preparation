package prepare.edt.duissigner.adapter.kms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Date;
import java.util.Enumeration;

/**
 * KeyStore type "KMS": the certificates come from the certificate store, the private keys stay in KMS.
 *
 * load(stream, password) reads the certificate store (PKCS#12, certificates only) given as the stream.
 * For each certificate that is not a CA, the alias is a key entry:
 *   getKey("other-user")         -> KmsPrivateKey for the KMS alias "alias/edt-other-user" (a handle, no key material)
 *   getCertificate("other-user") -> the role's certificate, from the certificate store
 *
 * Read-only: keys are created in KMS by DCC (locally by dcc-tools/kms-setup), never through this KeyStore.
 *
 * The store also holds the trusted DSP certificate (alias "dsp"), for the Orchestrator and the Response Listener.
 * This KeyStore reports it as a key entry too, but there is no KMS key behind it: the signer only asks for the
 * aliases of the two User Roles (edt.signing.aliases), so it is never used here.
 */
final class KmsKeyStoreSpi extends KeyStoreSpi {

    private final String kmsAliasPrefix;
    private KeyStore certificates;

    KmsKeyStoreSpi(String kmsAliasPrefix) {
        this.kmsAliasPrefix = kmsAliasPrefix;
    }

    @Override
    public void engineLoad(InputStream certificateStore, char[] password)
            throws IOException, NoSuchAlgorithmException, CertificateException {
        try {
            certificates = KeyStore.getInstance("PKCS12");
        } catch (KeyStoreException e) {
            throw new IOException("PKCS12 is not available", e);
        }
        certificates.load(certificateStore, password);
    }

    @Override
    public Key engineGetKey(String alias, char[] password) {
        X509Certificate certificate = roleCertificate(alias);
        if (certificate == null) {
            return null;
        }
        return new KmsPrivateKey(kmsAliasPrefix + alias, (ECPublicKey) certificate.getPublicKey());
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        try {
            return certificates.getCertificate(alias);
        } catch (KeyStoreException e) {
            return null;
        }
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        X509Certificate certificate = roleCertificate(alias);
        return certificate == null ? null : new Certificate[] {certificate};
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return roleCertificate(alias) != null;
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return engineContainsAlias(alias) && !engineIsKeyEntry(alias);
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        try {
            return certificates.getCreationDate(alias);
        } catch (KeyStoreException e) {
            return null;
        }
    }

    @Override
    public Enumeration<String> engineAliases() {
        try {
            return certificates.aliases();
        } catch (KeyStoreException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        try {
            return certificates.containsAlias(alias);
        } catch (KeyStoreException e) {
            return false;
        }
    }

    @Override
    public int engineSize() {
        try {
            return certificates.size();
        } catch (KeyStoreException e) {
            return 0;
        }
    }

    @Override
    public String engineGetCertificateAlias(Certificate certificate) {
        try {
            return certificates.getCertificateAlias(certificate);
        } catch (KeyStoreException e) {
            return null;
        }
    }

    /** The certificate of a role (an end-entity certificate, not a CA), or null. */
    private X509Certificate roleCertificate(String alias) {
        if (engineGetCertificate(alias) instanceof X509Certificate certificate
                && certificate.getBasicConstraints() == -1) {
            return certificate;
        }
        return null;
    }

    // Read-only KeyStore.

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
        throw new UnsupportedOperationException("the KMS KeyStore is read-only");
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
        throw new UnsupportedOperationException("the KMS KeyStore is read-only");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate certificate) {
        throw new UnsupportedOperationException("the KMS KeyStore is read-only");
    }

    @Override
    public void engineDeleteEntry(String alias) {
        throw new UnsupportedOperationException("the KMS KeyStore is read-only");
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) {
        throw new UnsupportedOperationException("the KMS KeyStore is read-only");
    }
}
