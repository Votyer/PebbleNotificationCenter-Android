package com.matejdro.pebblenotificationcenter.pebble.modules;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.util.SparseArray;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.matejdro.pebblecommons.pebble.CommModule;
import com.matejdro.pebblecommons.pebble.PebbleCommunication;
import com.matejdro.pebblecommons.pebble.PebbleImageToolkit;
import com.matejdro.pebblecommons.pebble.PebbleTalkerService;
import com.matejdro.pebblenotificationcenter.NCTalkerService;
import com.matejdro.pebblenotificationcenter.PebbleNotification;
import com.matejdro.pebblenotificationcenter.PebbleNotificationCenter;
import com.matejdro.pebblenotificationcenter.ProcessedNotification;
import com.matejdro.pebblenotificationcenter.appsetting.AppSetting;
import com.matejdro.pebblenotificationcenter.appsetting.AppSettingStorage;
import com.matejdro.pebblenotificationcenter.notifications.JellybeanNotificationListener;
import com.matejdro.pebblenotificationcenter.notifications.actions.DismissOnPebbleAction;
import com.matejdro.pebblenotificationcenter.notifications.actions.NotificationAction;
import com.matejdro.pebblenotificationcenter.notifications.actions.ReplaceNotificationAction;
import com.matejdro.pebblenotificationcenter.pebble.NotificationCenterDeveloperConnection;
import com.matejdro.pebblecommons.util.DeviceUtil;
import com.matejdro.pebblenotificationcenter.util.PreferencesUtil;
import com.matejdro.pebblecommons.util.TextUtil;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import timber.log.Timber;

/**
 * Created by Matej on 28.11.2014.
 */
public class NotificationSendingModule extends CommModule
{
    public static final int MODULE_NOTIFICATION_SENDING = 1;
    public static final String INTENT_NOTIFICATION = "Notification";

    public static final int TEXT_LIMIT = 2000;
    public static final int TITLE_TEXT_LIMIT = 30;

    private HashMap<String, Long> lastAppVibration = new HashMap<String, Long>();
    private HashMap<String, Long> lastAppNotification = new HashMap<String, Long>();
    private ProcessedNotification curSendingNotification;
    private Queue<ProcessedNotification> sendingQueue = new LinkedList<ProcessedNotification>();

    public NotificationSendingModule(PebbleTalkerService service)
    {
        super(service);
        service.registerIntent(INTENT_NOTIFICATION, this);
    }

