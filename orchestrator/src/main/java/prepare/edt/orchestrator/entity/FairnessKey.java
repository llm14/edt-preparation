package prepare.edt.orchestrator.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The fairness key of the send queue: one per Batch, so the Temporal Server shares the send rate between Batches.
 *
 * Temporal refuses a fairness key longer than 64 bytes ("fairness key length exceeds limit"), and a Batch ID
 * (batch-bucket/key/version) is usually longer. So a long Batch ID is replaced by its SHA-256, base64url without
 * padding (43 characters): still one key per Batch, the same every time (safe in workflow code, which is replayed).
 */
public final class FairnessKey {

    static final int MAX_BYTES = 64;

    private FairnessKey() {
    }

    public static String of(String batchId) {
        byte[] bytes = batchId.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return batchId;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
