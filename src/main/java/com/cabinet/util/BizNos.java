package com.cabinet.util;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务单号生成：日期 + 纳秒片段 + 进程内递增序列，保证同一运行期内不碰撞。
 * 原先各服务用 nanoTime()%10000 生成单号，紧凑循环/设备批量补传时低四位会碰撞，
 * 导致同一前缀出现重复单号（复核单/退款单/订单等按单号查询会命中多条）。
 */
public final class BizNos {

    private static final AtomicLong SEQ = new AtomicLong();

    private BizNos() {}

    public static String next(String prefix) {
        long s = SEQ.incrementAndGet();
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%05d", Math.abs(System.nanoTime()) % 100_000)
                + String.format("%04d", s % 10_000);
    }
}
