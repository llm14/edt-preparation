package prepare.edt.orchestrator.adapter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the workflows publish, instead of sending it to Kafka. */
public class RecordingResultPublisher implements ResultPublisherActivity {

    public final List<FlowStep> steps = new CopyOnWriteArrayList<>();
    public final List<DeviceResult> devices = new CopyOnWriteArrayList<>();
    public final List<BatchResult> batches = new CopyOnWriteArrayList<>();

    @Override
    public void publishFlowStep(FlowStep event) {
        steps.add(event);
    }

    @Override
    public void publishDeviceResult(DeviceResult event) {
        devices.add(event);
    }

    @Override
    public void publishBatchResult(BatchResult event) {
        batches.add(event);
    }
}
