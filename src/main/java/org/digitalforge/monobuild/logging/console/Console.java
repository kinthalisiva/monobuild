package org.digitalforge.monobuild.logging.console;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Console {

    private static final Logger CONSOLE = LoggerFactory.getLogger(Console.class);

    private final String leftRightFormat;
    private final Function<String, String> headerFormatFunction;
    private final String footer;
    private final NumberFormat percentageFormat;

    @Inject
    public Console() {
        // The width of the header and footer takes into account the width of the console timestamp from the appender
        // The full width of headers and footers should be 100 characters total
        leftRightFormat = "%-35s: %s";
        headerFormatFunction = h -> String.format( "=".repeat(((90 - h.length()) / 2) + (h.length() % 2) - 1) + " %%s %s", "=".repeat(((90 - h.length()) / 2) - 1));
        footer = "=".repeat(90);
        percentageFormat = NumberFormat.getPercentInstance();
    }

    public void info(String message, Object... params) {
        CONSOLE.info(message, params);
    }

    public void warn(String message, Object... params) {
        CONSOLE.warn(message, params);
    }

    public void error(String message, Object... params) {
        CONSOLE.error(message, params);
    }

    public void infoLeftRight(String left, Object... params) {
        CONSOLE.info(toLeftRightMessage(left, params));
    }

    public void warnLeftRight(String left, Object... params) {
        CONSOLE.warn(toLeftRightMessage(left, params));
    }

    public void errorLeftRight(String left, Object... params) {
        CONSOLE.error(toLeftRightMessage(left, params));
    }

    public void header(String header, Object... params) {
        String message = String.format(header, params);
        CONSOLE.info(String.format(headerFormatFunction.apply(message), message));
    }

    public void footer() {
        CONSOLE.info(footer);
    }

    public String formatMillis(long millis) {
        Duration duration = Duration.ofMillis(millis);
        if (millis < 1000) {
            return String.format("%sms", duration.toMillis());
        } else if (millis < 60000) {
            return String.format("%ss", duration.toSeconds() + Math.round(duration.toMillisPart() / 1000d));
        } else {
            return String.format("%sm %ss", duration.toMinutes(), duration.toSecondsPart() + Math.round(duration.toMillisPart() / 1000d));
        }
    }

    public String toPercent(int top, int bottom) {
        return percentageFormat.format(top / (double) bottom);
    }

    private String toLeftRightMessage(String left, Object... params) {
        Object right = params[params.length - 1];
        return String.format(leftRightFormat, String.format(left, params), right);
    }

}
