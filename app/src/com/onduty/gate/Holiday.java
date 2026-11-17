package com.onduty.gate;

import android.content.Context;

import java.util.Calendar;

/**
 * 内置法定节假日 / 调休补班表：2026、2027 两年。
 *
 * 数据来源：国务院办公厅通知
 * - 2026：国办发明电〔2025〕7 号（2025-11-04）
 * - 2027：国务院办公厅通知（2026-11-05）
 *
 * 纯本地静态数据：不联网、不缓存、不申请 INTERNET。
 * 其他年份回落到「周一至周五」，诊断区会如实标注「未内置」。
 */
public final class Holiday {

    /** 2026 年放假日，MMDD 闭区间。 */
    private static final int[][] OFF_2026 = {
            {101, 103}, {215, 223}, {404, 406}, {501, 505},
            {619, 621}, {925, 927}, {1001, 1007}
    };
    /** 2026 年调休补班日（即便周末也上班）。 */
    private static final int[][] MAKEUP_2026 = {
            {104, 104}, {214, 214}, {228, 228}, {509, 509}, {920, 920}, {1010, 1010}
    };

    /** 2027 年放假日，MMDD 闭区间。 */
    private static final int[][] OFF_2027 = {
            {101, 103}, {205, 213}, {403, 405}, {501, 505},
            {609, 609}, {915, 915}, {1001, 1007}
    };
    /** 2027 年调休补班日。 */
    private static final int[][] MAKEUP_2027 = {
            {131, 131}, {214, 214}, {508, 508}, {926, 926}, {1009, 1009}
    };

    private Holiday() {
    }

    /** 该年份是否内置了法定节假日数据。 */
    public static boolean hasData(int year) {
        return year == 2026 || year == 2027;
    }

    /** 判断 MMDD 是否落在任一闭区间内。 */
    private static boolean inRanges(int md, int[][] ranges) {
        for (int i = 0; i < ranges.length; i++) {
            if (md >= ranges[i][0] && md <= ranges[i][1]) return true;
        }
        return false;
    }

    /** 该日期是否为放假日。 */
    public static boolean isHoliday(int year, int md) {
        if (year == 2026) return inRanges(md, OFF_2026);
        if (year == 2027) return inRanges(md, OFF_2027);
        return false;
    }

    /** 该日期是否为调休补班日。 */
    public static boolean isMakeup(int year, int md) {
        if (year == 2026) return inRanges(md, MAKEUP_2026);
        if (year == 2027) return inRanges(md, MAKEUP_2027);
        return false;
    }

    /** 统一判定某天是否为工作日。 */
    public static boolean isWorkday(Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int md = (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH);
        if (hasData(year)) {
            if (isHoliday(year, md)) return false;
            if (isMakeup(year, md)) return true;
        }
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
    }

    /** 供运行诊断显示数据来源。 */
    public static String sourceText(Context c, int year) {
        if (year == 2026) return "内置表 2026";
        if (year == 2027) return "内置表 2027";
        return year + " 年未内置，按周一至周五";
    }
}
