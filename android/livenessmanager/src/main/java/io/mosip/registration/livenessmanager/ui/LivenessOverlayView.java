package io.mosip.registration.livenessmanager.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws over the camera preview: a dimmed scrim with an oval cut out for the face, and the progress ring
 * around the oval (UI_UX_SPEC §6.2).
 */
public final class LivenessOverlayView extends View {

    /** Oval width as a fraction of the view width; height is 1.3 times the width, like a face. */
    private static final float OVAL_WIDTH = 0.7f;
    private static final float OVAL_ASPECT = 1.3f;

    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private float progress;

    public LivenessOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        scrim.setColor(context.getColor(io.mosip.registration.livenessmanager.R.color.liveness_scrim));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2 * density);
        outline.setColor(context.getColor(io.mosip.registration.livenessmanager.R.color.liveness_info));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(6 * density);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(context.getColor(io.mosip.registration.livenessmanager.R.color.liveness_success));
    }

    /**
     * @param fraction progress ring, 0..1
     * @param color    ring colour, from the screen's tone
     */
    public void show(float fraction, int color) {
        progress = Math.max(0f, Math.min(1f, fraction));
        ring.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() * OVAL_WIDTH;
        float height = Math.min(width * OVAL_ASPECT, getHeight() * 0.85f);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        oval.set(left, top, left + width, top + height);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        path.addOval(oval, Path.Direction.CW);
        canvas.drawPath(path, scrim);
        canvas.drawOval(oval, outline);
        if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, ring);
        }
    }
}
