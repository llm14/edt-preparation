package prepare.edt.orchestrator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FairnessKeyTest {

    static final String BATCH = "batch-edt-manifests/sample.csv/8a5eca24-492c-4380-828c-f6741c051cc9";

    @Test
    void a_short_batch_id_is_the_key() {
        assertEquals("sample-batch", FairnessKey.of("sample-batch"));
    }

    @Test
    void a_long_batch_id_becomes_a_stable_hash_within_temporals_64_byte_limit() {
        String key = FairnessKey.of(BATCH);

        assertTrue(BATCH.length() > 64);
        assertEquals(43, key.length());
        assertEquals(key, FairnessKey.of(BATCH), "the same on every call (workflow replay)");
        assertNotEquals(key, FairnessKey.of(BATCH.replace("8a5e", "9a5e")), "one key per Batch");
    }
}
