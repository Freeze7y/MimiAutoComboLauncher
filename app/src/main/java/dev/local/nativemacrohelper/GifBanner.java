package dev.local.nativemacrohelper;

import android.content.Context;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Playback follows actual window/parent visibility; never runs in a hidden game session. */
public final class GifBanner extends ImageView {
    public GifBanner(Context c, AttributeSet attrs) { super(c, attrs); }
    private void updatePlayback(boolean visible) {
        Drawable image = getDrawable();
        if (image instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animation = (AnimatedImageDrawable) image;
            if (visible && isShown() && getWindowVisibility() == VISIBLE && android.animation.ValueAnimator.areAnimatorsEnabled()) animation.start();
            else animation.stop();
        }
    }
    @Override public void setImageDrawable(Drawable image) { super.setImageDrawable(image); updatePlayback(isShown()); }
    @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); updatePlayback(visible); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updatePlayback(visibility == VISIBLE); }
    @Override protected void onDetachedFromWindow() { updatePlayback(false); super.onDetachedFromWindow(); }
}
