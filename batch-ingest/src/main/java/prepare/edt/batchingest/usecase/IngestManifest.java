package prepare.edt.batchingest.usecase;

import prepare.edt.batchingest.entity.ManifestRef;

/**
 * Use case: one "object created" message has arrived for a Manifest.
 *
 *   1. verify DCC's signature, before anything else
 *   2. invalid: reject (no Batch)
 *   3. valid:   start the Batch (once, even if the message arrives twice)
 *
 * Errors reaching S3, KMS or Temporal are not caught: the message stays on the queue and comes back later.
 */
public final class IngestManifest {

    private final ManifestVerifier verifier;
    private final BatchStarter starter;

    public IngestManifest(ManifestVerifier verifier, BatchStarter starter) {
        this.verifier = verifier;
        this.starter = starter;
    }

    public Outcome ingest(ManifestRef manifest) {
        ManifestVerifier.Verdict verdict = verifier.verify(manifest);
        if (!verdict.valid()) {
            return new Outcome.Rejected(verdict.reason());
        }
        return starter.start(manifest) ? new Outcome.Started() : new Outcome.AlreadyStarted();
    }

    /** All three end the message's life: the caller deletes it from the queue. */
    public sealed interface Outcome {
        record Started() implements Outcome { }
        record AlreadyStarted() implements Outcome { }
        record Rejected(String reason) implements Outcome { }
    }
}
