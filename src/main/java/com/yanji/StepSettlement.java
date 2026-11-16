package com.yanji;

/**
 * 微信步数 → 叽分 的纯结算规则（无外部依赖，可在 JDK8 下独立验证）。
 *
 * <p>规则：每 stepUnit 步折算 1 叽分，单日上限 stepDailyCap（如每 1000 步 1 分、单日封顶 5 分）。
 * 整体每日叽分上限（如 10 分）由 PointsService 在入账时统一约束，这里只负责步数这一项的折算。
 */
public final class StepSettlement {

    private StepSettlement() {}

    public static int points(int steps, int stepUnit, int stepDailyCap) {
        if (steps <= 0 || stepUnit <= 0 || stepDailyCap <= 0) return 0;
        int earned = steps / stepUnit; // 向下取整：不足一个单位不算分
        return Math.min(earned, stepDailyCap);
    }
}
