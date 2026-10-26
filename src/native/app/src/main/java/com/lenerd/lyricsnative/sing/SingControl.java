package com.lenerd.lyricsnative.sing;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Toast;

import androidx.core.graphics.PathParser;

import com.lenerd.spotifyplus.sdk.SpotifyPlusComponent;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONArray;
import org.json.JSONObject;

public final class SingControl extends SpotifyPlusComponent<SingControl.ControlView> {
    private final SingEngine engine;

    public SingControl(SingEngine engine) { this.engine = engine; }
    public String getName() { return "LyricsSingControl"; }

    public ControlView createView(Context context, SpotifyPlusContext spotify) {
        engine.initializePreferences(context);
        return new ControlView(context, engine);
    }

    public void updateProps(ControlView view, JSONObject old, JSONObject props) {
        view.controlsVisible = props.optBoolean("controlsVisible", true);
        view.reducedMotion = props.optBoolean("reducedMotion", false);

        JSONArray animation = props.optJSONArray("iconAnimation");

        if (animation != null && animation.length() >= 7) {
            for (int i = 0; i < 7; i++) view.iconAnimation[i] = (float) animation.optDouble(i, 1);
            for (int i = 7; i < 10; i++) view.iconAnimation[i] = (float) animation.optDouble(i, 0);
            view.invalidate();
        }

        if (!view.controlsVisible) view.finishTouch(false);
    }

    public void onDropView(View view) { ((ControlView) view).dispose(); }

    public static final class ControlView extends View {
        final SingEngine engine;
        final float density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF bounds = new RectF();
        final Path microphone, sparkle;
        final float[] iconAnimation = {1, 1, 1, 1, 1, 1, 1, 0, 0, 0};

        boolean controlsVisible = true, reducedMotion, expanded, touching, openingTouch, holding;
        float expansion;
        ValueAnimator shapeAnimator, gainAnimator;

        final Runnable refresh = new Runnable() {
            public void run() {
                if (!engine.enabled() && engine.gain() < 1 && engine.ready()) {
                    cancelFade();
                    engine.setGain(1);
                    engine.commitGain();
                    collapse();
                }

                invalidate();
                updateDescription();
                postDelayed(this, 500);
            }
        };

        ControlView(Context context, SingEngine engine) {
            super(context);

            this.engine = engine;
            density = getResources().getDisplayMetrics().density;
            expanded = engine.gain() < 1;
            expansion = expanded ? 1 : 0;
            microphone = loadVector("microphone");
            sparkle = loadVector("sparkle");

            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
            setFocusable(true);
            updateDescription();
        }

        Path loadVector(String name) {
            try (java.io.InputStream stream = SingControl.class.getClassLoader().getResourceAsStream("assets/images/" + name + ".json")) {
                if (stream == null) return null;

                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[4096];
                int count;

                while ((count = stream.read(block)) != -1) bytes.write(block, 0, count);

                JSONObject source = new JSONObject(bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
                JSONArray box = source.getJSONArray("box"), paths = source.getJSONArray("paths");
                Path icon = new Path();

                for (int i = 0; i < paths.length(); i++) icon.addPath(PathParser.createPathFromPathData(paths.getString(i)));

                icon.offset((float) -box.getDouble(0), (float) -box.getDouble(1));

                Matrix scale = new Matrix();
                scale.setScale((float) (256 / box.getDouble(2)), (float) (256 / box.getDouble(3)));
                icon.transform(scale);

                return icon;
            } catch (Exception error) {
                android.util.Log.e("SpotifySing", "Icon unavailable: " + name, error);
                return null;
            }
        }

        void geometry() {
            float width = (44 + 6 * expansion) * density, height = (44 + 84 * expansion) * density;
            float right = getWidth() - 6 * density, bottom = getHeight() - 6 * density;
            bounds.set(right - width, bottom - height, right, bottom);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            geometry();

            float radius = (14 + 6 * expansion) * density;
            paint.setColor(Color.argb((int) (38 + 26 * expansion), 255, 255, 255));
            paint.setShadowLayer(8 * density, 0, 4 * density, 0x30000000);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.clearShadowLayer();

            if (expansion > 0) {
                int saved = canvas.save();
                Path capsule = new Path();
                capsule.addRoundRect(bounds, radius, radius, Path.Direction.CW);
                canvas.clipPath(capsule);

                paint.setColor(Color.argb((int) (255 * expansion), 255, 255, 255));
                float fill = engine.gain() * bounds.height();

                canvas.drawRect(bounds.left, bounds.bottom - fill, bounds.right, bounds.bottom, paint);
                canvas.restoreToCount(saved);
            }

            if (microphone != null) {
                float centerY = bounds.bottom - 22 * density;
                drawIcons(canvas, bounds.centerX(), centerY, Color.WHITE);

                int iconColor = Color.rgb((int) (255 + (100 - 255) * expansion), (int) (255 + (123 - 255) * expansion), (int) (255 + (113 - 255) * expansion));
                int saved = canvas.save();

                canvas.clipRect(bounds.left, bounds.bottom - engine.gain() * bounds.height(), bounds.right, bounds.bottom);
                drawIcons(canvas, bounds.centerX(), centerY, iconColor);
                canvas.restoreToCount(saved);
            }
        }

        void drawIcons(Canvas canvas, float x, float y, int color) {
            float unit = density * (1 + .12f * expansion);

            drawIcon(canvas, microphone, x, y, 21 * unit * iconAnimation[0], 1, 0, color);
            drawIcon(canvas, sparkle, x - 10 * unit, y - 7 * unit, 5.2f * unit * iconAnimation[1], iconAnimation[2], iconAnimation[7], color);
            drawIcon(canvas, sparkle, x + 10 * unit, y + 5 * unit, 6.5f * unit * iconAnimation[3], iconAnimation[4], iconAnimation[8], color);
            drawIcon(canvas, sparkle, x - 2 * unit, y + 11 * unit, 4.35f * unit * iconAnimation[5], iconAnimation[6], iconAnimation[9], color);
        }

        void drawIcon(Canvas canvas, Path icon, float x, float y, float size, float alpha, float rotation, int color) {
            if (icon == null || alpha <= 0 || size <= 0) return;

            paint.setColor(color);
            paint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));

