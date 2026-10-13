
package com.lenerd.lyricsnative;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.core.graphics.PathParser;

import com.lenerd.spotifyplus.sdk.SpotifyPlusComponent;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class LyricsTranslationIcon extends SpotifyPlusComponent<LyricsTranslationIcon.IconView> {
    private static final Map<String, Vector> vectors = new HashMap<>();

    public String getName() { return "LyricsTranslationIcon"; }
    public IconView createView(Context context, SpotifyPlusContext spotify) { return new IconView(context); }

    public void updateProps(IconView view, JSONObject previous, JSONObject props) {
        String name = props.optString("name", "globe");
        if (!name.equals("globe") && !name.equals("subtitle")) return;

        view.vector = load(name);
        view.invalidate();
    }

    private static synchronized Vector load(String name) {
        if (vectors.containsKey(name)) return vectors.get(name);

        try (InputStream stream = LyricsTranslationIcon.class.getClassLoader().getResourceAsStream("assets/images/" + name + ".json")) {
            if (stream == null) return null;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096];
            int length;

            while ((length = stream.read(block)) != -1) bytes.write(block, 0, length);

            JSONObject source = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            JSONArray box = source.getJSONArray("box"), paths = source.getJSONArray("paths");
            Path path = new Path();

            for (int i = 0; i < paths.length(); i++) path.addPath(PathParser.createPathFromPathData(paths.getString(i)));

            path.offset((float) -box.getDouble(0), (float) -box.getDouble(1));

            Vector vector = new Vector(path, (float) box.getDouble(2), (float) box.getDouble(3));
            vectors.put(name, vector);

            return vector;
        } catch (Exception error) {
            android.util.Log.e("LyricsTranslation", "Could not load icon " + name, error);
            return null;
        }
    }

    private static final class Vector {
        final Path path;
        final float width, height;

        Vector(Path path, float width, float height) {
            this.path = path;
            this.width = width;
            this.height = height;
        }
    }

    public static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Vector vector;

        IconView(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (vector == null) return;

            int save = canvas.save();
            canvas.scale(getWidth() / vector.width, getHeight() / vector.height);
            canvas.drawPath(vector.path, paint);
            canvas.restoreToCount(save);
        }
    }
}
