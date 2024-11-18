package com.eveningoutpost.dexdrip.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;

import com.eveningoutpost.dexdrip.g5model.BluetoothServices;
import com.eveningoutpost.dexdrip.g5model.Extensions;
import com.eveningoutpost.dexdrip.g5model.Transmitter;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.StatusItem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.val;

/**
 * POCTech collector
 * Created by Evgeniy on 10/11/2024.
 */
public class PoctechCollectionService extends G5BaseService {

    public final static String TAG = PoctechCollectionService.class.getSimpleName();

    @Setter
    @Getter
    private static final Object short_lock = new Object();
    private final Object mLock = new Object();
    private static boolean cycling_bt = false;
    private static volatile String transmitterID;
    public boolean isIntialScan = true;
    private boolean encountered133 = false;
    private BluetoothDevice device;
    private BluetoothAdapter mBluetoothAdapter;
    private static String static_device_address;
    private static boolean isScanning = false;
    private BluetoothGatt mGatt;
    public static boolean keep_running = true;
    private int currentBondState = 0;
    private int waitingBondConfirmation = 0; // 0 = not waiting, 1 = waiting, 2 = received
    private SharedPreferences prefs;
    private static boolean scan_scheduled = false;
    private boolean isConnected = false;
    private List<ScanFilter> filters;
    private ScanSettings settings;
    private int scanCycleCount = 0;
    private int maxScanCycles = 24;
    public static Timer scan_interval_timer = new Timer();
    private int maxScanIntervalInMilliseconds = 5 * 1000;
    private android.bluetooth.BluetoothManager mBluetoothManager;

    private BluetoothLeScanner mLEScanner;

    public long timeInMillisecondsOfLastSuccessfulSensorRead = new Date().getTime();

