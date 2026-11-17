package app.mysong.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

@SuppressLint("ViewConstructor") // Created in code with the activity's shared planet bitmap.
final class OrbitalArtwork extends View {
    enum Scene { CONSTELLATION, PLANET, RADIO }

    interface ScrubListener {
        void onStart();
        void onPosition(float fraction);
        void onFinish(boolean commit);
    }

    private static final int ICE = 0xff9ee4ff;
    private static final int SUN = 0xffffae77;
    private static final float ARC_START = 120;
    private static final float ARC_SWEEP = 300;
    private static final int ORBIT_RADIUS = 87;
    private static final int[][] CONSTELLATION = {{-130, 15}, {-101, -18}, {-67, -10},
            {-45, -31}, {-23, -6}, {-58, 23}, {-89, 25}};
    private static final int[][] SIGNALS = {{-47, -33}, {39, -17}, {62, -54}};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final float[][] stars = new float[118][3];
    private final Shader blueGlow = new RadialGradient(0, 0, 108,
            new int[]{0xff264159, 0xff1c293d, 0x00171e39}, new float[]{0, .40f, 1}, Shader.TileMode.CLAMP);
    private final Shader planetGlow = new RadialGradient(0, 0, 113,
            new int[]{0xff5d3454, 0xff302340, 0x001c213a}, new float[]{0, .57f, 1}, Shader.TileMode.CLAMP);
    private final float density;
    private Scene scene;
    private final Bitmap planet;
    private ScrubListener scrubListener;
    private boolean scrubbable;
    private boolean signalActive;
    private float fraction;
    private int pointerId = -1;

    OrbitalArtwork(Context context, Scene scene, Bitmap planet) {
        super(context);
        this.scene = scene;
        this.planet = planet;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        Random random = new Random(718);
        for (float[] star : stars) {
            star[0] = random.nextFloat();
            star[1] = random.nextFloat();
            star[2] = random.nextFloat();
        }
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
        canvas.drawColor(0xff10101b);
        for (float[] star : stars) {
            float brightness = star[2];
            fill(((int) (35 + brightness * brightness * 155) << 24) | 0xafd8ff);
            canvas.drawCircle(star[0] * getWidth(), star[1] * getHeight(),
                    density * (0.27f + 0.68f * brightness), paint);
        }
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
            drawConstellation(canvas);
        } else {
            canvas.translate(getWidth() / 2f, getHeight() * .82f);
            float scale = Math.min(getWidth() / 202f, getHeight() / 112f);
            canvas.scale(scale, scale);
            drawRadio(canvas);
        }
        canvas.restore();
    }

    private float planetScale() {
        return Math.min(getWidth(), getHeight()) / 215f;
    }

    private void drawPlanet(Canvas canvas) {
        glow(canvas, planetGlow, 113);
        disc(canvas, 69);

        stroke(0xff343850, 1.5f);
        bounds.set(-ORBIT_RADIUS, -ORBIT_RADIUS, ORBIT_RADIUS, ORBIT_RADIUS);
        canvas.drawArc(bounds, ARC_START, ARC_SWEEP, false, paint);
        for (int angle = 0; angle <= ARC_SWEEP; angle += 15) {
            double radians = Math.toRadians(ARC_START + angle);
            float x = (float) Math.cos(radians);
            float y = (float) Math.sin(radians);
            int outer = angle % 60 == 0 ? 98 : 94;
            canvas.drawLine(92 * x, 92 * y, outer * x, outer * y, paint);
        }
        if (!scrubbable) return;
        float sweep = ARC_SWEEP * fraction;
        stroke(0x409ee4ff, 7);
        canvas.drawArc(bounds, ARC_START, sweep, false, paint);
        stroke(ICE, 2.8f);
        canvas.drawArc(bounds, ARC_START, sweep, false, paint);
        double angle = Math.toRadians(ARC_START + sweep);
        float x = (float) Math.cos(angle) * ORBIT_RADIUS;
        float y = (float) Math.sin(angle) * ORBIT_RADIUS;
        fill(0xfff0fbff);
        canvas.drawCircle(x, y, 4.4f, paint);
    }

    private void drawConstellation(Canvas canvas) {
        canvas.save();
        canvas.translate(72, 12);
        glow(canvas, planetGlow, 113);
        canvas.save();
        canvas.rotate(-22);
        stroke(0x5b9ee4ff, .8f);
        canvas.drawOval(-69, -16, 69, 16, paint);
        canvas.restore();
        disc(canvas, 38);
        canvas.rotate(-22);
        stroke(0x9f9ee4ff, .9f);
        bounds.set(-69, -16, 69, 16);
        canvas.drawArc(bounds, 0, 180, false, paint);
        canvas.restore();

        stroke(0x919ee4ff, .72f);
        for (int i = 1; i < CONSTELLATION.length; i++) {
            int[] before = CONSTELLATION[i - 1];
            int[] after = CONSTELLATION[i];
            canvas.drawLine(before[0], before[1], after[0], after[1], paint);
        }
        for (int i = 0; i < CONSTELLATION.length; i++) {
            int[] star = CONSTELLATION[i];
            drawStar(canvas, star[0], star[1], i % 3 == 0 ? SUN : ICE);
        }
    }

    private void drawRadio(Canvas canvas) {
        glow(canvas, blueGlow, 108);
        for (int radius = 22; radius <= 88; radius += 22) {
            stroke(radius == 88 ? 0x839ee4ff : 0x4c9ee4ff, .75f);
            bounds.set(-radius, -radius, radius, radius);
            canvas.drawArc(bounds, 180, 180, false, paint);
        }
        stroke(0x509ee4ff, .75f);
        canvas.drawLine(-91, 0, 91, 0, paint);
        for (int i = -60; i <= 60; i += 30) {
            double radians = Math.toRadians(i);
            canvas.drawLine(0, 0, 88 * (float) Math.sin(radians),
                    -88 * (float) Math.cos(radians), paint);
        }
        bounds.set(-87, -87, 87, 87);
        fill(signalActive ? 0x2d9ee4ff : 0x159ee4ff);
        canvas.drawArc(bounds, -82, 31, true, paint);
        stroke(signalActive ? ICE : 0x879ee4ff, 1);
        canvas.drawLine(0, 0, 55, -68, paint);
        for (int i = 0; i < SIGNALS.length; i++) {
            int[] signal = SIGNALS[i];
            drawStar(canvas, signal[0], signal[1], signalActive && i == 2 ? SUN : ICE);
        }
    }

    private void disc(Canvas canvas, float radius) {
        bounds.set(-radius, -radius, radius, radius);
        fill(0xffffffff);
        canvas.drawBitmap(planet, null, bounds, paint);
        stroke(0x55967ea3, .7f);
        canvas.drawCircle(0, 0, radius, paint);
    }

    private void glow(Canvas canvas, Shader shader, float radius) {
        fill(0xffffffff);
        paint.setShader(shader);
        canvas.drawCircle(0, 0, radius, paint);
        paint.setShader(null);
    }

    private void drawStar(Canvas canvas, float x, float y, int color) {
        fill((color & 0x00ffffff) | 0x31000000);
        canvas.drawCircle(x, y, 4, paint);
        fill(color);
        canvas.drawCircle(x, y, 1.65f, paint);
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