    public void processNotification(PebbleNotification notificationSource)
    {
        Timber.d("notify internal");

        ProcessedNotification notification = new ProcessedNotification();
        notification.source = notificationSource;
        AppSettingStorage settingStorage = notificationSource.getSettingStorage(getService());

        String customTitle = settingStorage.getString(AppSetting.CUSTOM_TITLE);

        if (!customTitle.isEmpty())
        {
            if (customTitle.trim().isEmpty()) //Space in title
            {
                notificationSource.setTitle(notificationSource.getSubtitle());
                notificationSource.setSubtitle(null);
            }
            else
            {
                notificationSource.setTitle(customTitle);
            }
        }
        
        if (notificationSource.getSubtitle().isEmpty())
        {
            //Attempt to figure out subtitle
            String subtitle = "";
            String text = notificationSource.getText();

            if (text.contains("\n"))
            {
                int firstLineBreak = text.indexOf('\n');
                if (firstLineBreak < TITLE_TEXT_LIMIT && firstLineBreak < text.length() * 0.8)
                {
                    subtitle = text.substring(0, firstLineBreak).trim();
                    text = text.substring(firstLineBreak).trim();
                }
            }

            notificationSource.setText(text);
            notificationSource.setSubtitle(subtitle);
        }
        else if (notificationSource.getSubtitle().length() > TITLE_TEXT_LIMIT)
        {
            //Do not cut off titles which some apps make very long
            notificationSource.setText(notificationSource.getSubtitle() + "\n" + notificationSource.getText());
            notificationSource.setSubtitle("");
        }

        if (notificationSource.getTitle().trim().equals(notificationSource.getSubtitle().trim()))
            notificationSource.setSubtitle("");

        int textLimit = getMaximumTextLength(settingStorage);

        if (!notificationSource.isListNotification())
        {
            String combinedText = notificationSource.getTitle() + "\n" + notificationSource.getSubtitle() + "\n" + notificationSource.getText();
            List<String> regexList = settingStorage.getStringList(AppSetting.INCLUDED_REGEX);
            if (regexList.size() > 0 && !TextUtil.containsRegexes(combinedText, regexList))
                return;

            regexList = settingStorage.getStringList(AppSetting.EXCLUDED_REGEX);
            if (TextUtil.containsRegexes(combinedText, regexList))
                return;

            if (!notificationSource.isHistoryDisabled() &&
                    settingStorage.getBoolean(AppSetting.SAVE_TO_HISTORY) &&
                    canDisplayWearGroupNotification(notification.source, settingStorage))
            {
                NCTalkerService.fromPebbleTalkerService(getService()).getHistoryDatabase().storeNotification(notificationSource.getRawPostTime(), TextUtil.trimString(notificationSource.getTitle(), 30, true), TextUtil.trimString(notificationSource.getSubtitle(), 30, true), TextUtil.trimString(notificationSource.getText(), textLimit, true));
            }
        }

        notificationSource.setText(TextUtil.prepareString(notificationSource.getText(), textLimit));
        notificationSource.setTitle(TextUtil.prepareString(notificationSource.getTitle(), TITLE_TEXT_LIMIT));
        notificationSource.setSubtitle(TextUtil.prepareString(notificationSource.getSubtitle(), TITLE_TEXT_LIMIT));

        if (!notificationSource.isListNotification())
        {
            if (!settingStorage.getBoolean(AppSetting.SEND_BLANK_NOTIFICATIONS)) {
                if (notificationSource.getText().trim().isEmpty() && (notificationSource.getSubtitle() == null || notificationSource.getSubtitle().trim().isEmpty())) {
                    Timber.d("Discarding notification because it is empty");
                    return;
                }
            }


            if (getService().getGlobalSettings().getBoolean(PebbleNotificationCenter.NOTIFICATIONS_DISABLED, false))
                return;

            if (settingStorage.getBoolean(AppSetting.DISABLE_NOTIFY_SCREEN_OIN))
            {
                if (DeviceUtil.isScreenOn(getService()))
                {
                    Timber.d("notify failed - screen is on");
                    return;
                }
            }

            if (getService().getGlobalSettings().getBoolean(PebbleNotificationCenter.NO_NOTIFY_VIBRATE, false))
            {
                AudioManager am = (AudioManager) getService().getSystemService(Context.AUDIO_SERVICE);
                if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
                {
                    Timber.d("notify failed - ringer is silent");
                    return;
                }

            }

            if (settingStorage.getBoolean(AppSetting.QUIET_TIME_ENABLED))
            {
                int startHour = settingStorage.getInt(AppSetting.QUIET_TIME_START_HOUR);
                int startMinute = settingStorage.getInt(AppSetting.QUIET_TIME_START_MINUTE);
                int startTime = startHour * 60 + startMinute;

                int endHour = settingStorage.getInt(AppSetting.QUIET_TIME_END_HOUR);
                int endMinute = settingStorage.getInt(AppSetting.QUIET_TIME_END_MINUTE);
                int endTime = endHour * 60 + endMinute;

                Calendar calendar = Calendar.getInstance();
                int curHour = calendar.get(Calendar.HOUR_OF_DAY);
                int curMinute = calendar.get(Calendar.MINUTE);
                int curTime = curHour * 60 + curMinute;


                if ((endTime > startTime && curTime <= endTime && curTime >= startTime) || (endTime < startTime && (curTime <= endTime || curTime >= startTime)))
                {
                    Timber.d("notify failed - quiet time");
                    return;
                }
            }

            if (getService().getGlobalSettings().getBoolean("noNotificationsNoPebble", false) && !isWatchConnected(getService()))
            {
                Timber.d("notify failed - watch not connected");
                return;
            }

            if (settingStorage.getBoolean(AppSetting.RESPECT_ANDROID_INTERRUPT_FILTER) && JellybeanNotificationListener.isNotificationFilteredByDoNotInterrupt(notificationSource.getKey()))
            {
                Timber.d("notify failed - interrupt filter");
                return;
            }

            int minNotificationInterval = 0;
            try
            {
                minNotificationInterval = Integer.parseInt(settingStorage.getString(AppSetting.MINIMUM_NOTIFICATION_INTERVAL));
            }
            catch (NumberFormatException e)
            {
            }

            if (minNotificationInterval > 0) {
                Long lastNotification = lastAppNotification.get(notification.source.getKey().getPackage());
                if (lastNotification != null) {
                    if ((System.currentTimeMillis() - lastNotification) < minNotificationInterval * 1000) {
                        Timber.d("notification ignored - minimum interval not passed!");
                        return;
                    }
                }
            }
        }

        int colorFromConfig = settingStorage.getInt(AppSetting.STATUSBAR_COLOR);
        if (Color.alpha(colorFromConfig) != 0)
            notificationSource.setColor(colorFromConfig);

        SparseArray<ProcessedNotification> sentNotifications = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications;

        Random rnd = new Random();
        do
        {
            notification.id = rnd.nextInt();
        }
        while (sentNotifications.get(notification.id) != null);

        if (!notification.source.isListNotification() && !canDisplayWearGroupNotification(notification.source, settingStorage))
        {
            sentNotifications.put(notification.id, notification);
            Timber.d("notify failed - group");
            return;
        }

        DismissUpwardsModule.get(getService()).processDismissUpwards(notificationSource.getKey(), false);

        if (settingStorage.getBoolean(AppSetting.HIDE_NOTIFICATION_TEXT) && !notificationSource.isListNotification() && !notificationSource.isHidingTextDisallowed())
            sendNotificationAsPrivate(notification);
        else
            sendNotification(notification);

    }

