package io.antmedia.TalkVideoApp.liveVideoBroadcaster;

import android.Manifest;
import android.app.AlertDialog;


import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.widget.ContentLoadingProgressBar;
import android.support.v7.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import io.antmedia.TalkVideoApp.broadcaster.ILiveVideoBroadcaster;
import io.antmedia.TalkVideoApp.broadcaster.LiveVideoBroadcaster;

// import io.antmedia.android.broadcaster.MyCallback;

import io.antmedia.TalkVideoApp.broadcaster.utils.Resolution;

// import static io.antmedia.android.MainActivity.RTMP_BASE_URL;
import static io.antmedia.TalkVideoApp.MainActivity.SETTINGS_PREFS;

import android.telephony.TelephonyManager;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;


public class LiveVideoBroadcasterActivity extends AppCompatActivity {

    private static final String TAG = LiveVideoBroadcasterActivity.class.getSimpleName();
    private ViewGroup mRootView;
    boolean mIsRecording = false;
    private EditText mStreamNameEditText;
    private EditText mRTMPURLEditText;
    private Timer mTimer;
    private long mElapsedTime;
    public  TimerHandler mTimerHandler;
    private ImageButton mSettingsButton;
    private CameraResolutionsFragment mCameraResolutionsDialog;
    private Intent mLiveVideoBroadcasterServiceIntent;

    private TextView mStreamLiveStatus;

    private GLSurfaceView mGLView;
    private ILiveVideoBroadcaster mLiveVideoBroadcaster;
    private Button mBroadcastControlButton;
    private int fRate;
    private double bRate;

    private int sigval;

