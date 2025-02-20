/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.cm.QSConstants;
import com.android.internal.util.cm.QSUtils;
import com.android.internal.util.cm.QSUtils.OnQSChanged;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.SuController;

import java.util.ArrayList;

import cyanogenmod.app.CMStatusBarManager;
import cyanogenmod.app.CustomTile;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class PhoneStatusBarPolicy {
    private static final String TAG = "PhoneStatusBarPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final boolean SHOW_SYNC_ICON = false;

    private static final String SLOT_SYNC_ACTIVE = "sync_active";
    private static final String SLOT_CAST = "cast";
    private static final String SLOT_HOTSPOT = "hotspot";
    private static final String SLOT_BLUETOOTH = "bluetooth";
    private static final String SLOT_TTY = "tty";
    private static final String SLOT_ZEN = "zen";
    private static final String SLOT_VOLUME = "volume";
    private static final String SLOT_CDMA_ERI = "cdma_eri";
    private static final String SLOT_ALARM_CLOCK = "alarm_clock";
    private static final String SLOT_SU = "su";

    private static final String SDCARD_ABSENT = "sdcard_absent";
    private static final String SDCARD_KEYWORD = "SD";

    private final Context mContext;
    private final StatusBarManager mService;
    private final Handler mHandler = new Handler();
    private final CastController mCast;
    private final SuController mSuController;
    private boolean mAlarmIconVisible;
    private final HotspotController mHotspot;

    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    IccCardConstants.State[] mSimState;

    private boolean mZenVisible;
    private boolean mVolumeVisible;

    private int mZen;

    private boolean mBluetoothEnabled = false;
    StorageManager mStorageManager;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) {
                updateAlarm();
            }
            else if (action.equals(Intent.ACTION_SYNC_STATE_CHANGED)) {
                updateSyncState(intent);
            }
            else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                updateBluetooth();
            }
            else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                updateVolumeZen();
            }
            else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                updateSimState(intent);
            }
            else if (action.equals(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED)) {
                updateTTY(intent);
            }
            else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                updateAlarm();
            }
            else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                updateHeadset(intent);
            }
        }
    };

    private final OnQSChanged mQSListener = new OnQSChanged() {
        @Override
        public void onQSChanged() {
            processQSChangedLocked();
        }
    };

    private Runnable mRemoveCastIconRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon NOW");
            mService.setIconVisibility(SLOT_CAST, false);
        }
    };

    public PhoneStatusBarPolicy(Context context, CastController cast, HotspotController hotspot,
            SuController su) {
        mContext = context;
        mCast = cast;
        mHotspot = hotspot;
        mSuController = su;
        mService = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);

        // listen for broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        mSimState = new IccCardConstants.State[numPhones];
        for (int i = 0; i < numPhones; i++) {
            mSimState[i] = IccCardConstants.State.READY;
        }

        // TTY status
        mService.setIcon(SLOT_TTY,  R.drawable.stat_sys_tty_mode, 0, null);
        mService.setIconVisibility(SLOT_TTY, false);

        // Cdma Roaming Indicator, ERI
        mService.setIcon(SLOT_CDMA_ERI, R.drawable.stat_sys_roaming_cdma_0, 0, null);
        mService.setIconVisibility(SLOT_CDMA_ERI, false);

        // bluetooth status
        updateBluetooth();

        // Alarm clock
        mService.setIcon(SLOT_ALARM_CLOCK, R.drawable.stat_sys_alarm, 0, null);
        mService.setIconVisibility(SLOT_ALARM_CLOCK, false);

        // Sync state
        mService.setIcon(SLOT_SYNC_ACTIVE, R.drawable.stat_sys_sync, 0, null);
        mService.setIconVisibility(SLOT_SYNC_ACTIVE, false);
        // "sync_failing" is obsolete: b/1297963

        // zen
        mService.setIcon(SLOT_ZEN, R.drawable.stat_sys_zen_important, 0, null);
        mService.setIconVisibility(SLOT_ZEN, false);

        // volume
        mService.setIcon(SLOT_VOLUME, R.drawable.stat_sys_ringer_vibrate, 0, null);
        mService.setIconVisibility(SLOT_VOLUME, false);
        updateVolumeZen();

        if (mContext.getResources().getBoolean(R.bool.config_showSdcardAbsentIndicator)) {
            mStorageManager = (StorageManager) context
                    .getSystemService(Context.STORAGE_SERVICE);
            StorageEventListener listener = new StorageEventListener() {
                public void onStorageStateChanged(final String path,
                        final String oldState, final String newState) {
                    updateSDCardtoAbsent();
                }
            };
            mStorageManager.registerListener(listener);
        }

        // cast
        mService.setIcon(SLOT_CAST, R.drawable.stat_sys_cast, 0, null);
        mService.setIconVisibility(SLOT_CAST, false);
        mCast.addCallback(mCastCallback);

        // su
        mService.setIcon(SLOT_SU, R.drawable.stat_sys_su, 0, null);
        mService.setIconVisibility(SLOT_SU, false);
        mSuController.addCallback(mSuCallback);

        mAlarmIconObserver.onChange(true);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_ALARM_ICON),
                false, mAlarmIconObserver);

        // hotspot
        mService.setIcon(SLOT_HOTSPOT, R.drawable.stat_sys_hotspot, 0, null);
        mService.setIconVisibility(SLOT_HOTSPOT, mHotspot.isHotspotEnabled());
        mHotspot.addCallback(mHotspotCallback);

        QSUtils.registerObserverForQSChanges(mContext, mQSListener);
    }

    private ContentObserver mAlarmIconObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mAlarmIconVisible = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SHOW_ALARM_ICON, 1) == 1;
            updateAlarm();
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }
    };

    private final void updateSDCardtoAbsent() {
        mService.setIcon(SDCARD_ABSENT, R.drawable.stat_sys_no_sdcard, 0, null);
        mService.setIconVisibility(SDCARD_ABSENT, !isSdCardInsert(mContext));
    }

    private boolean isSdCardInsert(Context context) {
        return !mStorageManager.getVolumeState(getSDPath(context)).equals(
                android.os.Environment.MEDIA_REMOVED);
    }

    private String getSDPath(Context context) {
        StorageVolume[] volumes = mStorageManager.getVolumeList();
        for (int i = 0; i < volumes.length; i++) {
            if (volumes[i].isRemovable() && volumes[i].allowMassStorage()
                    && volumes[i].getDescription(context).contains(SDCARD_KEYWORD)) {
                return volumes[i].getPath();
            }
        }
        return null;
    }

    public void setZenMode(int zen) {
        mZen = zen;
        updateVolumeZen();
    }

    private void updateAlarm() {
        AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        boolean alarmSet = alarmManager.getNextAlarmClock(UserHandle.USER_CURRENT) != null;
        mService.setIconVisibility(SLOT_ALARM_CLOCK, alarmSet && mAlarmIconVisible);
    }

    private final void updateSyncState(Intent intent) {
        if (!SHOW_SYNC_ICON) return;
        boolean isActive = intent.getBooleanExtra("active", false);
        mService.setIconVisibility(SLOT_SYNC_ACTIVE, isActive);
    }

    private final void updateSimState(Intent intent) {
        IccCardConstants.State simState;
        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);

        // Obtain the subscription info from intent
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, 0);
        Log.d(TAG, "updateSimState for subId :" + subId);
        int phoneId = SubscriptionManager.getPhoneId(subId);
        Log.d(TAG, "updateSimState for phoneId :" + phoneId);
        Log.d(TAG, "updateSimState for Slot :" + SubscriptionManager.getSlotId(subId));
        if (phoneId >= 0 ) {
            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                simState = IccCardConstants.State.ABSENT;
            }
            else if (IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(stateExtra)) {
                simState = IccCardConstants.State.CARD_IO_ERROR;
            }
            else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
                simState = IccCardConstants.State.READY;
            }
            else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                final String lockedReason =
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                    simState = IccCardConstants.State.PIN_REQUIRED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                    simState = IccCardConstants.State.PUK_REQUIRED;
                }
                else {
                    simState = IccCardConstants.State.PERSO_LOCKED;
                }
            } else {
                simState = IccCardConstants.State.UNKNOWN;
            }
            mSimState[phoneId] = simState;
        }
    }

    private final void updateVolumeZen() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        boolean zenVisible = false;
        int zenIconId = 0;
        String zenDescription = null;

        boolean volumeVisible = false;
        int volumeIconId = 0;
        String volumeDescription = null;

        if (mZen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_none;
            zenDescription = mContext.getString(R.string.zen_no_interruptions);
        } else if (mZen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_important;
            zenDescription = mContext.getString(R.string.zen_important_interruptions);
        }

        if (mZen != Global.ZEN_MODE_NO_INTERRUPTIONS &&
                audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) {
            volumeVisible = true;
            volumeIconId = R.drawable.stat_sys_ringer_vibrate;
            volumeDescription = mContext.getString(R.string.accessibility_ringer_vibrate);
        }

        if (zenVisible) {
            mService.setIcon(SLOT_ZEN, zenIconId, 0, zenDescription);
        }
        if (zenVisible != mZenVisible) {
            mService.setIconVisibility(SLOT_ZEN, zenVisible);
            mZenVisible = zenVisible;
        }

        if (volumeVisible) {
            mService.setIcon(SLOT_VOLUME, volumeIconId, 0, volumeDescription);
        }
        if (volumeVisible != mVolumeVisible) {
            mService.setIconVisibility(SLOT_VOLUME, volumeVisible);
            mVolumeVisible = volumeVisible;
        }
    }

    private final void updateBluetooth() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        int iconId = R.drawable.stat_sys_data_bluetooth;
        String contentDescription =
                mContext.getString(R.string.accessibility_bluetooth_disconnected);
        if (adapter != null) {
            mBluetoothEnabled = (adapter.getState() == BluetoothAdapter.STATE_ON);
            if (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED) {
                iconId = R.drawable.stat_sys_data_bluetooth_connected;
                contentDescription = mContext.getString(R.string.accessibility_bluetooth_connected);
            }
        } else {
            mBluetoothEnabled = false;
        }

        mService.setIcon(SLOT_BLUETOOTH, iconId, 0, contentDescription);
        mService.setIconVisibility(SLOT_BLUETOOTH, mBluetoothEnabled);
    }

    private final void updateTTY(Intent intent) {
        int currentTtyMode = intent.getIntExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE,
                TelecomManager.TTY_MODE_OFF);
        boolean enabled = currentTtyMode != TelecomManager.TTY_MODE_OFF;

        if (DEBUG) Log.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY on");
            mService.setIcon(SLOT_TTY, R.drawable.stat_sys_tty_mode, 0,
                    mContext.getString(R.string.accessibility_tty_enabled));
            mService.setIconVisibility(SLOT_TTY, true);
        } else {
            // TTY is off
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY off");
            mService.setIconVisibility(SLOT_TTY, false);
        }
    }

    private void updateCast() {
        boolean isCasting = false;
        for (CastDevice device : mCast.getCastDevices()) {
            if (device.state == CastDevice.STATE_CONNECTING
                    || device.state == CastDevice.STATE_CONNECTED) {
                isCasting = true;
                break;
            }
        }
        if (DEBUG) Log.v(TAG, "updateCast: isCasting: " + isCasting);
        mHandler.removeCallbacks(mRemoveCastIconRunnable);
        if (isCasting) {
            mService.setIcon(SLOT_CAST, R.drawable.stat_sys_cast, 0,
                    mContext.getString(R.string.accessibility_casting));
            mService.setIconVisibility(SLOT_CAST, true);
        } else {
            // don't turn off the screen-record icon for a few seconds, just to make sure the user
            // has seen it
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon in 3 sec...");
            mHandler.postDelayed(mRemoveCastIconRunnable, 3000);
        }
    }

    private final HotspotController.Callback mHotspotCallback = new HotspotController.Callback() {
        @Override
        public void onHotspotChanged(boolean enabled) {
            mService.setIconVisibility(SLOT_HOTSPOT, enabled);
        }
    };

    private final CastController.Callback mCastCallback = new CastController.Callback() {
        @Override
        public void onCastDevicesChanged() {
            updateCast();
        }
    };

    private void updateSu() {
        mService.setIconVisibility(SLOT_SU, mSuController.hasActiveSessions());
        if (mSuController.hasActiveSessions()) {
            publishSuCustomTile();
        } else {
            unpublishSuCustomTile();
        }
    }

    private final SuController.Callback mSuCallback = new SuController.Callback() {
        @Override
        public void onSuSessionsChanged() {
            updateSu();
        }
    };

    private void publishSuCustomTile() {
        // This action should be performed as system
        final int userId = UserHandle.myUserId();
        long token = Binder.clearCallingIdentity();
        try {
            if (!QSUtils.isQSTileEnabledForUser(
                    mContext, QSConstants.DYNAMIC_TILE_SU, userId)) {
                return;
            }

            final UserHandle user = new UserHandle(userId);
            final int icon = QSUtils.getDynamicQSTileResIconId(mContext, userId,
                    QSConstants.DYNAMIC_TILE_SU);
            final String contentDesc = QSUtils.getDynamicQSTileLabel(mContext, userId,
                    QSConstants.DYNAMIC_TILE_SU);
            final Context resourceContext = QSUtils.getQSTileContext(mContext, userId);

            CustomTile.ListExpandedStyle style = new CustomTile.ListExpandedStyle();
            ArrayList<CustomTile.ExpandedListItem> items = new ArrayList<>();
            for (String pkg : mSuController.getPackageNamesWithActiveSuSessions()) {
                CustomTile.ExpandedListItem item = new CustomTile.ExpandedListItem();
                item.setExpandedListItemDrawable(icon);
                item.setExpandedListItemTitle(getActiveSuApkLabel(pkg));
                item.setExpandedListItemSummary(pkg);
                item.setExpandedListItemOnClickIntent(getCustomTilePendingIntent(pkg));
                items.add(item);
            }
            style.setListItems(items);

            CMStatusBarManager statusBarManager = CMStatusBarManager.getInstance(mContext);
            CustomTile tile = new CustomTile.Builder(resourceContext)
                    .setLabel(contentDesc)
                    .setContentDescription(contentDesc)
                    .setIcon(icon)
                    .setOnSettingsClickIntent(getCustomTileSettingsIntent())
                    .setExpandedStyle(style)
                    .hasSensitiveData(true)
                    .build();
            statusBarManager.publishTileAsUser(QSConstants.DYNAMIC_TILE_SU,
                    PhoneStatusBarPolicy.class.hashCode(), tile, user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void unpublishSuCustomTile() {
        // This action should be performed as system
        final int userId = UserHandle.myUserId();
        long token = Binder.clearCallingIdentity();
        try {
            CMStatusBarManager statusBarManager = CMStatusBarManager.getInstance(mContext);
            statusBarManager.removeTileAsUser(QSConstants.DYNAMIC_TILE_SU,
                    PhoneStatusBarPolicy.class.hashCode(), new UserHandle(userId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private PendingIntent getCustomTilePendingIntent(String pkg) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setPackage(pkg);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(mContext, 0, i, PendingIntent.FLAG_UPDATE_CURRENT, null);
    }

    private Intent getCustomTileSettingsIntent() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private String getActiveSuApkLabel(String pkg) {
        final PackageManager pm = mContext.getPackageManager();
        ApplicationInfo ai = null;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (final NameNotFoundException e) {
            // Ignore
        }
        return (String) (ai != null ? pm.getApplicationLabel(ai) : pkg);
    }

    private void processQSChangedLocked() {
        final int userId = UserHandle.myUserId();
        final boolean hasSuAccess = mSuController.hasActiveSessions();
        final boolean isEnabledForUser = QSUtils.isQSTileEnabledForUser(mContext,
                QSConstants.DYNAMIC_TILE_SU, userId);
        boolean enabled = (userId == UserHandle.USER_OWNER) && isEnabledForUser && hasSuAccess;
        if (enabled) {
            publishSuCustomTile();
        } else {
            unpublishSuCustomTile();
        }
    }
	
    private final void updateHeadset(Intent intent) {
        final String action = intent.getAction();
        final int state = intent.getIntExtra("state", 4);
        final int mic = intent.getIntExtra("microphone", 4);

        switch (state) {
            case 0:
                try {
                    mService.setIconVisibility("headset", false);
                } catch (Exception e) {
                }
			    break;
            case 1:
                if (mic == 1)
                    mService.setIcon("headset", R.drawable.stat_sys_headset_with_mic, 0, null);
                else
                    mService.setIcon("headset", R.drawable.stat_sys_headset_without_mic, 0, null);
                mService.setIconVisibility("headset", true);
			    break;
        }
    }
}