    private void notificationTransferCompleted()
    {
        if (curSendingNotification.vibrated)
            lastAppVibration.put(curSendingNotification.source.getKey().getPackage(), System.currentTimeMillis());
        lastAppNotification.put(curSendingNotification.source.getKey().getPackage(), System.currentTimeMillis());

        curSendingNotification = null;
    }

    private void sendNotificationAsPrivate(ProcessedNotification notification)
    {
        PebbleNotification coverNotification = new PebbleNotification(notification.source.getTitle(), "Use Show action to uncover it.", notification.source.getKey());
        coverNotification.setSubtitle("Hidden notification");
        coverNotification.setHidingTextDisallowed(true);
        coverNotification.setNoHistory(true);

        ArrayList<NotificationAction> actions = new ArrayList<>();
        actions.add(new ReplaceNotificationAction("Show", notification));
        actions.add(new DismissOnPebbleAction(getService()));
        coverNotification.setActions(actions);

        processNotification(coverNotification);
    }

    public void sendNotification(ProcessedNotification notification)
    {
        NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications.put(notification.id, notification);

        int pebbleAppMode = 0;
        if (!notification.source.isListNotification())
        {
            //Different type of notification depending on Pebble app
            SystemModule systemModule = SystemModule.get(getService());
            systemModule.updateCurrentlyRunningApp();

            UUID currentApp = systemModule.getCurrentRunningApp();
            Timber.d("Current app: " + currentApp);
            if (currentApp == null)
                currentApp = SystemModule.UNKNOWN_UUID;
            pebbleAppMode = PreferencesUtil.getPebbleAppNotificationMode(getService().getGlobalSettings(), currentApp);
        }

        if (pebbleAppMode == 0) //NC Notification
        {
            sendNCNotification(notification);
        }
        else if (pebbleAppMode == 1) //Pebble native notification
        {
            sendNativeNotification(notification);
        }
        else if (pebbleAppMode == 2) //No notification
        {
            Timber.d("notify failed - pebble app");
        }
    }

    private void sendNativeNotification(ProcessedNotification notification)
    {
        Timber.d("Sending native notification...");

        notification.nativeNotification = true;

        PebbleKit.FirmwareVersionInfo watchfirmware = PebbleKit.getWatchFWVersion(getService());
        if (watchfirmware.getMajor() > 2 || (watchfirmware.getMajor() == 2 && watchfirmware.getMinor() > 8))
        {
            NotificationCenterDeveloperConnection.fromDevConn(getService().getDeveloperConnection()).sendNotification(notification);
        }
        else
        {
            getService().getDeveloperConnection().sendBasicNotification(notification.source.getText(), notification.source.getSubtitle() + "\n" + notification.source.getText());
        }

    }

    private void sendNCNotification(ProcessedNotification notification)
    {
        Timber.d("SendNC");

        notification.nativeNotification = false;

        //Split text into chunks
        String text = notification.source.getText();
        while (text.length() > 0)
        {
            String chunk = TextUtil.trimString(text, 100, false);
            notification.textChunks.add(chunk);
            text = text.substring(chunk.length());
        }

        Timber.d("BeginSend " + notification.id + " " + notification.source.getTitle() + " " + notification.source.getSubtitle() + " " + notification.textChunks.size());

        SystemModule.get(getService()).openApp();

        if (curSendingNotification != null && !curSendingNotification.source.isListNotification())
        {
            sendingQueue.add(notification);
            return;
        }

        curSendingNotification = notification;

        PebbleCommunication communication = getService().getPebbleCommunication();
        communication.queueModulePriority(this);
        communication.sendNext();
    }