    static {
        System.loadLibrary("rtmp-jni");
    }


    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LiveVideoBroadcaster.LocalBinder binder = (LiveVideoBroadcaster.LocalBinder) service;
            if (mLiveVideoBroadcaster == null) {
                mLiveVideoBroadcaster = binder.getService();
                mLiveVideoBroadcaster.init(LiveVideoBroadcasterActivity.this, mGLView);
                mLiveVideoBroadcaster.setAdaptiveStreaming(true);
            }
            mLiveVideoBroadcaster.openCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
        }
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mLiveVideoBroadcaster = null;
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        
        // Hide title
        //requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);


        //binding on resume not to having leaked service connection
        mLiveVideoBroadcasterServiceIntent = new Intent(this, LiveVideoBroadcaster.class);
        //this makes service do its job until done
        startService(mLiveVideoBroadcasterServiceIntent);

        setContentView(R.layout.activity_live_video_broadcaster);

        mTimerHandler = new TimerHandler();
        mStreamNameEditText = (EditText) findViewById(R.id.stream_name_edit_text);
        mRTMPURLEditText = (EditText) findViewById(R.id.rtmp_url_edit_text);

        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        String restoredText = prefs.getString("URL", null);

        if (restoredText != null)
        {
            mStreamNameEditText.setText(prefs.getString("Key", ""));
            mRTMPURLEditText.setText(prefs.getString("URL", "rtmp://b.rtmp.youtube.com/live2"));
            Log.i(TAG, "settings: " + prefs.getString("URL", "rtmp://b.rtmp.youtube.com/live2"));
        }
        else
        {
            Log.i(TAG, "settings: " + "Settings Not Found...");
        }

        mRootView = (ViewGroup)findViewById(R.id.root_layout);
        mSettingsButton = (ImageButton)findViewById(R.id.settings_button);
        mStreamLiveStatus = (TextView) findViewById(R.id.stream_live_status);

        Log.i(TAG, "IsRecording: " + mIsRecording);

        //Get the instance of TelephonyManager
        TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        String strength = "";
        CellInfoLte cellInfolte = null;
        CellInfoWcdma cellInfowcdma = null;
        CellInfoGsm cellInfogsm = null;
        // CellInfoTdscdma = null;

        try {
            // cellInfolte = (CellInfoLte) tm.getAllCellInfo().get(0);
        }
        catch (Exception e) {
            // System.out.println(e);
            Log.i(TAG, "Failure: " + e);
        }
        if (cellInfolte != null)
        {
            CellSignalStrengthLte cellSignalStrengthLte = cellInfolte.getCellSignalStrength();
            strength = String.valueOf(cellSignalStrengthLte.getDbm());
            Log.i(TAG, "dBm: " + strength);

            CellLocation cellLocation = CellLocation.getEmpty();
            cellLocation.requestLocationUpdate();
        }

        try {
            // cellInfogsm = (CellInfoGsm) tm.getAllCellInfo().get(0);
        }
        catch (Exception e) {
            // System.out.println("errr");
            Log.i(TAG, "No GSM System On Device: " + e);
        }

        if ((cellInfolte == null) && (cellInfogsm == null))
        {
            // System.out.println("errr");
            Log.i(TAG, "Only LTE and GSM support this feature.");
        }

        File logFile = new File("sdcard/Download/System-Log.txt");

        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
                Log.i(TAG, "Created New Log File");
            } catch (IOException e) {
                Log.e(TAG, "Failure:");
                e.printStackTrace();
            }
        }

        if (logFile.exists()) {
            try
            {
                Process p = Runtime.getRuntime().exec("logcat -b all -v long --format usec --format year -d");

                BufferedReader stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
                BufferedReader stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String line = "";
                StringBuilder log = new StringBuilder();
                String logtext = "";

                while ((line = stdInput.readLine()) != null)
                {
                    log.append(line);
                }

                logtext = log.toString();

                //BufferedWriter for performance, true to set append to file flag
                BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
                buf.append(logtext);
                buf.newLine();
                buf.close();
            }
            catch (IOException e)
            {
                Log.e(TAG, "Failure:");
                e.printStackTrace();
            }

        }

        mBroadcastControlButton = (Button) findViewById(R.id.toggle_broadcasting);

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL activity.
        mGLView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        if (mGLView != null) {
            mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        }


    }

    public void changeCamera(View v)
    {
        if (mLiveVideoBroadcaster != null)
        {
            mLiveVideoBroadcaster.changeCamera();
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        //this lets activity bind
        bindService(mLiveVideoBroadcasterServiceIntent, mConnection, 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {

        switch (requestCode) {

            case LiveVideoBroadcaster.PERMISSIONS_REQUEST: {
                if (mLiveVideoBroadcaster.isPermissionGranted()) {
                    mLiveVideoBroadcaster.openCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
                }
                else {
                    if (
                            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ||
                            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_PHONE_STATE) ||
                            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )

                    {
                        mLiveVideoBroadcaster.requestPermission();
                    }
                    else {
                        new AlertDialog.Builder(LiveVideoBroadcasterActivity.this)
                                .setTitle(R.string.permission)
                                .setMessage(getString(R.string.app_doesnot_work_without_permissions))
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {

                                        try {
                                            //Open the specific App Info page:
                                            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                             intent.setData(Uri.parse("package:" + getApplicationContext().getPackageName()));
                                            startActivity(intent);

                                        } catch ( ActivityNotFoundException e ) {
                                            //e.printStackTrace();

                                            //Open the generic Apps page:
                                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                                            startActivity(intent);

                                        }
                                    }
                                })
                                .show();
                    }
                }
                return;
            }
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        Log.i(TAG, "onPause");

        //hide dialog if visible not to create leaked window exception
        if (mCameraResolutionsDialog != null && mCameraResolutionsDialog.isVisible())
        {
            mCameraResolutionsDialog.dismiss();
        }
        mLiveVideoBroadcaster.pause();
    }


    @Override
    protected void onStop()
    {
        super.onStop();
        unbindService(mConnection);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE || newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
        {
            mLiveVideoBroadcaster.setDisplayOrientation();
        }

    }

    public void showSetResolutionDialog(View v)
    {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment fragmentDialog = getSupportFragmentManager().findFragmentByTag("dialog");
        if (fragmentDialog != null) {

            ft.remove(fragmentDialog);
        }

        ArrayList<Resolution> sizeList = mLiveVideoBroadcaster.getPreviewSizeList();


        if (sizeList != null && sizeList.size() > 0)
        {
            mCameraResolutionsDialog = new CameraResolutionsFragment();

            mCameraResolutionsDialog.setCameraResolutions(sizeList, mLiveVideoBroadcaster.getPreviewSize());
            mCameraResolutionsDialog.show(ft, "resolutiton_dialog");
        }
        else
        {
            Snackbar.make(mRootView, "No resolution available",Snackbar.LENGTH_LONG).show();
        }
    }

    public void toggleBroadcasting(View v)
    {
        if (!mIsRecording)
        {
            if (mLiveVideoBroadcaster != null)
            {
                if (!mLiveVideoBroadcaster.isConnected())
                {
                    String streamName = mStreamNameEditText.getText().toString();
                    String rtmpURL = mRTMPURLEditText.getText().toString();

                    Log.i(TAG, "Stream Key: " + streamName);
                    Log.i(TAG, "URL: " + rtmpURL);

                    SharedPreferences pref = getApplicationContext().getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
                    SharedPreferences.Editor editor = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit();
                    editor.putString("URL", rtmpURL);
                    editor.putString("Key", streamName);
                    editor.apply();

                    new AsyncTask<String, String, Boolean>()
                    {
                        ContentLoadingProgressBar progressBar;

                        @Override
                        protected void onPreExecute()
                        {
                            progressBar = new ContentLoadingProgressBar(LiveVideoBroadcasterActivity.this);
                            progressBar.show();
                        }

                        @Override
                        protected Boolean doInBackground(String... url)
                        {
                            Log.i(TAG, "ConnectURL: " + url[0]);
                            return mLiveVideoBroadcaster.startBroadcasting(url[0]);

                        }

                        @Override
                        protected void onPostExecute(Boolean result)
                        {
                            progressBar.hide();
                            mIsRecording = result;
                            if (result)
                            {
                                mStreamLiveStatus.setVisibility(View.VISIBLE);

                                mBroadcastControlButton.setText(R.string.stop_broadcasting);
                                mStreamNameEditText.setVisibility(View.GONE);
                                mRTMPURLEditText.setVisibility(View.GONE);
                                mSettingsButton.setVisibility(View.GONE);

                                // Start Recording Duration
                                startTimer();
                            }
                            else
                            {
                                Snackbar.make(mRootView, R.string.stream_not_started, Snackbar.LENGTH_LONG).show();

                                triggerStopRecording();
                            }
                        }
                    }.execute(rtmpURL + "/" + streamName);
                }
                else
                {
                    Snackbar.make(mRootView, R.string.streaming_not_finished, Snackbar.LENGTH_LONG).show();
                }
            }
            else {
                Snackbar.make(mRootView, R.string.oopps_shouldnt_happen, Snackbar.LENGTH_LONG).show();
            }
        }
        else
        {
            triggerStopRecording();
        }

    }


    public void triggerStopRecording() {
        if (mIsRecording) {
            mBroadcastControlButton.setText(R.string.start_broadcasting);

            mStreamLiveStatus.setVisibility(View.GONE);
            mStreamLiveStatus.setText(R.string.live_indicator);
            mSettingsButton.setVisibility(View.VISIBLE);
            // mStreamNameEditText.setVisibility(View.VISIBLE);
            // mRTMPURLEditText.setVisibility(View.VISIBLE);

            stopTimer();
            mLiveVideoBroadcaster.stopBroadcasting();
        }

        mIsRecording = false;
    }

    //This method starts a mTimer and updates the textview to show elapsed time for recording
    public void startTimer() {

        if(mTimer == null) {
            mTimer = new Timer();
        }

        mElapsedTime = 0;
        mTimer.scheduleAtFixedRate(new TimerTask() {

            public void run()
            {
                mElapsedTime += 1; //increase every sec

                mTimerHandler.obtainMessage(TimerHandler.INCREASE_TIMER).sendToTarget();

                if (mLiveVideoBroadcaster == null || !mLiveVideoBroadcaster.isConnected())
                {
                    mTimerHandler.obtainMessage(TimerHandler.CONNECTION_LOST).sendToTarget();
                }
            }
        }, 0, 1000);
    }


    public void stopTimer()
    {
        if (mTimer != null) {
            this.mTimer.cancel();
        }
        this.mTimer = null;
        this.mElapsedTime = 0;
    }

    public void setResolution(Resolution size) {
        mLiveVideoBroadcaster.setResolution(size);
    }


    private class TimerHandler extends Handler
    {
        static final int CONNECTION_LOST = 2;
        static final int INCREASE_TIMER = 1;

        public void handleMessage(Message msg)
        {
            fRate = mLiveVideoBroadcaster.get_frame_rate();
            bRate = mLiveVideoBroadcaster.get_bitrate();

            double MbRate = bRate / 1000000;

            switch (msg.what)
            {
                case INCREASE_TIMER:
                    // mStreamLiveStatus.setText(getString(R.string.live_indicator) + " - " + getDurationString((int) mElapsedTime));
                    mStreamLiveStatus.setText(getString(R.string.live_indicator) + " - " + fRate + " FPS, " + MbRate + " Mbps");
                    break;
                case CONNECTION_LOST:
                    triggerStopRecording();
                    new AlertDialog.Builder(LiveVideoBroadcasterActivity.this)
                            .setMessage(R.string.broadcast_connection_lost)
                            .setPositiveButton(android.R.string.yes, null)
                            .show();

                    break;
            }
        }
    }

    public static String getDurationString(int seconds)
    {
        if(seconds < 0 || seconds > 2000000)
        {
            //there is an codec problem and duration is not set correctly,so display meaningfull string
            seconds = 0;
        }

        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        seconds = seconds % 60;

        if(hours == 0)
            return twoDigitString(minutes) + " : " + twoDigitString(seconds);
        else
            return twoDigitString(hours) + " : " + twoDigitString(minutes) + " : " + twoDigitString(seconds);
    }

    public static String twoDigitString(int number) {

        if (number == 0) {
            return "00";
        }

        if (number / 10 == 0) {
            return "0" + number;
        }

        return String.valueOf(number);
    }
}
