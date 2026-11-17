package com.onduty.gate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 主界面：展示当前判定状态，提供目标 App、时间窗、休假与网络绑定的设置入口。
 *
 * <p>界面数据全部来自 {@link Guard}，本类不持有判定逻辑，只负责绑定控件、
 * 弹窗交互与每秒一次的状态刷新。
 */
public class MainActivity extends Activity {

    /** startActivityForResult 请求码：选择目标应用。 */
    private static final int REQ_PICK = 101;
    /** Android 13+ 通知权限请求码。 */
    private static final int REQ_NOTIF_PERM = 900;

    /* ---- 界面控件 ---- */
    private TextView tvMode, tvReason, tvPerm, tvTargetCount, tvTargetSummary, tvScheduleMain;
    private TextView tvOfficeState, tvHomeState, tvFpConflict, tvDiag, tvLog, tvPresetHint;
    private TextView btnStart, btnEnd, tvFooter;
    private View vAccent, cardPerm, btnEndVacation;
    private Switch swSummary, swSchedule, swEnabled;
    /** 周一至周日七个星期胶囊。 */
    private final TextView[] days = new TextView[7];
    /** 时间窗 / 办公网 / 家庭网 / 休假 四个状态胶囊。 */
    private TextView pillTime, pillOffice, pillHome, pillVacation;

    /* ---- 状态刷新 ---- */
    /** 主线程 Handler，驱动每秒刷新与网络回调。 */
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 周期性刷新任务。 */
    private Runnable uiTick;
    /** 正在把配置写入控件时置 true，避免监听器把界面值反向写回。 */
    private boolean binding = false;
    private ConnectivityManager cm;
    /** 网络变化回调：切网后立即刷新界面，不必等下一秒轮询。 */
    private ConnectivityManager.NetworkCallback netCb;

    /** 初始化界面、绑定事件并申请必要权限。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindActions();
        tvFooter.setText("查看使用说明与局限 · 零联网 · 无定位 · 无存储读写");
        if (Guard.summaryOn(this)) requestNotifPermission();
    }

    /** 根据 ID 查找并缓存所有控件引用。 */

    private void bindViews() {
        tvMode = findViewById(R.id.tvMode);
        tvReason = findViewById(R.id.tvReason);
        vAccent = findViewById(R.id.vAccent);
        cardPerm = findViewById(R.id.cardPerm);
        tvPerm = findViewById(R.id.tvPerm);
        tvTargetCount = findViewById(R.id.tvTargetCount);
        tvTargetSummary = findViewById(R.id.tvTargetSummary);
        tvScheduleMain = findViewById(R.id.tvScheduleMain);
        tvOfficeState = findViewById(R.id.tvOfficeState);
        tvHomeState = findViewById(R.id.tvHomeState);
        tvFpConflict = findViewById(R.id.tvFpConflict);
        tvDiag = findViewById(R.id.tvDiag);
        tvLog = findViewById(R.id.tvLog);
        tvPresetHint = findViewById(R.id.tvPresetHint);
        btnStart = findViewById(R.id.btnStart);
        btnEnd = findViewById(R.id.btnEnd);
        btnEndVacation = findViewById(R.id.btnEndVacation);
        tvFooter = findViewById(R.id.tvFooter);
        swSummary = findViewById(R.id.swSummary);
        swSchedule = findViewById(R.id.swSchedule);
        swEnabled = findViewById(R.id.swEnabled);

        pillTime = findViewById(R.id.pillTime);
        pillOffice = findViewById(R.id.pillOffice);
        pillHome = findViewById(R.id.pillHome);
        pillVacation = findViewById(R.id.pillVacation);

        days[0] = findViewById(R.id.day0);
        days[1] = findViewById(R.id.day1);
        days[2] = findViewById(R.id.day2);
        days[3] = findViewById(R.id.day3);
        days[4] = findViewById(R.id.day4);
        days[5] = findViewById(R.id.day5);
        days[6] = findViewById(R.id.day6);
    }