    private void sendInitialNotificationPacket()
    {
        Timber.d("Initial notify packet " + curSendingNotification.id);

        curSendingNotification.nextChunkToSend = 0;

        AppSettingStorage settingStorage = curSendingNotification.source.getSettingStorage(getService());

        int periodicVibrationInterval = 0;
        try
        {
            periodicVibrationInterval = Math.min(Integer.parseInt(settingStorage.getString(AppSetting.PERIODIC_VIBRATION)), 30000);
        } catch (NumberFormatException e)
        {
        }

        PebbleDictionary data = new PebbleDictionary();
        List<Byte> vibrationPattern = getVibrationPattern(curSendingNotification, settingStorage);

        int amountOfActions = 0;
        if (curSendingNotification.source.getActions() != null)
            amountOfActions = curSendingNotification.source.getActions().size();

        boolean showMenuInstantly = getService().getGlobalSettings().getBoolean("showMenuInstantly", true);

        byte flags = 0;
        flags |= (byte) (curSendingNotification.source.isListNotification() ? 0x2 : 0);
        flags |= (byte) ((settingStorage.getBoolean(AppSetting.SWITCH_TO_MOST_RECENT_NOTIFICATION) || curSendingNotification.source.shouldNCForceSwitchToThisNotification()) ? 0x4 : 0);
        flags |= (byte) (curSendingNotification.source.shouldScrollToEnd() ? 0x8 : 0);

        if (amountOfActions > 0 && showMenuInstantly)
        {
            flags |= (byte) ((curSendingNotification.source.shouldForceActionMenu() || settingStorage.getInt(AppSetting.SELECT_PRESS_ACTION) == 2) ? 0x10 : 0);
            flags |= (byte) (settingStorage.getInt(AppSetting.SELECT_HOLD_ACTION) == 2 ? 0x20 : 0);
        }

        int textLength = curSendingNotification.source.getText().getBytes().length;

        byte[] configBytes = new byte[12 + vibrationPattern.size()];
        configBytes[0] = flags;
        configBytes[1] = (byte) (periodicVibrationInterval >>> 0x08);
        configBytes[2] = (byte) periodicVibrationInterval;
        configBytes[3] = (byte) amountOfActions;
        configBytes[4] = (byte) (textLength >>> 0x08);
        configBytes[5] = (byte) textLength;

        int shakeAction = settingStorage.getInt(AppSetting.SHAKE_ACTION);
        if (shakeAction == 2 && !showMenuInstantly )
            configBytes[6] = 1;
        else
            configBytes[6] = (byte) shakeAction;

        configBytes[7] = (byte) settingStorage.getInt(AppSetting.TITLE_FONT);
        configBytes[8] = (byte) settingStorage.getInt(AppSetting.SUBTITLE_FONT);
        configBytes[9] = (byte) settingStorage.getInt(AppSetting.BOCY_FONT);

        if (getService().getPebbleCommunication().getConnectedPebblePlatform() == PebbleCommunication.PEBBLE_PLATFORM_BASSALT)
            configBytes[10] = PebbleImageToolkit.getGColor8FromRGBColor(curSendingNotification.source.getColor());

        configBytes[11] = (byte) vibrationPattern.size();

        for (int i = 0; i < vibrationPattern.size(); i++)
            configBytes[12 + i] = vibrationPattern.get(i);

        data.addUint8(0, (byte) 1);
        data.addUint8(1, (byte) 0);
        data.addInt32(2, curSendingNotification.id);
        data.addBytes(3, configBytes);
        data.addString(4, curSendingNotification.source.getTitle());
        data.addString(5, curSendingNotification.source.getSubtitle());
        data.addUint8(999, (byte) 1);

        getService().getPebbleCommunication().sendToPebble(data);
    }

    private void sendMoreText()
    {
        Timber.d("Sending more text... " + curSendingNotification.id + " " + curSendingNotification.nextChunkToSend);

        PebbleDictionary data = new PebbleDictionary();
        data.addUint8(0, (byte) 1);
        data.addUint8(1, (byte) 1);
        data.addInt32(2, curSendingNotification.id);
        data.addString(3, curSendingNotification.textChunks.get(curSendingNotification.nextChunkToSend));

        getService().getPebbleCommunication().sendToPebble(data);
        curSendingNotification.nextChunkToSend++;
    }

    @Override
    public boolean sendNextMessage()
    {
        if (curSendingNotification == null && !sendingQueue.isEmpty())
            curSendingNotification = sendingQueue.poll();

        if (curSendingNotification == null)
            return false;

        if (curSendingNotification.nextChunkToSend == -1)
        {
            sendInitialNotificationPacket();
        }
        else if (curSendingNotification.nextChunkToSend < curSendingNotification.textChunks.size())
        {
            sendMoreText();
        }
        else
        {
            notificationTransferCompleted();
            return sendNextMessage();
        }


        return true;
    }