    private static PendingIntent pendingIntent;
    public ArrayList<Long> advertiseTimeMS = new ArrayList<Long>();


    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            initScanCallback();
        }
        advertiseTimeMS.add((long)0);

        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        listenForChangeInSettings(true);

        // TODO check this
        //bgToSpeech = BgToSpeech.setupTTS(getApplicationContext()); //keep reference to not being garbage collected
        // handler = new Handler(getApplicationContext().getMainLooper());

        final IntentFilter bondintent = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);//KS turn on
        bondintent.addAction(BluetoothDevice.ACTION_FOUND);//KS add
        registerReceiver(mPairReceiver, bondintent);//KS turn on

        final IntentFilter pairingRequestFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingRequestFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        if (Build.VERSION.SDK_INT < 26) {
            registerReceiver(mPairingRequestRecevier, pairingRequestFilter);
        } else {
            UserError.Log.d(TAG, "Not registering pairing receiver on Android 8+");
        }
    }

    public final SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            checkPreferenceKey(key, prefs);

            if (key.compareTo("run_ble_scan_constantly") == 0 || key.compareTo("always_unbond_G5") == 0
                    || key.compareTo("always_get_new_keys") == 0 || key.compareTo("run_G5_ble_tasks_on_uithread") == 0) {
                UserError.Log.i(TAG, "G5 Setting Change");
                cycleScan(0);
            }
        }
    };

    private final BroadcastReceiver mPairingRequestRecevier = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ((device != null) && (device.getAddress() != null)) {
                UserError.Log.e(TAG,"Processing mPairingRequestReceiver");
                JoH.doPairingRequest(context, this, intent, device.getAddress());
            } else {
                UserError.Log.e(TAG,"Received pairing request but device was null");
            }
        }
    };

    public synchronized void cycleScan(int delay) {

        UserError.Log.d(TAG,"cycleScan keep_running=" + keep_running);
        if (!keep_running) {
            UserError.Log.e(TAG," OnDestroy failed to stop service. Shutting down now to prevent service from being initiated onScanResult().");
            stopSelf();
            return;
        }
        if (JoH.ratelimit("G5-timeout",60) || !scan_scheduled) {
            if (JoH.ratelimit("g5-scan-log",60)) {
                UserError.Log.d(TAG, "cycleScan running");
            }
            scan_scheduled=true;
            //Log.e(TAG, "Scheduling cycle scan, delay: " + delay);
            final Timer single_timer = new Timer();
            single_timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (scanConstantly()) {
                        startScan();
                    } else {
                        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                            } else {

                                try {
                                    if (enforceMainThread()) {
                                        Handler iHandler = new Handler(Looper.getMainLooper());
                                        iHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                scanLogic();
                                            }
                                        });

                                    } else {
                                        scanLogic();
                                    }
                                } catch
                                (NullPointerException e) {
                                    //Known bug in Samsung API 21 stack
                                    UserError.Log.e(TAG,"Caught the NullPointerException in cyclescan");
                                } finally {
                                    scan_scheduled=false;
                                }
                            }
                        }
                    }
                    scan_scheduled=false;
                }
            }, delay);
        } else {
            UserError.Log.e(TAG,"jamorham blocked excessive scan schedule");
        }
    }

    private synchronized void scanLogic() {
        UserError.Log.d(TAG,"scanLogic keep_running=" + keep_running);
        if (!keep_running) return;
        if (JoH.ratelimit("G5-scanlogic", 1)) {//KS test change 2 -> 1 to support restart collector after n min missed readings
            try {
                mLEScanner.stopScan(mScanCallback);
                isScanning = false;
                if (!isConnected) {
                    mLEScanner.startScan(filters, settings, mScanCallback);
                    lastState="Scanning";
                    if (JoH.ratelimit("g5-scan-log",60)) {
                        UserError.Log.w(TAG, "scan cycle start");
                    }
                }
                isScanning = true;
            } catch (IllegalStateException | NullPointerException is) {
                setupBluetooth();
            }
            scanCycleCount++;
            if (!isIntialScan && scanCycleCount > maxScanCycles) {
                scan_interval_timer.cancel();
                scan_interval_timer = new Timer();
                scan_interval_timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        //Log.e(TAG, "cycling scan to stop until expected advertisement");
                        if (isScanning) {
                            keepAlive();
                        }
                        stopScan();
                    }
                }, maxScanIntervalInMilliseconds);
            }
            //last ditch
            else if (!isIntialScan && getMillisecondsSinceLastSuccesfulSensorRead() > 11 * 60 * 1000) {
                UserError.Log.e(TAG, "MSSinceSensorRx: " + getMillisecondsSinceLastSuccesfulSensorRead());
                isIntialScan = true;
                cycleBT();
            }
            //startup or re-auth, sit around and wait for tx to advertise
            else {
                scan_interval_timer.cancel();
                scan_interval_timer = new Timer();
                scan_interval_timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        //Log.e(TAG, "cycling scan");
                        cycleScan(0);
                    }
                }, maxScanIntervalInMilliseconds);
            }
        }
    }

    private long getMillisecondsSinceLastSuccesfulSensorRead() {
        return new Date().getTime() - timeInMillisecondsOfLastSuccessfulSensorRead;
    }

    public void setupBluetooth() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //First time using the app or bluetooth was turned off?
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            Timer single_timer = new Timer();
            single_timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (mBluetoothAdapter != null) mBluetoothAdapter.enable();
                    } catch (SecurityException e) {
                        JoH.static_toast_short("Please enable Bluetooth!");
                    }
                }
            }, 1000);
            single_timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    setupBluetooth();
                }
            }, 10000);
        } else {
            if (Build.VERSION.SDK_INT >= 21) {
                mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
                settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                filters = new ArrayList<>();
                filters.add(new ScanFilter.Builder().setDeviceName(getTransmitterID()).build());
            }

            // unbond here to avoid clashes when we are mid-connection
            if (alwaysUnbond()) {
                forgetDevice();
            }
            JoH.ratelimit("G5-timeout",0);//re-init to ensure onStartCommand always executes cycleScan
            cycleScan(0);
        }
    }

    private synchronized void forgetDevice() {
        UserError.Log.d(TAG,"forgetDevice() start");
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        final Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName() != null) {

                    if (device.getName().equals(getTransmitterID())) {
                        try {
                            UserError.Log.e(TAG, "removingBond: "+getTransmitterID());
                            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
                            m.invoke(device, (Object[]) null);
                        } catch (Exception e) { UserError.Log.e(TAG, e.getMessage(), e); }
                    }

                }
            }
        }
        UserError.Log.d(TAG,"forgetDevice() finished");
    }

    private boolean alwaysUnbond() {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return sharedPreferences.getBoolean("always_unbond_G5", false);
    }

    private boolean scanConstantly() {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return sharedPreferences.getBoolean("run_ble_scan_constantly", false);
    }

    public synchronized void startScan() {
        UserError.Log.e(TAG, "Initial scan?" + isIntialScan);
        if (isScanning) {
            UserError.Log.d(TAG, "alreadyScanning");
            scan_interval_timer.cancel();
            UserError.Log.d(TAG,"startScan keep_running=" + keep_running);
            if (!keep_running) return;
            return;
        }

        UserError.Log.d(TAG,"startScan keep_running=" + keep_running);
        if (!keep_running) return;

        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            setupBluetooth();
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                setupLeScanCallback();
                mBluetoothAdapter.startLeScan(new UUID[]{BluetoothServices.Advertisement}, mLeScanCallback);
            } else {
                if (enforceMainThread()){
                    Handler iHandler = new Handler(Looper.getMainLooper());
                    iHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            startLogic();
                        }
                    });
                } else {
                    startLogic();
                }
                UserError.Log.e(TAG, "startScan normal");
            }
        }
    }

    private synchronized void startLogic() {
        try {
            isScanning = true;
            mLEScanner.startScan(filters, settings, mScanCallback);
        } catch (Exception e) {
            isScanning = false;
            setupBluetooth();
        }
    }

    private void setupLeScanCallback() {
        if (mLeScanCallback == null) {
            mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                    // Check if the device has a name, the Dexcom transmitter always should. Match it with the transmitter id that was entered.
                    // We get the last 2 characters to connect to the correct transmitter if there is more than 1 active or in the room.
                    // If they match, connect to the device.
                    if (device.getName() != null) {

                        if (device.getName().toUpperCase().equals(getTransmitterID().toUpperCase())) {
                            connectToDevice(device);
                        }
                    }
                }
            };
        }
    }

    public void listenForChangeInSettings(boolean listen) {
        try {
            if (listen) {
                prefs.registerOnSharedPreferenceChangeListener(prefListener);
            } else {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error with preference listener: " + e + " " + listen);
        }
    }

    final BroadcastReceiver mPairReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            UserError.Log.d(TAG, "onReceive ACTION: " + action);
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                final BluetoothDevice parcel_device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // TODO do we need to filter on the last 2 characters of the device name here?
                currentBondState = parcel_device.getBondState();
                UserError.Log.d(TAG, "onReceive FOUND: " + parcel_device.getName() + " STATE: " + parcel_device.getBondState());
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                final BluetoothDevice parcel_device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // TODO do we need to filter on the last 2 characters of the device name here?
                currentBondState = parcel_device.getBondState();
                final int bond_state_extra = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                final int previous_bond_state_extra = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

                UserError.Log.e(TAG, "onReceive UPDATE Name " + parcel_device.getName() + " Value " + parcel_device.getAddress()
                        + " Bond state " + parcel_device.getBondState() + bondState(parcel_device.getBondState()) + " "
                        + "bs: " + bondState(bond_state_extra) + " was " + bondState(previous_bond_state_extra));

                try {
                    // TODO check getBondState() or bond_state_extra ?
                    if (parcel_device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        if (parcel_device.getAddress().equals(device.getAddress())) {
                            if (waitingBondConfirmation == 1) {
                                waitingBondConfirmation = 2; // received
                                UserError.Log.e(TAG, "Bond confirmation received!");
                            }
                        }
                    }
                } catch (Exception e) {
                    UserError.Log.wtf(TAG, "Got exception trying to process bonded confirmation: ", e);
                }

            }
        }
    };


    private ScanCallback mScanCallback;

    private void initScanCallback(){
        mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                UserError.Log.d(TAG, "result: " + result.toString());
                BluetoothDevice btDevice = result.getDevice();
                UserError.Log.d(TAG, "Имена BLE = " + btDevice.getName() + " и адрес = " +btDevice.getAddress());
                if (btDevice.getName() != null) {

                    if (btDevice.getName().equals(getTransmitterID())) {
                        if (advertiseTimeMS.size() > 0)
                            if ((new Date().getTime() - advertiseTimeMS.get(advertiseTimeMS.size()-1)) > 2.5*60*1000)
                                advertiseTimeMS.clear();
                        advertiseTimeMS.add(new Date().getTime());
                        isIntialScan = false;
                        //device = btDevice;
                        device = mBluetoothAdapter.getRemoteDevice(btDevice.getAddress());
                        static_device_address = btDevice.getAddress();
                        stopScan();
                        connectToDevice(btDevice);
                    } else {
                        //stopScan(10000);
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                UserError.Log.e(TAG, "Scan Failed Error Code: " + errorCode);
                if (errorCode == 1) {
                    UserError.Log.e(TAG, "Already Scanning: " + isScanning);
                    //isScanning = true;
                } else if (errorCode == 2){
                    cycleBT();
                }
            }
        };
    }

    private synchronized void cycleBT() {
        synchronized (short_lock) {
            if (JoH.ratelimit("cyclebt", 20)) {

                // TODO cycling_bt not used as never set to true - rate limit any sync used instead
                if (cycling_bt) {
                    UserError.Log.e(TAG, "jamorham Already concurrent BT cycle in progress!");
                    return;
                }
                encountered133 = false;
                stopScan();
                if (g5BluetoothWatchdog()) {
                    UserError.Log.e(TAG, "Cycling BT-gatt - disabling BT");
                    mBluetoothAdapter.disable();
                    Timer single_timer = new Timer();
                    single_timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            mBluetoothAdapter.enable();
                            UserError.Log.e(TAG, "Cycling BT-gatt - enableing BT");
                            cycling_bt = false;
                        }
                    }, 3000);
                } else {
                    UserError.Log.e(TAG, "Wanted to cycle g5 bluetooth but is disabled in advanced bluetooth preferences!");
                    waitFor(3000);
                }
            }
            keepAlive();
        }
    }

    public synchronized void keepAlive(int wake_in_ms) {
        UserError.Log.d(TAG,"keepAlive keep_running=" + keep_running);
        if (!keep_running) return;
        if (JoH.ratelimit("G5-keepalive", 5)) {
            long wakeTime;
            if (wake_in_ms==0) {
                wakeTime = getNextAdvertiseTime() - 60 * 1000;
            } else {
                wakeTime = Calendar.getInstance().getTimeInMillis() + wake_in_ms;
            }
            //Log.e(TAG, "Delay Time: " + minuteDelay);
            UserError.Log.e(TAG, "Scheduling Wake Time: in " +  JoH.qs((wakeTime-JoH.tsl())/1000,0)+ " secs "+ JoH.dateTimeText(wakeTime));
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (pendingIntent != null)
                alarm.cancel(pendingIntent);
            pendingIntent = PendingIntent.getService(this, 0, new Intent(this, this.getClass()), 0);
            // TODO use wakeIntent feature
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
            } else
                alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else {
            UserError.Log.e(TAG, "Ignoring keepalive call due to ratelimit");
        }
    }

    private long getNextAdvertiseTime() {
        long millisecondsSinceTx = getMillisecondsSinceTxLastSeen();
        long timeToExpected  = (300*1000 - (millisecondsSinceTx%(300*1000)));
        long expectedTxTime = new Date().getTime() + timeToExpected - 3*1000;
        UserError.Log.e(TAG, "millisecondsSinceTxAd: " + millisecondsSinceTx );
        UserError.Log.e(TAG, "advertiseTimeMS.get(0): " + advertiseTimeMS.get(0) + " " + JoH.dateTimeText(advertiseTimeMS.get(0)));
        UserError.Log.e(TAG, "timeInMillisecondsOfLastSuccessfulSensorRead: " + " " + timeInMillisecondsOfLastSuccessfulSensorRead + JoH.dateTimeText(timeInMillisecondsOfLastSuccessfulSensorRead) );
        //Log.e(TAG, "timeToExpected: " + timeToExpected );
        //Log.e(TAG, "expectedTxTime: " + expectedTxTime );

        return expectedTxTime;
    }

    private long getMillisecondsSinceTxLastSeen() {
        return new Date().getTime() - advertiseTimeMS.get(0);
    }

    public synchronized void keepAlive() {
        keepAlive(0);
    }

    protected void waitFor(final int millis) {
        synchronized (mLock) {
            try {
                UserError.Log.e(TAG, "waiting " + millis + "ms");
                mLock.wait(millis);
            } catch (final InterruptedException e) {
                UserError.Log.e(TAG, "Sleeping interrupted", e);
            }
        }
    }

    private boolean g5BluetoothWatchdog() {
        return Pref.getBoolean("g5_bluetooth_watchdog", true);
    }

    private synchronized void connectToDevice(BluetoothDevice device) {
        if (JoH.ratelimit("G5connect-rate", 2)) {

            UserError.Log.d(TAG, "connectToDevice() start");
            if (mGatt != null) {
                UserError.Log.i(TAG, "BGatt isnt null, Closing.");
                try {
                    mGatt.close();
                } catch (NullPointerException e) {
                    // concurrency related null pointer
                }
                mGatt = null;
            }
            UserError.Log.i(TAG, "Request Connect");
            final BluetoothDevice mDevice = device;
            if (enforceMainThread()) {
                Handler iHandler = new Handler(Looper.getMainLooper());
                iHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        connectGatt(mDevice);
                    }
                });
            } else {
                connectGatt(mDevice);
            }

        } else {
            UserError.Log.e(TAG, "connectToDevice baulking due to rate-limit");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {};
    private synchronized void connectGatt(BluetoothDevice mDevice) {
        UserError.Log.i(TAG, "mGatt Null, connecting...");
        lastState="Found, Connecting";

        mGatt = mDevice.connectGatt(getApplicationContext(), false, gattCallback);
    }

    private boolean enforceMainThread() {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return sharedPreferences.getBoolean("run_G5_ble_tasks_on_uithread", false);
    }

    private BluetoothAdapter.LeScanCallback mLeScanCallback = null;

    public synchronized void stopScan(){
        if (!isScanning) {
            UserError.Log.d(TAG, "alreadyStoppedScanning");
            return;
        }
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            } else {

                try {

                    if (enforceMainThread()){
                        Handler iHandler = new Handler(Looper.getMainLooper());
                        iHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                stopLogic();
                            }
                        });
                    } else {
                        stopLogic();
                    }


                } catch (NullPointerException e) {
                    //Known bug in Samsung API 21 stack
                    UserError.Log.e(TAG,"stopscan() Caught the NullPointerException");
                }
            }
        }
    }

    private synchronized void stopLogic() {
        try {
            UserError.Log.e(TAG, "stopScan");
            try {
                mLEScanner.stopScan(mScanCallback);
            } catch (NullPointerException | IllegalStateException e) {
                UserError.Log.e(TAG, "Exception in stopLogic: " + e);
            }
            isScanning = false;
        } catch (IllegalStateException is) {

        }
    }

    private static void init_tx_id() {
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
        if (transmitterID == null) {
            init_tx_id();
        }
        return transmitterID;
    }

    public static List<StatusItem> megaStatus() {
        UserError.Log.d(TAG, "Метод 11");
        final List<StatusItem> l = new ArrayList<>();
        final String tx_id = Pref.getStringDefaultBlank("poct_txid");
        l.add(new StatusItem("Transmitter ID", tx_id));
        return  l;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}