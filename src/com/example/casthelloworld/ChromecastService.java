package com.example.casthelloworld;

import android.app.IntentService;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v7.media.MediaRouter;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.io.IOException;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p/>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class ChromecastService extends Service {
    private static final String SERVICE_NAME = ChromecastService.class.getSimpleName();

    // TODO: Rename actions, choose action names that describe tasks that this
    // IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
    public static final String ACTION_LAUNCH_RECEIVER = "com.example.casthelloworld.action.LAUNCH_RECEIVER";
    public static final String ACTION_MESSAGE = "com.example.casthelloworld.action.MESSAGE";

    // TODO: Rename parameters
    public static final String EXTRA_DEVICE = "com.example.casthelloworld.extra.DEVICE";
    public static final String EXTRA_MESSAGE = "com.example.casthelloworld.extra.MESSAGE";

    // Chromecast required members
    private GoogleApiClient mApiClient;
    private HelloWorldChannel mHelloWorldChannel;
    private CastDevice mSelectedDevice;
    private Cast.Listener mCastListener;
    private ConnectionCallbacks mConnectionCallbacks;
    private boolean mWaitingForReconnect;
    private boolean mApplicationStarted;
    private String mSessionId;
    private MediaRouter mMediaRouter;
    private IBinder mBinder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show();
        mBinder = new LocalBinder();
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        return super.onStartCommand(intent,flags,startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Class used to return an instance of this to modules calling bindService
     */
    public class LocalBinder extends Binder {
        ChromecastService getService() {
            // Return this instance of LocalService so clients can call public methods
            return ChromecastService.this;
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements
            GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(SERVICE_NAME, "onConnected");

            if (mApiClient == null) {
                // We got disconnected while this runnable was pending
                // execution.
                return;
            }

            try {
                if (mWaitingForReconnect) {
                    mWaitingForReconnect = false;

                    // Check if the receiver app is still running
                    if ((connectionHint != null)
                            && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
                        Log.d(SERVICE_NAME, "App  is no longer running");
                        //teardown(true);
                        stopSelf(); // stop the service
                    } else {
                        // Re-create the custom message channel
                        try {
                            Cast.CastApi.setMessageReceivedCallbacks(
                                    mApiClient,
                                    mHelloWorldChannel.getNamespace(),
                                    mHelloWorldChannel);
                        } catch (IOException e) {
                            Log.e(SERVICE_NAME, "Exception while creating channel", e);
                        }
                    }
                } else {
                    // Launch the receiver app
                    Cast.CastApi.launchApplication(mApiClient, getString(R.string.app_id), false)
                            .setResultCallback(
                                    new ResultCallback<Cast.ApplicationConnectionResult>() {
                                        @Override
                                        public void onResult(
                                                Cast.ApplicationConnectionResult result) {
                                            Status status = result.getStatus();
                                            Log.d(SERVICE_NAME,
                                                    "ApplicationConnectionResultCallback.onResult:"
                                                            + status.getStatusCode());
                                            if (status.isSuccess()) {
                                                ApplicationMetadata applicationMetadata = result
                                                        .getApplicationMetadata();
                                                String applicationStatus = result
                                                        .getApplicationStatus();
                                                boolean wasLaunched = result.getWasLaunched();
                                                Log.d(SERVICE_NAME, "application name: "
                                                        + applicationMetadata.getName()
                                                        + ", status: " + applicationStatus
                                                        + ", wasLaunched: " + wasLaunched);

                                                // Create the custom message
                                                // channel
                                                mHelloWorldChannel = new HelloWorldChannel();
                                                try {
                                                    Cast.CastApi.setMessageReceivedCallbacks(
                                                            mApiClient,
                                                            mHelloWorldChannel.getNamespace(),
                                                            mHelloWorldChannel);
                                                } catch (IOException e) {
                                                    Log.e(SERVICE_NAME, "Exception while creating channel",
                                                            e);
                                                }

                                                // set the initial instructions
                                                // on the receiver
                                                sendMessage(getString(R.string.instructions));
                                            } else {
                                                Log.e(SERVICE_NAME, "application could not launch");
                                                //teardown(true);
                                                stopSelf(); // stop the service
                                            }
                                        }
                                    });
                }
            } catch (Exception e) {
                Log.e(SERVICE_NAME, "Failed to launch application", e);
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(SERVICE_NAME, "onConnectionSuspended");
            mWaitingForReconnect = true;
        }
    }


    /**
     * Tear down the connection to the receiver
     */
    private void teardown(boolean selectDefaultRoute) {
        Log.d(SERVICE_NAME, "teardown");
        if (mApiClient != null) {
            if (mApplicationStarted) {
                if (mApiClient.isConnected() || mApiClient.isConnecting()) {
                    try {
                        Cast.CastApi.stopApplication(mApiClient, mSessionId);
                        if (mHelloWorldChannel != null) {
                            Cast.CastApi.removeMessageReceivedCallbacks(
                                    mApiClient,
                                    mHelloWorldChannel.getNamespace());
                            mHelloWorldChannel = null;
                        }
                    } catch (IOException e) {
                        Log.e(SERVICE_NAME, "Exception while removing channel", e);
                    }
                    mApiClient.disconnect();
                }
                mApplicationStarted = false;
            }
            mApiClient = null;
        }
        if (selectDefaultRoute) {
            mMediaRouter.selectRoute(mMediaRouter.getDefaultRoute());
        }
        mSelectedDevice = null;
        mWaitingForReconnect = false;
        mSessionId = null;
    }

    /**
     * Start the receiver app
     */
    private void launchReceiver() {
        try {
            mCastListener = new Cast.Listener() {

                @Override
                public void onApplicationDisconnected(int errorCode) {
                    Log.d(SERVICE_NAME, "application has stopped");
                    //teardown(true);
                    stopSelf(); // stop the service
                }

            };
            // Connect to Google Play services
            mConnectionCallbacks = new ConnectionCallbacks();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(mSelectedDevice, mCastListener);
            mApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult connectionResult) {
                            Toast.makeText(ChromecastService.this, "Connection Failed", Toast.LENGTH_SHORT).show();
                            Log.e(SERVICE_NAME, "Connection Failed");
                        }
                    })
                    .build();

            mApiClient.connect();
        } catch (Exception e) {
            Toast.makeText(ChromecastService.this, "Failed launchReceiver", Toast.LENGTH_SHORT).show();
            Log.e(SERVICE_NAME, "Failed launchReceiver", e);
        }
    }






    /**
     * Send a text message to the receiver
     */
    private void sendMessage(String message) {
        if (mApiClient != null && mHelloWorldChannel != null) {
            try {
                Cast.CastApi.sendMessage(mApiClient,
                        mHelloWorldChannel.getNamespace(), message).setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(Status result) {
                                if (!result.isSuccess()) {
                                    Log.e(SERVICE_NAME, "Sending message failed");
                                }
                            }
                        });
            } catch (Exception e) {
                Log.e(SERVICE_NAME, "Exception while sending message", e);
            }
        } else {
            Toast.makeText(ChromecastService.this, message, Toast.LENGTH_SHORT).show();
        }
    }



    /**
     * Custom message channel
     */
    class HelloWorldChannel implements Cast.MessageReceivedCallback {

        /**
         * @return custom namespace
         */
        public String getNamespace() {
            return getString(R.string.namespace);
        }

        /*
         * Receive message from the receiver app
         */
        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace,
                                      String message) {
            Log.d(SERVICE_NAME, "Message Received: " + message);
        }
    }
}