    @Override
    public void gotMessageFromPebble(PebbleDictionary message)
    {
    }

    @Override
    public void gotIntent(Intent intent)
    {
        PebbleNotification notification = intent.getParcelableExtra("notification");
        if (notification == null)
            return;

        processNotification(notification);
    }

    @Override
    public void pebbleAppOpened()
    {
        if (curSendingNotification != null)
        {
            curSendingNotification.nextChunkToSend = -1;

            getService().getPebbleCommunication().queueModulePriority(this);
        }
    }

    private List<Byte> getVibrationPattern(ProcessedNotification notification, AppSettingStorage settingStorage)
    {
        Long lastVibration = lastAppVibration.get(notification.source.getKey().getPackage());
        int minInterval = 0;

        try
        {
            minInterval = Integer.parseInt(settingStorage.getString(AppSetting.MINIMUM_VIBRATION_INTERVAL));
        }
        catch (NumberFormatException e)
        {
        }

        Timber.d("MinInterval: " + minInterval);
        Timber.d("LastVib: " + lastVibration);

        if (minInterval == 0 || lastVibration == null ||
                (System.currentTimeMillis() - lastVibration) > minInterval * 1000)
        {
            notification.vibrated = true;
            return AppSetting.parseVibrationPattern(settingStorage);
        }
        else
        {
            ArrayList<Byte> list = new ArrayList<Byte>(2);
            list.add((byte) 0);
            list.add((byte) 0);
            return list;
        }
    }

    private boolean canDisplayWearGroupNotification(PebbleNotification notification, AppSettingStorage settingStorage)
    {
        boolean groupNotificationEnabled = settingStorage.getBoolean(AppSetting.USE_WEAR_GROUP_NOTIFICATIONS);
        if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY && groupNotificationEnabled)
        {
            return false; //Don't send summary notifications, we will send group ones instead.
        }
        else if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE && !groupNotificationEnabled)
        {
            return false; //Don't send group notifications, they are not enabled.
        }

        boolean sendIdentical = settingStorage.getBoolean(AppSetting.SEND_IDENTICAL_NOTIFICATIONS);
        if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE || !sendIdentical)
        {
            SparseArray<ProcessedNotification> sentNotifications = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications;

            //Prevent re-sending of the first message.
            for (int i = 0; i < sentNotifications.size(); i++)
            {
                ProcessedNotification comparing = sentNotifications.valueAt(i);
                if ((comparing.source.getWearGroupType() != PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY || !sendIdentical) && notification.hasIdenticalContent(comparing.source))
                {
                    Timber.d("group notify failed - same notification exists");
                    return false;
                }
            }
        }

        return true;
    }

    public boolean isAnyNotificationWaiting()
    {
        return curSendingNotification != null || !sendingQueue.isEmpty();
    }

    public void removeNotificationFromSendingQueue(int id)
    {
        Iterator<ProcessedNotification> iterator = sendingQueue.iterator();
        while (iterator.hasNext())
        {
            ProcessedNotification notification = iterator.next();

            if (notification.id == id)
            {
                iterator.remove();
            }
        }
    }

    public ProcessedNotification getCurrrentSendingNotification()
    {
        return curSendingNotification;
    }

    public static void notify(PebbleNotification notification, Context context)
    {
        Intent intent = new Intent(context, NCTalkerService.class);
        intent.setAction(INTENT_NOTIFICATION);
        intent.putExtra("notification", notification);

        context.startService(intent);
    }

    public static NotificationSendingModule get(PebbleTalkerService service)
    {
        return (NotificationSendingModule) service.getModule(MODULE_NOTIFICATION_SENDING);
    }

    public static int getMaximumTextLength(AppSettingStorage storage)
    {
        int limit = TEXT_LIMIT;

        try
        {
            limit = Math.min(Integer.parseInt(storage.getString(AppSetting.MAXIMUM_TEXT_LENGTH)), TEXT_LIMIT);
            if (limit < 4) //Minimum limit is 4 to allow ...
                limit = 4;
        }
        catch (NumberFormatException e)
        {

        }

        return limit;
    }

    /**
     * Wrapper for PebbleKit.isWatchConnected
     */
    public static boolean isWatchConnected(Context context)
    {
        try {
            return PebbleKit.isWatchConnected(context);
        } catch (Exception e) {
            return false;
        }
    }
}
