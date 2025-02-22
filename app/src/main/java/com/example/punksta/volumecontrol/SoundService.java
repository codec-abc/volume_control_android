package com.example.punksta.volumecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.example.punksta.volumecontrol.data.Settings;
import com.example.punksta.volumecontrol.data.SoundProfile;
import com.example.punksta.volumecontrol.model.SoundProfileStorage;
import com.example.punksta.volumecontrol.util.DNDModeChecker;
import com.example.punksta.volumecontrol.util.ProfileApplier;
import com.punksta.apps.libs.VolumeControl;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SoundService extends Service {

    private static final int staticNotificationNumber = 1;
    private static final String staticNotificationId = "static";
    private static final int PROFILE_ID_PREFIX = 10000;
    private static final int VOLUME_ID_PREFIX = 100;
    private static String APPLY_PROFILE_ACTION = "APPLY_PROFILE";
    private static String STOP_ACTION = "STOP_ACTION";
    private static String CHANGE_VOLUME_ACTION = "CHANGE_VOLUME_ACTION";
    private static String FOREGROUND_ACTION = "FOREGROUND_ACTION";
    private static String PROFILE_ID = "PROFILE_ID";
    private static String EXTRA_VOLUME = "EXTRA_VOLUME";
    private static String EXTRA_VOLUME_DELTA = "EXTRA_VOLUME_DELTA";
    private static String EXTRA_TYPE = "EXTRA_TYPE";
    private static String EXTRA_SHOW_PROFILES = "EXTRA_SHOW_PROFILES";
    private static String EXTRA_VOLUME_TYPES_IDS = "EXTRA_VOLUME_TYPES_IDS";
    NotificationManager manager;
    private List<Integer> profilesToShow = null;
    private boolean showProfiles = false;
    private VolumeControl control;
    private SoundProfileStorage soundProfileStorage;
    SoundProfileStorage.Listener listener = this::updateNotification;
    private VolumeControl.VolumeListener volumeListener = new MainActivity.TypeListener(AudioManager.STREAM_MUSIC) {
        @Override
        public void onChangeIndex(int autodioStream, int currentLevel, int max) {
            updateNotification();
        }
    };

    private static RemoteViews buildVolumeSlider(Context context, VolumeControl control, int typeId, String typeName) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_volume_slider);
        views.removeAllViews(R.id.volume_slider);

        int maxLevel = control.getMaxLevel(typeId);
        int minLevel = control.getMinLevel(typeId);

        int currentLevel = control.getLevel(typeId);

        int maxSliderLevel = Math.min(maxLevel, 8);

        float delta = maxLevel / (float) maxSliderLevel;

        for (int i = control.getMinLevel(typeId); i <= maxSliderLevel; i++) {

            int volumeLevel = (maxLevel * i) / maxSliderLevel;

            boolean isActive = volumeLevel <= currentLevel;
            RemoteViews sliderItemView = new RemoteViews(
                    context.getPackageName(),
                    isActive ? R.layout.notification_slider_active : R.layout.notification_slider_inactive
            );

            if (i == maxSliderLevel) {
                sliderItemView.setViewVisibility(R.id.deliver_item, View.GONE);
            }

            int requestId = VOLUME_ID_PREFIX + (volumeLevel + 1) * 100 + typeId;


            sliderItemView.setOnClickPendingIntent(
                    R.id.notification_slider_item,
                    PendingIntent.getService(
                            context,
                            requestId,
                            setVolumeIntent(context, typeId, volumeLevel),
                            PendingIntent.FLAG_UPDATE_CURRENT)
            );
            views.addView(R.id.volume_slider, sliderItemView);
        }


        views.setTextViewText(R.id.volume_title, capitalize(typeName) + " " + (currentLevel - minLevel) + "/" + (maxLevel - minLevel));


        views.setOnClickPendingIntent(
                R.id.volume_up,
                PendingIntent.getService(
                        context,
                        VOLUME_ID_PREFIX + 10 + typeId,
                        setVolumeByDeltaIntent(context, typeId, delta),
                        PendingIntent.FLAG_UPDATE_CURRENT)
        );

        views.setOnClickPendingIntent(
                R.id.volume_down,
                PendingIntent.getService(
                        context,
                        VOLUME_ID_PREFIX + 20 + typeId,
                        setVolumeByDeltaIntent(context, typeId, -delta),
                        PendingIntent.FLAG_UPDATE_CURRENT)
        );

        return views;
    }

    private static Notification buildForegroundNotification(
            Context context,
            SoundProfile[] profiles,
            VolumeControl control,
            List<Integer> volumeTypesToShow
    ) {
        Notification.Builder builder = new Notification.Builder(context);


        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.notification_view);
        if (profiles != null) {
            remoteViews.removeAllViews(R.id.notifications_user_profiles);
            for (SoundProfile profile : profiles) {
                RemoteViews profileViews = new RemoteViews(context.getPackageName(), R.layout.notification_profile_name);
                profileViews.setTextViewText(R.id.notification_profile_title, profile.name);
                Intent i = getIntentForProfile(context, profile);
                PendingIntent pendingIntent;

                int requestId = PROFILE_ID_PREFIX + profile.id;

                pendingIntent = PendingIntent.getService(context, requestId, i, 0);
                profileViews.setOnClickPendingIntent(R.id.notification_profile_title, pendingIntent);
                remoteViews.addView(R.id.notifications_user_profiles, profileViews);
            }
        }

        if (volumeTypesToShow != null) {
            remoteViews.removeAllViews(R.id.volume_sliders);

            for (AudioType notificationType : AudioType.getAudioTypes(true)) {
                if (volumeTypesToShow.contains(notificationType.audioStreamName)) {
                    remoteViews.addView(R.id.volume_sliders, buildVolumeSlider(context, control, notificationType.audioStreamName, context.getString(notificationType.nameId)));
                }
            }

            remoteViews.setOnClickPendingIntent(R.id.remove_notification_action, PendingIntent.getService(context, 100, getStopIntent(context), 0));
        }
        builder
                .setContentTitle(context.getString(R.string.app_name))
                .setOngoing(true)
                .setContentText(context.getString(R.string.notification_widget))
