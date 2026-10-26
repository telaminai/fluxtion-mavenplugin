package com.fluxtion.maven;

import org.apache.maven.plugin.logging.SystemStreamLog;

import java.util.ArrayList;
import java.util.List;

/** Captures what a mojo logs, so tests can assert a credential never appears. */
final class RecordingLog extends SystemStreamLog {

    final List<String> lines = new ArrayList<>();

    @Override
    public void debug(CharSequence content) {
        lines.add("DEBUG " + content);
    }

    @Override
    public void info(CharSequence content) {
        lines.add("INFO " + content);
    }

    @Override
    public void warn(CharSequence content) {
        lines.add("WARN " + content);
    }

    @Override
    public void error(CharSequence content) {
        lines.add("ERROR " + content);
    }

    @Override
    public void error(Throwable error) {
        lines.add("ERROR " + error);
    }

    String all() {
        return String.join("\n", lines);
    }
}
