package areaminer;
public enum Mode {
    OFF, PLANE, CUBE, STAIRS, FLATTEN;
    public Mode next() { return values()[(ordinal() + 1) % values().length]; }
    public String label(Messages m) { return m.get("mode." + name().toLowerCase(java.util.Locale.ROOT)); }
}
