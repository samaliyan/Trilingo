package io.github.samaliyan.trilingo;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Small line icons drawn in code (24×24 design grid), so the app needs no icon library.
 * Each icon scales to whatever bounds it gets and is tinted with a single colour.
 */
final class Icons {
    private Icons() { }

    enum Kind { BACK, CLOSE, SETTINGS, COPY, SPEAK, PASTE, SEARCH, SHARE, EXAMPLES, TABLE, FAMILY, BOOK, CHEVRON, MIC, PLAY, STAR, STAR_ON, CAMERA, CHECK, CARDS, FOLDER, PLUS, PEN, EAR, LIST, SORT, BUBBLE }

    static Drawable of(Kind kind, int color) {
        return new IconDrawable(kind, color);
    }

    private static final class IconDrawable extends Drawable {
        private final Kind kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconDrawable(Kind kind, int color) {
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float s = Math.min(w, h) / 24f;
            c.save();
            c.translate(getBounds().left + (w - 24 * s) / 2f, getBounds().top + (h - 24 * s) / 2f);
            c.scale(s, s);
            stroke.setStrokeWidth(2f);
            path.reset();
            switch (kind) {
                case BACK:
                    line(c, 19, 12, 5, 12);
                    path.moveTo(11, 6);
                    path.lineTo(5, 12);
                    path.lineTo(11, 18);
                    c.drawPath(path, stroke);
                    break;
                case CLOSE:
                    line(c, 7, 7, 17, 17);
                    line(c, 17, 7, 7, 17);
                    break;
                case SETTINGS:
                    // Three sliders: reads as "settings" and stays clear at small sizes.
                    line(c, 4, 7, 20, 7);
                    line(c, 4, 12, 20, 12);
                    line(c, 4, 17, 20, 17);
                    c.drawCircle(9, 7, 2.4f, fill);
                    c.drawCircle(15, 12, 2.4f, fill);
                    c.drawCircle(8, 17, 2.4f, fill);
                    break;
                case COPY:
                    r.set(8.5f, 8.5f, 19.5f, 19.5f);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    path.moveTo(15.5f, 5.5f);
                    path.lineTo(15.5f, 5f);
                    path.quadTo(15.5f, 4.5f, 15f, 4.5f);
                    path.lineTo(6f, 4.5f);
                    path.quadTo(4.5f, 4.5f, 4.5f, 6f);
                    path.lineTo(4.5f, 15f);
                    path.quadTo(4.5f, 15.5f, 5f, 15.5f);
                    path.lineTo(5.5f, 15.5f);
                    c.drawPath(path, stroke);
                    break;
                case SPEAK:
                    path.moveTo(4, 9.5f);
                    path.lineTo(7.5f, 9.5f);
                    path.lineTo(12, 5.5f);
                    path.lineTo(12, 18.5f);
                    path.lineTo(7.5f, 14.5f);
                    path.lineTo(4, 14.5f);
                    path.close();
                    c.drawPath(path, fill);
                    r.set(10, 8.5f, 17, 15.5f);
                    c.drawArc(r, -50, 100, false, stroke);
                    r.set(9, 5, 21, 19);
                    c.drawArc(r, -50, 100, false, stroke);
                    break;
                case PASTE:
                    r.set(5.5f, 5.5f, 18.5f, 20.5f);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    r.set(9, 3.5f, 15, 7.5f);
                    c.drawRoundRect(r, 1.5f, 1.5f, fill);
                    line(c, 9, 12, 15, 12);
                    line(c, 9, 16, 13.5f, 16);
                    break;
                case SEARCH:
                    c.drawCircle(10.5f, 10.5f, 6, stroke);
                    line(c, 15, 15, 20, 20);
                    break;
                case SHARE:
                    c.drawCircle(17.5f, 5.5f, 2.6f, stroke);
                    c.drawCircle(6.5f, 12, 2.6f, stroke);
                    c.drawCircle(17.5f, 18.5f, 2.6f, stroke);
                    line(c, 8.8f, 10.7f, 15.2f, 6.9f);
                    line(c, 8.8f, 13.3f, 15.2f, 17.1f);
                    break;
                case EXAMPLES:
                    // speech bubble with two lines of text
                    path.moveTo(7, 4.5f);
                    path.lineTo(17.5f, 4.5f);
                    path.quadTo(20.5f, 4.5f, 20.5f, 7.5f);
                    path.lineTo(20.5f, 13.5f);
                    path.quadTo(20.5f, 16.5f, 17.5f, 16.5f);
                    path.lineTo(12, 16.5f);
                    path.lineTo(8, 20);
                    path.lineTo(8, 16.5f);
                    path.lineTo(6.5f, 16.5f);
                    path.quadTo(3.5f, 16.5f, 3.5f, 13.5f);
                    path.lineTo(3.5f, 7.5f);
                    path.quadTo(3.5f, 4.5f, 7, 4.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    line(c, 7.5f, 9, 16.5f, 9);
                    line(c, 7.5f, 12.5f, 13.5f, 12.5f);
                    break;
                case TABLE:
                    r.set(4, 4, 20, 20);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    line(c, 4, 9.5f, 20, 9.5f);
                    line(c, 4, 14.75f, 20, 14.75f);
                    line(c, 10, 9.5f, 10, 20);
                    break;
                case FAMILY:
                    // one word branching into three (verb → its phrasal verbs)
                    c.drawCircle(5.5f, 12, 2.5f, fill);
                    line(c, 8, 12, 12, 12);
                    path.moveTo(12, 6);
                    path.lineTo(12, 18);
                    c.drawPath(path, stroke);
                    line(c, 12, 6, 15.5f, 6);
                    line(c, 12, 12, 15.5f, 12);
                    line(c, 12, 18, 15.5f, 18);
                    c.drawCircle(18.5f, 6, 2, stroke);
                    c.drawCircle(18.5f, 12, 2, stroke);
                    c.drawCircle(18.5f, 18, 2, stroke);
                    break;
                case BOOK:
                    // an open book: two pages meeting at the spine
                    path.moveTo(12, 6.5f);
                    path.quadTo(8.5f, 4.5f, 3.5f, 5);
                    path.lineTo(3.5f, 18);
                    path.quadTo(8.5f, 17.5f, 12, 19.5f);
                    path.quadTo(15.5f, 17.5f, 20.5f, 18);
                    path.lineTo(20.5f, 5);
                    path.quadTo(15.5f, 4.5f, 12, 6.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    line(c, 12, 6.5f, 12, 19.5f);
                    break;
                case MIC:
                    r.set(9, 3.5f, 15, 14.5f);
                    c.drawRoundRect(r, 3, 3, stroke);
                    r.set(5.5f, 7.5f, 18.5f, 17.5f);
                    c.drawArc(r, 0, 180, false, stroke);
                    line(c, 12, 17.5f, 12, 20.5f);
                    line(c, 9, 20.5f, 15, 20.5f);
                    break;
                case PLAY:
                    path.moveTo(8, 5);
                    path.lineTo(19, 12);
                    path.lineTo(8, 19);
                    path.close();
                    c.drawPath(path, fill);
                    break;
                case STAR:
                case STAR_ON:
                    for (int i = 0; i < 10; i++) {
                        double a = Math.PI / 2 + i * Math.PI / 5;
                        float rr = i % 2 == 0 ? 8.5f : 3.6f;
                        float x = 12 + (float) (rr * Math.cos(a)), y = 12.8f - (float) (rr * Math.sin(a));
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    path.close();
                    c.drawPath(path, kind == Kind.STAR_ON ? fill : stroke);
                    break;
                case CAMERA:
                    r.set(3, 7, 21, 19);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    path.moveTo(8.5f, 7);
                    path.lineTo(10, 4.5f);
                    path.lineTo(14, 4.5f);
                    path.lineTo(15.5f, 7);
                    c.drawPath(path, stroke);
                    c.drawCircle(12, 13, 3.3f, stroke);
                    break;
                case CHECK:
                    path.moveTo(4.5f, 12.5f);
                    path.lineTo(9.5f, 17.5f);
                    path.lineTo(19.5f, 7);
                    c.drawPath(path, stroke);
                    break;
                case EAR:
                    // headphones: listening practice
                    r.set(4, 4.5f, 20, 20.5f);
                    c.drawArc(r, 180, 180, false, stroke);
                    r.set(3.5f, 13, 8, 20.5f);
                    c.drawRoundRect(r, 2, 2, stroke);
                    r.set(16, 13, 20.5f, 20.5f);
                    c.drawRoundRect(r, 2, 2, stroke);
                    break;
                case LIST:
                    // lines of text and a magnifier: words found in a text
                    line(c, 4, 6, 20, 6);
                    line(c, 4, 10.5f, 20, 10.5f);
                    line(c, 4, 15, 11, 15);
                    line(c, 4, 19.5f, 9, 19.5f);
                    c.drawCircle(16.5f, 16.5f, 3, stroke);
                    line(c, 18.7f, 18.7f, 21, 21);
                    break;
                case SORT:
                    line(c, 4, 7, 20, 7);
                    line(c, 4, 12, 15, 12);
                    line(c, 4, 17, 10, 17);
                    break;
                case BUBBLE:
                    // a speech bubble: quick translation over other apps
                    path.moveTo(5, 4.5f);
                    path.lineTo(19, 4.5f);
                    path.quadTo(20.5f, 4.5f, 20.5f, 6);
                    path.lineTo(20.5f, 15);
                    path.quadTo(20.5f, 16.5f, 19, 16.5f);
                    path.lineTo(10, 16.5f);
                    path.lineTo(6, 20);
                    path.lineTo(6, 16.5f);
                    path.lineTo(5, 16.5f);
                    path.quadTo(3.5f, 16.5f, 3.5f, 15);
                    path.lineTo(3.5f, 6);
                    path.quadTo(3.5f, 4.5f, 5, 4.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    break;
                case CARDS:
                    r.set(7, 3.5f, 20, 16.5f);
                    c.drawRoundRect(r, 2, 2, stroke);
                    path.moveTo(4, 7.5f);
                    path.lineTo(4, 18.5f);
                    path.quadTo(4, 20.5f, 6, 20.5f);
                    path.lineTo(16, 20.5f);
                    c.drawPath(path, stroke);
                    break;
                case FOLDER:
                    path.moveTo(3.5f, 6.5f);
                    path.quadTo(3.5f, 5, 5, 5);
                    path.lineTo(9.5f, 5);
                    path.lineTo(11.5f, 7.5f);
                    path.lineTo(19, 7.5f);
                    path.quadTo(20.5f, 7.5f, 20.5f, 9);
                    path.lineTo(20.5f, 17.5f);
                    path.quadTo(20.5f, 19, 19, 19);
                    path.lineTo(5, 19);
                    path.quadTo(3.5f, 19, 3.5f, 17.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    break;
                case PLUS:
                    line(c, 12, 5, 12, 19);
                    line(c, 5, 12, 19, 12);
                    break;
                case PEN:
                    path.moveTo(4.5f, 19.5f);
                    path.lineTo(5.5f, 15.5f);
                    path.lineTo(15.5f, 5.5f);
                    path.lineTo(18.5f, 8.5f);
                    path.lineTo(8.5f, 18.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    line(c, 13.5f, 7.5f, 16.5f, 10.5f);
                    break;
                case CHEVRON:
                    path.moveTo(9.5f, 6);
                    path.lineTo(15.5f, 12);
                    path.lineTo(9.5f, 18);
                    c.drawPath(path, stroke);
                    break;
            }
            c.restore();
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, stroke);
        }

        @Override public void setAlpha(int a) {
            stroke.setAlpha(a);
            fill.setAlpha(a);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @SuppressWarnings("deprecation")   // still required by Drawable
        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override public int getIntrinsicWidth() {
            return -1;
        }

        @Override public int getIntrinsicHeight() {
            return -1;
        }
    }
}
