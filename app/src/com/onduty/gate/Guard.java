package com.onduty.gate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 规则与状态机：休假 > 地点 > 时间窗 > 默认放行。
 * 仅面向工作软件通知，不含影院/会议等生活场景。
 * fail-open：任何判据不可用都回到 ALLOW。
 */
public class Guard {

    /** 判定结果：放行，所有通知照常提醒。 */
    public static final int MODE_ALLOW = 0;
    /** 判定结果：静默，收起目标 App 的通知并发无声摘要。 */
    public static final int MODE_SILENCE = 1;

    /** 地点：无网络或拿不到网关。 */
    public static final int PLACE_UNKNOWN = 0;
    /** 地点：命中已绑定的办公网指纹。 */
    public static final int PLACE_WORK = 1;
    /** 地点：命中已绑定的家庭网指纹。 */
    public static final int PLACE_AWAY = 2;
    /** 地点：连着网但与两处指纹都不匹配。 */
    public static final int PLACE_OTHER = 3;

    /* 编号保持向后兼容：0 自定义 / 1 工作日 / 2 每天 / 3 法定工作日 / 4 休假。
       1 与 3 现在都按「法定工作日（内置节假日表）」处理。 */
    public static final int DAY_CUSTOM = 0;
    public static final int DAY_WORKDAY = 1;
    public static final int DAY_EVERY = 2;
    public static final int DAY_LEGAL = 3;
    public static final int DAY_VACATION = 4;

    /** 办公网连上即视为到岗，不再做驻留确认；此常量保留仅为回退参考。 */
    @Deprecated
    public static final long ENTER_MS = 2 * 60 * 1000L;
    /** 离开确认：连家庭网需连续满 5 分钟才翻转为下班。 */
    public static final long LEAVE_MS = 5 * 60 * 1000L;
    /** 翻转后冷却：家庭网判定变化后 10 分钟内不再翻转。 */
    public static final long COOLDOWN_MS = 10 * 60 * 1000L;
    /** 下班后休息时间：前 N 分钟照常放行，之后到 OVERTIME_AFTER_MIN 收起，满 OVERTIME_AFTER_MIN 按加班放行。 */
    public static final int REST_FIRST_MIN = 20;
    public static final int OVERTIME_AFTER_MIN = 60;

    /** 偏好设置文件名；保留旧名以兼容历史配置。 */
    private static final String P = "guard2";

    /** 目标应用列表的内存缓存，避免每次判定都解析 JSON。 */
    private static List<String[]> targetCache;
    /** 缓存对应的配置版本号，变化时失效缓存。 */
    private static long targetVer = -1;

