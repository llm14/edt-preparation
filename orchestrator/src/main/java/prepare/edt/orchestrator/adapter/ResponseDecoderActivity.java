package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import prepare.edt.orchestrator.entity.Facts;

/** Decodes a Response and returns the Facts the Flow decides on (architecture model: Response Decoder Activity). */
@ActivityInterface
public interface ResponseDecoderActivity {

    /** Fails with the non-retryable type "ResponseNotDecodable" if the Response cannot be decoded. */
    @ActivityMethod(name = "DecodeResponse")
    Facts decode(String srv, String responseXml);
}
