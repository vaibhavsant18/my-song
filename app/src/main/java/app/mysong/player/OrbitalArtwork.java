package app.mysong.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

@SuppressLint("ViewConstructor") // Each illustration is created in code with its initial scene.
final class OrbitalArtwork extends View {
    enum Scene { CONSTELLATION, PLANET, RADIO }

    interface ScrubListener {
        void onStart();
        void onPosition(float fraction);
        void onFinish(boolean commit);
    }

    private static final int INK = 0xff171717;
    private static final int RED = 0xffc61c31;
    private static final int GRID = 0xffd9d5ce;
    private static final int GUIDE = 0xffaaa59e;
    private static final float ARC_START = 120;
    private static final float ARC_SWEEP = 300;
    private static final int ORBIT_RADIUS = 87;
    private static final int PLANET_RADIUS = 69;
    private static final int[][] CONSTELLATION = {{-130, 15}, {-101, -18}, {-67, -10},
            {-45, -31}, {-23, -6}, {-58, 23}, {-89, 25}};
    private static final int[][] SIGNALS = {{-47, -33}, {39, -17}, {62, -54}};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path planetDots = planetDots();
    private final RectF bounds = new RectF();
    private final float density;
    private Scene scene;
    private ScrubListener scrubListener;
    private boolean scrubbable;
    private boolean signalActive;
    private float fraction;
    private int pointerId = -1;

