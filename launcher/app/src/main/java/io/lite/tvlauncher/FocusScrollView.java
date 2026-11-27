package io.lite.tvlauncher;

import android.content.Context;
import android.graphics.Rect;
import android.widget.ScrollView;

/**
 * ScrollView, который держит выбранную карточку подальше от краёв экрана,
 * чтобы при навигации пультом следующий ряд всегда был виден заранее.
 */
public class FocusScrollView extends ScrollView {

    private final int edge;

    public FocusScrollView(Context context, int edgePx) {
        super(context);
        edge = edgePx;
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setSmoothScrollingEnabled(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        int height = getHeight();
        int scrollY = getScrollY();
        int visibleTop = scrollY + edge;
        int visibleBottom = scrollY + height - edge;

        int delta = 0;
        if (rect.bottom > visibleBottom) {
            delta = rect.bottom - visibleBottom;
        } else if (rect.top < visibleTop) {
            delta = rect.top - visibleTop;
        }

        int range = Math.max(0, getChildAt(0).getHeight() - (height - getPaddingTop() - getPaddingBottom()));
        int target = Math.max(0, Math.min(scrollY + delta, range));
        return target - scrollY;
    }
}
