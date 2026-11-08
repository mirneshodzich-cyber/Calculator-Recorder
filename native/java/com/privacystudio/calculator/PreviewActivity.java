package com.privacystudio.calculator;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;

/** In-app native MP4 playback. Never writes to MediaStore and only reads our own preview cache. */
public final class PreviewActivity extends Activity {
    private static volatile boolean visible = false;
    public static boolean isShowing() { return visible; }
    private VideoView videoView;
    private File decodedFile;
    private TextView message;

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.rgb(9,14,24));
        getWindow().setNavigationBarColor(Color.rgb(9,14,24));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(9,14,24));
        page.setPadding(dp(this,14),dp(this,12),dp(this,14),dp(this,14));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText("‹ Nazad u sef");
        back.setOnClickListener(v->finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(this,150),dp(this,52)));
        TextView title = new TextView(this);
        title.setText("Privatni pregled");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title);
        page.addView(header);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        frame.addView(videoView,new FrameLayout.LayoutParams(-1,-1));
        message = new TextView(this);
        message.setText("Učitavanje videa...");
        message.setGravity(Gravity.CENTER);
        message.setTextSize(14);
        message.setTextColor(Color.WHITE);
        message.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,16));
        frame.addView(message,new FrameLayout.LayoutParams(-1,-1));
        page.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        TextView note = new TextView(this);
        note.setText("Pregled je unutar aplikacije. Original ostaje šifriran; izlaz u galeriju se radi zasebno iz sefa.");
        note.setTextColor(0xffaebdd1);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(this,8),dp(this,16),dp(this,8),dp(this,10));
        page.addView(note);
        setContentView(page);

        try {
            String path=getIntent().getStringExtra("previewPath");
            if(path==null) throw new SecurityException("Nedostaje putanja pregleda.");
            File root = new File(getCacheDir(),"private_preview").getCanonicalFile();
            decodedFile=new File(path).getCanonicalFile();
            if(!decodedFile.getPath().startsWith(root.getPath()+File.separator) || !decodedFile.isFile())
                throw new SecurityException("Video nije dostupan u privatnoj memoriji.");
            MediaController controls = new MediaController(this);
            controls.setAnchorView(videoView);
            videoView.setMediaController(controls);
            videoView.setOnPreparedListener(mp->{
                message.setVisibility(View.GONE);
                mp.setOnVideoSizeChangedListener((mediaPlayer,w,h)->controls.setAnchorView(videoView));
                videoView.start();
                videoView.postDelayed(controls::show,450);
            });
            videoView.setOnErrorListener((mp,what,extra)->{
                message.setVisibility(View.VISIBLE);
                message.setText("Video-player nije mogao otvoriti ovaj MP4 ("+what+"/"+extra+").\nPovratak u sef i izvoz mogu pomoći pri provjeri datoteke.");
                return true;
            });
            videoView.setVideoPath(decodedFile.getAbsolutePath());
            videoView.requestFocus();
        } catch(Exception e) {
            message.setText("Pregled nije uspio: "+e.getMessage());
        }
    }
    @Override public void onStart(){super.onStart();visible=true;RecorderService.appVisible(true);}
    @Override public void onPause(){if(videoView!=null&&videoView.isPlaying())videoView.pause();super.onPause();}
    @Override public void onStop(){visible=false;if(!MainActivity.isShowing())RecorderService.appVisible(false);super.onStop();}
    @Override protected void onDestroy(){
        if(videoView!=null)videoView.stopPlayback();
        if(!isChangingConfigurations() && decodedFile!=null && decodedFile.exists()) {
            // The plaintext temporary video is never published and is deleted on exit.
            if(!decodedFile.delete()) android.util.Log.w("PrivatePlayer","Could not delete temporary video");
        }
        super.onDestroy();
    }
}