//                .setAutoCancel(false)
                .setSmallIcon(R.drawable.notification_icon)
                .setTicker(context.getString(R.string.app_name))
                .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0));


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(staticNotificationId);
            if ((volumeTypesToShow != null && volumeTypesToShow.size() > 0) || (profiles != null && profiles.length > 0)) {
                builder.setContentText(context.getString(R.string.notification_widget_featured))
                        .setCustomBigContentView(remoteViews);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return (builder.build());
        } else {
            return builder.getNotification();
        }
    }

    private static String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static Intent getIntentForProfile(Context content, SoundProfile profile) {
        Intent result = new Intent(content, SoundService.class);
        result.setAction(APPLY_PROFILE_ACTION);
        result.putExtra(PROFILE_ID, profile.id);
        return result;
    }

    public static Intent getStopIntent(Context content) {
        Intent result = new Intent(content, SoundService.class);
        result.setAction(STOP_ACTION);
        return result;
    }

    public static Intent setVolumeByDeltaIntent(Context context, int typeId, float delta) {
        Intent result = new Intent(context, SoundService.class);
        result.setAction(CHANGE_VOLUME_ACTION);
        result.putExtra(EXTRA_VOLUME_DELTA, delta);
        result.putExtra(EXTRA_TYPE, typeId);
        return result;
    }

    public static Intent setVolumeIntent(Context context, int typeId, int value) {
        Intent result = new Intent(context, SoundService.class);
        result.setAction(CHANGE_VOLUME_ACTION);
        result.putExtra(EXTRA_VOLUME, value);
        result.putExtra(EXTRA_TYPE, typeId);
        return result;
    }

    public static Intent getIntentForForeground(Context context, Settings settings) {
        Intent result = new Intent(context, SoundService.class);
        result.setAction(FOREGROUND_ACTION);
        result.putExtra(EXTRA_SHOW_PROFILES, settings.showProfilesInNotification);
        result.putExtra(EXTRA_VOLUME_TYPES_IDS, new ArrayList<>(Arrays.asList(settings.volumeTypesToShow)));
        return result;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification() {
        try {
            manager.notify(
                    staticNotificationNumber,
                    buildForegroundNotification(
                            this,
                            showProfiles ? soundProfileStorage.loadAll() : new SoundProfile[0],
                            control,
                            profilesToShow
                    )
            );
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent != null ? intent.getAction() : null;

        if (!DNDModeChecker.isDNDPermissionGranted(this) && !STOP_ACTION.equals(action)) {
            Toast.makeText(this, getString(R.string.dnd_permission_title), Toast.LENGTH_LONG).show();
            return super.onStartCommand(intent, flags, startId);
        }

        if (APPLY_PROFILE_ACTION.equals(action)) {
            try {
                SoundProfile profile = soundProfileStorage.loadById(intent.getIntExtra(PROFILE_ID, -1));
                ProfileApplier.applyProfile(control, profile);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return super.onStartCommand(intent, flags, startId);
        } else if (STOP_ACTION.equals(action)) {
            this.stopSelf(startId);
            return super.onStartCommand(intent, flags, startId);
        } else if (CHANGE_VOLUME_ACTION.equals(action)) {
            int type = intent.getIntExtra(EXTRA_TYPE, 0);
            if (intent.hasExtra(EXTRA_VOLUME)) {
                int volume = intent.getIntExtra(EXTRA_VOLUME, 0);
                control.setVolumeLevel(type, volume);
            } else {
                float delta = intent.getFloatExtra(EXTRA_VOLUME_DELTA, 0);
                int currentVolume = control.getLevel(type);
                int nextVolume = (int) (delta > 0 ? Math.ceil(currentVolume + delta) : Math.floor(currentVolume + delta));
                control.setVolumeLevel(type, nextVolume);
            }
            return START_STICKY;
        } else if (FOREGROUND_ACTION.equals(action)) {
            soundProfileStorage.addListener(listener);
            createStaticNotificationChannel();
            showProfiles = intent.getBooleanExtra(EXTRA_SHOW_PROFILES, true);
            profilesToShow = (List<Integer>) intent.getSerializableExtra(EXTRA_VOLUME_TYPES_IDS);

            try {
                startForeground(
                        staticNotificationNumber,
                        buildForegroundNotification(this, showProfiles ? soundProfileStorage.loadAll() : new SoundProfile[0], control, profilesToShow)
                );
            } catch (JSONException e) {
                e.printStackTrace();
            }

            for (Integer id : profilesToShow) {
                control.registerVolumeListener(id, volumeListener, false);
            }
            return START_NOT_STICKY;
        } else {
            return super.onStartCommand(intent, flags, startId);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        soundProfileStorage.removeListener(listener);
        control.unregisterAll();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        soundProfileStorage = SoundProfileStorage.getInstance(this);
        manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        control = new VolumeControl(this, new Handler());
    }

    private void createStaticNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(staticNotificationId, "Static notification widget", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }
}
