package io.lite.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лёгкий лаунчер для Android TV.
 *
 * Почему не лагает:
 *  - ноль библиотек (ни AndroidX, ни Leanback, ни Compose) — мало кода, быстрый старт;
 *  - список приложений и иконки грузятся в фоновом потоке и кешируются;
 *  - фон рисуется один раз в маленькую картинку, дальше это одна текстура;
 *  - нет рекомендаций, каналов, рекламы, сетевых запросов и фоновых сервисов;
 *  - анимация фокуса — только scale/alpha, которые GPU делает без перерисовки.
 */
public class MainActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final float FOCUS_SCALE = 1.1f;
    private static final int ANIM_MS = 140;
    private static final int TILE_DEFAULT = 0xFF1B2338;
    private static final int BANNER_BG = 0xFF161D2E;
    private static final float LABEL_DIM = 0.55f;

    /** Список приложений переживает пересоздание экрана — повторный показ мгновенный. */
    private static List<AppEntry> sApps;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Interpolator interp = new DecelerateInterpolator(1.6f);
    private final List<View> cards = new ArrayList<>();
    private final Runnable reloadTask = this::reload;

    private SharedPreferences prefs;
    private float density;
    private int cardW, cardH, gap, sidePad;
    private int loadGen;

    private TextView clock, date, greeting;
    private FocusScrollView scroll;
    private LinearLayout content;

    private String lastFocusKey;
    private int lastFocusIndex;

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        computeSizes(dm.widthPixels);

        getWindow().setBackgroundDrawable(new AuroraDrawable());
        setContentView(buildRoot());

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addDataScheme("package");
        register(packageReceiver, pkg);

        IntentFilter ext = new IntentFilter();
        ext.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        ext.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        register(packageReceiver, ext);

        if (sApps != null) render();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        safeUnregister(timeReceiver);
    }

    @Override
    protected void onDestroy() {
        safeUnregister(packageReceiver);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // «Домой», нажатое прямо на главном экране, — вернуться к началу.
        if (hasWindowFocus()) goTop();
    }

    @Override
    public void onBackPressed() {
        // Лаунчер нельзя закрыть «Назад»; вместо этого — наверх.
        if (scroll.getScrollY() > 0) goTop();
    }

    private void goTop() {
        scroll.smoothScrollTo(0, 0);
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- разметка

    private void computeSizes(int screenW) {
        sidePad = dp(56);
        gap = dp(22);
        cardW = (screenW - 2 * sidePad - (COLUMNS - 1) * gap) / COLUMNS;
        cardH = Math.round(cardW * 9f / 16f);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(sidePad, dp(28), sidePad, dp(10));

        clock = text(54, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        header.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dateBox = new LinearLayout(this);
        dateBox.setOrientation(LinearLayout.VERTICAL);
        date = text(17, 0xEBFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        greeting = text(14, 0x8CFFFFFF, Typeface.create("sans-serif", Typeface.NORMAL));
        dateBox.addView(date);
        dateBox.addView(greeting);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(22);
        dlp.bottomMargin = dp(6);
        header.addView(dateBox, dlp);
        root.addView(header);

        scroll = new FocusScrollView(this, dp(110));
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(6), 0, dp(24));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(sidePad, 0, sidePad, dp(40));
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** Полностью перестраивает сетку из кеша (без обращения к системе). */
    private void render() {
        List<AppEntry> all = sApps;
        if (all == null) return;

        String keepKey = lastFocusKey;
        int keepIndex = lastFocusIndex;
        content.removeAllViews();
        cards.clear();

        List<String> pinned = getPinned();
        Set<String> hidden = getHidden();
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (AppEntry e : all) byPkg.put(e.pkg, e);

        List<View> favs = new ArrayList<>();
        for (String p : pinned) {
            AppEntry e = byPkg.get(p);
            if (e != null && !hidden.contains(p)) favs.add(appCard(e));
        }
        List<View> rest = new ArrayList<>();
        int hiddenInstalled = 0;
        for (AppEntry e : all) {
            if (hidden.contains(e.pkg)) hiddenInstalled++;
            else if (!pinned.contains(e.pkg)) rest.add(appCard(e));
        }
        List<View> sys = systemTiles(hiddenInstalled);

        if (!favs.isEmpty()) {
            section("Избранное", true);
            grid(favs);
            section("Все приложения", false);
        } else {
            section("Приложения", true);
            hint("Удерживайте ОК на приложении, чтобы закрепить его здесь или скрыть");
        }
        grid(rest);
        section("Система", false);
        grid(sys);

        View target = keepKey == null ? null : content.findViewWithTag(keepKey);
        if (target == null && !cards.isEmpty()) {
            target = cards.get(Math.max(0, Math.min(keepIndex, cards.size() - 1)));
        }
        if (target != null) target.requestFocus();
    }

    private void section(String title, boolean first) {
        TextView t = text(15, 0xB3FFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(title);
        t.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? dp(4) : dp(30);
        lp.bottomMargin = dp(14);
        content.addView(t, lp);
    }

    private void hint(String s) {
        TextView t = text(13, 0x73FFFFFF, Typeface.DEFAULT);
        t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = -dp(8);
        lp.bottomMargin = dp(14);
        content.addView(t, lp);
    }

    private void grid(List<View> cells) {
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(16);
                content.addView(row, rlp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS != 0) clp.leftMargin = gap;
            row.addView(cells.get(i), clp);
        }
    }

    private View appCard(final AppEntry e) {
        boolean banner = e.banner != null;
        return card("app:" + e.pkg, e.label,
                banner ? e.banner : e.icon,
                banner, banner ? BANNER_BG : e.tint, 0.52f,
                () -> launch(e), () -> showAppMenu(e));
    }

    private List<View> systemTiles(int hiddenCount) {
        List<View> v = new ArrayList<>();
        v.add(card("sys:settings", "Настройки", getDrawable(R.drawable.ic_settings), false, 0xFF283152, 0.36f,
                this::openSettings, null));
        v.add(card("sys:network", "Сеть", getDrawable(R.drawable.ic_wifi), false, 0xFF1C3A46, 0.36f,
                this::openNetwork, null));
        if (hiddenCount > 0) {
            v.add(card("sys:hidden", "Скрытые (" + hiddenCount + ")", getDrawable(R.drawable.ic_hidden), false, 0xFF35294A, 0.36f,
                    this::showHiddenDialog, null));
        }
        return v;
    }

    private View card(final String key, String label, Drawable img, boolean fill, int color, float iconFrac,
                      final Runnable onClick, final Runnable onMenu) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        final FrameLayout card = new FrameLayout(this);
        card.setTag(key);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(fill ? rounded(color) : tile(color));
        card.setForeground(focusRing());
        card.setClipToOutline(true);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(img);
        if (fill) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(cardH * iconFrac);
            card.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        cell.addView(card, new LinearLayout.LayoutParams(cardW, cardH));

        final TextView name = text(13, Color.WHITE, Typeface.DEFAULT);
        name.setText(label);
        name.setAlpha(LABEL_DIM);
        name.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        cell.addView(name, nlp);

        final int index = cards.size();
        cards.add(card);
        final float lift = cardH * (FOCUS_SCALE - 1f) / 2f;

        card.setOnFocusChangeListener((v, has) -> {
            if (has) {
                lastFocusKey = key;
                lastFocusIndex = index;
            }
            float s = has ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(ANIM_MS).setInterpolator(interp).start();
            name.animate().alpha(has ? 1f : LABEL_DIM).translationY(has ? lift : 0f)
                    .setDuration(ANIM_MS).setInterpolator(interp).start();
        });
        card.setOnClickListener(v -> onClick.run());
        if (onMenu != null) {
            card.setOnLongClickListener(v -> {
                onMenu.run();
                return true;
            });
            card.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                    onMenu.run();
                    return true;
                }
                return false;
            });
        }
        return cell;
    }

    // ---------------------------------------------------------------- рисование

    private Drawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    /** Плитка для приложений без баннера: мягкий градиент в цвет иконки. */
    private Drawable tile(int color) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{shade(color, 0.10f), shade(color, -0.06f)});
        g.setCornerRadius(dp(12));
        return g;
    }

    private Drawable focusRing() {
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(12));
        ring.setStroke(dp(3), 0xF2FFFFFF);

        GradientDrawable press = new GradientDrawable();
        press.setColor(0x1FFFFFFF);
        press.setCornerRadius(dp(12));

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new LayerDrawable(new Drawable[]{press, ring}));
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private static int shade(int color, float dv) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] + dv));
        return Color.HSVToColor(hsv);
    }

    /** Цвет плитки: «сочный» средний цвет иконки, приглушённый под тёмную тему. */
    private static int tintFor(Drawable d) {
        if (d == null) return TILE_DEFAULT;
        Bitmap bmp = null;
        try {
            final int n = 12;
            bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(old);

            float[] hsv = new float[3];
            float r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    float wt = 0.1f + hsv[1] * hsv[2];
                    r += Color.red(p) * wt;
                    g += Color.green(p) * wt;
                    b += Color.blue(p) * wt;
                    w += wt;
                }
            }
            if (w == 0) return TILE_DEFAULT;
            Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
            if (hsv[1] < 0.12f) return TILE_DEFAULT;
            hsv[1] = Math.min(0.7f, hsv[1]);
            hsv[2] = 0.36f;
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return TILE_DEFAULT;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /**
     * Фон: тёмный градиент и два мягких «сияния».
     * Рисуется один раз в картинку 1/4 размера и дальше просто растягивается — одна текстура на кадр.
     */
    static final class AuroraDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private Bitmap bmp;

        @Override
        protected void onBoundsChange(Rect bounds) {
            int w = Math.max(1, bounds.width() / 4);
            int h = Math.max(1, bounds.height() / 4);
            if (bmp != null && bmp.getWidth() == w && bmp.getHeight() == h) return;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF0E1528, 0xFF04060B, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            float r = Math.max(w, h);
            p.setShader(new RadialGradient(w * 0.12f, -h * 0.10f, r * 0.62f, 0x473A63FF, 0x003A63FF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.96f, h * 1.08f, r * 0.50f, 0x2E00C2A8, 0x0000C2A8, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            bmp = out;
        }

        @Override
        public void draw(Canvas canvas) {
            if (bmp != null) canvas.drawBitmap(bmp, null, getBounds(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    // ---------------------------------------------------------------- загрузка приложений

    static final class AppEntry {
        final String pkg;
        final ComponentName component;
        final String label;
        final boolean leanback;
        Drawable banner;
        Drawable icon;
        int tint = TILE_DEFAULT;

        AppEntry(String pkg, ComponentName component, String label, boolean leanback) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.leanback = leanback;
        }
    }

    private void reload() {
        final int gen = ++loadGen;
        try {
            worker.execute(() -> {
                final List<AppEntry> list = loadApps();
                main.post(() -> {
                    if (gen != loadGen || isDestroyed()) return;
                    sApps = list;
                    render();
                });
            });
        } catch (RuntimeException ignored) {
            // экран уже закрывается
        }
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, found);   // ТВ-приложения — в приоритете
        collect(pm, Intent.CATEGORY_LAUNCHER, false, found);           // «телефонные» — если нет ТВ-версии
        List<AppEntry> list = new ArrayList<>(found.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private void collect(PackageManager pm, String category, boolean leanback, Map<String, AppEntry> out) {
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        } catch (RuntimeException e) {
            return;
        }
        String self = getPackageName();
        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || self.equals(ai.packageName) || out.containsKey(ai.packageName)) continue;
            CharSequence l = ri.loadLabel(pm);
            String label = l == null ? ai.packageName : l.toString().trim();
            AppEntry e = new AppEntry(ai.packageName, new ComponentName(ai.packageName, ai.name), label, leanback);
            try {
                e.banner = ai.loadBanner(pm);
            } catch (Throwable ignored) {
            }
            if (e.banner == null) {
                e.icon = loadIcon(pm, ri);
                e.tint = tintFor(e.icon);
            }
            out.put(ai.packageName, e);
        }
    }

    private Drawable loadIcon(PackageManager pm, ResolveInfo ri) {
        try {
            int res = ri.getIconResource();
            if (res != 0) {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXHIGH, null);
                if (d != null) return d;
            }
        } catch (Throwable ignored) {
        }
        try {
            return ri.loadIcon(pm);
        } catch (Throwable t) {
            return pm.getDefaultActivityIcon();
        }
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.removeCallbacks(reloadTask);
            main.postDelayed(reloadTask, 500);
        }
    };

    // ---------------------------------------------------------------- действия

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (start(i)) return;

        PackageManager pm = getPackageManager();
        Intent alt = null;
        try {
            alt = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        } catch (Throwable ignored) {
        }
        if (alt == null) alt = pm.getLaunchIntentForPackage(e.pkg);
        if (alt == null || !start(alt)) toast("Не удалось открыть «" + e.label + "»");
    }

    private boolean start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openSettings() {
        if (!start(new Intent(Settings.ACTION_SETTINGS))) toast("Настройки недоступны");
    }

    private void openNetwork() {
        if (start(new Intent(Settings.ACTION_WIFI_SETTINGS))) return;
        if (start(new Intent(Settings.ACTION_WIRELESS_SETTINGS))) return;
        openSettings();
    }

    private void showAppMenu(final AppEntry e) {
        final List<String> pinned = getPinned();
        final int idx = pinned.indexOf(e.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (idx < 0) {
            labels.add("Добавить в избранное");
            actions.add(() -> {
                pinned.add(e.pkg);
                savePinned(pinned);
                render();
            });
        } else {
            if (idx > 0) {
                labels.add("Сдвинуть влево");
                actions.add(() -> {
                    Collections.swap(pinned, idx, idx - 1);
                    savePinned(pinned);
                    render();
                });
            }
            if (idx < pinned.size() - 1) {
                labels.add("Сдвинуть вправо");
                actions.add(() -> {
                    Collections.swap(pinned, idx, idx + 1);
                    savePinned(pinned);
                    render();
                });
            }
            labels.add("Убрать из избранного");
            actions.add(() -> {
                pinned.remove(e.pkg);
                savePinned(pinned);
                render();
            });
        }
        labels.add("Скрыть");
        actions.add(() -> {
            Set<String> h = getHidden();
            h.add(e.pkg);
            saveHidden(h);
            pinned.remove(e.pkg);
            savePinned(pinned);
            lastFocusKey = null; // фокус перейдёт на соседнюю карточку
            render();
        });
        labels.add("О приложении");
        actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + e.pkg))));
        labels.add("Удалить");
        actions.add(() -> {
            if (!start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)))) {
                toast("Системное приложение нельзя удалить");
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showHiddenDialog() {
        List<AppEntry> all = sApps;
        if (all == null) return;
        Set<String> hidden = getHidden();
        final List<AppEntry> list = new ArrayList<>();
        for (AppEntry e : all) if (hidden.contains(e.pkg)) list.add(e);
        if (list.isEmpty()) return;
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("Вернуть на главный экран")
                .setItems(names, (d, which) -> {
                    Set<String> h = getHidden();
                    h.remove(list.get(which).pkg);
                    saveHidden(h);
                    lastFocusKey = "app:" + list.get(which).pkg;
                    render();
                })
                .show();
    }

    // ---------------------------------------------------------------- часы

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private void updateClock() {
        Locale loc = Locale.getDefault();
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", loc).format(now.getTime()));
        String d = new SimpleDateFormat("EEEE, d MMMM", loc).format(now.getTime());
        date.setText(d.isEmpty() ? d : d.substring(0, 1).toUpperCase(loc) + d.substring(1));
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? "Доброе утро" : h >= 12 && h < 18 ? "Добрый день"
                : h >= 18 && h < 23 ? "Добрый вечер" : "Доброй ночи");
    }

    // ---------------------------------------------------------------- настройки пользователя

    private List<String> getPinned() {
        List<String> l = new ArrayList<>();
        for (String p : prefs.getString("pinned", "").split("\n")) {
            if (!p.isEmpty() && !l.contains(p)) l.add(p);
        }
        return l;
    }

    private void savePinned(List<String> l) {
        prefs.edit().putString("pinned", TextUtils.join("\n", l)).apply();
    }

    private Set<String> getHidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
    }

    private void saveHidden(Set<String> s) {
        prefs.edit().putStringSet("hidden", new HashSet<>(s)).apply();
    }

    // ---------------------------------------------------------------- мелочи

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    private void safeUnregister(BroadcastReceiver r) {
        try {
            unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