    OrbitalArtwork(Context context, Scene scene) {
        super(context);
        this.scene = scene;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setScene(Scene scene) {
        if (this.scene == scene) return;
        this.scene = scene;
        invalidate();
    }

    void setScrubListener(ScrubListener listener) {
        scrubListener = listener;
    }

    void setProgress(float fraction, boolean scrubbable) {
        if (this.fraction == fraction && this.scrubbable == scrubbable) return;
        this.fraction = fraction;
        this.scrubbable = scrubbable;
        invalidate();
    }

    void setSignalActive(boolean active) {
        if (signalActive == active) return;
        signalActive = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        if (scene == Scene.PLANET) {
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float scale = planetScale();
            canvas.scale(scale, scale);
            drawPlanet(canvas);
        } else if (scene == Scene.CONSTELLATION) {
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float scale = Math.min(getWidth() / 285f, getHeight() / 102f);
            canvas.scale(scale, scale);
            drawGrid(canvas, 132, -40, 40);
            drawConstellation(canvas);
        } else {
            canvas.translate(getWidth() / 2f, getHeight() * .82f);
            float scale = Math.min(getWidth() / 202f, getHeight() / 112f);
            canvas.scale(scale, scale);
            drawGrid(canvas, 92, -88, 8);
            drawRadio(canvas);
        }
        canvas.restore();
    }

    private float planetScale() {
        return Math.min(getWidth(), getHeight()) / 215f;
    }

    private void drawPlanet(Canvas canvas) {
        drawSphere(canvas, PLANET_RADIUS);
        stroke(INK, .9f);
        bounds.set(-ORBIT_RADIUS, -ORBIT_RADIUS, ORBIT_RADIUS, ORBIT_RADIUS);
        canvas.drawArc(bounds, ARC_START, ARC_SWEEP, false, paint);
        stroke(GUIDE, .75f);
        for (int angle = 0; angle <= ARC_SWEEP; angle += 15) {
            double radians = Math.toRadians(ARC_START + angle);
            float x = (float) Math.cos(radians);
            float y = (float) Math.sin(radians);
            int outer = angle % 60 == 0 ? 98 : 94;
            canvas.drawLine(92 * x, 92 * y, outer * x, outer * y, paint);
        }
        if (!scrubbable) return;
        float sweep = ARC_SWEEP * fraction;
        stroke(RED, 2);
        canvas.drawArc(bounds, ARC_START, sweep, false, paint);
        double angle = Math.toRadians(ARC_START + sweep);
        float x = (float) Math.cos(angle) * ORBIT_RADIUS;
        float y = (float) Math.sin(angle) * ORBIT_RADIUS;
        fill(RED);
        canvas.drawCircle(x, y, 4, paint);
    }

    private void drawConstellation(Canvas canvas) {
        canvas.save();
        canvas.translate(72, 12);
        canvas.save();
        canvas.rotate(-22);
        stroke(GUIDE, .75f);
        canvas.drawOval(-69, -16, 69, 16, paint);
        canvas.restore();
        drawSphere(canvas, 38);
        canvas.rotate(-22);
        stroke(INK, .9f);
        bounds.set(-69, -16, 69, 16);
        canvas.drawArc(bounds, 0, 180, false, paint);
        canvas.restore();

        stroke(INK, .75f);
        for (int i = 1; i < CONSTELLATION.length; i++) {
            int[] before = CONSTELLATION[i - 1];
            int[] after = CONSTELLATION[i];
            canvas.drawLine(before[0], before[1], after[0], after[1], paint);
        }
        for (int i = 0; i < CONSTELLATION.length; i++) {
            int[] star = CONSTELLATION[i];
            fill(i == 3 ? RED : INK);
            canvas.drawCircle(star[0], star[1], i == 3 ? 2.5f : 1.65f, paint);
        }
    }

    private void drawRadio(Canvas canvas) {
        for (int radius = 22; radius <= 88; radius += 22) {
            stroke(radius == 88 ? INK : GUIDE, .75f);
            bounds.set(-radius, -radius, radius, radius);
            canvas.drawArc(bounds, 180, 180, false, paint);
        }
        stroke(GUIDE, .65f);
        canvas.drawLine(-91, 0, 91, 0, paint);
        for (int i = -60; i <= 60; i += 30) {
            double radians = Math.toRadians(i);
            canvas.drawLine(0, 0, 88 * (float) Math.sin(radians),
                    -88 * (float) Math.cos(radians), paint);
        }
        stroke(signalActive ? RED : INK, 1);
        canvas.drawLine(0, 0, 66, -57, paint);
        for (int i = 0; i < SIGNALS.length; i++) {
            int[] signal = SIGNALS[i];
            fill(signalActive && i == 2 ? RED : INK);
            canvas.drawCircle(signal[0], signal[1], i == 2 ? 2.8f : 1.7f, paint);
        }
    }

    private void drawSphere(Canvas canvas, float radius) {
        fill(0xfffbfaf7);
        canvas.drawCircle(0, 0, radius, paint);
        canvas.save();
        float scale = radius / PLANET_RADIUS;
        canvas.scale(scale, scale);
        fill(INK);
        canvas.drawPath(planetDots, paint);
        canvas.restore();
        stroke(GUIDE, .65f);
        canvas.drawCircle(0, 0, radius, paint);
    }

    private static Path planetDots() {
        Path dots = new Path();
        for (int y = -64; y <= 64; y += 4) {
            for (int x = -64; x <= 64; x += 4) {
                float nx = (float) x / PLANET_RADIUS;
                float ny = (float) y / PLANET_RADIUS;
                float distance = nx * nx + ny * ny;
                if (distance > .94f) continue;
                float depth = (float) Math.sqrt(1 - distance);
                float light = Math.max(0, -.66f * nx - .29f * ny + .64f * depth);
                dots.addCircle(x, y, .35f + 1.25f * light, Path.Direction.CW);
            }
        }
        return dots;
    }

    private void drawGrid(Canvas canvas, int halfWidth, int top, int bottom) {
        fill(GRID);
        for (int y = top; y <= bottom; y += 8) {
            for (int x = -halfWidth; x <= halfWidth; x += 8) {
                canvas.drawCircle(x, y, .55f, paint);
            }
        }
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
    }

    // The native slider below the illustration provides the accessible and keyboard controls.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (scene != Scene.PLANET || !scrubbable || scrubListener == null) return false;
            float x = event.getX() - getWidth() / 2f;
            float y = event.getY() - getHeight() / 2f;
            if (Math.abs(Math.hypot(x, y) - ORBIT_RADIUS * planetScale()) > 24 * density) return false;
            pointerId = event.getPointerId(0);
            getParent().requestDisallowInterceptTouchEvent(true);
            scrubListener.onStart();
        }
        if (pointerId == -1) return false;
        int index = event.findPointerIndex(pointerId);
        if (index < 0 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            finishTouch(false);
        } else {
            float x = event.getX(index) - getWidth() / 2f;
            float y = event.getY(index) - getHeight() / 2f;
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP && index == event.getActionIndex())) {
                scrubListener.onPosition(positionAt(x, y));
                finishTouch(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                scrubListener.onPosition(positionAt(x, y));
            }
        }
        return true;
    }

    private void finishTouch(boolean commit) {
        pointerId = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
        scrubListener.onFinish(commit);
    }

    static float positionAt(float x, float y) {
        float degrees = ((float) Math.toDegrees(Math.atan2(y, x)) - ARC_START + 720) % 360;
        if (degrees > ARC_SWEEP) return degrees < (ARC_SWEEP + 360) / 2 ? 1 : 0;
        return degrees / ARC_SWEEP;
    }
}
