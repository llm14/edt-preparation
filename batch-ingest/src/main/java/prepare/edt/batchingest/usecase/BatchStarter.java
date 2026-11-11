package prepare.edt.batchingest.usecase;

import prepare.edt.batchingest.entity.ManifestRef;

/** Port: starts the Batch workflow of a valid Manifest (model component "Batch Starter"). */
public interface BatchStarter {

    /**
     * @return true if this call started it, false if the Batch of this Manifest was already started
     *         (a message delivered twice). Throws an unchecked exception when Temporal cannot be reached.
     */
    boolean start(ManifestRef manifest);
}
