package com.fluxtion.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The account lines printed from {@code GET /v1/usage}: the plan, the compile credits (the response's top-level
 * {@code available}, which is the compile balance) and the interpret credits ({@code kinds.interpret.available}), then
 * the plan's limits from {@code capabilities}. A compiler from before credit kinds and plans sends neither
 * {@code kinds} nor {@code capabilities}; the interpret figure and the limits line are then left out, never guessed.
 *
 * <p>The inputs limit is {@code maxInputs}: event types plus the methods of exported interfaces, each method one
 * input. A compiler from before that limit states only {@code maxEventTypes}, which limits event types alone and
 * is printed as such ({@code 5 event types}), with nothing said about exported methods. The current compiler also
 * sends {@code maxEventTypes} as a deprecated alias equal to {@code maxInputs}; whenever {@code maxInputs} is present
 * it wins and the two are never printed side by side.</p>
 *
 * <p>Every server-supplied value passes through {@link CompilerAuthClient#printable(String)}.</p>
 */
final class UsageSummary {

    private UsageSummary() {
    }

    static List<String> lines(Map<String, Object> usage) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder("Plan: ").append(planName(text(usage.get("plan"))))
                .append("; compile credits available: ").append(orUnknown(number(usage.get("available"))));
        Map<String, Object> interpret = map(map(usage.get("kinds")), "interpret");
        if (interpret != null) {
            b.append("; interpret credits available: ").append(orUnknown(number(interpret.get("available"))));
        }
        out.add(b.append('.').toString());

        Map<String, Object> caps = map(usage.get("capabilities"));
        if (caps != null) {
            List<String> limits = new ArrayList<>();
            String nodes = number(caps.get("maxNodes"));
            if (nodes != null) limits.add(nodes + " nodes");
            String inputs = number(caps.get("maxInputs"));
            if (inputs != null) {
                limits.add(inputs + " inputs (event types + exported methods)");
            } else {
                // a compiler before the inputs limit: its maxEventTypes counts event types only
                String eventTypes = number(caps.get("maxEventTypes"));
                if (eventTypes != null) limits.add(eventTypes + ("1".equals(eventTypes) ? " event type" : " event types"));
            }
            String interfaces = number(caps.get("maxExportedInterfaces"));
            if (interfaces != null)
                limits.add(interfaces + ("1".equals(interfaces) ? " exported interface" : " exported interfaces"));
            Object targets = caps.get("targets");
            if (targets instanceof List && !((List<?>) targets).isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object t : (List<?>) targets) names.add(targetName(text(t)));
                limits.add("targets " + String.join(", ", names));
            }
            if (!limits.isEmpty()) {
                out.add("Plan limits: " + String.join(", ", limits)
                        + (map(caps.get("overrides")) != null && !map(caps.get("overrides")).isEmpty()
                        ? " (with account overrides)." : "."));
            }
        }
        return out;
    }

    /** {@code free} → {@code Free}; the server's word otherwise. */
    static String planName(String plan) {
        if (plan == null || plan.isEmpty()) return "unknown";
        return Character.toUpperCase(plan.charAt(0)) + plan.substring(1);
    }

    static String targetName(String target) {
        if (target == null) return "?";
        switch (target.toLowerCase(java.util.Locale.ROOT)) {
            case "java":
                return "Java";
            case "cpp":
                return "C++";
            default:
                return target;
        }
    }

    private static String orUnknown(String v) {
        return v == null ? "unknown" : v;
    }

    private static String text(Object v) {
        return v == null ? null : CompilerAuthClient.printable(v.toString());
    }

    /** A whole number as digits ({@code 250}, not {@code 250.0}); null when absent or not a number. */
    private static String number(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : v.toString();
        }
        return v == null ? null : CompilerAuthClient.printable(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return parent == null ? null : map(parent.get(key));
    }
}