    /** 取得本模块的私有偏好设置。 */
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    // ---------------- 总开关 ----------------

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean("enabled", v).apply();
    }

    // ---------------- 目标应用 ----------------

    public static synchronized List<String[]> targets(Context c) {
        long v = prefs(c).getLong("t_ver", 0);
        if (targetCache != null && targetVer == v) return targetCache;
        List<String[]> list = new ArrayList<String[]>();
        String raw = prefs(c).getString("targets", "");
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String pkg = o.optString("pkg", "");
                    if (pkg.length() == 0) continue;
                    list.add(new String[]{pkg, o.optString("label", pkg)});
                }
            } catch (Exception ignored) {
            }
        }
        targetCache = list;
        targetVer = v;
        return list;
    }

    public static synchronized void setTargets(Context c, List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", list.get(i)[0]);
                o.put("label", list.get(i)[1]);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        long v = prefs(c).getLong("t_ver", 0) + 1;
        prefs(c).edit().putString("targets", arr.toString()).putLong("t_ver", v).apply();
        targetCache = new ArrayList<String[]>(list);
        targetVer = v;
    }

    public static boolean hasTarget(Context c, String pkg) {
        List<String[]> all = targets(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i)[0].equals(pkg)) return true;
        }
        return false;
    }

    // ---------------- 时间窗 ----------------

    public static boolean scheduleOn(Context c) {
        return prefs(c).getBoolean("sched_on", true);
    }

    public static void setScheduleOn(Context c, boolean v) {
        prefs(c).edit().putBoolean("sched_on", v).apply();
    }

    public static int startMin(Context c) {
        return prefs(c).getInt("sched_start", 9 * 60);
    }

    public static int endMin(Context c) {
        return prefs(c).getInt("sched_end", 18 * 60);
    }

    public static void setStartMin(Context c, int v) {
        prefs(c).edit().putInt("sched_start", v).apply();
    }

    public static void setEndMin(Context c, int v) {
        prefs(c).edit().putInt("sched_end", v).apply();
    }

    public static int dayMask(Context c) {
        return prefs(c).getInt("sched_days", 0x1F);
    }

    public static void setDayMask(Context c, int v) {
        prefs(c).edit().putInt("sched_days", v).apply();
    }

    public static int dayMode(Context c) {
        return prefs(c).getInt("sched_daymode", DAY_LEGAL);
    }

    public static void setDayMode(Context c, int v) {
        prefs(c).edit().putInt("sched_daymode", v).apply();
    }

    /** 法定工作日：内置表 2026/2027 优先，其他年份回落周一至周五。全程零联网。 */
    public static boolean legalWorkday(Context c, Calendar cal) {
        return Holiday.isWorkday(cal);
    }

    /**
     * 判断某一天是否属于「工作时段覆盖的日期」。
     * 法定/每天/休假按内置节假日表或全选处理，自定义按星期掩码判断。
     */
    public static boolean dayAllowed(Context c, Calendar dayCal) {
        int mode = dayMode(c);
        if (mode == DAY_LEGAL || mode == DAY_WORKDAY || mode == DAY_VACATION) return legalWorkday(c, dayCal);
        int mask = mode == DAY_EVERY ? 0x7F : dayMask(c);
        int dow = dayCal.get(Calendar.DAY_OF_WEEK);
        int idx = dow == Calendar.SUNDAY ? 6 : dow - 2;
        return ((mask >> idx) & 1) == 1;
    }

    /**
     * 当前时刻是否落在上班时间窗内，且当天是允许的工作日。
     * 支持跨零点时间窗（如 22:00–06:00）：跨零点时对日期做 -1 天偏移再判工作日。
     */
    public static boolean inWindow(Context c, long now) {
        int start = startMin(c);
        int end = endMin(c);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        boolean in;
        int shift = 0;
        if (start == end) {
            in = true;
        } else if (start < end) {
            in = minutes >= start && minutes < end;
        } else {
            if (minutes >= start) in = true;
            else if (minutes < end) {
                in = true;
                shift = -1;
            } else in = false;
        }
        if (!in) return false;
        Calendar dcal = (Calendar) cal.clone();
        if (shift != 0) dcal.add(Calendar.DAY_OF_YEAR, shift);
        return dayAllowed(c, dcal);
    }

    // ---------------- 休假 ----------------

    /** 休假是否仍在进行中：结束时间未到即为有效。 */
    public static boolean vacationActive(Context c, long now) {
        return prefs(c).getLong("vac_end", 0) > now;
    }

    /** 设置休假区间（毫秒时间戳）。 */
    public static void setVacation(Context c, long start, long end) {
        prefs(c).edit().putLong("vac_start", start).putLong("vac_end", end).apply();
    }

    /** 结束休假：清除时间区间，并把日期模式复位为法定工作日，避免全天误放行。 */
    public static void clearVacation(Context c) {
        prefs(c).edit().remove("vac_start").remove("vac_end")
                .putInt("sched_daymode", DAY_LEGAL).apply();
    }

    /** 休假的剩余时间描述，供顶部状态卡显示。 */
    public static String vacationText(Context c, long now) {
        long end = prefs(c).getLong("vac_end", 0);
        if (end <= now) return "无";
        long left = end - now;
        long hours = left / 3600000L;
        long mins = (left % 3600000L) / 60000L;
        return hours > 0 ? "剩余 " + hours + " 小时 " + mins + " 分" : "剩余 " + mins + " 分";
    }

    // ---------------- 网络指纹 ----------------

    public static String fpOffice(Context c) {
        return prefs(c).getString("fp_office", "");
    }

    public static String fpHome(Context c) {
        return prefs(c).getString("fp_home", "");
    }

    public static void setFpOffice(Context c, String json) {
        prefs(c).edit().putString("fp_office", json).apply();
    }

    public static void setFpHome(Context c, String json) {
        prefs(c).edit().putString("fp_home", json).apply();
    }

    public static void clearFpOffice(Context c) {
        prefs(c).edit().remove("fp_office").apply();
        resetPlace(c);
        addLog(c, "已删除办公网络绑定");
    }

    public static void clearFpHome(Context c) {
        prefs(c).edit().remove("fp_home").apply();
        resetPlace(c);
        addLog(c, "已删除家庭网络绑定");
    }

    public static boolean hasAnyFp(Context c) {
        return fpOffice(c).length() > 0 || fpHome(c).length() > 0;
    }

    public static boolean fingerprintConflict(Context c) {
        String o = fpOffice(c);
        String h = fpHome(c);
        if (o.length() == 0 || h.length() == 0) return false;
        return NetFp.matches(NetFp.parse(o), NetFp.parse(h));
    }

    // ---------------- 摘要 ----------------

    /** 是否启用「收起后发无声摘要」。 */
    public static boolean summaryOn(Context c) {
        return prefs(c).getBoolean("summary_on", true);
    }

    /** 设置无声摘要开关。 */
    public static void setSummaryOn(Context c, boolean v) {
        prefs(c).edit().putBoolean("summary_on", v).apply();
    }

    // ---------------- 地点状态机 ----------------

    /** 读取当前判定的地点状态。 */
    public static int lastPlace(Context c) {
        return prefs(c).getInt("pl_last", PLACE_UNKNOWN);
    }

    /** 清空地点状态与全部待定/冷却数据，回到未知。 */
    private static void resetPlace(Context c) {
        prefs(c).edit()
                .putInt("pl_last", PLACE_UNKNOWN)
                .putLong("pl_flip", 0)
                .putString("pl_gw", "")
                .remove("pl_pend").remove("pl_since")
                .apply();
    }

    /** 手动强制设定地点（诊断用），并重置待定与冷却计数。 */
    public static void forcePlace(Context c, int place) {
        prefs(c).edit()
                .putInt("pl_last", place)
                .putLong("pl_flip", System.currentTimeMillis())
                .putString("pl_gw", NetFp.current(c).gateway)
                .remove("pl_pend").remove("pl_since")
                .apply();
    }

    /** 家庭网切换确认已持续的分钟数，0 表示当前没有待定切换。 */
    public static int pendingMin(Context c) {
        long since = prefs(c).getLong("pl_since", 0);
        if (since <= 0) return 0;
        long d = System.currentTimeMillis() - since;
        if (d < 0) return 0;
        return (int) Math.max(1, Math.ceil(d / 60000.0));
    }

    /**
     * 带迟滞与冷却的地点判定。
     * OTHER / UNKNOWN 是「明确不在岗」，立即生效且不参与冷却；
     * 掉线后重连按新网关重新计时（修复 2.0.1 的冷却残留）。
     */
    public static int tickPlace(Context c, long now) {
        SharedPreferences p = prefs(c);
        int last = p.getInt("pl_last", PLACE_UNKNOWN);
        String off = p.getString("fp_office", "");
        String home = p.getString("fp_home", "");
        if (off.length() == 0 && home.length() == 0) return last;

        NetFp cur = NetFp.current(c);
        if (!cur.valid) {
            if (last != PLACE_UNKNOWN) {
                p.edit().putInt("pl_last", PLACE_UNKNOWN)
                        .putLong("pl_flip", 0)
                        .remove("pl_pend").remove("pl_since").apply();
                addLog(c, cur.wifi ? "Wi-Fi 拿不到网关 → 地点未知，改由时间窗判定"
                        : "已断开 Wi-Fi → 地点未知，改由时间窗判定");
            } else {
                p.edit().remove("pl_pend").remove("pl_since").apply();
            }
            return PLACE_UNKNOWN;
        }

        boolean mo = off.length() > 0 && NetFp.matches(cur, NetFp.parse(off));
        boolean mh = home.length() > 0 && NetFp.matches(cur, NetFp.parse(home));
        int raw = mo && !mh ? PLACE_WORK : (mh && !mo ? PLACE_AWAY : PLACE_OTHER);

        if (raw == PLACE_OTHER) {
            if (last != PLACE_OTHER) {
                p.edit().putInt("pl_last", PLACE_OTHER).putLong("pl_flip", 0)
                        .putString("pl_gw", cur.gateway)
                        .remove("pl_pend").remove("pl_since").apply();
                addLog(c, "网络切换 → 其他网络（" + cur.shortLabel() + "）");
            } else {
                p.edit().putString("pl_gw", cur.gateway).apply();
            }
            return PLACE_OTHER;
        }

        // 办公网是强信号：连上即视为到岗，不做驻留确认、不受冷却阻挡。
        // 正常场景下只有公司工位才会连办公网，等待 2 分钟反而会漏掉刚到岗的通知。
        if (raw == PLACE_WORK) {
            if (last != PLACE_WORK) {
                p.edit().putInt("pl_last", PLACE_WORK).putLong("pl_flip", now)
                        .putString("pl_gw", cur.gateway)
                        .remove("pl_pend").remove("pl_since").apply();
                addLog(c, "连上办公网络 → 立即视为到岗");
            } else {
                p.edit().putString("pl_gw", cur.gateway)
                        .remove("pl_pend").remove("pl_since").apply();
            }
            return PLACE_WORK;
        }

        // 网关变化（包括从空值恢复）即作废旧的待定计数与冷却，仅对家庭网生效
        String gwBefore = p.getString("pl_gw", "");
        if (!gwBefore.equals(cur.gateway)) {
            p.edit().putLong("pl_flip", 0).putString("pl_gw", cur.gateway)
                    .remove("pl_pend").remove("pl_since").apply();
            addLog(c, "网络切换 → 家庭网络（" + cur.gateway + "），开始确认");
        }

        if (raw == last) {
            p.edit().remove("pl_pend").remove("pl_since").apply();
            return last;
        }

        long flipAt = p.getLong("pl_flip", 0);
        if (flipAt > 0 && now - flipAt < COOLDOWN_MS) return last;

        int pend = p.getInt("pl_pend", -1);
        long since = p.getLong("pl_since", 0);
        if (pend != raw || since <= 0) {
            p.edit().putInt("pl_pend", raw).putLong("pl_since", now).apply();
            return last;
        }
        long need = raw == PLACE_WORK ? ENTER_MS : LEAVE_MS;
        if (now - since >= need) {
            p.edit().putInt("pl_last", raw).putLong("pl_flip", now)
                    .remove("pl_pend").remove("pl_since").apply();
            addLog(c, raw == PLACE_WORK ? "地点判定 → 办公网络" : "地点判定 → 家庭网络");
            return raw;
        }
        return last;
    }

    // ---------------- Mode ----------------

    /**
     * 核心判定入口，按优先级依次判断：
     * <ol>
     *   <li>总开关关闭 / 休假中 → 一律放行；</li>
     *   <li>办公网：窗内放行；窗外按 offDutyPhase 分早到、休息前后段、加班；</li>
     *   <li>家庭网 → 收起；</li>
     *   <li>其他网络 / 断网 → 回落到时间窗判定。</li>
     * </ol>
     * 任何判据缺失都 fail-open 到放行，避免误把用户全天静音。
     *
     * @param reason 用于诊断区展示的判定理由，由本方法填充
     * @return {@link #MODE_ALLOW} 或 {@link #MODE_SILENCE}
     */
    public static int resolveMode(Context c, long now, StringBuilder reason) {
        if (!enabled(c)) {
            reason.append("总开关关闭，全部放行");
            return MODE_ALLOW;
        }
        if (vacationActive(c, now)) {
            reason.append("休假中 · ").append(vacationText(c, now)).append("，全部放行");
            return MODE_ALLOW;
        }

        boolean schedOn = scheduleOn(c);
        boolean inWin = schedOn && inWindow(c, now);
        int place = tickPlace(c, now);

        if (place == PLACE_WORK) {
            if (!schedOn || inWin) {
                reason.append("已连办公网络，视为上班");
                return MODE_ALLOW;
            }
            int phase = offDutyPhase(c, now);
            if (phase == PHASE_EARLY) {
                reason.append("提前连上办公网络，按已抵达放行");
                return MODE_ALLOW;
            }
            if (phase == PHASE_REST_EARLY) {
                reason.append("休息时间 · 下班后前 ").append(REST_FIRST_MIN)
                        .append(" 分钟照常放行");
                return MODE_ALLOW;
            }
            if (phase == PHASE_REST_LATE) {
                reason.append("休息时间 · 下班 ")
                        .append(REST_FIRST_MIN).append("–").append(OVERTIME_AFTER_MIN)
                        .append(" 分钟，收起工作通知");
                return MODE_SILENCE;
            }
            reason.append("加班中 · 仍连办公网络，按上班放行");
            return MODE_ALLOW;
        }
        if (place == PLACE_AWAY) {
            reason.append("已连家庭网络，视为下班");
            return MODE_SILENCE;
        }
        if (schedOn && !inWin) {
            reason.append(place == PLACE_OTHER ? "当前 Wi-Fi 不是办公网络，且已在时间窗之外"
                    : "处在上班时间窗之外");
            return MODE_SILENCE;
        }
        if (schedOn) {
            reason.append(place == PLACE_OTHER ? "当前 Wi-Fi 不是办公/家庭网络，按时间窗放行"
                    : "处在上班时间窗内（地点未知）");
            return MODE_ALLOW;
        }
        reason.append("未启用任何判据，默认放行");
        return MODE_ALLOW;
    }

    /* 下班后一小时的阶段，resolveMode 与 statusTag 共用同一套判定，避免口径分叉。 */
    /** 不在办公网窗外阶段。 */
    private static final int PHASE_NONE = 0;
    /** 提前到岗（距上班不足 2 小时）。 */
    private static final int PHASE_EARLY = 1;
    /** 下班后前 20 分钟，照常放行。 */
    private static final int PHASE_REST_EARLY = 2;
    /** 下班后 20–60 分钟，收起。 */
    private static final int PHASE_REST_LATE = 3;
    /** 满 60 分钟，按加班放行。 */
    private static final int PHASE_OVERTIME = 4;

    /**
     * 仅用于「连着办公网、但已不在时间窗内」的情形。
     * 返回距离下班时间 endMin 的阶段；跨零点时间窗按 endMin 之后的连续分钟计算。
     */
    private static int offDutyPhase(Context c, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int start = startMin(c);
        int end = endMin(c);

        if (start < end) {
            if (minutes < start) return PHASE_EARLY;
            if (minutes >= end) {
                int after = minutes - end;
                if (after < REST_FIRST_MIN) return PHASE_REST_EARLY;
                if (after < OVERTIME_AFTER_MIN) return PHASE_REST_LATE;
                return PHASE_OVERTIME;
            }
        } else if (start > end) {
            // 跨零点：窗内是 [start,24h) ∪ [0,end)；窗外是 (end,start)
            if (minutes > end && minutes < start) {
                int after = minutes - end;
                if (minutes >= start - 120) return PHASE_EARLY;  // 距上班不足 2 小时视为早到
                if (after < REST_FIRST_MIN) return PHASE_REST_EARLY;
                if (after < OVERTIME_AFTER_MIN) return PHASE_REST_LATE;
                return PHASE_OVERTIME;
            }
        }
        return PHASE_NONE;
    }

    /** 顶部状态卡的辅助标签：休假 / 已抵达 / 休息中 / 加班中 / 无。 */
    public static String statusTag(Context c, long now, int place, boolean inWin) {
        if (vacationActive(c, now)) return "休假中";
        if (place != PLACE_WORK) return "";
        if (inWin) return "上班中";
        if (!scheduleOn(c)) return "";

        int phase = offDutyPhase(c, now);
        if (phase == PHASE_EARLY) return "已抵达";
        if (phase == PHASE_REST_EARLY || phase == PHASE_REST_LATE) return "休息中";
        if (phase == PHASE_OVERTIME) return "加班中";
        return "";
    }

    /** 判定结果的中文名，供顶部状态卡主文字显示。 */
    public static String modeName(Context c, int mode) {
        return mode == MODE_SILENCE ? c.getString(R.string.mode_silence)
                : c.getString(R.string.mode_allow);
    }

    // ---------------- 文案 ----------------

    /** 把「距零点的分钟数」格式化为 HH:mm。 */
    public static String fmtTime(int min) {
        int h = min / 60;
        int m = min % 60;
        StringBuilder sb = new StringBuilder();
        if (h < 10) sb.append('0');
        sb.append(h).append(':');
        if (m < 10) sb.append('0');
        sb.append(m);
        return sb.toString();
    }

    /** 当前日期模式的展示文案。 */
    public static String daysText(Context c) {
        int mode = dayMode(c);
        if (mode == DAY_VACATION) return "休假";
        if (mode == DAY_LEGAL || mode == DAY_WORKDAY) return "法定工作日";
        if (mode == DAY_EVERY) return "每天";
        return daysText(dayMask(c));
    }

    /** 把星期掩码格式化为中文描述（如「周一、三、五」）。 */
    public static String daysText(int mask) {
        if (mask == 0) return "未选择星期";
        if (mask == 0x7F) return "每天";
        if (mask == 0x1F) return "工作日";
        if (mask == 0x60) return "周末";
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder("周");
        boolean first = true;
        for (int i = 0; i < 7; i++) {
            if (((mask >> i) & 1) == 1) {
                if (!first) sb.append('、');
                sb.append(names[i]);
                first = false;
            }
        }
        return sb.toString();
    }

    // ---------------- 日志 ----------------

    /** 追加一条带时间戳的事件日志，最多保留 12 行。 */
    public static synchronized void addLog(Context c, String msg) {
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        String old = prefs(c).getString("log", "");
        StringBuilder sb = new StringBuilder();
        sb.append(time).append("  ").append(msg);
        if (old.length() > 0) {
            String[] lines = old.split("\n");
            int keep = Math.min(lines.length, 11);
            for (int i = 0; i < keep; i++) sb.append('\n').append(lines[i]);
        }
        prefs(c).edit().putString("log", sb.toString()).apply();
    }

    /** 读取全部事件日志文本。 */
    public static String logText(Context c) {
        String s = prefs(c).getString("log", "");
        return s.length() == 0 ? "暂无事件" : s;
    }

    /** 清空事件日志。 */
    public static void clearLog(Context c) {
        prefs(c).edit().remove("log").apply();
    }

    /** 仅在判定结果真正变化时写日志，避免每分钟轮询刷屏。 */
    public static void logTransition(Context c, int mode, String reason) {
        int last = prefs(c).getInt("last_mode", -1);
        if (last == mode) return;
        prefs(c).edit().putInt("last_mode", mode).apply();
        if (last == -1) addLog(c, "启动 → " + modeName(c, mode) + "（" + reason + "）");
        else addLog(c, modeName(c, last) + " → " + modeName(c, mode) + "（" + reason + "）");
    }

    // ---------------- 诊断 ----------------

    /** 生成「运行诊断」卡片的多行文本：网络、指纹、地点状态、时间窗、节假日来源。 */
    public static String diag(Context c, long now) {
        NetFp cur = NetFp.current(c);
        String off = fpOffice(c);
        String home = fpHome(c);
        boolean mo = cur.valid && off.length() > 0 && NetFp.matches(cur, NetFp.parse(off));
        boolean mh = cur.valid && home.length() > 0 && NetFp.matches(cur, NetFp.parse(home));

        StringBuilder sb = new StringBuilder();
        sb.append("网络：");
        if (!cur.wifi) sb.append("未连接 Wi-Fi");
        else if (!cur.valid) sb.append("已连 Wi-Fi（拿不到网关）");
        else if (mo && !mh) sb.append("办公网络");
        else if (mh && !mo) sb.append("家庭网络");
        else if (mo && mh) sb.append("办公/家庭指纹重叠");
        else sb.append("其他网络（未记录）");
        if (cur.wifi && cur.gateway.length() > 0) {
            sb.append("\n    网关 ").append(cur.gateway);
            if (cur.dns.length() > 0) sb.append(" · DNS ").append(cur.dns.replace(",", " / "));
        }

        int place = lastPlace(c);
        long flip = prefs(c).getLong("pl_flip", 0);
        sb.append("\n地点状态：");
        if (place == PLACE_WORK) sb.append("办公网络");
        else if (place == PLACE_AWAY) sb.append("家庭网络");
        else if (place == PLACE_OTHER) sb.append("其他网络");
        else sb.append("未知");
        if (flip > 0) {
            sb.append(" · ").append(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(flip)))
                    .append(" 起（已 ").append(Math.max(1, (int) Math.ceil((now - flip) / 60000.0)))
                    .append(" 分钟）");
        }
        if (pendingMin(c) > 0) sb.append("\n切换确认中：已持续 ").append(pendingMin(c)).append(" 分钟");

        sb.append("\n时间窗：");
        if (scheduleOn(c)) {
            sb.append(daysText(c)).append("  ").append(fmtTime(startMin(c)))
                    .append(" - ").append(fmtTime(endMin(c)));
            if (dayMode(c) == DAY_VACATION) {
                sb.append(" · 休假中，").append(vacationText(c, now));
            } else {
                sb.append(inWindow(c, now) ? " · 当前在时段内" : " · 当前在时段外");
            }
            int m = dayMode(c);
            if (m == DAY_LEGAL || m == DAY_WORKDAY) {
                Calendar cal = Calendar.getInstance();
                sb.append("\n    今天").append(legalWorkday(c, cal) ? "上班" : "休息");
                sb.append(" · 节假日数据：").append(Holiday.sourceText(c, cal.get(Calendar.YEAR)));
            }
        } else sb.append("关闭");

        String tag = statusTag(c, now, place, inWindow(c, now));
        sb.append("\n状态标记：").append(tag.length() == 0 ? "无" : tag);
        return sb.toString();
    }
}
