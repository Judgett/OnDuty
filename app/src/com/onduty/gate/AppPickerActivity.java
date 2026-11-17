package com.onduty.gate;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 目标应用选择页：列出桌面可见应用，供用户勾选需要被收起通知的工作软件。
 * 只查询带 LAUNCHER 的应用，不申请 QUERY_ALL_PACKAGES。
 */
public class AppPickerActivity extends Activity {

    /** 列表项：包名 + 展示名。 */
    private static class AppItem {
        String pkg;
        String label;
    }

    /** 全部应用。 */
    private final ArrayList<AppItem> all = new ArrayList<AppItem>();
    /** 搜索过滤后的应用。 */
    private final ArrayList<AppItem> shown = new ArrayList<AppItem>();
    /** 当前勾选的包名集合。 */
    private final HashSet<String> selected = new HashSet<String>();
    /** 包名 → 图标缓存。 */
    private final HashMap<String, Drawable> icons = new HashMap<String, Drawable>();
    /** 应用列表是否已加载完成。 */
    private boolean loaded = false;

    private EditText etSearch;
    private ListView lvApps;
    private TextView tvLoading, tvNoMatch, btnDone;

    /** 中文排序器，保证列表按拼音顺序排列。 */
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    /** 初始化应用列表、搜索框与适配器。 */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        etSearch = findViewById(R.id.etSearch);
        lvApps = findViewById(R.id.lvApps);
        tvLoading = findViewById(R.id.tvLoading);
        tvNoMatch = findViewById(R.id.tvNoMatch);
        btnDone = findViewById(R.id.btnDone);

        List<String[]> pre = Guard.targets(this);
        for (int i = 0; i < pre.size(); i++) {
            selected.add(pre.get(i)[0]);
        }

        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithResult();
            }
        });

        lvApps.setAdapter(new AppAdapter());
        lvApps.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                AppItem item = shown.get(position);
                if (selected.contains(item.pkg)) {
                    selected.remove(item.pkg);
                } else {
                    selected.add(item.pkg);
                }
                ((BaseAdapter) lvApps.getAdapter()).notifyDataSetChanged();
                updateDoneText();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        updateDoneText();
        loadApps();
    }

    /** 更新底部「完成」按钮上显示已选数量。 */

    private void updateDoneText() {
        if (selected.isEmpty()) {
            btnDone.setText(R.string.done);
        } else {
            btnDone.setText(getString(R.string.done) + " · " + selected.size());
        }
    }

    /** 把选中的应用打包返回结果并关闭页面。 */

    private void finishWithResult() {
        ArrayList<String> pkgs = new ArrayList<String>();
        ArrayList<String> labels = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            AppItem item = all.get(i);
            if (selected.contains(item.pkg)) {
                pkgs.add(item.pkg);
                labels.add(item.label);
            }
        }
        // 选中的应用可能因为过滤没在列表里，补上
        for (int i = 0; i < shown.size(); i++) {
            AppItem item = shown.get(i);
            if (selected.contains(item.pkg) && !pkgs.contains(item.pkg)) {
                pkgs.add(item.pkg);
                labels.add(item.label);
            }
        }
        Intent data = new Intent();
        data.putExtra("pkgs", pkgs.toArray(new String[0]));
        data.putExtra("labels", labels.toArray(new String[0]));
        setResult(RESULT_OK, data);
        finish();
    }

    /** 后台加载桌面可见应用，按中文名称排序。 */

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<AppItem> result = new ArrayList<AppItem>();
                try {
                    Intent main = new Intent(Intent.ACTION_MAIN, null);
                    main.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
                    HashSet<String> seen = new HashSet<String>();
                    if (infos != null) {
                        for (int i = 0; i < infos.size(); i++) {
                            ResolveInfo info = infos.get(i);
                            if (info == null || info.activityInfo == null) continue;
                            String pkg = info.activityInfo.packageName;
                            if (pkg == null || pkg.equals(getPackageName())) continue;
                            if (!seen.add(pkg)) continue;
                            AppItem item = new AppItem();
                            item.pkg = pkg;
                            CharSequence label = info.loadLabel(pm);
                            item.label = label == null ? pkg : label.toString();
                            result.add(item);
                        }
                    }
                } catch (Throwable ignored) {
                }
                Collections.sort(result, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem lhs, AppItem rhs) {
                        return collator.compare(lhs.label, rhs.label);
                    }
                });

                final ArrayList<AppItem> loadedList = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(loadedList);
                        loaded = true;
                        tvLoading.setVisibility(View.GONE);
                        applyFilter(etSearch.getText().toString());
                    }
                });
            }
        }).start();
    }

    /** 按关键字过滤应用列表（匹配名称或包名）。 */

    private void applyFilter(String keyword) {
        if (!loaded) return;
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (int i = 0; i < all.size(); i++) {
            AppItem item = all.get(i);
            if (kw.length() == 0
                    || item.label.toLowerCase(Locale.ROOT).contains(kw)
                    || item.pkg.toLowerCase(Locale.ROOT).contains(kw)) {
                shown.add(item);
            }
        }
        try {
            ((BaseAdapter) lvApps.getAdapter()).notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
        tvNoMatch.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 读取并缓存应用图标。 */

    private Drawable iconOf(String pkg) {
        Drawable d = icons.get(pkg);
        if (d != null) return d;
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            d = null;
        }
        if (d != null) icons.put(pkg, d);
        return d;
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_app, parent, false);
            }
            AppItem item = shown.get(position);
            TextView tvName = row.findViewById(R.id.tvName);
            TextView tvPkg = row.findViewById(R.id.tvPkg);
            ImageView ivIcon = row.findViewById(R.id.ivIcon);
            CheckBox cb = row.findViewById(R.id.cbApp);
            tvName.setText(item.label);
            tvPkg.setText(item.pkg);
            ivIcon.setImageDrawable(iconOf(item.pkg));
            cb.setChecked(selected.contains(item.pkg));
            return row;
        }
    }
}
