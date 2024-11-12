package com.eveningoutpost.dexdrip.services;

import static com.eveningoutpost.dexdrip.models.JoH.tsl;

import static com.eveningoutpost.dexdrip.utils.DexCollectionType.PoctechCT14;

import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.BOND;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.CLOSE;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.CLOSED;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.CONNECT;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.CONNECT_NOW;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.DISCOVER;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.GET_DATA;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.INIT;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.PREBOND;
import static com.eveningoutpost.dexdrip.services.PoctechCollectionService.STATE.SCAN;

import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.g5model.CalibrationState;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.BroadcastGlucose;
import com.eveningoutpost.dexdrip.utilitymodels.CollectionServiceStarter;
import com.eveningoutpost.dexdrip.utilitymodels.PersistentStore;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.RxBleProvider;
import com.eveningoutpost.dexdrip.utilitymodels.StatusItem;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.watch.thinjam.BlueJayEntry;
import com.polidea.rxandroidble2.RxBleClient;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.val;

/**
 * POCTech collector
 * Created by Evgeniy on 10/11/2024.
 */
public class PoctechCollectionService extends G5BaseService {

    public final static String TAG = PoctechCollectionService.class.getSimpleName();
    private static volatile STATE state = INIT;
    private static final int DEFAULT_AUTOMATA_DELAY = 100;

    private static volatile String transmitterID;
    private static volatile String transmitterMAC;

    private static volatile String lastScanError = null;
    private static volatile int lastScanException = -1;
    public static volatile String lastSensorStatus = null;
    public static volatile CalibrationState lastSensorState = null;
    public static volatile long lastUsableGlucosePacketTime = 0;
    private static volatile String static_connection_state = null;
    public static volatile long static_last_connected = 0;
    @Setter
    @Getter
    private static long last_transmitter_timestamp = 0;
    private static long lastStateUpdated = 0;
    private static long wakeup_time = 0;
    private static long wakeup_jitter = 0;
    private static long max_wakeup_jitter = 0;
    private static volatile long connecting_time = 0;

    public static boolean android_wear = false;
    public static boolean wear_broadcast = false;
    public static boolean keep_running = true;

    private static RxBleClient rxBleClient;

//    private static volatile String static_connection_state = null;

    public enum STATE {
        INIT("Initializing"),
        SCAN("Scanning"),
        CONNECT("Waiting connect"),
        CONNECT_NOW("Power connect"),
        DISCOVER("Examining"),
        CHECK_AUTH("Checking Auth"),
        PREBOND("Bond Prepare"),
        BOND("Bonding"),
        UNBOND("UnBonding"),
        RESET("Reseting"),
        GET_DATA("Getting Data"),
        CLOSE("Sleeping"),
        CLOSED("Deep Sleeping");


        private String str;

        STATE(String custom) {
            this.str = custom;
        }

        STATE() {
            this.str = toString();
        }

        public String getString() {
            return str;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void init_tx_id() {
        UserError.Log.d(TAG, "Метод 1");
        val TXID_PREF = "poct_txid";
        val txid = Pref.getString(TXID_PREF, "NULL");
        val txid_filtered = txid.trim();
        transmitterID = txid_filtered;
        UserError.Log.d(TAG, "Метод 1" + transmitterID);
        if (!txid.equals(txid_filtered)) {
            Pref.setString(TXID_PREF, txid_filtered);
            UserError.Log.wtf(TAG, "Had to fix invalid txid: :" + txid + ": -> :" + txid_filtered + ":");
        }
    }

    public static String getTransmitterID() {
        UserError.Log.d(TAG, "Метод 2");
        if (transmitterID == null) {
            init_tx_id();
        }
        return transmitterID;
    }

    private synchronized void initialize() {
        UserError.Log.d(TAG, "Метод 3");
        if (state == INIT) {
            msg("Initializing");
            static_connection_state = null;
            if (rxBleClient == null) {
                //rxBleClient = RxBleClient.create(xdrip.getAppContext());
                rxBleClient = RxBleProvider.getSingleton();
            }
            init_tx_id();
            // load prefs etc
            changeState(SCAN);
        } else {
            UserError.Log.wtf(TAG, "Attempt to initialize when not in INIT state");
        }
    }

    public static void msg(String msg) {
        UserError.Log.d(TAG, "Метод 4");
        lastState = msg + " " + JoH.hourMinuteString();
        UserError.Log.d(TAG, "Status: " + lastState);
        lastStateUpdated = tsl();
        if (android_wear && wear_broadcast) {
            BroadcastGlucose.sendLocalBroadcast(null);
        }
    }

    public void changeState(STATE new_state) {
        UserError.Log.d(TAG, "Метод 5");
        if (shouldServiceRun()) {
            changeState(new_state, DEFAULT_AUTOMATA_DELAY);
        } else {
            UserError.Log.d(TAG, "Stopping service due to having being disabled in preferences");
            stopSelf();
        }
    }

    public void changeState(STATE new_state, int timeout) {
        UserError.Log.d(TAG, "Метод 6");
        if ((state == CLOSED || state == CLOSE) && new_state == CLOSE) {
            UserError.Log.d(TAG, "Not closing as already closed");
        } else {
            UserError.Log.d(TAG, "Changing state from: " + state + " to " + new_state);
            state = new_state;
            if (android_wear && wear_broadcast) {
                msg(new_state.toString());
            }
//            background_automata(timeout);
        }
    }

//    public synchronized void background_automata(final int timeout) {
//        UserError.Log.d(TAG, "Метод 3");
//        if (background_launch_waiting) {
//            UserError.Log.d(TAG, "Blocked by existing background automata pending");
//            return;
//        }
//        final PowerManager.WakeLock wl = JoH.getWakeLock("jam-g5-background", timeout + 5000);
//        background_launch_waiting = true;
//        new Thread(() -> {
//            JoH.threadSleep(timeout);
//            background_launch_waiting = false;
//            automata();
//            JoH.releaseWakeLock(wl);
//        }).start();
//    }

    private static boolean shouldServiceRun() {
        UserError.Log.d(TAG, "Метод 7");
//        if (!Pref.getBooleanDefaultFalse(OB1G5_PREFS)) return false; ??????????????????????????????
        if (!(DexCollectionType.getDexCollectionType() == PoctechCT14)) return false;

        if (!android_wear) {
            if (Home.get_forced_wear()) {
                if (JoH.quietratelimit("forced-wear-notice", 3))
                    UserError.Log.d(TAG, "Not running due to forced wear");
                return false;
            }

            if (BlueJayEntry.isPhoneCollectorDisabled()) {
                UserError.Log.d(TAG, "Not running as BlueJay is collector");
                return false;
            }

        } else {
            // android wear code
            if (!PersistentStore.getBoolean(CollectionServiceStarter.pref_run_wear_collector))
                return false;
        }
        return true;
    }

    private String getTransmitterBluetoothName() {
        UserError.Log.d(TAG, "Метод 7");
        if (transmitterID == null || transmitterID.length() <= 4) return null;
        final String transmitterIdLastTwo = getLastTwoCharacters(transmitterID);
        // todo check for bad config
        return "Dexcom" + transmitterIdLastTwo;
    }

    public static List<StatusItem> megaStatus() {
        UserError.Log.d(TAG, "Метод 11");
        final List<StatusItem> l = new ArrayList<>();
        final String tx_id = Pref.getStringDefaultBlank("poct_txid");
        l.add(new StatusItem("Transmitter ID", tx_id));
        return  l;
    }
}