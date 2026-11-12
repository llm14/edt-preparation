package prepare.edt.orchestrator.usecase;

import prepare.edt.orchestrator.entity.ManifestLocation;

/** Port: reads a byte range of a Manifest (S3 GetObject with a Range header). */
public interface ManifestBytes {

    /**
     * @return up to {@code length} bytes from {@code start}; fewer at the end of the object, none after it.
     *         Throws an unchecked exception when the Manifest cannot be read (the activity is retried).
     */
    byte[] read(ManifestLocation manifest, long start, int length);
}
