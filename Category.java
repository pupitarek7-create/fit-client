package net.fit.client.module;

public enum Category {
    RENDER("RENDER"), VISUALS("VISUALS"), MISC("MISC"), CLIENT("CLIENT");

    public final String label;

    Category(String label) { this.label = label; }
}
