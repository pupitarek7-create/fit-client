package net.fit.client.module;

public abstract class Setting {
    public final String name;

    protected Setting(String name) { this.name = name; }

    public static final class Bool extends Setting {
        public boolean value;

        public Bool(String name, boolean value) { super(name); this.value = value; }

        public void toggle() { value = !value; }
    }

    public static final class Num extends Setting {
        public double value;
        public final double min, max, step;

        public Num(String name, double value, double min, double max, double step) {
            super(name);
            this.value = value; this.min = min; this.max = max; this.step = step;
        }

        public int intValue() { return (int) Math.round(value); }

        public void add(int direction) {
            value = Math.max(min, Math.min(max, value + direction * step));
        }
    }
}
