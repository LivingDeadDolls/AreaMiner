package areaminer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Help text and tab completion for /areaminer, shared so the Paper and Fabric commands cannot drift apart. */
// The Japanese aliases 平面/立体/階段/整地 are accepted as mode names in every language.
public final class CommandText {
    private CommandText() {}

    /** veinMining: whether the server has vein mining on (the ore line is left out otherwise). */
    public static List<String> help(String toggleKeys, Messages m, boolean veinMining) {
        List<String> lines = new ArrayList<>(List.of(m.get("help.1", toggleKeys), m.get("help.2"), m.get("help.3"), m.get("help.4"), m.get("help.7")));
        if (veinMining) lines.add(m.get("help.6"));
        lines.add(m.get("help.5"));
        return lines;
    }

    /** Candidates for the last argument; args are the words typed so far (the last may be partial or empty). */
    public static List<String> suggestions(String[] args, boolean admin) {
        if (args.length == 0) return List.of();
        String first = args[0].toLowerCase(Locale.ROOT);
        boolean plane = first.equals("plane") || first.equals("平面");
        boolean cube = first.equals("cube") || first.equals("立体");
        boolean stairs = first.equals("stairs") || first.equals("階段");
        boolean flatten = first.equals("flatten") || first.equals("整地");
        List<String> options = new ArrayList<>();
        switch (args.length) {
            case 1 -> {
                options.addAll(List.of("plane", "cube", "stairs", "flatten", "next", "off", "status", "help", "highlight", "drops", "durability", "tools"));
                if (admin) options.add("reload");
            }
            case 2 -> {
                if (plane || cube || stairs || flatten) options.addAll(range(1, Settings.MAX_SIDE));
                else switch (first) {
                    case "highlight" -> options.addAll(List.of("on", "off"));
                    case "drops" -> options.addAll(List.of("feet", "block"));
                    case "durability" -> options.addAll(List.of("normal", "half", "none"));
                    case "tools" -> options.add("all");
                    default -> { }
                }
            }
            case 3 -> {
                if (plane || cube || flatten) options.addAll(range(1, Settings.MAX_SIDE));
                else if (stairs) options.addAll(range(2, 10));
            }
            case 4 -> { if (cube || stairs || flatten) options.addAll(range(1, 10)); }
            default -> { }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }

    private static List<String> range(int from, int to) {
        List<String> values = new ArrayList<>();
        for (int i = from; i <= to; i++) values.add(Integer.toString(i));
        return values;
    }
}
