package net.fit.client.module;

import java.util.ArrayList;
import java.util.List;

import net.fit.client.module.impl.SusChunkFinder;

public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();
    public static SusChunkFinder susChunkFinder;

    private ModuleManager() { }

    public static void init() {
        susChunkFinder = new SusChunkFinder();
        MODULES.add(susChunkFinder);
    }

    public static List<Module> all() { return MODULES; }

    public static List<Module> in(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module module : MODULES) if (module.category == category) out.add(module);
        return out;
    }
}
