package com.lenerd.lyricsnative;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.util.Log;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Spotify pins Now Playing to portrait from code, so hold that request back while the lyrics are open. */
final class LyricsOrientation {
    private static final int UNLOCKED = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    private static final WeakHashMap<Activity, Integer> requested = new WeakHashMap<>();

    private LyricsOrientation() { }

    static void register(SpotifyPlusContext context) {
        try {
            Method method = Activity.class.getMethod("setRequestedOrientation", int.class);

            context.getHooks().before(method, call -> {
                Activity activity = (Activity) call.getThisObject();
                int orientation = (int) call.getArgs()[0];
                if (orientation == UNLOCKED || !requested.containsKey(activity)) return;

                requested.put(activity, orientation);
                call.getArgs()[0] = UNLOCKED;
            });
        } catch (Exception error) {
            Log.e("BeautifulLyrics", "Could not hook the requested orientation", error);
        }
    }

    static void unlock(Activity activity) {
        if (requested.containsKey(activity)) return;

        requested.put(activity, activity.getRequestedOrientation());
        activity.setRequestedOrientation(UNLOCKED);
    }

    static void restore(Activity activity) {
        Integer orientation = requested.remove(activity);
        if (orientation != null) activity.setRequestedOrientation(orientation);
    }
}
