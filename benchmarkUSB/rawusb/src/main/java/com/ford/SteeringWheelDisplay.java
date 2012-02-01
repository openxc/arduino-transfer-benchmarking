package com.ford;

import java.nio.ByteBuffer;

import java.util.concurrent.TimeUnit;

import java.util.Map;
import java.util.LinkedList;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import android.os.Bundle;
import android.os.Handler;

import android.R.id;
import android.R.id;
import android.R.id;

import android.util.Log;

import android.widget.TextView;

public class SteeringWheelDisplay extends Activity {

    private static String TAG = "rawusb";

    private final Handler mHandler = new Handler();
    private UsbManager mUsbManager;

    private TextView mDeviceNameView;
    private TextView mInterfaceCountView;
    private TextView mEndpointCountView;
    private TextView mTransferredBytesView;
    private TextView mElapsedTimeView;
    private TextView mTransferRateView;
    private TextView mSteeringWheelAngleView;
    // pool of requests for the IN endpoint
    private final LinkedList<UsbRequest> mInRequestPool =
        new LinkedList<UsbRequest>();

    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpoint;

    StringBuffer mBuffer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.detail);
		Log.i(TAG, "SteeringWheelDisplay created");
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        mDeviceNameView = (TextView) findViewById(R.id.device_name);
        mInterfaceCountView = (TextView) findViewById(R.id.interface_count);
        mEndpointCountView = (TextView) findViewById(R.id.endpoint_count);
        mTransferredBytesView = (TextView) findViewById(R.id.transfer_total);
        mElapsedTimeView = (TextView) findViewById(R.id.elapsed_time);
        mTransferRateView = (TextView) findViewById(R.id.transfer_rate);
        mSteeringWheelAngleView = (TextView) findViewById(
                R.id.steering_wheel_angle);

        mElapsedTimeView.setText("0");

        mBuffer = new StringBuffer();
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mSetupDeviceTask);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mSetupDeviceTask);
    }

    private void parseStringBuffer() {
        int newlineIndex = mBuffer.indexOf("\r\n");
        if(newlineIndex != -1) {
            final String messageString = mBuffer.substring(0, newlineIndex);
            mBuffer.delete(0, newlineIndex + 1);

            try {
                final JSONObject message = new JSONObject(messageString);
                mHandler.post(new Runnable() {
                        public void run() {
                    try {
                        mSteeringWheelAngleView.setText(
                                message.getInt("value") + "");
                    } catch(JSONException e) {
                        Log.i(TAG, "Couldn't decode JSON from: " +
                                messageString);
                    }
                }});
            } catch(JSONException e) {
                Log.i(TAG, "Couldn't decode JSON from: " +
                        messageString);
            }
        }
    }

    public UsbRequest getInRequest() {
        synchronized(mInRequestPool) {
            if (mInRequestPool.isEmpty()) {
                UsbRequest request = makeRequest();
                return request;
            } else {
                return mInRequestPool.removeFirst();
            }
        }
    }

    private UsbRequest makeRequest() {
        Log.i(TAG, "Created new USB request");
        UsbRequest request = new UsbRequest();
        request.initialize(mConnection, mEndpoint);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        request.setClientData(buffer);
        return request;
    }

    private void transferData() {
        int transferred = 0;
        final long startTime = System.nanoTime();
        final long endTime;

        UsbRequest request = getInRequest();
        request.queue((ByteBuffer) request.getClientData(), 64);

        while(transferred < 1000 * 500) {
            request = mConnection.requestWait();
            ByteBuffer buffer = (ByteBuffer) request.getClientData();

            byte[] receivedBytes = buffer.array();
            String received = new String(receivedBytes);
            int newlineIndex = received.indexOf("\r\n");
            if(newlineIndex != -1) {
                final String messageString =
                    received.substring(0, newlineIndex) + "\r\n";
                transferred += messageString.length();
            }
            UsbRequest nextRequest = getInRequest();
            nextRequest.queue((ByteBuffer) nextRequest.getClientData(), 64);

            synchronized (mInRequestPool) {
                mInRequestPool.add(request);
            }
        }
        endTime = System.nanoTime();

        double kilobytesTransferred = transferred / 1000.0;
        long elapsedTime = TimeUnit.SECONDS.convert(
            System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        Log.i(TAG, "Transferred " + kilobytesTransferred + " KB in "
            + elapsedTime + " seconds at " +
            kilobytesTransferred / elapsedTime + " KB/s");
    }

    private final Runnable mSetupDeviceTask = new Runnable() {
        public void run() {
            String deviceName =
                getIntent().getStringExtra("usb_device_name");
            Log.i(TAG, "Clicked " + deviceName);

            mDeviceNameView.setText(deviceName);

            Map<String, UsbDevice> devices = mUsbManager.getDeviceList();
            UsbDevice device = devices.get(deviceName);
            mInterfaceCountView.setText(
                    Integer.toString(device.getInterfaceCount()));
            UsbInterface iface = device.getInterface(0);
            mEndpointCountView.setText(
                    Integer.toString(iface.getEndpointCount()));
            mEndpoint = iface.getEndpoint(1);
            mConnection = mUsbManager.openDevice(device);
            mConnection.claimInterface(iface, true);

            new Thread(new Runnable() {
                public void run() {
                    for(int i = 0; i < 10; i++) {
                        UsbRequest request = makeRequest();
                        synchronized (mInRequestPool) {
                            mInRequestPool.add(request);
                        }
                    }
                    transferData();
                }
            }).start();
        }
    };
}