    /** 为开关、按钮、胶囊绑定点击与状态变化监听。 */

    private void bindActions() {
        findViewById(R.id.btnPerm).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openListenerSettings(); }
        });
        findViewById(R.id.btnManage).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, AppPickerActivity.class), REQ_PICK);
            }
        });

        swSummary.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                Guard.setSummaryOn(MainActivity.this, on);
                if (on) requestNotifPermission();
            }
        });
        swSchedule.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                Guard.setScheduleOn(MainActivity.this, on);
                applyNow();
                refresh();
            }
        });
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                Guard.setEnabled(MainActivity.this, on);
                Guard.addLog(MainActivity.this, on ? "启用自动判定" : "暂停自动判定，全部放行");
                applyNow();
                refresh();
            }
        });

        for (int i = 0; i < 7; i++) {
            final int idx = i;
            days[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Guard.dayMode(MainActivity.this) != Guard.DAY_CUSTOM) {
                        Guard.setDayMode(MainActivity.this, Guard.DAY_CUSTOM);
                        Toast.makeText(MainActivity.this, "已切换为自定义星期", Toast.LENGTH_SHORT).show();
                    }
                    Guard.setDayMask(MainActivity.this, shownMask() ^ (1 << idx));
                    applyNow();
                    refresh();
                }
            });
        }

        findViewById(R.id.btnPresetLegal).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Guard.setDayMode(MainActivity.this, Guard.DAY_LEGAL);
                Guard.setDayMask(MainActivity.this, 0x1F);
                applyNow();
                refresh();
            }
        });
        findViewById(R.id.btnPresetVacation).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showVacationDialog(); }
        });
        findViewById(R.id.btnPresetEvery).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Guard.setDayMode(MainActivity.this, Guard.DAY_EVERY);
                Guard.setDayMask(MainActivity.this, 0x7F);
                applyNow();
                refresh();
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime(true); }
        });
        btnEnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime(false); }
        });

        findViewById(R.id.btnOffice).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { recordNetwork(true); }
        });
        findViewById(R.id.btnOffice).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return deleteNetwork(true); }
        });
        findViewById(R.id.btnHome).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { recordNetwork(false); }
        });
        findViewById(R.id.btnHome).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return deleteNetwork(false); }
        });

        btnEndVacation.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Guard.clearVacation(MainActivity.this);
                Guard.addLog(MainActivity.this, "结束休假，恢复自动判定");
                applyNow();
                refresh();
                Toast.makeText(MainActivity.this, "已结束休假", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Guard.clearLog(MainActivity.this);
                refresh();
            }
        });
        tvFooter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        });
    }

    /** 界面回到前台时刷新状态并启动每秒轮询。 */
    @Override protected void onResume() {
        super.onResume();
        refresh();
        registerNet();
        uiTick = new Runnable() {
            @Override public void run() {
                refresh();
                ui.postDelayed(this, 5000);
            }
        };
        ui.postDelayed(uiTick, 5000);
    }

    /** 界面离开前台时停止轮询，避免无谓耗电。 */
    @Override protected void onPause() {
        super.onPause();
        if (uiTick != null) ui.removeCallbacks(uiTick);
        unregisterNet();
    }

    /** 注册默认网络回调，网络变化时立即刷新地点胶囊。 */

    private void registerNet() {
        try {
            if (cm == null) cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || netCb != null) return;
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { postRefresh(); }
                @Override public void onLost(Network network) { postRefresh(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { postRefresh(); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable ignored) {
        }
    }

    /** 注销默认网络回调。 */

    private void unregisterNet() {
        try {
            if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb);
        } catch (Throwable ignored) {
        }
        netCb = null;
    }

    /** 把刷新任务投递到主线程。 */

    private void postRefresh() {
        ui.post(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    /** 从 Guard 读取当前判定与配置，刷新全部界面元素。 */

    private void refresh() {
        binding = true;
        long now = System.currentTimeMillis();
        boolean access = isListenerEnabled(this);
        cardPerm.setVisibility(access ? View.GONE : View.VISIBLE);
        if (!access) tvPerm.setText(R.string.perm_missing);

        List<String[]> targets = Guard.targets(this);
        tvTargetCount.setText(targets.isEmpty() ? "未选择" : targets.size() + " 个");
        if (targets.isEmpty()) {
            tvTargetSummary.setText("点右侧「管理」选择要接管的 App，静默时只收起它们的通知");
        } else {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(targets.size(), 4);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append("、");
                sb.append(targets.get(i)[1]);
            }
            if (targets.size() > n) sb.append(" 等 ").append(targets.size()).append(" 个");
            if (Guard.summaryOn(this) && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                sb.append("\n系统通知权限未授予，收起摘要不会显示（点本页任意处可重新申请）");
            }
            tvTargetSummary.setText(sb.toString());
        }
        swSummary.setChecked(Guard.summaryOn(this));
        swEnabled.setChecked(Guard.enabled(this));
        swSchedule.setChecked(Guard.scheduleOn(this));

        tvScheduleMain.setText(Guard.daysText(this) + "  "
                + Guard.fmtTime(Guard.startMin(this)) + " → " + Guard.fmtTime(Guard.endMin(this)));
        btnStart.setText(Guard.fmtTime(Guard.startMin(this)));
        btnEnd.setText(Guard.fmtTime(Guard.endMin(this)));
        int shown = shownMask();
        for (int i = 0; i < 7; i++) days[i].setSelected(((shown >> i) & 1) == 1);

        int dmode = Guard.dayMode(this);
        boolean onVacation = Guard.vacationActive(this, now);
        findViewById(R.id.btnPresetLegal).setSelected(dmode == Guard.DAY_LEGAL || dmode == Guard.DAY_WORKDAY);
        findViewById(R.id.btnPresetVacation).setSelected(dmode == Guard.DAY_VACATION || onVacation);
        findViewById(R.id.btnPresetEvery).setSelected(dmode == Guard.DAY_EVERY);
        if (onVacation) {
            tvPresetHint.setText("休假中 · " + Guard.vacationText(this, now) + "，期间全部放行");
        } else if (dmode == Guard.DAY_LEGAL || dmode == Guard.DAY_WORKDAY) {
            tvPresetHint.setText("节假日数据：" + Holiday.sourceText(this,
                    Calendar.getInstance().get(Calendar.YEAR)));
        } else {
            tvPresetHint.setText("按所选星期或每天判定");
        }
        btnEndVacation.setVisibility(onVacation ? View.VISIBLE : View.GONE);

        String office = Guard.fpOffice(this);
        String home = Guard.fpHome(this);
        tvOfficeState.setText(office.length() == 0 ? getString(R.string.not_recorded)
                : "已记录 · " + NetFp.describeSaved(office) + "（长按删除）");
        tvHomeState.setText(home.length() == 0 ? getString(R.string.not_recorded)
                : "已记录 · " + NetFp.describeSaved(home) + "（长按删除）");
        tvFpConflict.setVisibility(Guard.fingerprintConflict(this) ? View.VISIBLE : View.GONE);

        StringBuilder reason = new StringBuilder();
        int mode = Guard.resolveMode(this, now, reason);
        tvMode.setText(withTag(mode, now));
        int accent = mode == Guard.MODE_SILENCE ? Color.parseColor("#FFC46B") : Color.parseColor("#A9C7FF");
        vAccent.setBackgroundColor(accent);

        StringBuilder rs = new StringBuilder(reason);
        if (Guard.pendingMin(this) > 0) {
            rs.append("\n网络切换确认中：已持续 ").append(Guard.pendingMin(this)).append(" 分钟");
        }
        if (!access) rs.append("\n未授予通知使用权，本应用无法读取通知");
        tvReason.setText(rs.toString());

        int place = Guard.lastPlace(this);
        pillTime.setSelected(Guard.scheduleOn(this) && Guard.inWindow(this, now));
        pillOffice.setSelected(place == Guard.PLACE_WORK);
        pillHome.setSelected(place == Guard.PLACE_AWAY);
        pillVacation.setSelected(onVacation);

        tvDiag.setText(Guard.diag(this, now));
        tvLog.setText(Guard.logText(this));
        binding = false;
    }

    /** 31sp 状态词后面挂一个小字号标记：加班中 / 已抵达 / 休假中。 */
    private CharSequence withTag(int mode, long now) {
        String base = Guard.modeName(this, mode);
        String tag = Guard.statusTag(this, now, Guard.lastPlace(this),
                Guard.scheduleOn(this) && Guard.inWindow(this, now));
        if (tag.length() == 0) return base;
        SpannableString ss = new SpannableString(base + " · " + tag);
        int from = base.length();
        ss.setSpan(new RelativeSizeSpan(0.52f), from, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(Color.parseColor("#C7D8FE")), from, ss.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 把时间窗设置立即写入配置并刷新。 */

    private void applyNow() {
        StringBuilder sb = new StringBuilder();
        int mode = Guard.resolveMode(this, System.currentTimeMillis(), sb);
        if (mode == Guard.MODE_ALLOW) Fold.clear(this);
        Guard.logTransition(this, mode, sb.toString());
    }

    /** 弹出时间选择器，设置上班开始或结束时间。 */

    private void pickTime(final boolean isStart) {
        int cur = isStart ? Guard.startMin(this) : Guard.endMin(this);
        TimePickerDialog dialog = new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                int value = hourOfDay * 60 + minute;
                if (isStart) Guard.setStartMin(MainActivity.this, value);
                else Guard.setEndMin(MainActivity.this, value);
                applyNow();
                refresh();
            }
        }, cur / 60, cur % 60, true);
        dialog.show();
    }

    /** 统一弹窗：圆角白底 + 淡入缩放，去掉系统默认的生硬方框感。 */
    private AlertDialog.Builder softDialog() {
        return new AlertDialog.Builder(this, R.style.SoftDialog);
    }

    /** 显示后应用圆角背景，避免弹窗出现系统默认的方形白底。 */
    private AlertDialog showSoft(AlertDialog dialog) {
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawableResource(R.drawable.bg_dialog);
        return dialog;
    }

    /** 弹出休假设置对话框，可选起始时间与持续天数。 */

    private void showVacationDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_vacation, null);
        final DatePicker dp = view.findViewById(R.id.dpVacation);
        final TimePicker tp = view.findViewById(R.id.tpVacation);
        final RadioGroup rg = view.findViewById(R.id.rgVacationDays);

        Calendar now = Calendar.getInstance();
        dp.init(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH), null);
        tp.setIs24HourView(true);
        tp.setCurrentHour(now.get(Calendar.HOUR_OF_DAY));
        tp.setCurrentMinute(now.get(Calendar.MINUTE));

        showSoft(softDialog()
                .setTitle(R.string.vacation_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Calendar start = Calendar.getInstance();
                        start.set(dp.getYear(), dp.getMonth(), dp.getDayOfMonth(),
                                tp.getCurrentHour(), tp.getCurrentMinute(), 0);
                        int checked = rg.getCheckedRadioButtonId();
                        int days = 1;
                        if (checked == R.id.rbDay2) days = 2;
                        else if (checked == R.id.rbDay3) days = 3;
                        else if (checked == R.id.rbDay7) days = 7;
                        long from = start.getTimeInMillis();
                        Guard.setVacation(MainActivity.this, from, from + days * 86400000L);
                        Guard.setDayMode(MainActivity.this, Guard.DAY_VACATION);
                        Guard.addLog(MainActivity.this, "开启休假 " + days + " 天，期间全部放行");
                        applyNow();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** 把当前网络指纹记录为办公网或家庭网。 */

    private void recordNetwork(boolean office) {
        NetFp fp = NetFp.current(this);
        if (!fp.valid) {
            Toast.makeText(this, R.string.need_office_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        if (office) {
            Guard.setFpOffice(this, fp.toJson());
            Guard.forcePlace(this, Guard.PLACE_WORK);
            Toast.makeText(this, R.string.fp_saved_office, Toast.LENGTH_SHORT).show();
        } else {
            Guard.setFpHome(this, fp.toJson());
            Guard.forcePlace(this, Guard.PLACE_AWAY);
            Toast.makeText(this, R.string.fp_saved_home, Toast.LENGTH_SHORT).show();
        }
        Guard.addLog(this, (office ? "记录办公网络 " : "记录家庭网络 ") + fp.gateway);
        if (Guard.fingerprintConflict(this)) Guard.addLog(this, "两处指纹相同，地点判据已失效");
        applyNow();
        refresh();
    }

    /** 长按后弹出确认框，删除办公/家庭网络绑定。 */

    private boolean deleteNetwork(final boolean office) {
        String saved = office ? Guard.fpOffice(this) : Guard.fpHome(this);
        if (saved.length() == 0) {
            Toast.makeText(this, office ? "尚未绑定办公网络" : "尚未绑定家庭网络", Toast.LENGTH_SHORT).show();
            return true;
        }
        showSoft(softDialog()
                .setTitle(office ? "删除办公网络绑定？" : "删除家庭网络绑定？")
                .setMessage("删除后该地点不再参与判定，需要时重新点「记录当前网络」即可绑定。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (office) Guard.clearFpOffice(MainActivity.this);
                        else Guard.clearFpHome(MainActivity.this);
                        applyNow();
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .create());
        return true;
    }

    /** Android 13+ 申请 POST_NOTIFICATIONS，用于无声摘要。 */

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PERM);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF_PERM) {
            if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.perm_notif_needed, Toast.LENGTH_SHORT).show();
            }
            refresh();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_PICK && result == RESULT_OK && data != null) {
            String[] pkgs = data.getStringArrayExtra("pkgs");
            String[] labels = data.getStringArrayExtra("labels");
            List<String[]> list = new ArrayList<String[]>();
            if (pkgs != null && labels != null) {
                for (int i = 0; i < pkgs.length && i < labels.length; i++) {
                    list.add(new String[]{pkgs[i], labels[i]});
                }
            }
            Guard.setTargets(this, list);
            Guard.addLog(this, "接管应用：" + list.size() + " 个");
            refresh();
        }
    }

    /** 跳转到系统「通知使用权」授权页。 */

    private void openListenerSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
                Toast.makeText(this, "请手动到 系统设置 → 通知 → 通知使用权 中允许本应用", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 读取界面上当前勾选的星期掩码。 */

    private int shownMask() {
        int m = Guard.dayMode(this);
        if (m == Guard.DAY_EVERY) return 0x7F;
        if (m == Guard.DAY_WORKDAY || m == Guard.DAY_LEGAL || m == Guard.DAY_VACATION) return 0x1F;
        return Guard.dayMask(this);
    }

    /** dp 转 px。 */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 弹出使用说明与局限对话框。 */

    private void showHelp() {
        TextView body = new TextView(this);
        body.setText(getString(R.string.help_body));
        body.setTextSize(13);
        body.setTextColor(getResources().getColor(R.color.text_secondary));
        body.setLineSpacing(dp(6), 1.05f);
        int padH = dp(22);
        body.setPadding(padH, dp(10), padH, dp(6));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        scroll.setPadding(0, 0, 0, dp(4));
        showSoft(softDialog()
                .setTitle("怎么用 · 有什么局限")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .create());
    }

    /** 逐项解析 enabled_notification_listeners，避免包名子串误判。 */
    public static boolean isListenerEnabled(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (TextUtils.isEmpty(flat)) return false;
            String[] items = flat.split(":");
            for (int i = 0; i < items.length; i++) {
                ComponentName cn = ComponentName.unflattenFromString(items[i]);
                if (cn != null && c.getPackageName().equals(cn.getPackageName())) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
