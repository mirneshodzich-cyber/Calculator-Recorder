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
import android.widget.SeekBar;
import android.os.Handler;
import android.os.Looper;
import java.io.File;

/** In-app native MP4 playback. Never writes to MediaStore and only reads our own preview cache. */
public final class PreviewActivity extends Activity {
    private static volatile boolean visible = false;
    public static boolean isShowing() { return visible; }
    private VideoView videoView;
    private MediaPlayer audioPlayer;
    private final Handler playbackHandler=new Handler(Looper.getMainLooper());
    private SeekBar seek;
    private Button audioToggle;
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
            boolean audio="audio".equals(getIntent().getStringExtra("recordType"))||decodedFile.getName().endsWith(".m4a");
            if(audio){
                videoView.setVisibility(View.GONE);
                message.setVisibility(View.GONE);
                LinearLayout audioPanel=new LinearLayout(this);
                audioPanel.setGravity(Gravity.CENTER);
                audioPanel.setOrientation(LinearLayout.VERTICAL);
                audioPanel.setPadding(dp(this,16),dp(this,30),dp(this,16),dp(this,30));
                TextView audioTitle=new TextView(this);
                audioTitle.setText("Diktafon · privatno preslušavanje");
                audioTitle.setTextColor(Color.WHITE);
                audioTitle.setTextSize(19);
                audioTitle.setGravity(Gravity.CENTER);
                audioPanel.addView(audioTitle);
                audioToggle=new Button(this);
                audioToggle.setText("▶ Reprodukuj audio");
                audioToggle.setAllCaps(false);
                LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(-1,dp(this,58));
                buttonLayout.topMargin=dp(this,28);
                audioPanel.addView(audioToggle,buttonLayout);
                seek=new SeekBar(this);
                LinearLayout.LayoutParams seekLayout=new LinearLayout.LayoutParams(-1,dp(this,56));
                seekLayout.topMargin=dp(this,14);
                audioPanel.addView(seek,seekLayout);
                TextView info=new TextView(this);
                info.setText("Dešifrirana privremena kopija briše se po izlasku.");
                info.setTextColor(0xffaebdd1);info.setGravity(Gravity.CENTER);
                audioPanel.addView(info);
                frame.addView(audioPanel,new FrameLayout.LayoutParams(-1,-1));
                audioPlayer=new MediaPlayer();
                audioPlayer.setDataSource(decodedFile.getAbsolutePath());
                audioToggle.setEnabled(false);
                audioPlayer.setOnPreparedListener(mp->{
                    seek.setMax(Math.max(1,mp.getDuration()));
                    audioToggle.setEnabled(true);
                    audioToggle.setText("▶ Reprodukuj audio");
                });
                audioPlayer.setOnCompletionListener(mp->{audioToggle.setText("▶ Reprodukuj ponovno");});
                audioPlayer.setOnErrorListener((mp,what,extra)->{
                    audioToggle.setText("Audio greška "+what+"/"+extra);
                    audioToggle.setEnabled(false);
                    return true;
                });
                audioToggle.setOnClickListener(v->{
                    if(audioPlayer==null)return;
                    try{
                        if(audioPlayer.isPlaying()){audioPlayer.pause();audioToggle.setText("▶ Nastavi");}
                        else{audioPlayer.start();audioToggle.setText("Ⅱ Pauza");}
                    }catch(Exception e){audioToggle.setText("Audio greška");}
                });
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                    public void onProgressChanged(SeekBar bar,int progress,boolean user){
                        if(user&&audioPlayer!=null)audioPlayer.seekTo(progress);
                    }
                    public void onStartTrackingTouch(SeekBar bar){}
                    public void onStopTrackingTouch(SeekBar bar){}
                });
                playbackHandler.post(new Runnable(){public void run(){
                    if(audioPlayer!=null&&seek!=null){
                        try{if(!seek.isPressed())seek.setProgress(audioPlayer.getCurrentPosition());}
                        catch(Exception ignored){}
                        playbackHandler.postDelayed(this,450);
                    }
                }});
                audioPlayer.prepareAsync();
                return;
            }
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
    @Override public void onPause(){
        if(videoView!=null&&videoView.isPlaying())videoView.pause();
        if(audioPlayer!=null){try{if(audioPlayer.isPlaying())audioPlayer.pause();}catch(Exception ignored){}}
        super.onPause();
    }
    @Override public void onStop(){visible=false;if(!MainActivity.isShowing())RecorderService.appVisible(false);super.onStop();}
    @Override protected void onDestroy(){
        if(videoView!=null)videoView.stopPlayback();
        playbackHandler.removeCallbacksAndMessages(null);
        if(audioPlayer!=null){audioPlayer.release();audioPlayer=null;}
        if(!isChangingConfigurations() && decodedFile!=null && decodedFile.exists()) {
            // The plaintext temporary video is never published and is deleted on exit.
            if(!decodedFile.delete()) android.util.Log.w("PrivatePlayer","Could not delete temporary video");
        }
        super.onDestroy();
    }
}
