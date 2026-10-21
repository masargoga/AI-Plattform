package com.agenticpilot.source.connector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The rules of a robots.txt that apply to this crawler: Allow and Disallow with the longest match winning, and Crawl-delay. */
public final class RobotsRules {

    private record Rule(Pattern pattern, int length, boolean allow) {
    }

    private final List<Rule> rules;
    private final Duration crawlDelay;
    private final boolean disallowAll;

    private RobotsRules(List<Rule> rules, Duration crawlDelay, boolean disallowAll) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
        this.disallowAll = disallowAll;
    }

    public static RobotsRules allowAll() {
        return new RobotsRules(List.of(), Duration.ZERO, false);
    }

    public static RobotsRules disallowAll() {
        return new RobotsRules(List.of(), Duration.ZERO, true);
    }

    /** Parses a robots.txt. The group that names the crawler is used; otherwise the "*" group. */
    public static RobotsRules parse(String robots, String crawlerToken) {
        String token = crawlerToken.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> general = new ArrayList<>();
        Duration specificDelay = null;
        Duration generalDelay = null;
        boolean specificFound = false;
        List<String> agents = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : robots.split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (field.equals("user-agent")) {
                if (!lastWasAgent) {
                    agents.clear();
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            boolean forUs = agents.stream().anyMatch(a -> !a.equals("*") && token.contains(a));
            boolean forAll = agents.contains("*");
            if (forUs) {
                specificFound = true;
            }
            switch (field) {
                case "disallow", "allow" -> {
                    if (value.isEmpty() && field.equals("disallow")) {
                        continue; // an empty Disallow allows everything
                    }
                    Rule rule = new Rule(compile(value), value.length(), field.equals("allow"));
                    if (forUs) {
                        specific.add(rule);
                    } else if (forAll) {
                        general.add(rule);
                    }
                }
                case "crawl-delay" -> {
                    try {
                        Duration d = Duration.ofMillis((long) (Double.parseDouble(value) * 1000));
                        if (forUs) {
                            specificDelay = d;
                        } else if (forAll) {
                            generalDelay = d;
                        }
                    } catch (NumberFormatException ignored) {
                        // not a number: no delay requested
                    }
                }
                default -> { }
            }
        }
        List<Rule> chosen = specificFound ? specific : general;
        Duration delay = specificFound ? (specificDelay == null ? Duration.ZERO : specificDelay) : (generalDelay == null ? Duration.ZERO : generalDelay);
        return new RobotsRules(chosen, delay, false);
    }

    private static Pattern compile(String path) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '$' && i == path.length() - 1) {
                sb.append('$');
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }

    /** True when the crawler may fetch the path (with query string). */
    public boolean isAllowed(String pathAndQuery) {
        if (disallowAll) {
            return false;
        }
        Rule best = null;
        for (Rule r : rules) {
            if (r.pattern().matcher(pathAndQuery).find() && (best == null || r.length() > best.length() || (r.length() == best.length() && r.allow()))) {
                best = r;
            }
        }
        return best == null || best.allow();
    }

    public Duration crawlDelay() {
        return crawlDelay;
    }
}
