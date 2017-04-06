/*

The MIT License (MIT)

Copyright (c) 2016 Esa Riihinen

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


*/

package com.geniem.rnble;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.BlockingQueue;  

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.geniem.rnble.ReadWriteOperation.Type;
import com.facebook.react.bridge.LifecycleEventListener;

import android.os.ParcelUuid;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import android.util.Base64;

class RNBLEModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

    private static final String TAG = "RNBLEModule";

    // used for serializing r/w characteristic operations, poll time for looking at r/w responses
    private static final int SLEEP_MS = 50;

    // timeout for receiving r/w operation callbacks
    private static final int TIMEOUT_MS = 500;

    // enable disconnect on pause
    private static final boolean DISCONNECT_ON_PAUSE = false;

    private Context context;

    private Thread readWriteThread;

    private BlockingQueue<ReadWriteOperation> readWriteOperationQueue;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback scanCallback;
    private final BluetoothGattCallback gattCallback = new RnbleGattCallback(this);
    private String serviceUuid;
    private String bluetoothDeviceAddress;
    private BluetoothGatt bluetoothGatt;
    private int connectionState = STATE_DISCONNECTED;
    private List<BluetoothGattService> discoveredServices;
    private List<String> scannedDeviceAddresses = new ArrayList<String>();
    private Boolean allowDuplicates = false;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public RNBLEModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.context = reactContext;
        reactContext.addLifecycleEventListener(this);
    } 

    private ReadWriteOperation currentOperation;

    /** 
     * Start read / write thread for sending characteristic operations
    */
    private void startReadWriteThread() {
        if (readWriteThread != null && readWriteThread.isAlive())
            return;
        readWriteOperationQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        readWriteThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        currentOperation = readWriteOperationQueue.take();
                        responseReceived = false;
                        currentOperation.handle(bluetoothGatt);
                        blockUntilResponseReceived();
                    } catch (InterruptedException e) {
                        // quit running thread on interrupt
                        Log.d(TAG, "rw-thread interrupted");
                        return;
                    } catch (NullPointerException e) {
                        // in case currentOperation is null for some reason
                        // this shouldnt happen
                        throw new RuntimeException("Nullpointer exception in startReadWriteThread, RNBLEModule");
                    }
                }
            }
        });
        readWriteThread.start();
    }

    /**
     * Block read/write thread until we receive go-ahead 
     * by receiving the callback from either onCharacteristicRead or onCharacteristicWrite.
     * 
     * This is a polling operation that happens every SLEEP_MS milliseconds on the r/w thread
     */
    private void blockUntilResponseReceived() throws InterruptedException, NullPointerException {
        while (!responseReceived) {
            int timeoutCounter = currentOperation.incrementTimeout();
            if (timeoutCounter * SLEEP_MS > TIMEOUT_MS) {
                return;
            }
            Thread.sleep(SLEEP_MS);
        }
        Log.d(TAG, "response received");
    }

    private boolean responseReceived = false;

    /**
     * Stop read/write thread 
     */
    private void stopReadWriteThread() {
        try {
            readWriteThread.interrupt();
            readWriteThread = null;
        } catch (NullPointerException e) {
            Log.d(TAG, "no rw-thread to stop");
        }
    }

    @Override
    public void initialize() {
        super.initialize();
        bluetoothManager = (BluetoothManager) this.context.getSystemService(ReactApplicationContext.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

    }

    /**
     * @return the name of this module. This will be the name used to {@code require()} this module
     * from javascript.
     */
    @Override
    public String getName() {
        return "RNBLE";
    }

    @ReactMethod
    public void getState() {
        Log.d(TAG, "getState");
        WritableMap params = Arguments.createMap();
        if (bluetoothAdapter == null) {
            params.putString("state", "unsupported");
        } else {
            params.putString("state", stateToString(bluetoothAdapter.getState()));
        }
        sendEvent("ble.stateChange", params);
    }

    @ReactMethod
    public void startScanning(String serviceUuid, Boolean allowDuplicates) {
        Log.d(TAG, "RNBLE startScanning - service uuid: " + serviceUuid);
        if (bluetoothLeScanner != null) {
            if (scanCallback == null) {
                this.allowDuplicates = allowDuplicates;
                scannedDeviceAddresses.clear();
                this.serviceUuid = serviceUuid;
                scanCallback = new RnbleScanCallback(this);
                try {
                    bluetoothLeScanner.startScan(null, buildScanSettings(), scanCallback);
                } catch (Exception ex) {
                    scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                    getState();
                    Log.d(TAG, ex.getMessage());
                }
            }
        }

        if (bluetoothLeScanner == null || scanCallback == null) {
            Log.d(TAG, "RNBLE startScanning - FAIlED to start scan");
        }
    }

    @ReactMethod
    public void stopScanning() {
        Log.d(TAG, "stopScanning");
        if (bluetoothLeScanner != null && scanCallback != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
            } catch (Exception ex) {
                Log.d(TAG, ex.getMessage());
            }
            scanCallback = null;
        }
    }

    @ReactMethod
    public void disconnect(final String peripheralUuid) {
        Log.d(TAG, "disconnect");
        WritableMap params = Arguments.createMap();
        params.putString("peripheralUuid", peripheralUuid);
        stopReadWriteThread();

        if (bluetoothGatt == null) {
            Log.w(TAG, "BluetoothGAtt not initialized");

            WritableMap error = Arguments.createMap();
            error.putInt("erroCode", -1);
            error.putString("errorMessage", "BluetoothGatt not initialized.");
            params.putMap("error", error);

        } else {
            Log.d(TAG, "BluetoothGatt disconnect");
            bluetoothGatt.disconnect();
        }
        connectionState = STATE_DISCONNECTED;
        this.sendEvent("ble.disconnect.", params);
    }

    @ReactMethod
    public void connect(final String peripheralUuid) { //in android peripheralUuid is the mac address of the BLE device
        Log.d(TAG, "RNBLE Connect called");
        if (bluetoothAdapter == null || peripheralUuid == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified peripheralUuid.");

            WritableMap error = Arguments.createMap();
            error.putInt("erroCode", -1);
            error.putString("errorMessage", "BluetoothAdapter not initialized or unspecified peripheralUuid.");

            WritableMap params = Arguments.createMap();
            params.putString("peripheralUuid", peripheralUuid);
            params.putMap("error", error);

            this.sendEvent("ble.connect", params);
            return;
        }

        // Previously connected device.  Try to reconnect.
        /*        if (bluetoothDeviceAddress != null && peripheralUuid.equalsIgnoreCase(bluetoothDeviceAddress)
                && bluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing bluetoothGatt for connection.");
            if (bluetoothGatt.connect()) {
                connectionState = STATE_CONNECTING;
                return;
            }
        }
        */
        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peripheralUuid);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            connectionState = STATE_DISCONNECTED;

            WritableMap error = Arguments.createMap();
            error.putInt("erroCode", -2);
            error.putString("errorMessage", "Device not found.  Unable to connect.");

            WritableMap params = Arguments.createMap();
            params.putString("peripheralUuid", peripheralUuid);
            params.putMap("error", error);

            this.sendEvent("ble.connect", params);
            return;
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        bluetoothDeviceAddress = peripheralUuid;
        connectionState = STATE_CONNECTING;
    }

    @ReactMethod
    public void discoverServices(final String peripheralUuid, ReadableArray uuids) {
        Log.d(TAG, "discoverServices");
        WritableArray filteredServiceUuids = Arguments.createArray();

        if (bluetoothGatt != null && this.discoveredServices != null && uuids != null && uuids.size() > 0) {
            //filter discovered services
            for (BluetoothGattService service : this.discoveredServices) {
                String uuid = service.getUuid().toString();
                for (int i = 0; i < uuids.size(); i++) {
                    if (uuid.equalsIgnoreCase(uuids.getString(i))) {
                        filteredServiceUuids.pushString(toNobleUuid(uuid));
                    }
                }
            }
        } else if (uuids == null || uuids.size() == 0) {
            //if no uuids are requested return all discovered service uuids
            for (BluetoothGattService service : this.discoveredServices) {
                String uuid = service.getUuid().toString();
                filteredServiceUuids.pushString(toNobleUuid(uuid));
            }
        }

        WritableMap params = Arguments.createMap();
        params.putString("peripheralUuid", peripheralUuid);
        params.putArray("serviceUuids", filteredServiceUuids);

        this.sendEvent("ble.servicesDiscover", params);
    }

    @ReactMethod
    public void discoverCharacteristics(final String peripheralUuid, final String serviceUuid,
            ReadableArray characteristicUuids) {
        Log.d(TAG, "discoverCharacteristics");
        WritableArray requestedCharacteristics = Arguments.createArray();
        List<BluetoothGattCharacteristic> filteredCharacteristics = new ArrayList<BluetoothGattCharacteristic>();

        for (BluetoothGattService service : this.discoveredServices) {
            String uuid = service.getUuid().toString();
            //filter requested service
            if (uuid != null && uuid.equalsIgnoreCase(serviceUuid)) {
                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();

                //remove characteristics from the characteristics list based on requested characteristicUuids
                if (characteristicUuids != null && characteristicUuids.size() > 0) {
                    for (int i = 0; i < characteristicUuids.size(); i++) {
                        Iterator<BluetoothGattCharacteristic> iterator = characteristics.iterator();
                        while (iterator.hasNext()) {
                            BluetoothGattCharacteristic characteristic = iterator.next();
                            if (characteristicUuids.getString(i)
                                    .equalsIgnoreCase(characteristic.getUuid().toString())) {
                                filteredCharacteristics.add(characteristic);
                                break;
                            }
                        }
                    }
                }
                // if no filter uuids given, just pass all
                else {
                    Iterator<BluetoothGattCharacteristic> iterator = characteristics.iterator();
                    while (iterator.hasNext()) {
                        BluetoothGattCharacteristic characteristic = iterator.next();
                        filteredCharacteristics.add(characteristic);
                    }
                }

                //process characteristics
                for (BluetoothGattCharacteristic c : filteredCharacteristics) {
                    WritableArray properties = Arguments.createArray();
                    int propertyBitmask = c.getProperties();

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) {
                        properties.pushString("boradcast");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        properties.pushString("read");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        properties.pushString("writeWithoutResponse");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        properties.pushString("write");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        properties.pushString("notify");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        properties.pushString("indicaste");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) {
                        properties.pushString("authenticatedSignedWrites");
                    }

                    if ((propertyBitmask & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) {
                        properties.pushString("extendedProperties");
                    }

                    WritableMap characteristicObject = Arguments.createMap();
                    characteristicObject.putArray("properties", properties);
                    characteristicObject.putString("uuid", toNobleUuid(c.getUuid().toString()));

                    requestedCharacteristics.pushMap(characteristicObject);
                }
                break;
            }
        }

        WritableMap params = Arguments.createMap();
        params.putString("peripheralUuid", peripheralUuid);
        params.putString("serviceUuid", toNobleUuid(serviceUuid));
        params.putArray("characteristics", requestedCharacteristics);
        this.sendEvent("ble.characteristicsDiscover", params);
    }

    @ReactMethod
    public void discoverDescriptors(final String peripheralUuid, final String serviceUuid,
            final String characteristicUuid) {
        Log.d(TAG, "discover descriptors");
        WritableArray descriptors = Arguments.createArray();

        for (BluetoothGattService service : this.discoveredServices) {
            String uuid = service.getUuid().toString();
            //filter requested service
            if (uuid != null && uuid.equalsIgnoreCase(serviceUuid)) {
                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                for (BluetoothGattCharacteristic characteristic : characteristics) {
                    String cUuid = characteristic.getUuid().toString();
                    if (cUuid != null && cUuid.equalsIgnoreCase(characteristicUuid)) {
                        List<BluetoothGattDescriptor> descriptorList = characteristic.getDescriptors();
                        for (BluetoothGattDescriptor descriptor : descriptorList) {
                            descriptors.pushString(descriptor.getUuid().toString());
                        }
                        break;
                    }
                }
                break;
            }
        }

        WritableMap params = Arguments.createMap();
        params.putString("peripheralUuid", peripheralUuid);
        params.putString("serviceUuid", toNobleUuid(serviceUuid));
        params.putString("characteristicUuid", toNobleUuid(characteristicUuid));
        params.putArray("descriptors", descriptors);
        this.sendEvent("ble.descriptorsDiscover", params);
    }

    @ReactMethod
    public void read(String peripheralUuid, String serviceUuid, String characteristicUuid) {
        try {
            Log.d(TAG, String.format("reading characteristic, p: %s s: %s c: %s", peripheralUuid, serviceUuid,
                    characteristicUuid));
        } catch (NullPointerException e) {
            Log.d(TAG, "reading characteristic, NullPointerException");
        }
        Boolean dataRead = false;
        for (BluetoothGattService service : this.discoveredServices) {
            String uuid = service.getUuid().toString();
            //find requested service
            if (uuid != null && uuid.equalsIgnoreCase(serviceUuid)) {
                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                //find requested characteristic
                for (BluetoothGattCharacteristic characteristic : characteristics) {
                    String cUuid = characteristic.getUuid().toString();
                    if (cUuid != null && cUuid.equalsIgnoreCase(characteristicUuid)) {
                        if (bluetoothGatt != null) {
                            ReadWriteOperation operation = new ReadWriteOperation(Type.READ, characteristic);
                            queueReadWriteOperation(operation);
                            // bluetoothGatt.readCharacteristic(characteristic);
                            dataRead = true;
                        }
                        break;
                    }
                }
                break;
            }
        }
        if (!dataRead)
            Log.e(TAG, "no characteristic found for read");
    }

    @ReactMethod
    public void write(String deviceUuid, String serviceUuid, String characteristicUuid, String data,
            Boolean withoutResponse) {
        Log.d(TAG, "writing to characteristic");
        for (BluetoothGattService service : this.discoveredServices) {
            String uuid = service.getUuid().toString();
            //find requested service
            if (uuid != null && uuid.equalsIgnoreCase(serviceUuid)) {
                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                //find requested characteristic
                for (BluetoothGattCharacteristic characteristic : characteristics) {
                    String cUuid = characteristic.getUuid().toString();
                    if (cUuid != null && cUuid.equalsIgnoreCase(characteristicUuid)) {
                        if (bluetoothGatt != null) {
                            //set new data to characteristic
                            if (withoutResponse) {
                                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                            } else {
                                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); // TODO: not tested
                            }
                            byte[] bArr = Base64.decode(data, Base64.DEFAULT);
                            //Log.d(TAG, "bArr: " + Arrays.toString(bArr) + "\n" + " bArr length: " + bArr.lengthig

                            //log out as binary
                            //String binaryString = "";
                            //for (byte b : bArr) {
                            //    binaryString += Integer.toBinaryString(b & 255 | 256).substring(1) + " ";
                            //}
                            //Log.d(TAG, binaryString);

                            characteristic.setValue(bArr);
                            //write the data to the characterustic
                            ReadWriteOperation operation = new ReadWriteOperation(Type.WRITE, characteristic);
                            queueReadWriteOperation(operation);

                            // if (!bluetoothGatt.writeCharacteristic(characteristic)) {
                            //     Log.d(TAG, "Error initating BLE write operation.");
                            // }
                        }
                        break;
                    }
                }
                break;
            }
        }
    }

    private void queueReadWriteOperation(ReadWriteOperation operation) {
        try {
            startReadWriteThread();
            readWriteOperationQueue.add(operation);
        } catch (NullPointerException e) {
            throw new RuntimeException(TAG + " rw - queue is null");
        }
    }

    @Override
    public void onHostResume() {
        startReadWriteThread();
        Log.d(TAG, "onHostResume");
    }

    @Override
    public void onHostPause() {
        Log.v(TAG, "onHostPause");
        // stopReadWriteThread();
        if (bluetoothLeScanner != null && scanCallback != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            scanCallback = null;
        }
        if (DISCONNECT_ON_PAUSE && bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
            connectionState = STATE_DISCONNECTED;
        }
    }

    @Override
    public void onHostDestroy() {
        Log.v(TAG, "onHostDestroy");
        if (bluetoothLeScanner != null && scanCallback != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            scanCallback = null;
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
            connectionState = STATE_DISCONNECTED;
        }
    }

    private void sendEvent(String eventName, WritableMap params) {
        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName,
                params);
    }

    // private List<ScanFilter> buildScanFilters() {
    //     List<ScanFilter> scanFilters = new ArrayList<>();

    //     ScanFilter.Builder builder = new ScanFilter.Builder();
    //     if(serviceUuid != null){
    //         //builder.setDeviceAddress(serviceUuid);
    //         builder.setServiceUuid(ParcelUuid.fromString(serviceUuid));
    //     }
    //     scanFilters.add(builder.build());

    //     return scanFilters;
    // }

    private ScanSettings buildScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        return builder.build();
    }

    private String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                return "poweredOff";
            case BluetoothAdapter.STATE_TURNING_OFF:
                return "turningOff";
            case BluetoothAdapter.STATE_ON:
                return "poweredOn";
            case BluetoothAdapter.STATE_TURNING_ON:
                return "turningOn";
            default:
                return "unknown";
            }
    }

    // GATT callback and methods
    private class RnbleGattCallback extends BluetoothGattCallback {
        private RNBLEModule rnbleModule;

        public RnbleGattCallback(RNBLEModule rnbleModule) {
            this.rnbleModule = rnbleModule;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice remoteDevice = gatt.getDevice();
            String remoteAddress = remoteDevice.getAddress();
            WritableMap params = Arguments.createMap();
            params.putString("peripheralUuid", remoteAddress); //remote address used here instead of uuid, not converted to noble format

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Discovering services.");
                connectionState = STATE_CONNECTED;
                // Attempts to discover services after successful connection.
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                Log.i(TAG, "Disconnected from GATT server.");
                rnbleModule.sendEvent("ble.disconnect", params);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "onServicesDiscovered");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rnbleModule.discoveredServices = bluetoothGatt.getServices();
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }

            connectionState = STATE_CONNECTED;

            BluetoothDevice remoteDevice = gatt.getDevice();
            String remoteAddress = remoteDevice.getAddress();

            WritableMap params = Arguments.createMap();
            params.putString("peripheralUuid", remoteAddress);
            rnbleModule.sendEvent("ble.connect", params);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "characteristic onRead");
            responseReceived = true;
            byte[] characteristicValue = null;
            Boolean notification = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristicValue = characteristic.getValue();
            } else {
                Log.w(TAG, "onCharacteristicRead received: " + status);
            }

            WritableMap params = Arguments.createMap();

            BluetoothDevice remoteDevice = gatt.getDevice();
            String remoteAddress = remoteDevice.getAddress();

            params.putString("peripheralUuid", remoteAddress);

            params.putString("serviceUuid", toNobleUuid(characteristic.getService().getUuid().toString()));
            params.putString("characteristicUuid", toNobleUuid(characteristic.getUuid().toString()));
            params.putString("data", Arrays.toString(characteristicValue));
            params.putBoolean("isNotification", notification);
            rnbleModule.sendEvent("ble.data", params);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            responseReceived = true;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "characteristic written successfully");
                WritableMap params = Arguments.createMap();

                BluetoothDevice remoteDevice = gatt.getDevice();
                String remoteAddress = remoteDevice.getAddress();

                params.putString("peripheralUuid", remoteAddress);
                params.putString("serviceUuid", toNobleUuid(characteristic.getService().getUuid().toString()));
                params.putString("characteristicUuid", toNobleUuid(characteristic.getUuid().toString()));

                Log.w(TAG, "sending ble.write callback");
                rnbleModule.sendEvent("ble.write", params);
            } else {
                Log.d(TAG, "onServicesDiscovered received: " + status);
            }
        }
    };

    private String toNobleUuid(String uuid) {
        String result = uuid.replaceAll("[\\s\\-()]", "");
        return result.toLowerCase();
    }

    //RnbleScanCallback scan callback
    private class RnbleScanCallback extends ScanCallback {
        private RNBLEModule rnbleModule;

        public RnbleScanCallback(RNBLEModule rnbleModule) {
            this.rnbleModule = rnbleModule;
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                processScanResult(result);
            }
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            boolean isDuplicate = false;

            //filter out duplicate entries if requested
            if (!rnbleModule.allowDuplicates) {
                for (String s : scannedDeviceAddresses) {
                    BluetoothDevice device = result.getDevice();
                    String address = device.getAddress();

                    if (s.equals(address)) {
                        isDuplicate = true;
                        break;
                    }
                }
            }
            if (!isDuplicate) {
                scannedDeviceAddresses.add(result.getDevice().getAddress());
                // super.onScanResult(callbackType, result);
                //filter based on service UUID here, since ScanFilter class' service UUID filtering did not work in "Samsun Galaxy Tab S"
                ScanRecord record = result.getScanRecord();
                if (record != null) {
                    List<ParcelUuid> serviceUuids = record.getServiceUuids();

                    if (serviceUuids != null) {
                        for (ParcelUuid p : serviceUuids) {
                            if (serviceUuid != null && p.toString().toUpperCase().equals(serviceUuid.toUpperCase())) {
                                processScanResult(result);
                            }
                        }
                    } else if (serviceUuid == null || serviceUuid.isEmpty()) {
                        processScanResult(result);
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.d(TAG, "Scan failed with error: " + errorCode);
        }

        private void processScanResult(ScanResult scanResult) {

            if (scanResult == null) {
                return;
            }

            ScanRecord record = scanResult.getScanRecord();
            BluetoothDevice device = scanResult.getDevice();

            if (record != null) {
                WritableMap params = Arguments.createMap();
                WritableMap advertisement = Arguments.createMap();

                //add service uuids to advertisement map
                WritableArray serviceUuids = Arguments.createArray();
                List<ParcelUuid> uuids = record.getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        serviceUuids.pushString(toNobleUuid(uuid.toString()));
                    }
                }

                advertisement.putArray("serviceUuids", serviceUuids);

                //add serviceData array to advetisement map
                WritableArray serviceData = Arguments.createArray();
                WritableMap serviceDataMap = Arguments.createMap();

                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        byte[] data = record.getServiceData(uuid);
                        if (uuid != null && data != null) {
                            serviceDataMap.putString("uuid", toNobleUuid(uuid.toString()));
                            serviceDataMap.putString("data", Arrays.toString(data));
                            serviceData.pushMap(serviceDataMap);
                        }
                    }
                }
                advertisement.putArray("serviceData", serviceData);

                //add manufacturer data to advertisement map
                byte[] manufacturerData = null;
                if (record.getManufacturerSpecificData() != null) {
                    manufacturerData = record.getManufacturerSpecificData().valueAt(0);
                }
                if (manufacturerData != null) {
                    advertisement.putString("manufacturerData", Arrays.toString(manufacturerData));
                } else {
                    advertisement.putNull("manufacturerData");
                }

                //add local name to advertisement map
                advertisement.putString("localName", record.getDeviceName());

                //add tx power level to advertisement map
                advertisement.putInt("txPowerLevel", record.getTxPowerLevel());

                params.putMap("advertisement", advertisement);

                //add rssi to params
                params.putInt("rssi", scanResult.getRssi());

                // add id to params
                params.putString("id", device.getAddress());

                // add address to params
                params.putString("address", device.getAddress());

                // add address type to params
                params.putString("addressType", "unknown");

                //add connectable to params
                int flags = record.getAdvertiseFlags();
                params.putBoolean("connectable", (flags & 2) == 2); //TODO: double check this to ensure it is correct

                // Log.d(TAG, "--- sending event with:" + params.toString());
                rnbleModule.sendEvent("ble.discover", params);
            }
        }
    }
}
