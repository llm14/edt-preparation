package prepare.edt.batchingest.adapter.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.ProviderException;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsInvalidSignatureException;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.VerifyRequest;
import software.amazon.awssdk.services.kms.model.VerifyResponse;

class KmsVerifySignatureSpiTest {

    static final byte[] MANIFEST = "device_id,device_type,flow\n".getBytes(StandardCharsets.UTF_8);

    final KeyPair dcc = keyPair();
    VerifyRequest lastRequest;
    boolean kmsDown;

    /** Plays KMS Verify like the real one: checks the DIGEST with the key's public half; a mismatch is an error. */
    final KmsClient kms = new KmsClient() {
        @Override
        public VerifyResponse verify(VerifyRequest request) {
            lastRequest = request;
            if (kmsDown) {
                throw SdkClientException.create("Unable to execute HTTP request: Connection refused");
            }
            try {
                Signature check = Signature.getInstance("NONEwithECDSA");   // the input is already a digest
                check.initVerify(dcc.getPublic());
                check.update(request.message().asByteArray());
                if (!check.verify(request.signature().asByteArray())) {
                    throw KmsInvalidSignatureException.builder().message("invalid").build();
                }
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            return VerifyResponse.builder().signatureValid(true).build();
        }

        @Override
        public String serviceName() {
            return "kms";
        }

        @Override
        public void close() {
        }
    };

    boolean verifyWithKms(byte[] content, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA", new KmsProvider(kms));
        verifier.initVerify(new KmsPublicKey("alias/dcc-manifest"));
        verifier.update(content);
        return verifier.verify(signature);
    }

    @Test
    void kms_verify_gets_the_sha256_digest_and_the_key_alias() throws Exception {
        assertTrue(verifyWithKms(MANIFEST, sign(MANIFEST)));

        assertEquals("alias/dcc-manifest", lastRequest.keyId());
        assertEquals(MessageType.DIGEST, lastRequest.messageType());
        assertEquals(32, lastRequest.message().asByteArray().length, "only the 32-byte digest goes to KMS");
    }

    @Test
    void kms_invalid_signature_exception_means_false() throws Exception {
        assertFalse(verifyWithKms("something else".getBytes(StandardCharsets.UTF_8), sign(MANIFEST)));
    }

    @Test
    void kms_unreachable_is_not_a_verdict() {
        kmsDown = true;

        assertThrows(ProviderException.class, () -> verifyWithKms(MANIFEST, sign(MANIFEST)));
    }

    @Test
    void batch_ingest_never_signs() throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA", new KmsProvider(kms));

        assertThrows(InvalidKeyException.class, () -> signer.initSign(dcc.getPrivate()));
    }

    byte[] sign(byte[] content) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(dcc.getPrivate());
        signature.update(content);
        return signature.sign();
    }

    static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
