package prepare.edt.responselistener.adapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;

/**
 * Collects the log records of a test, each with its MDC (the fields the JSON log writes), by adding a handler to the JBoss
 * log manager's root logger. The workflows log through SLF4J, which the test classpath routes to the JBoss log manager,
 * so no JVM setting is needed. Use it in a try block; it removes itself on close.
 */
public final class LogCapture extends ExtHandler implements AutoCloseable {

    /** One captured line: the logger name, the message text, the MDC at that moment, and the exception if the line had one. */
    public record Line(String logger, String message, Map<String, String> fields, Throwable thrown) {
    }

    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final org.jboss.logmanager.Logger root = LogContext.getLogContext().getLogger("");
    private final Level previousRootLevel = root.getLevel();

    /**
     * Also sets the root level to INFO while it captures, and restores it on close: the test runner may set a higher level
     * (Gradle does, unless it runs with -i), and the INFO lines of the workflows would then never reach the handler.
     */
    public LogCapture() {
        setLevel(Level.ALL);
        root.setLevel(Level.INFO);
        root.addHandler(this);
    }

    @Override
    protected void doPublish(ExtLogRecord record) {
        lines.add(new Line(record.getLoggerName(), record.getFormattedMessage(), record.getMdcCopy(), record.getThrown()));
    }

    /** The lines written by one class's logger, for example {@code lines(BatchWorkflowImpl.class)}. */
    public List<Line> lines(Class<?> logger) {
        return lines.stream().filter(l -> logger.getName().equals(l.logger())).toList();
    }

    /** The lines written by the logger with this name, for example {@code lines("edt.interaction")}. */
    public List<Line> lines(String loggerName) {
        return lines.stream().filter(l -> loggerName.equals(l.logger())).toList();
    }

    /** The lines of that class's logger with exactly this message text. */
    public List<Line> lines(Class<?> logger, String message) {
        return lines(logger).stream().filter(l -> message.equals(l.message())).toList();
    }

    @Override
    public void close() {
        root.removeHandler(this);
        root.setLevel(previousRootLevel);
    }
}
