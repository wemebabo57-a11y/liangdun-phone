package com.youlong.hd;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 自研 · 应用列表页（**本应用自己的界面**）。
 *
 * <p><b>性能说明（重要，2026-10-05 修复）</b>：本页最初用
 * {@code ScrollView + 一次性 addView} 构建全部行，实测在 143 个应用的机器上
 * **滑动即卡死**：每行含图标 + 三个 TextView，全部常驻内存，
 * 布局与测量成本随行数增长，图标还容易把内存顶爆（伴随闪退）。
 *
 * <p>现改为 {@link RecyclerView} + {@link ListAdapter}：
 * <ul>
 *   <li>只保留屏幕内可见的十来行视图，滑动时复用 —— 行数再多也不卡；</li>
 *   <li>图标在绑定时**按需异步加载** + LRU 缓存（上限 64），不再一次性读上百个 Drawable；</li>
 *   <li>数据用 {@link DiffUtil} 局部刷新，避免整表重建。</li>
 * </ul>
 */
public class AppListActivity extends AppCompatActivity {

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private AppAdapter mAdapter;
    private TextView mHeaderSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("应用列表");
        setContentView(buildContentView());

        mAdapter = new AppAdapter();
        RecyclerView rv = findViewById(R.id.app_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(mAdapter);
        rv.setHasFixedSize(true);
        rv.setItemAnimator(null);   // 关掉动画：滚动更稳、开销更低

        loadAppsAsync();
    }

    private View buildContentView() {
        final int pad = dp(18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F7);
        root.setPadding(pad, pad, pad, 0);

        TextView title = new TextView(this);
        title.setText("已安装应用");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);

        mHeaderSummary = new TextView(this);
        mHeaderSummary.setText("正在读取…");
        mHeaderSummary.setTextSize(13);
        mHeaderSummary.setTextColor(0xFF6E6E73);
        mHeaderSummary.setPadding(0, dp(6), 0, dp(10));
        root.addView(mHeaderSummary);

