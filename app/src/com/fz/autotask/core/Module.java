package com.fz.autotask.core;

/**
 * 一个「模块」= 一项可在凌晨自动执行的任务。
 * 接入新模块只需要两步：
 *   1) 新建类 implements Module
 *   2) 在 ModuleRegistry 的静态块里 add 一行
 * 宿主（闹钟 / 通知 / 配置 / 权限）全都不用改。
 */
public interface Module {

    /** 稳定 id，用于存开关状态，不要改。 */
    String id();

    /** 面板上显示的名字。 */
    String name();

    /** 面板上显示的一句话说明。 */
    String description();

    /**
     * 执行一次。返回一行人类可读的结果（会进汇总通知）。
     * 抛异常 = 本模块失败，其它模块照常跑。
     */
    String run(Config cfg) throws Exception;
}
