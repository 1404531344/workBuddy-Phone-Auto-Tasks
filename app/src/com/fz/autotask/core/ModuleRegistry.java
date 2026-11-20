package com.fz.autotask.core;

import java.util.ArrayList;
import java.util.List;

/** 模块总表：整个 App 的「插件」清单就在这里。 */
public final class ModuleRegistry {

    private static final List<Module> MODULES = new ArrayList<Module>();

    static {
        // ↓↓↓ 接入新模块 = 在这里加一行 ↓↓↓
        MODULES.add(new com.fz.autotask.modules.workbuddy.WorkBuddyModule());
        MODULES.add(new com.fz.autotask.modules.workbuddy.TravelModule());
        MODULES.add(new com.fz.autotask.modules.demo.DemoModule());
        // ↑↑↑ 接入新模块 = 在这里加一行 ↑↑↑
    }

    private ModuleRegistry() {
    }

    public static List<Module> all() {
        return MODULES;
    }

    public static Module byId(String id) {
        for (Module m : MODULES) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }
}
