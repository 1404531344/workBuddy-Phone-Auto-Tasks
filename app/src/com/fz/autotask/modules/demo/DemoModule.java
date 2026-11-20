package com.fz.autotask.modules.demo;

import com.fz.autotask.core.Config;
import com.fz.autotask.core.Module;

/**
 * 示例模块：用来演示「以后接入新模块」有多简单。
 * 它什么都不做，只是证明宿主会把每个启用模块的返回值汇总进同一条通知。
 */
public final class DemoModule implements Module {

    @Override
    public String id() {
        return "demo";
    }

    @Override
    public String name() {
        return "示例模块";
    }

    @Override
    public String description() {
        return "什么都不做，只回一行字，用来验证模块链路";
    }

    @Override
    public String run(Config cfg) throws Exception {
        return "示例任务已执行";
    }
}