        TextView legend = new TextView(this);
        legend.setText("标记说明：红字「高危权限」= 该应用申请了无障碍或悬浮窗权限"
                + "（锁机病毒最常滥用的两类）。点任意一行打开系统应用详情，在那里卸载。");
        legend.setTextSize(12);
        legend.setTextColor(0xFF8E8E93);
        legend.setBackgroundColor(0xFFFFFFFF);
        legend.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(legend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setId(R.id.app_list);
        rv.setClipToPadding(false);
        rv.setPadding(0, dp(10), 0, dp(10));
        // 权重 1：占满剩余高度，列表自己滚动（外层不再套 ScrollView）
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** 一条应用记录（只存必要字段，图标不预加载）。 */
    static final class AppEntry {
        final String label;
        final String pkg;
        final boolean highRisk;

        AppEntry(String label, String pkg, boolean highRisk) {
            this.label = label;
            this.pkg = pkg;
            this.highRisk = highRisk;
        }
    }

    /** 供自检复用：统计第三方应用与高危权限数量（不构建界面）。 */
    static String summarize(android.content.Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            String self = ctx.getPackageName();
            int total = 0, risky = 0;
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (self.equals(ai.packageName)) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system) continue;
                total++;
                if (hasHighRisk(ctx, ai.packageName)) risky++;
            }
            return "第三方应用=" + total + " 带高危权限=" + risky;
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static boolean hasHighRisk(android.content.Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            String[] perms = pi.requestedPermissions;
            if (perms == null) return false;
            for (String p : perms) {
                if ("android.permission.SYSTEM_ALERT_WINDOW".equals(p)
                        || "android.permission.BIND_ACCESSIBILITY_SERVICE".equals(p)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            final List<AppEntry> list = collectApps();
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                int risky = 0;
                for (AppEntry e : list) if (e.highRisk) risky++;
                mHeaderSummary.setText("共 " + list.size() + " 个第三方应用"
                        + (risky > 0 ? "，其中 " + risky + " 个带高危权限" : ""));
                mAdapter.submitList(list);
            });
        }, "applist-load").start();
    }

    private List<AppEntry> collectApps() {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            String self = getPackageName();
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if (self.equals(ai.packageName)) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system) continue;

                String label;
                try {
                    label = pm.getApplicationLabel(ai).toString();
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                out.add(new AppEntry(label, ai.packageName,
                        hasHighRisk(this, ai.packageName)));
            }
        } catch (Throwable t) {
            CrashLogger.event("[应用列表] 读取失败: " + t);
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.highRisk != b.highRisk) return a.highRisk ? -1 : 1;
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    // ==================================================================
    // 适配器：视图复用 + 图标懒加载
    // ==================================================================

    private final class AppAdapter extends ListAdapter<AppEntry, AppHolder> {

        AppAdapter() {
            super(new DiffUtil.ItemCallback<AppEntry>() {
                @Override
                public boolean areItemsTheSame(AppEntry a, AppEntry b) {
                    return a.pkg.equals(b.pkg);
                }

                @Override
                public boolean areContentsTheSame(AppEntry a, AppEntry b) {
                    return a.pkg.equals(b.pkg)
                            && a.label.equals(b.label)
                            && a.highRisk == b.highRisk;
                }
            });
        }

        @Override
        public AppHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new AppHolder(buildRowView());
        }

        @Override
        public void onBindViewHolder(AppHolder holder, int position) {
            holder.bind(getItem(position));
        }
    }

    private View buildRowView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setId(R.id.app_icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ip.rightMargin = dp(12);
        row.addView(icon, ip);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setId(R.id.app_name);
        name.setTextSize(15);
        name.setTextColor(0xFF1C1C1E);
        col.addView(name);

        TextView risk = new TextView(this);
        risk.setId(R.id.app_risk);
        risk.setTextSize(11);
        risk.setTextColor(0xFFFF3B30);
        col.addView(risk);

        TextView pkg = new TextView(this);
        pkg.setId(R.id.app_pkg);
        pkg.setTextSize(12);
        pkg.setTextColor(0xFF8E8E93);
        col.addView(pkg);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(0xFFC7C7CC);
        row.addView(arrow);

        return row;
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView risk;
        private final TextView pkg;

        AppHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            risk = itemView.findViewById(R.id.app_risk);
            pkg = itemView.findViewById(R.id.app_pkg);
        }

        void bind(final AppEntry e) {
            name.setText(e.label);
            pkg.setText(e.pkg);
            risk.setText(e.highRisk ? "高危权限（无障碍 / 悬浮窗）" : "");

            // 图标：只在真正要显示这一行时才读；命中 LRU 就直接用
            icon.setTag(e.pkg);
            Drawable cached = IconCache.get(e.pkg);
            if (cached != null) {
                icon.setImageDrawable(cached);
            } else {
                icon.setImageDrawable(null);
                new Thread(() -> {
                    Drawable d = null;
                    try {
                        d = getPackageManager().getApplicationIcon(e.pkg);
                    } catch (Throwable ignored) {
                    }
                    final Drawable fd = d;
                    if (fd != null) IconCache.put(e.pkg, fd);
                    mMain.post(() -> {
                        // 视图可能已被复用给别的应用，用 tag 校验后再设置
                        if (fd != null && e.pkg.equals(icon.getTag())) {
                            icon.setImageDrawable(fd);
                        }
                    });
                }, "icon").start();
            }

            itemView.setOnClickListener(v -> openAppDetail(e.pkg));
        }
    }

    /** 图标 LRU 缓存：上限 64，避免上百个 Drawable 常驻把内存顶爆。 */
    private static final class IconCache {
        private static final android.util.LruCache<String, Drawable> CACHE =
                new android.util.LruCache<String, Drawable>(64) {
                    @Override
                    protected int sizeOf(String key, Drawable value) {
                        return 1;
                    }
                };

        static Drawable get(String pkg) {
            return CACHE.get(pkg);
        }

        static void put(String pkg, Drawable d) {
            if (d != null) CACHE.put(pkg, d);
        }
    }

    private void openAppDetail(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            CrashLogger.event("[应用列表] 打开应用详情失败: " + pkg, t);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
