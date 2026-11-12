package prepare.edt.duissigner.adapter;

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny JCA provider for tests: offers "SHA256withECDSA", passes the work on to SunEC, and counts the signatures.
 * It proves that the signer really uses the provider named in its configuration.
 *
 * A provider is a list of "engine.algorithm = implementation class" entries.
 * The implementation class extends the engine's SPI (Service Provider Interface), here SignatureSpi.
 */
public final class SpyProvider extends Provider {

    static final String NAME = "TEST-SPY";
    static final AtomicInteger SIGNATURES = new AtomicInteger();

    public SpyProvider() {
        super(NAME, "1.0", "Counts signatures, delegates to SunEC (tests only)");
        put("Signature.SHA256withECDSA", SpySignature.class.getName());
    }

    /** JCA creates this class by reflection, so it must be public with a public no-argument constructor. */
    public static final class SpySignature extends SignatureSpi {

        private final Signature delegate;

        public SpySignature() throws Exception {
            delegate = Signature.getInstance("SHA256withECDSA", "SunEC");
        }

        @Override
        protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
            delegate.initSign(key);
        }

        @Override
        protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
            delegate.initVerify(key);
        }

        @Override
        protected void engineUpdate(byte b) throws SignatureException {
            delegate.update(b);
        }

        @Override
        protected void engineUpdate(byte[] bytes, int offset, int length) throws SignatureException {
            delegate.update(bytes, offset, length);
        }

        @Override
        protected byte[] engineSign() throws SignatureException {
            SIGNATURES.incrementAndGet();
            return delegate.sign();
        }

        @Override
        protected boolean engineVerify(byte[] signature) throws SignatureException {
            return delegate.verify(signature);
        }

        @Override
        @Deprecated
        protected void engineSetParameter(String param, Object value) {
            throw new InvalidParameterException("not supported");
        }

        @Override
        @Deprecated
        protected Object engineGetParameter(String param) {
            throw new InvalidParameterException("not supported");
        }
    }
}