            int saved = canvas.save();
            canvas.rotate(rotation, x, y);
            canvas.translate(x - size / 2, y - size / 2);
            canvas.scale(size / 256, size / 256);
            canvas.drawPath(icon, paint);
            canvas.restoreToCount(saved);
            paint.setAlpha(255);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(refresh);
        }

        @Override
        protected void onDetachedFromWindow() {
            dispose();
            super.onDetachedFromWindow();
        }

        void hold() {
            if (!holding) {
                holding = true;
                engine.emitInteraction(true);
            }
        }

        void release() {
            if (holding) {
                holding = false;
                engine.emitInteraction(false);
            }
        }

        void animateShape(float target) {
            if (shapeAnimator != null) shapeAnimator.cancel();

            shapeAnimator = ValueAnimator.ofFloat(expansion, target);
            shapeAnimator.setDuration(reducedMotion ? 0 : 280);
            shapeAnimator.setInterpolator(new PathInterpolator(.2f, 0, .2f, 1));
            shapeAnimator.addUpdateListener(value -> {
                expansion = (float) value.getAnimatedValue();
                invalidate();
            });
            shapeAnimator.start();
        }

        void open() {
            if (!engine.ready()) {
                Toast.makeText(getContext(), engine.status(), Toast.LENGTH_SHORT).show();
                return;
            }

            feedback();
            hold();
            expanded = true;
            engine.setEnabled(true);
            animateShape(1);

            cancelFade();
            gainAnimator = ValueAnimator.ofFloat(engine.gain(), 0);
            gainAnimator.setDuration(reducedMotion ? 0 : 700);
            gainAnimator.setInterpolator(new PathInterpolator(.2f, 0, .2f, 1));
            gainAnimator.addUpdateListener(value -> {
                engine.setGain((float) value.getAnimatedValue());
                invalidate();
            });
            gainAnimator.addListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator value) {
                    gainAnimator = null;
                    engine.commitGain();
                    updateDescription();
                    if (!touching) release();
                }
            });

            gainAnimator.start();
            updateDescription();
        }

        void cancelFade() {
            if (gainAnimator != null) {
                gainAnimator.removeAllListeners();
                gainAnimator.cancel();
                gainAnimator = null;
            }
        }

        void collapse() {
            expanded = false;
            animateShape(0);
            updateDescription();
            engine.emitState();
        }

        void finishTouch(boolean released) {
            if (!touching) return;

            touching = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);

            if (!openingTouch) {
                engine.commitGain();

                if (released) {
                    if (engine.gain() >= 1) turnOff();
                    else feedback();
                }
            }

            openingTouch = false;
            updateDescription();
            if (gainAnimator == null) release();
        }

        void drag(float y) {
            geometry();

            float top = bounds.top + 4 * density, bottom = bounds.bottom - 4 * density;
            engine.setGain(Math.max(0, Math.min(1, (bottom - y) / (bottom - top))));
            invalidate();
            updateDescription();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                geometry();
                if (!controlsVisible || !bounds.contains(event.getX(), event.getY())) return false;

                touching = true;
                openingTouch = !expanded;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);

                if (openingTouch) {
                    open();
                    if (!expanded) finishTouch(false);
                } else {
                    feedback();
                    hold();
                    cancelFade();
                    drag(event.getY());
                }

                return true;
            }

            if (!touching) return false;

            if (action == MotionEvent.ACTION_MOVE && !openingTouch) {
                drag(event.getY());
            } else if (action == MotionEvent.ACTION_UP) {
                if (!openingTouch) drag(event.getY());
                finishTouch(true);
                performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                finishTouch(false);
            }

            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        void feedback() { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }

        void turnOff() {
            if (expanded) feedback();
            engine.setEnabled(false);
            collapse();
        }

        void updateDescription() {
            setContentDescription(expanded ? "Vocal volume, " + Math.round(engine.gain() * 100) + " percent" : "Reduce vocals");
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(expanded ? "android.widget.SeekBar" : "android.widget.Button");

            if (expanded) {
                info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 100, engine.gain() * 100));
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle args) {
            if (!controlsVisible) return false;
            if (action == AccessibilityNodeInfo.ACTION_CLICK && !expanded) {
                open();
                return true;
            }

            float next;

            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
                next = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100f;
            } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                next = engine.gain() + .1f;
            } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                next = engine.gain() - .1f;
            } else {
                return super.performAccessibilityAction(action, args);
            }

            cancelFade();
            engine.setGain(Math.max(0, Math.min(1, next)));
            engine.commitGain();

            if (engine.gain() >= 1) {
                turnOff();
            } else {
                expanded = true;
                engine.setEnabled(true);
                animateShape(1);
            }

            release();
            invalidate();
            updateDescription();
            return true;
        }

        void dispose() {
            removeCallbacks(refresh);
            cancelFade();
            if (shapeAnimator != null) shapeAnimator.cancel();

            if (touching) finishTouch(false);
            engine.commitGain();
            release();
            if (engine.gain() >= 1) engine.setEnabled(false);
        }
    }
}
