package io.antmedia.TalkVideoApp;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import io.antmedia.TalkVideoApp.liveVideoBroadcaster.*;
// import io.antmedia.TalkVideoApp.liveVideoPlayer.LiveVideoPlayerActivity;




public class MainActivity extends AppCompatActivity {

    /**
     * PLEASE WRITE RTMP BASE URL of the your RTMP SERVER.
     */
    public static final String RTMP_BASE_URL = "rtmp://b.rtmp.youtube.com/live2/";
    public static final String SETTINGS_PREFS = "Settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(io.antmedia.TalkVideoApp.liveVideoBroadcaster.R.layout.activity_main);
        Intent i = new Intent(this, LiveVideoBroadcasterActivity.class);
        startActivity(i);
    }

    public void openVideoBroadcaster(View view) {
        Intent i = new Intent(this, LiveVideoBroadcasterActivity.class);
        startActivity(i);
    }

    public void openVideoPlayer(View view) {
        // Intent i = new Intent(this, LiveVideoPlayerActivity.class);
        // startActivity(i);
    }
}
