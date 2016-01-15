package com.example.fernandoraviolo.mymediaplayer;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.util.Util;

public class MainActivity extends AppCompatActivity implements RadioPlayer.Listener {

    private static final String STREAM_URL = "http://rsncast.dyn.rsn.net.au:6330/rsn";
    private static final String TAG = MainActivity.class.getSimpleName();

    private RadioPlayer player;
    private Uri streamUri;
    private boolean playerNeedsPrepare;
    private FloatingActionButton fab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        streamUri = Uri.parse(STREAM_URL);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (player.getPlayerControl().isPlaying()) {
                    player.getPlayerControl().pause();
                    //playerNeedsPrepare = true;
                }
                else {
                    if (!maybeRequestPermission()) {
                        player.seekTo(0);
                        player.getPlayerControl().start();
                        //preparePlayer(true);
                    }
                }
            }
        });

        preparePlayer(false);
    }


    private void preparePlayer(boolean playWhenReady) {
        if (player == null) {
            player = new RadioPlayer(getRendererBuilder());
            player.addListener(this);
            playerNeedsPrepare = true;
            EventLogger eventLogger = new EventLogger();
            eventLogger.startSession();
            player.addListener(eventLogger);
            player.setInfoListener(eventLogger);
            player.setInternalErrorListener(eventLogger);
        }
        if (playerNeedsPrepare) {
            player.prepare();
            playerNeedsPrepare = false;
            player.seekTo(0);
            //updateButtonVisibilities();
        }

        player.setPlayWhenReady(playWhenReady);
    }

    private RadioPlayer.RendererBuilder getRendererBuilder() {
        String userAgent = Util.getUserAgent(this, "ExoPlayerDemo");
        return new ExtractorRendererBuilder(this, userAgent, streamUri);
    }

    /**
     * Checks whether it is necessary to ask for permission to read storage. If necessary, it also
     * requests permission.
     *
     * @return true if a permission request is made. False if it is not necessary.
     */
    @TargetApi(23)
    private boolean maybeRequestPermission() {
        if (requiresPermission(streamUri)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
            return true;
        } else {
            return false;
        }
    }

    @TargetApi(23)
    private boolean requiresPermission(Uri uri) {
        return Util.SDK_INT >= 23
                && Util.isLocalFileUri(uri)
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        switch(playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                fab.setImageResource(android.R.drawable.ic_media_ff);
                break;
            case ExoPlayer.STATE_ENDED:
                fab.setImageResource(android.R.drawable.ic_media_pause);
                break;
            case ExoPlayer.STATE_IDLE:
                break;
            case ExoPlayer.STATE_PREPARING:
                fab.setImageResource(android.R.drawable.ic_media_rew);
                break;
            case ExoPlayer.STATE_READY:
                if (playWhenReady) {
                    fab.setImageResource(android.R.drawable.ic_media_pause);
                } else {
                    fab.setImageResource(android.R.drawable.ic_media_play);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onError(Exception e) {

    }
}
