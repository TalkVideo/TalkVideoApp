package io.antmedia.TalkVideoApp.broadcaster;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Surface;
import android.view.View;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import io.antmedia.TalkVideoApp.R;
import io.antmedia.TalkVideoApp.broadcaster.encoder.AudioHandler;
import io.antmedia.TalkVideoApp.broadcaster.encoder.CameraSurfaceRenderer;
import io.antmedia.TalkVideoApp.broadcaster.encoder.TextureMovieEncoder;
import io.antmedia.TalkVideoApp.broadcaster.encoder.VideoEncoderCore;
import io.antmedia.TalkVideoApp.broadcaster.network.IMediaMuxer;
import io.antmedia.TalkVideoApp.broadcaster.network.RTMPStreamer;
import io.antmedia.TalkVideoApp.broadcaster.utils.Resolution;
import io.antmedia.TalkVideoApp.broadcaster.utils.Utils;

import android.telephony.TelephonyManager;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;

/**
 * Created by mekya on 28/03/2017.
 */

public class LiveVideoBroadcaster extends Service implements ILiveVideoBroadcaster, CameraHandler.ICameraViewer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = LiveVideoBroadcaster.class.getSimpleName();
    private volatile static CameraProxy sCameraProxy;
    private IMediaMuxer mRtmpStreamer;
    private AudioRecorderThread audioThread;
    private boolean isRecording = false;
    private GLSurfaceView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private CameraHandler mCameraHandler;
    private AudioHandler audioHandler;
    private Activity context;
    private volatile static boolean sCameraReleased;
    private ArrayList<Resolution> choosenPreviewsSizeList;
    private final IBinder mBinder = new LocalBinder();
    private int currentCameraId= Camera.CameraInfo.CAMERA_FACING_FRONT;

    private int frameRate = 30;
    public static final int PERMISSIONS_REQUEST = 8954;

    public final static int SAMPLE_AUDIO_RATE_IN_HZ = 44100;
    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();
    private Resolution previewSize;
    private AlertDialog mAlertDialog;
    private HandlerThread mRtmpHandlerThread;
    private HandlerThread audioHandlerThread;
    private ConnectivityManager connectivityManager;
    private boolean adaptiveStreamingEnabled = false;
    private Timer adaptiveStreamingTimer = null;


    public boolean isConnected() {
        if (mRtmpStreamer != null) {
            return mRtmpStreamer.isConnected();
        }
        return false;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mGLView.requestRender();
    }

    public int get_bitrate()
    {
        return(mRenderer.getBitrate());
    }

    public int get_frame_rate()
    {
        return(mRenderer.getFrameRate());
    }


    public void pause()
    {
        if (mAlertDialog != null && mAlertDialog.isShowing())
        {
            mAlertDialog.dismiss();
        }

        //first making mGLView GONE is important otherwise
        //camera function is called after release exception may be thrown
        //especially in htc one x 4.4.2
        mGLView.setVisibility(View.GONE);
        stopBroadcasting();

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
                if (!sCameraReleased /*|| context.equals(sCurrentActivity.get())*/) {
                    releaseCamera();
                }
            }
        });
        mGLView.onPause();
        mGLView.setOnTouchListener(null);

    }

    public void setDisplayOrientation() {
        if (sCameraProxy != null) {

            sCameraProxy.setDisplayOrientation(getCameraDisplayOrientation());
            if (!isConnected()) {
                setRendererPreviewSize();
            }
        }
    }

    public ArrayList<Resolution> getPreviewSizeList() {
        return choosenPreviewsSizeList;
    }

    public Resolution getPreviewSize() {
        return previewSize;
    }

    public class LocalBinder extends Binder {
        public ILiveVideoBroadcaster getService() {
            // Return this instance of LocalService so clients can call public methods
            return LiveVideoBroadcaster.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }



    @Override
    public void onDestroy() {
        audioHandlerThread.quitSafely();
        mRtmpHandlerThread.quitSafely();
        mCameraHandler.invalidateHandler();
        super.onDestroy();
    }

    public void init(Activity activity, GLSurfaceView glView) {
        try {
            audioHandlerThread = new HandlerThread("AudioHandlerThread", Process.THREAD_PRIORITY_AUDIO);
            audioHandlerThread.start();
            audioHandler = new AudioHandler(audioHandlerThread.getLooper());
            mCameraHandler = new CameraHandler(this);
            this.context = activity;

            // Define a handler that receives camera-control messages from other threads.  All calls
            // to Camera must be made on the same thread.  Note we create this before the renderer
            // thread, so we know the fully-constructed object will be visible.
            mRenderer = new CameraSurfaceRenderer(mCameraHandler, sVideoEncoder);
            mGLView = glView;
            mGLView.setRenderer(mRenderer);
            mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            mRtmpHandlerThread = new HandlerThread("RtmpStreamerThread"); //, Process.THREAD_PRIORITY_BACKGROUND);
            mRtmpHandlerThread.start();
            mRtmpStreamer = new RTMPStreamer(mRtmpHandlerThread.getLooper());

            connectivityManager = (ConnectivityManager) this.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean hasConnection() {
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.isConnected()) {
            return true;
        }
        return false;
    }

    public boolean startBroadcasting(String rtmpUrl) {

        isRecording = false;

        if (sCameraProxy == null || sCameraProxy.isReleased()) {
            Log.w(TAG, "Camera should be opened before calling this function");
            return false;
        }

        if (!hasConnection()) {
            Log.w(TAG, "There is no active network connection");
        }


        if (Utils.doesEncoderWorks(context) != Utils.ENCODER_WORKS) {
            Log.w(TAG, "This device does not have hardware encoder");
            Snackbar.make(mGLView, R.string.not_eligible_for_broadcast, Snackbar.LENGTH_LONG).show();
            return false;
        }

        try {
            boolean result = mRtmpStreamer.open(rtmpUrl);
            if (result) {
                final long recordStartTime = System.currentTimeMillis();
                mGLView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setOptions(mRtmpStreamer);
                        setRendererPreviewSize();
                        // notify the renderer that we want to change the encoder's state
                        mRenderer.startRecording(recordStartTime);
                    }
                });


                int minBufferSize = AudioRecord
                        .getMinBufferSize(SAMPLE_AUDIO_RATE_IN_HZ,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT);

                audioHandler.startAudioEncoder(mRtmpStreamer, SAMPLE_AUDIO_RATE_IN_HZ, minBufferSize);

                audioThread = new AudioRecorderThread(SAMPLE_AUDIO_RATE_IN_HZ, recordStartTime, audioHandler);
                audioThread.start();
                isRecording = true;

                if (adaptiveStreamingEnabled) {
                    adaptiveStreamingTimer = new Timer();

                    adaptiveStreamingTimer.schedule(new TimerTask() {
                        public int previousFrameCount;
                        public int frameQueueIncreased;

                        @Override
                        public void run() {

                            // mGLView.getHolder().setFormat(PixelFormat.RGB_888);
                            // mGLView.setAlpha(.5f);

                            // mGLView.setRotationX(10.3f);

                            // ViewOverlay overlay = mGLView.getOverlay();
                            // Drawable drawable = Drawable.createFromPath("/sdcard/Download/Pix.png");
                            // overlay.add(drawable);

                            int frameCountInQueue = mRtmpStreamer.getVideoFrameCountInQueue();
                            Log.i(TAG, "video frameCountInQueue : " + frameCountInQueue);
                            if (frameCountInQueue > previousFrameCount) {
                                frameQueueIncreased++;
                            }
                            else {
                                frameQueueIncreased--;
                            }
                            previousFrameCount = frameCountInQueue;

                            if (frameQueueIncreased > 10)
                            {
                                //decrease bitrate
                                System.out.println("decrease bitrate");
                                mGLView.queueEvent(new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        int frameRate = mRenderer.getFrameRate();

                                        if (frameRate >= 13)
                                        {
                                            frameRate -= 3;
                                            mRenderer.setFrameRate(frameRate);
                                        }
                                        else
                                        {
                                            int bitrate = mRenderer.getBitrate();

                                            if (bitrate > 200000) // 200kbit
                                            {
                                                bitrate -= 100000;
                                                mRenderer.setBitrate(bitrate);
                                                // notify the renderer that we want to change the encoder's state
                                                mRenderer.recorderConfigChanged();
                                            }
                                        }
                                    }
                                });
                                frameQueueIncreased = 0;

                            }

                            if (frameQueueIncreased < -10)
                            {
                                //increase bitrate
                                System.out.println("//increase bitrate");
                                mGLView.queueEvent(new Runnable() {
                                    @Override
                                    public void run() {
                                        int frameRate = mRenderer.getFrameRate();

                                        if (frameRate <= 27)
                                        {
                                            frameRate += 3;
                                            mRenderer.setFrameRate(frameRate);
                                        }
                                        else
                                        {
                                            int bitrate = mRenderer.getBitrate();
                                            int br_increase = 0;


                                            if ( (bitrate < 6000000) && (previewSize.height <= 1080) )
                                            {
                                                br_increase = 500000;
                                            }

                                            if ( (bitrate < 4000000) && (previewSize.height <= 720) )
                                            {
                                                br_increase = 200000;
                                            }

                                            if ( (bitrate < 2000000) && (previewSize.height <= 480 ) )
                                            {
                                                br_increase = 100000;
                                            }

                                            if ( (bitrate < 1000000) && (previewSize.height <= 360 ) )
                                            {
                                                br_increase = 50000;
                                            }

                                            bitrate += br_increase;


                                            if ( (bitrate > 1000000) && (previewSize.height <= 360 ) )
                                            {
                                                bitrate = 1000000;
                                            }

                                            if ( (bitrate > 2000000) && (previewSize.height <= 480 ) )
                                            {
                                                bitrate = 2000000;
                                            }

                                            if ( (bitrate > 4000000) && (previewSize.height <= 720 ) )
                                            {
                                                bitrate = 4000000;
                                            }

                                            if ( (bitrate > 6000000) && (previewSize.height <= 1080 ) )
                                            {
                                                bitrate = 6000000;
                                            }

                                            mRenderer.setBitrate(bitrate);
                                            // notify the renderer that we want to change the encoder's state
                                            mRenderer.recorderConfigChanged();

                                        }
                                    }
                                });

                                frameQueueIncreased = 0;
                            }

                            Log.i(TAG, "BitRate: " + mRenderer.getBitrate());
                            Log.i(TAG, "FrameRate: " + mRenderer.getFrameRate());

                            //Get the instance of TelephonyManager
                            TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
                            String strength = "";
                            CellInfoLte cellInfolte = null;
                            CellInfoWcdma cellInfowcdma = null;
                            CellInfoGsm cellInfogsm = null;
                            // CellInfoTdscdma = null;

                            try {
                                cellInfolte = (CellInfoLte) tm.getAllCellInfo().get(0);
                            }
                            catch (Exception e) {
                                // System.out.println(e);
                                Log.i(TAG, "Failure: " + e);
                            }
                            if (cellInfolte != null)
                            {
                                String clinfo = cellInfolte.toString();
                                Log.i(TAG, "clinfo: " + clinfo);

                                CellSignalStrengthLte cellSignalStrengthLte = cellInfolte.getCellSignalStrength();
                                strength = String.valueOf(cellSignalStrengthLte.getDbm());
                                Log.i(TAG, "dBm: " + strength);

                                CellLocation cellLocation = CellLocation.getEmpty();

                                try {
                                    cellLocation.requestLocationUpdate();
                                    cellLocation = tm.getCellLocation();
                                }
                                catch (Exception e) {
                                    Log.i(TAG, "Tried To Get Location: " + e);
                                }
                                if (null != cellLocation) {

                                }
                            }

                            try {
                                cellInfogsm = (CellInfoGsm) tm.getAllCellInfo().get(0);
                            }
                            catch (Exception e) {
                                // System.out.println("errr");
                                Log.i(TAG, "GSM System Not Present: " + e);
                            }
                            if ((cellInfolte == null) && (cellInfogsm == null))
                            {
                                // System.out.println("errr");
                                //Log.i(TAG, "Only LTE and GSM support this feature.");
                            }

                            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                            final boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);


                            // locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);

                            List<String> clproviders = locationManager.getAllProviders();
                            // Log.i(TAG, "Calling getLastKnownLocation(): " + LocationManager.GPS_PROVIDER);
                            Log.i(TAG, "Calling getLastKnownLocation(): " + clproviders.toString());

                            // locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1, 1, yourLocationListener);

                            // locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,null);

                            LocationProvider provider = locationManager.getProvider(LocationManager.GPS_PROVIDER);

                            // Retrieve a list of location providers that have fine accuracy, no monetary cost, etc
                            Criteria criteria = new Criteria();
                            criteria.setAccuracy(Criteria.ACCURACY_FINE);
                            criteria.setCostAllowed(false);
                            String providerName = locationManager.getBestProvider(criteria, true);

                            // If no suitable provider is found, null is returned.
                            if (providerName != null) {
                                Log.i(TAG, "GPS provider is null  ");
                            }

                            /*
                            final LocationListener listener = new LocationListener() {
                                @Override
                                public void onLocationChanged(Location location) {
                                    location.getLongitude();
                                }
                             };
                            */

                            if (!gpsEnabled) {
                                Log.i(TAG, "GPS System Not Found.");
                            }
                            else {
                                Log.i(TAG, "GPS System Found.");
                            }

                            try {
                                Location lastloc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                // Log.i(TAG, "Last Known Location: " + lastloc.toString() );
                                double lati = 0;
                                lati = lastloc.getLatitude();
                                Log.i(TAG, "Last Known Location : " + lati);
                            }
                            catch (Exception e) {
                                Log.i(TAG, "Error Calling getLastKnownLocation(): " + e);
                            }

                            /*
                            // Attempt to get YouTube chat
                            URL u;
                            try {
                                u = new URL("https://www.youtube.com/live_chat?v=<videoId>");

                                URLConnection yc = u.openConnection();
                                yc.setDoInput(true);
                                yc.setDoOutput(true);

                                // May crash here
                                BufferedReader in = new BufferedReader(new InputStreamReader(yc.getInputStream()));

                                String inputLine;
                                StringBuilder a = new StringBuilder();

                                while ((inputLine = in.readLine()) != null) {
                                    a.append(inputLine);
                                }
                                in.close();

                                Log.i(TAG, "YouTube Chat: " + a.toString());

                                Context context = getApplicationContext();
                                CharSequence text = "Hello toast!";
                                int duration = Toast.LENGTH_SHORT;

                                // Toast toast = Toast.makeText(context, text, duration);
                                // toast.show();

                                // Snackbar.make(mGLView, R.string.not_eligible_for_broadcast, Snackbar.LENGTH_LONG).show();
                                // Toast.makeText(getApplicationContext(), a.toString(), Toast.LENGTH_SHORT).show();

                            } catch (MalformedURLException e) {
                                // Toast.makeText(getApplicationContext(), "Error", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            } catch (IOException e) {
                                // Toast.makeText(getApplicationContext(), "Error", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }

                            */

                        }
                    }, 0, 500);
                }
            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return isRecording;
    }


    public void stopBroadcasting() {
        if (isRecording) {

            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    // notify the renderer that we want to change the encoder's state
                    mRenderer.stopRecording();
                }
            });
            if (adaptiveStreamingTimer != null) {
                adaptiveStreamingTimer.cancel();
                adaptiveStreamingTimer = null;
            }

            if (audioThread != null) {
                audioThread.stopAudioRecording();
            }

            if (audioHandler != null) {
                audioHandler.sendEmptyMessage(AudioHandler.END_OF_STREAM);
            }

            int i = 0;
            while (sVideoEncoder.isRecording()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (i>5) {
                    //timeout 250ms
                    //force stop recording
                    sVideoEncoder.stopRecording();
                    break;
                }
                i++;
            }
        }

    }

    public void setResolution(Resolution size) {
        Camera.Parameters parameters = sCameraProxy.getParameters();
        parameters.setPreviewSize(size.width, size.height);
        parameters.setRecordingHint(true);

        // parameters.setRotation(180);
        // parameters.setZoom(90);
        // parameters.setAutoWhiteBalanceLock(false);
        // parameters.setWhiteBalance("shade");

        System.out.println("set resolution stop preview");
        sCameraProxy.stopPreview();
        sCameraProxy.setParameters(parameters);
        sCameraProxy.startPreview();
        previewSize = size;
        setRendererPreviewSize();
    }

     public void setWhiteBalance(String whiteBalance)
     {
         Camera.Parameters parameters = sCameraProxy.getParameters();
     }

    public void turnFlashOn()
    {
        Camera.Parameters parameters = sCameraProxy.getParameters();
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
    }

    public void turnFlashOff()
    {
        Camera.Parameters parameters = sCameraProxy.getParameters();
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
    }




    private void setRendererPreviewSize()
    {
        int rotation = context.getWindowManager().getDefaultDisplay()
                .getRotation();
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setCameraPreviewSize(previewSize.height, previewSize.width);
                }
            });
        }
        else {
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setCameraPreviewSize(previewSize.width, previewSize.height);
                }
            });
        }
    }

    @Override
    public void handleSetSurfaceTexture(SurfaceTexture st) {
        if (sCameraProxy != null && !context.isFinishing() && st != null) {
            {
                st.setOnFrameAvailableListener(this);
                sCameraProxy.stopPreview();
                sCameraProxy.setPreviewTexture(st);
                sCameraProxy.startPreview();
            }
        }
    }


    public void openCamera(int cameraId) {
        //check permission
        if (!isPermissionGranted())
        {
            requestPermission();
            return;
        }

        if(cameraId == Camera.CameraInfo.CAMERA_FACING_FRONT &&
                !getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            //if fron camera is requested but not found, then open the back camera
            cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }


        currentCameraId = cameraId;
        mGLView.setVisibility(View.GONE);
        new AsyncTask<Integer, Void, Camera.Parameters>() {

            @Override
            protected void onPreExecute() {
            }

            @Override
            protected Camera.Parameters doInBackground(Integer... params) {

                Camera.Parameters parameters = null;
                sCameraReleased = false;
                System.out.println("--- releaseCamera call in doInBackground --- ");
                releaseCamera();
                try {
                    int tryCount = 0;
                    do {
                        sCameraProxy = new CameraProxy(params[0]);
                        if (sCameraProxy.isCameraAvailable()) {
                            break;
                        }
                        Thread.sleep(1000);
                        tryCount++;
                    } while (tryCount <= 3);
                    if (sCameraProxy.isCameraAvailable()) {
                        System.out.println("--- camera opened --- ");
                        parameters = sCameraProxy.getParameters();
                        if (parameters != null) {
                            setCameraParameters(parameters);

                            if (Utils.doesEncoderWorks(context) == Utils.ENCODER_NOT_TESTED)
                            {
                                boolean encoderWorks = VideoEncoderCore.doesEncoderWork(previewSize.width, previewSize.height, 300000, 20);
                                Utils.setEncoderWorks(context, encoderWorks);
                            }
                        }
                    }
                    else {
                        sCameraProxy = null;
                    }
                    Log.d(TAG, "onResume complete: " + this);

                } catch (Exception e) {
                    e.printStackTrace();
                }

                return parameters;
            }

            @Override
            protected void onPostExecute(Camera.Parameters parameters) {
                if (context.isFinishing()) {
                    releaseCamera();
                }
                else if (sCameraProxy != null && parameters != null) {
                    mGLView.setVisibility(View.VISIBLE);
                    mGLView.onResume();
                    //mGLView.setAlpha(0.7f);
                    setRendererPreviewSize();

                    if (Utils.doesEncoderWorks(context) != Utils.ENCODER_WORKS) {
                        showEncoderNotExistDialog();
                    }

                }
                else {
                    Snackbar.make(mGLView, R.string.camera_not_running_properly, Snackbar.LENGTH_LONG)
                            .show();
                }

            }
        }.execute(currentCameraId);
    }

    private void releaseCamera() {
        try {
            if (sCameraProxy != null) {
                System.out.println("releaseCamera stop preview");
                sCameraProxy.release();
                sCameraProxy = null;
                sCameraReleased = true;
                System.out.println("-- camera released --");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    @Override
    public void setAdaptiveStreaming(boolean enable) {
        this.adaptiveStreamingEnabled = enable;
    }

    private int setCameraParameters(Camera.Parameters parameters) {

        List<Camera.Size> previewSizeList = parameters.getSupportedPreviewSizes();
        Collections.sort(previewSizeList, new Comparator<Camera.Size>() {

            @Override
            public int compare(Camera.Size lhs, Camera.Size rhs) {
                if (lhs.height == rhs.height) {
                    return lhs.width == rhs.width ? 0 : (lhs.width > rhs.width ? 1 : -1);
                } else if (lhs.height > rhs.height) {
                    return 1;
                }
                return -1;
            }
        });

        int preferredHeight = 720;

        choosenPreviewsSizeList = new ArrayList<>();

        int diff = Integer.MAX_VALUE;
        Resolution choosenSize = null;

        for (int i = 0; i < previewSizeList.size(); i++)
        {
            Camera.Size size = previewSizeList.get(i);

            Log.i(TAG, "Height: " + size.height);
            Log.i(TAG, "Width: " + size.width);

            // if ((size.width % 16 == 0) && (size.height % 16 == 0))
            // if ((size.width == 640) && (size.height == 360))
            // if ((size.width == 1920) && (size.height == 1080))
            if (true)
            {
                Resolution resolutionSize = new Resolution(size.width, size.height);
                choosenPreviewsSizeList.add(resolutionSize);
                int currentDiff = Math.abs(size.height - preferredHeight);

                if (currentDiff < diff)
                {
                    diff = currentDiff;
                    choosenSize = resolutionSize;
                }
            }
        }

        int[] requestedFrameRate = new int[]{frameRate * 1000, frameRate * 1000};
        int[] bestFps = findBestFrameRate(parameters.getSupportedPreviewFpsRange(), requestedFrameRate);
        parameters.setPreviewFpsRange(bestFps[0], bestFps[1]);

        int len = choosenPreviewsSizeList.size();
        int resolutionIndex = len-1;

        if (choosenSize != null) {
            resolutionIndex = choosenPreviewsSizeList.indexOf(choosenSize);
        }


        if (resolutionIndex >=0) {
            Resolution size = choosenPreviewsSizeList.get(resolutionIndex);
            parameters.setPreviewSize(size.width, size.height);
            parameters.setRecordingHint(true);
        }
        if (parameters.getSupportedFocusModes().contains(
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        sCameraProxy.setDisplayOrientation(getCameraDisplayOrientation());


        if (parameters.isVideoStabilizationSupported()) {
            parameters.setVideoStabilization(true);
        }

        //sCameraDevice.setParameters(parameters);
        sCameraProxy.setParameters(parameters);
        Camera.Size size = parameters.getPreviewSize();
        // this.previewSize = new Resolution(size.width, size.height);
        this.previewSize = new Resolution(1280, 720);

        return len;
    }


    public boolean isPermissionGranted() {
        boolean cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        boolean microPhonePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        boolean storagePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        boolean phonePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;

        boolean locationPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        return cameraPermissionGranted && microPhonePermissionGranted && storagePermissionGranted && phonePermissionGranted && locationPermissionGranted;
    }

    public void requestPermission() {

        boolean cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        boolean microPhonePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        boolean storagePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        boolean phonePermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;

        boolean locationPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        final List<String> permissionList = new ArrayList();
        if (!cameraPermissionGranted) {
            permissionList.add(Manifest.permission.CAMERA);
        }
        if (!microPhonePermissionGranted) {
            permissionList.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!storagePermissionGranted) {
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!phonePermissionGranted) {
            permissionList.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!locationPermissionGranted) {
            permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (permissionList.size() > 0 )
        {
            if (ActivityCompat.shouldShowRequestPermissionRationale(context,
                    Manifest.permission.CAMERA)) {
                mAlertDialog = new AlertDialog.Builder(context)
                        .setTitle(R.string.permission)
                        .setMessage(getString(R.string.camera_permission_is_required))
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                String[] permissionArray = permissionList.toArray(new String[permissionList.size()]);
                                ActivityCompat.requestPermissions(context,
                                        permissionArray,
                                        PERMISSIONS_REQUEST);
                            }
                        })
                        .show();
            }
            else if (ActivityCompat.shouldShowRequestPermissionRationale(context,
                    Manifest.permission.RECORD_AUDIO)) {
                mAlertDialog = new AlertDialog.Builder(context)
                        .setMessage(getString(R.string.microphone_permission_is_required))
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                String[] permissionArray = permissionList.toArray(new String[permissionList.size()]);
                                ActivityCompat.requestPermissions(context,
                                        permissionArray,
                                        PERMISSIONS_REQUEST);
                            }
                        })
                        .show();

            }
            else {
                String[] permissionArray = permissionList.toArray(new String[permissionList.size()]);
                ActivityCompat.requestPermissions(context,
                        permissionArray,
                        PERMISSIONS_REQUEST);
            }

        }
    }

    public int[] findBestFrameRate(List<int[]> frameRateList, int[] requestedFrameRate) {
        int[] bestRate = frameRateList.get(0);
        int requestedAverage = (requestedFrameRate[0] + requestedFrameRate[1]) / 2;
        int bestRateAverage = (bestRate[0] + bestRate[1]) / 2;

        int size = frameRateList.size();
        for (int i=1; i < size; i++) {
            int[] rate = frameRateList.get(i);

            int rateAverage = (rate[0] + rate[1]) / 2;


            if (Math.abs(requestedAverage - bestRateAverage) >= Math.abs(requestedAverage - rateAverage)) {

                if ((Math.abs(requestedFrameRate[0] - rate[0]) <=
                        Math.abs(requestedFrameRate[0] - bestRate[0])) ||
                        (Math.abs(requestedFrameRate[1] - rate[1]) <=
                                Math.abs(requestedFrameRate[1] - bestRate[1]))) {
                    bestRate = rate;
                    bestRateAverage = rateAverage;
                }
            }
        }

        return bestRate;
    }

    public void showEncoderNotExistDialog() {
        mAlertDialog = new AlertDialog.Builder(context)
                //.setTitle("")
                .setMessage(R.string.not_eligible_for_broadcast)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .show();
    }

    public int getCameraDisplayOrientation() {
        Camera.CameraInfo info =
                new Camera.CameraInfo();
        Camera.getCameraInfo(currentCameraId, info);
        int rotation = context.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public void changeCamera() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            Snackbar.make(mGLView, R.string.only_one_camera_exists, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (sCameraProxy == null) {
            Snackbar.make(mGLView, R.string.first_call_open_camera, Snackbar.LENGTH_LONG).show();
            return;
        }

        //swap the id of the camera to be used
        if(currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK){
            currentCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        else {
            currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }

        new AsyncTask<Void, Void, Camera.Parameters>() {

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                mGLView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        // Tell the renderer that it's about to be paused so it can clean up.
                        mRenderer.notifyPausing();
                    }
                });
                mGLView.onPause();
                mGLView.setOnTouchListener(null);
            }

            @Override
            protected Camera.Parameters doInBackground(Void... voids) {
                releaseCamera();
                try {
                    sCameraProxy = new CameraProxy(currentCameraId);
                    Camera.Parameters parameters = sCameraProxy.getParameters();
                    if (parameters != null) {
                        setCameraParameters(parameters);
                        return parameters;
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Camera.Parameters parameters) {
                super.onPostExecute(parameters);
                if (parameters != null) {
                    mGLView.onResume();
                    setRendererPreviewSize();
                }
                else {
                    Snackbar.make(mGLView, R.string.camera_not_running_properly, Snackbar.LENGTH_LONG)
                            .show();
                }

            }
        }.execute();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
