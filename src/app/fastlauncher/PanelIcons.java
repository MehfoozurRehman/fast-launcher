package app.fastlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Line icons for the control panel, drawn on a 24-unit grid in the launcher's stroke style. */
final class PanelIcons extends View {
    static final int WIFI = 0, HOTSPOT = 1, ROTATE = 2, TORCH = 3, BLUETOOTH = 4, DND = 5, DATA = 6,
            LOCATION = 7, PLANE = 8, BATTERY = 9, SCREENSHOT = 10, LOCK = 11, GEAR = 12, SUN = 13,
            SPEAKER = 14, PREV = 15, PLAY = 16, PAUSE = 17, NEXT = 18, ROTATE_LOCK = 19,
            BELL = 20, VIBRATE = 21, BELL_OFF = 22, DARK = 23, EYE = 24, CAST = 25, RECORD = 26,
            CALC = 27, MIC = 28, POWER = 29;

    int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    PanelIcons(Context c, int type, int color) {
        super(c);
        this.type = type;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        f.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setColor(int color) {
        p.setColor(color);
        f.setColor(color);
        invalidate();
    }

    void setType(int t) {
        if (t != type) { type = t; invalidate(); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float u = Math.min(w, h) / 24f;
        c.save();
        c.translate(getPaddingLeft() + (w - 24 * u) / 2f, getPaddingTop() + (h - 24 * u) / 2f);
        c.scale(u, u);
        p.setStrokeWidth(1.9f);
        path.reset();
        switch (type) {
            case WIFI:
                r.set(2, 5, 22, 25); c.drawArc(r, 220, 100, false, p);
                r.set(5.5f, 8.5f, 18.5f, 21.5f); c.drawArc(r, 220, 100, false, p);
                r.set(9, 12, 15, 18); c.drawArc(r, 220, 100, false, p);
                c.drawCircle(12, 18.6f, 1.4f, f);
                break;
            case HOTSPOT:
                c.drawCircle(12, 12, 1.8f, f);
                r.set(7.5f, 7.5f, 16.5f, 16.5f);
                c.drawArc(r, 135, 270, false, p);
                r.set(3.5f, 3.5f, 20.5f, 20.5f);
                c.drawArc(r, 135, 270, false, p);
                break;
            case ROTATE:
            case ROTATE_LOCK:
                r.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(r, 300, 290, false, p);
                path.moveTo(15.5f, 2.8f); path.lineTo(16.6f, 6.6f); path.lineTo(12.8f, 7.4f);
                c.drawPath(path, p);
                if (type == ROTATE_LOCK) {
                    r.set(9, 11.5f, 15, 16.5f); c.drawRoundRect(r, 1, 1, f);
                    r.set(10.2f, 9, 13.8f, 13); c.drawArc(r, 180, 180, false, p);
                }
                break;
            case TORCH:
                path.moveTo(7, 3); path.lineTo(17, 3); path.lineTo(17, 7); path.lineTo(14.5f, 11);
                path.lineTo(14.5f, 21); path.lineTo(9.5f, 21); path.lineTo(9.5f, 11); path.lineTo(7, 7); path.close();
                c.drawPath(path, p);
                c.drawLine(7, 7, 17, 7, p);
                c.drawLine(12, 13.5f, 12, 15.5f, p);
                break;
            case BLUETOOTH:
                path.moveTo(7, 7.5f); path.lineTo(17, 16.5f); path.lineTo(12, 21); path.lineTo(12, 3);
                path.lineTo(17, 7.5f); path.lineTo(7, 16.5f);
                c.drawPath(path, p);
                break;
            case DND:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                c.drawPath(path, p);
                c.drawLine(8, 12, 16, 12, p);
                break;
            case DATA:
                c.drawLine(9, 4, 9, 20, p); path.moveTo(5.5f, 7.5f); path.lineTo(9, 4); path.lineTo(12.5f, 7.5f);
                c.drawLine(15, 4, 15, 20, p); path.moveTo(11.5f, 16.5f); path.lineTo(15, 20); path.lineTo(18.5f, 16.5f);
                c.drawPath(path, p);
                break;
            case LOCATION:
                path.moveTo(12, 21.5f);
                path.cubicTo(12, 21.5f, 5, 14.5f, 5, 9.5f);
                path.cubicTo(5, 5.6f, 8.1f, 2.5f, 12, 2.5f);
                path.cubicTo(15.9f, 2.5f, 19, 5.6f, 19, 9.5f);
                path.cubicTo(19, 14.5f, 12, 21.5f, 12, 21.5f);
                c.drawPath(path, p);
                c.drawCircle(12, 9.5f, 2.5f, p);
                break;
            case PLANE:
                path.moveTo(12, 2.5f); path.cubicTo(13.2f, 2.5f, 13.5f, 4, 13.5f, 5); path.lineTo(13.5f, 9.5f);
                path.lineTo(21, 14); path.lineTo(21, 16); path.lineTo(13.5f, 13.5f); path.lineTo(13.5f, 18);
                path.lineTo(16, 20); path.lineTo(16, 21.5f); path.lineTo(12, 20.5f); path.lineTo(8, 21.5f);
                path.lineTo(8, 20); path.lineTo(10.5f, 18); path.lineTo(10.5f, 13.5f); path.lineTo(3, 16);
                path.lineTo(3, 14); path.lineTo(10.5f, 9.5f); path.lineTo(10.5f, 5);
                path.cubicTo(10.5f, 4, 10.8f, 2.5f, 12, 2.5f); path.close();
                c.drawPath(path, p);
                break;
            case BATTERY:
                r.set(7, 4.5f, 17, 21); c.drawRoundRect(r, 2.2f, 2.2f, p);
                c.drawLine(10, 2.5f, 14, 2.5f, p);
                path.moveTo(12.8f, 8); path.lineTo(10, 13); path.lineTo(14, 13); path.lineTo(11.2f, 18);
                c.drawPath(path, p);
                break;
            case SCREENSHOT:
                path.moveTo(3.5f, 8); path.lineTo(3.5f, 3.5f); path.lineTo(8, 3.5f);
                path.moveTo(16, 3.5f); path.lineTo(20.5f, 3.5f); path.lineTo(20.5f, 8);
                path.moveTo(20.5f, 16); path.lineTo(20.5f, 20.5f); path.lineTo(16, 20.5f);
                path.moveTo(8, 20.5f); path.lineTo(3.5f, 20.5f); path.lineTo(3.5f, 16);
                c.drawPath(path, p);
                c.drawCircle(12, 12, 3, p);
                break;
            case LOCK:
                r.set(5, 10.5f, 19, 21); c.drawRoundRect(r, 2.5f, 2.5f, p);
                r.set(8, 3.5f, 16, 13.5f); c.drawArc(r, 180, 180, false, p);
                c.drawLine(8, 8.5f, 8, 10.5f, p); c.drawLine(16, 8.5f, 16, 10.5f, p);
                c.drawCircle(12, 15.7f, 1.3f, f);
                break;
            case GEAR: {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(12 + (float) Math.cos(a) * 6.5f, 12 + (float) Math.sin(a) * 6.5f,
                            12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, p);
                }
                c.drawCircle(12, 12, 6.5f, p);
                c.drawCircle(12, 12, 2.6f, p);
                break;
            }
            case SUN:
                c.drawCircle(12, 12, 4, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(12 + (float) Math.cos(a) * 6.8f, 12 + (float) Math.sin(a) * 6.8f,
                            12 + (float) Math.cos(a) * 9, 12 + (float) Math.sin(a) * 9, p);
                }
                break;
            case SPEAKER:
                path.moveTo(3.5f, 9.5f); path.lineTo(7.5f, 9.5f); path.lineTo(12.5f, 5); path.lineTo(12.5f, 19);
                path.lineTo(7.5f, 14.5f); path.lineTo(3.5f, 14.5f); path.close();
                c.drawPath(path, p);
                r.set(11, 8, 19, 16); c.drawArc(r, -50, 100, false, p);
                r.set(11, 4.5f, 22.5f, 19.5f); c.drawArc(r, -50, 100, false, p);
                break;
            case PREV:
                path.moveTo(18, 5.5f); path.lineTo(9, 12); path.lineTo(18, 18.5f); path.close();
                c.drawPath(path, f);
                r.set(5, 5.5f, 7.2f, 18.5f); c.drawRoundRect(r, 1, 1, f);
                break;
            case NEXT:
                path.moveTo(6, 5.5f); path.lineTo(15, 12); path.lineTo(6, 18.5f); path.close();
                c.drawPath(path, f);
                r.set(16.8f, 5.5f, 19, 18.5f); c.drawRoundRect(r, 1, 1, f);
                break;
            case PLAY:
                path.moveTo(7.5f, 4.5f); path.lineTo(19.5f, 12); path.lineTo(7.5f, 19.5f); path.close();
                c.drawPath(path, f);
                break;
            case BELL:
            case BELL_OFF:
                path.moveTo(6, 17); path.lineTo(6, 11); path.cubicTo(6, 7.5f, 8.7f, 4.5f, 12, 4.5f);
                path.cubicTo(15.3f, 4.5f, 18, 7.5f, 18, 11); path.lineTo(18, 17); path.close();
                c.drawPath(path, p);
                c.drawLine(4.5f, 17, 19.5f, 17, p);
                c.drawLine(10.5f, 20, 13.5f, 20, p);
                c.drawLine(12, 2.5f, 12, 4.5f, p);
                if (type == BELL_OFF) c.drawLine(4, 4, 20, 20, p);
                break;
            case VIBRATE:
                r.set(8, 4, 16, 20); c.drawRoundRect(r, 1.8f, 1.8f, p);
                c.drawLine(4.5f, 9, 4.5f, 15, p); c.drawLine(19.5f, 9, 19.5f, 15, p);
                c.drawLine(2, 10.5f, 2, 13.5f, p); c.drawLine(22, 10.5f, 22, 13.5f, p);
                break;
            case DARK:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(12, 3.5f);
                r.set(3.5f, 3.5f, 20.5f, 20.5f);
                path.arcTo(r, -90, 180);
                path.close();
                c.drawPath(path, f);
                break;
            case EYE:
                path.moveTo(2.5f, 12); path.cubicTo(6, 5.5f, 18, 5.5f, 21.5f, 12);
                path.cubicTo(18, 18.5f, 6, 18.5f, 2.5f, 12);
                c.drawPath(path, p);
                c.drawCircle(12, 12, 3, p);
                break;
            case CAST:
                path.moveTo(3, 8); path.lineTo(3, 6); path.cubicTo(3, 5, 4, 4.5f, 5, 4.5f); path.lineTo(19, 4.5f);
                path.cubicTo(20, 4.5f, 21, 5, 21, 6); path.lineTo(21, 18); path.cubicTo(21, 19, 20, 19.5f, 19, 19.5f);
                path.lineTo(14, 19.5f);
                c.drawPath(path, p);
                r.set(-1, 11.5f, 11, 23.5f); c.drawArc(r, -90, 90, false, p);
                r.set(-1, 15.5f, 7, 23.5f); c.drawArc(r, -90, 90, false, p);
                c.drawCircle(3, 19.5f, 1.3f, f);
                break;
            case RECORD:
                c.drawCircle(12, 12, 8.5f, p);
                c.drawCircle(12, 12, 4, f);
                break;
            case CALC:
                r.set(5, 3, 19, 21); c.drawRoundRect(r, 2.5f, 2.5f, p);
                r.set(8, 6, 16, 9.5f); c.drawRoundRect(r, 1, 1, p);
                for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) c.drawCircle(9 + j * 6, 13 + i * 3, 0.9f, f);
                break;
            case MIC:
                r.set(9, 3, 15, 14); c.drawRoundRect(r, 3, 3, p);
                r.set(6, 7, 18, 17.5f); c.drawArc(r, 0, 180, false, p);
                c.drawLine(12, 17.5f, 12, 21, p);
                break;
            case POWER:
                r.set(4.5f, 5, 19.5f, 20); c.drawArc(r, -60, 300, false, p);
                c.drawLine(12, 3, 12, 11.5f, p);
                break;
            case PAUSE:
                r.set(6.5f, 5, 10, 19); c.drawRoundRect(r, 1.2f, 1.2f, f);
                r.set(14, 5, 17.5f, 19); c.drawRoundRect(r, 1.2f, 1.2f, f);
                break;
        }
        c.restore();
    }
}
