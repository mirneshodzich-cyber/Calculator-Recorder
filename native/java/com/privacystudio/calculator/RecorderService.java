package com.privacystudio.calculator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated foreground recorder. Stops after 90 minutes outside the app. */
public class RecorderService extends Service {
    public static final String CAMERA="com.privacystudio.calculator.START_CAMERA";
    public static final String SCREEN="com.privacystudio.calculator.START_SCREEN";
    public static final String AUDIO="com.privacystudio.calculator.START_AUDIO";
    public static final String NOTICE_PREFERENCES="recorder_notification_v11";
    public static final String NOTICE_LABEL="custom_label";
    public static final String STOP="com.privacystudio.calculator.STOP";
    private static final String CHANNEL="camera_capture_active";
    private static final String TAG="VideoRecorder";
    private static final String PREFS="video_recorder_state_v4";
    private static final int NOTIFY_ID=2401;
    private static final long IDLE_MS=90L*60L*1000L;
    private static volatile RecorderService instance;
    private static volatile boolean active=false;
    private static volatile boolean preparing=false;
    private static volatile long startedAt=0;
    private static volatile String currentMode="camera";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    // Monotonic timestamp when the user last left the app. Zero means app is visible.
    // Keep this independent of recording start: no timeout while the UI is on screen.
    private volatile long backgroundSince=0;
    private volatile boolean appInForeground=true;
    private MediaRecorder recorder;
    private CameraDevice camera;
    private CameraCaptureSession cameraSession;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private File rawFile;
    private String lens="back";
    private int frameWidth=0,frameHeight=0;
    private boolean finishing=false;
    private boolean recorderHadError=false;
    private long preparingSince=0;
    private boolean foregoundShown=false;
    private int generation=0;
    private final Runnable watchdog=new Runnable(){@Override public void run(){
        if(preparing&&preparingSince>0 && SystemClock.elapsedRealtime()-preparingSince>20000){
            setError("Kamera/ekran nije započeo snimanje u roku od 20 sekundi",null);
            finish();
        } else if((active||preparing)&&!appInForeground&&backgroundSince>0
                && SystemClock.elapsedRealtime()-backgroundSince>=IDLE_MS){
            Log.i(TAG,"Stop: 90 minutes outside the application");finish();
        }
        if(active||preparing)handler.postDelayed(this,5000);
    }};

    public static JSONObject status(Context c){
        JSONObject o=new JSONObject();try{
            o.put("recording",active||preparing);o.put("preparing",preparing);
            o.put("saving",instance!=null&&instance.finishing);
            o.put("phase",instance!=null&&instance.finishing?"saving":preparing?"preparing":active?"recording":"idle");
            o.put("startedAt",startedAt);o.put("mode",currentMode);
            android.content.SharedPreferences state=c.getSharedPreferences(PREFS,MODE_PRIVATE);
            o.put("lastError",state.getString("lastError",""));
            o.put("lastSavedAt",state.getLong("lastSavedAt",0));
            o.put("lastSavedName",state.getString("lastSavedName",""));
            o.put("lastAttemptAt",state.getLong("lastAttemptAt",0));
            o.put("lastRawBytes",state.getLong("lastRawBytes",0));
            File dir=new File(c.getFilesDir(),"capture_pending");
            File[] files=dir.listFiles((d,name)->name.endsWith(".tmp"));
            o.put("unprocessedFiles",files==null?0:files.length);
            if(instance!=null){
                RecorderService s=instance;
                long remaining=s.appInForeground||s.backgroundSince<=0?IDLE_MS:
                    Math.max(0,IDLE_MS-(SystemClock.elapsedRealtime()-s.backgroundSince));
                o.put("idleSecondsLeft",remaining/1000);
            }
        }catch(Exception ignored){}return o;
    }
    private void setError(String message,Throwable cause){
        Log.e(TAG,message,cause);
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("lastError",message+(cause==null?"":": "+cause.getMessage())).apply();
    }
    private void saveSuccess(JSONObject record){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("lastError","")
            .putLong("lastSavedAt",System.currentTimeMillis())
            .putString("lastSavedName",record.optString("name","Video spremljen"))
            .apply();
    }
    public static boolean busy(){return active||preparing||(instance!=null&&instance.finishing);}
    public static void userInteracted(){
        RecorderService s=instance;
        // Only actual interaction in our visible UI can reset the background countdown.
        if(s!=null&&(MainActivity.isShowing()||PreviewActivity.isShowing()))appVisible(true);
    }
    public static void appVisible(boolean visible){
        RecorderService s=instance;
        if(s==null)return;
        if(visible){
            s.appInForeground=true;
            s.backgroundSince=0;
        }else if(s.appInForeground||s.backgroundSince<=0){
            s.appInForeground=false;
            s.backgroundSince=SystemClock.elapsedRealtime();
        }
    }
    @Override public void onCreate(){
        super.onCreate();instance=this;makeChannel();
        // Preserve orphan temporary files: they may contain recoverable MP4s.
        // Vault -> "Obnovi pronađene snimke" validates and imports them.
        new RecorderStore(this).purgePreviews();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(STOP.equals(action)){finish();return START_NOT_STICKY;}
        if(!CAMERA.equals(action)&&!SCREEN.equals(action)&&!AUDIO.equals(action))return START_NOT_STICKY;
        if(active||preparing||finishing)return START_NOT_STICKY;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("lastError","").apply();
        recorderHadError=false;
        preparing=true;preparingSince=SystemClock.elapsedRealtime();
        currentMode=CAMERA.equals(action)?"camera":SCREEN.equals(action)?"screen":"audio";
        startedAt=System.currentTimeMillis();
        appInForeground=MainActivity.isShowing()||PreviewActivity.isShowing();
        backgroundSince=appInForeground?0:SystemClock.elapsedRealtime();
        lens=intent.getStringExtra("lens")==null?"back":intent.getStringExtra("lens");
        generation++;
        try{
            startVisibleNotification(currentMode);
            if(getFilesDir().getUsableSpace()<64L*1024L*1024L)throw new IllegalStateException("Premalo slobodne interne memorije (manje od 64 MB)");
            File pending=new File(getFilesDir(),"capture_pending");pending.mkdirs();
            rawFile=new File(pending,"capture_"+startedAt+".tmp");
            handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,5000);
            if(CAMERA.equals(action))beginCamera(generation);
            else if(SCREEN.equals(action))beginScreen(intent);
            else beginAudio();
        }catch(Exception e){setError("Pokretanje snimanja nije uspjelo",e);finish();}
        return START_NOT_STICKY;
    }
    private void makeChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Aktivno snimanje",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Vidljivi status snimanja videa, ekrana ili zvuka");channel.setShowBadge(false);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private void startVisibleNotification(String mode){
        Intent launch=new Intent(this,MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String preferred=getSharedPreferences(NOTICE_PREFERENCES,MODE_PRIVATE).getString(NOTICE_LABEL,"Calculator");
        if(preferred==null||preferred.trim().isEmpty())preferred="Calculator";
        if(preferred.length()>36)preferred=preferred.substring(0,36);
        String status="audio".equals(mode)?"正在录音":"screen".equals(mode)?"正在录制屏幕":"正在录制视频";
        // Customizable app label, but never remove the truthful recording status.
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification notification=builder.setSmallIcon(R.drawable.ic_recording_status)
                .setContentTitle(preferred+" · "+status)
                .setContentText(status)
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=29){
            int type="screen".equals(mode)?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:
                    "audio".equals(mode)?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if("camera".equals(mode)&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFY_ID,notification,type);
        } else startForeground(NOTIFY_ID,notification);
        foregoundShown=true;
    }
    public static String getNoticeLabel(Context c){
        return c.getSharedPreferences(NOTICE_PREFERENCES,MODE_PRIVATE).getString(NOTICE_LABEL,"Calculator");
    }
    public static String saveNoticeLabel(Context c,String proposed){
        String label=proposed==null?"":proposed.trim();
        if(label.isEmpty()||label.length()>36||label.matches(".*[\\n\\r].*"))
            throw new IllegalArgumentException("Naziv mora imati 1–36 znakova i ne smije sadržavati novi red.");
        c.getSharedPreferences(NOTICE_PREFERENCES,MODE_PRIVATE).edit().putString(NOTICE_LABEL,label).apply();
        RecorderService running=instance;
        if(running!=null&&running.foregoundShown){
            running.handler.post(()->{
                try{if(running.foregoundShown)running.startVisibleNotification(currentMode);}
                catch(Exception e){Log.w(TAG,"Notification title update failed",e);}
            });
        }
        return label;
    }
    private void beginAudio() throws Exception {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Potrebna je dozvola za mikrofon.");
        // AAC-LC at 256 kbps / 48kHz. Save original .m4a bytes; never transcode.
        MediaRecorder rec=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
        rec.setAudioSource(MediaRecorder.AudioSource.MIC);
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        rec.setAudioEncodingBitRate(256000);
        rec.setAudioSamplingRate(48000);
        rec.setOutputFile(rawFile.getAbsolutePath());
        rec.setOnErrorListener((mr,what,extra)->{
            recorderHadError=true;
            setError("Diktafon je prijavio grešku ("+what+"/"+extra+")",null);
            handler.post(this::finish);
        });
        rec.prepare();recorder=rec;rec.start();
        active=true;preparing=false;startedAt=System.currentTimeMillis();
    }
    private MediaRecorder recorder(String source,int width,int height,int bitrate,int orientation,boolean withMic)throws Exception{
        MediaRecorder rec=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
        if(withMic)rec.setAudioSource(MediaRecorder.AudioSource.MIC);
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        rec.setVideoSize(width,height);rec.setVideoFrameRate(30);
        rec.setVideoEncodingBitRate(bitrate);
        rec.setOrientationHint(orientation);
        if(withMic){rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);rec.setAudioEncodingBitRate(192000);rec.setAudioSamplingRate(48000);}
        rec.setOutputFile(rawFile.getAbsolutePath());rec.prepare();
        rec.setOnErrorListener((mr,what,extra)->{
            recorderHadError=true;
            setError("Video enkoder je prijavio grešku (kod "+what+", dodatno "+extra+")",null);
            handler.post(this::finish);
        });
        return rec;
    }
    /** Only choose Camera2 resolutions the device's H.264 encoder reports supporting.
     *  This avoids offering 4K on devices where only the camera sensor can output 4K.
     *  No re-encoding is introduced; the highest compatible resolution is recorded.
     */
    private boolean h264EncoderSupports(int width,int height){
        try{
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                if(!info.isEncoder())continue;
                for(String mime:info.getSupportedTypes())if("video/avc".equalsIgnoreCase(mime)){
                    try{
                        MediaCodecInfo.VideoCapabilities video=info.getCapabilitiesForType(mime).getVideoCapabilities();
                        if(video!=null&&video.areSizeAndRateSupported(width,height,30.0))return true;
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception e){Log.w(TAG,"Cannot inspect encoder capabilities",e);}
        return false;
    }
    private int compatibleH264Bitrate(int width,int height,int requested){
        int supported=0;
        try{
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                if(!info.isEncoder())continue;
                for(String mime:info.getSupportedTypes())if("video/avc".equalsIgnoreCase(mime)){
                    try{
                        MediaCodecInfo.VideoCapabilities video=info.getCapabilitiesForType(mime).getVideoCapabilities();
                        if(video!=null&&video.areSizeAndRateSupported(width,height,30.0))
                            supported=Math.max(supported,video.getBitrateRange().getUpper());
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
        return supported>0?Math.max(1000000,Math.min(requested,supported)):requested;
    }
    private void beginCamera(final int g)throws Exception{
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Camera permission missing");
        CameraManager manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
        int facing="front".equals(lens)?CameraCharacteristics.LENS_FACING_FRONT:CameraCharacteristics.LENS_FACING_BACK;
        String id=null;CameraCharacteristics characteristics=null;
        for(String candidate:manager.getCameraIdList()){
            CameraCharacteristics chars=manager.getCameraCharacteristics(candidate);
            Integer direction=chars.get(CameraCharacteristics.LENS_FACING);
            if(direction!=null&&direction==facing){id=candidate;characteristics=chars;break;}
        }
        if(id==null||characteristics==null)throw new IllegalStateException("Tražena kamera nije dostupna.");
        StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes=map==null?null:map.getOutputSizes(MediaRecorder.class);
        if(sizes==null||sizes.length==0)throw new IllegalStateException("Kamera nema podržanu video rezoluciju.");
        Size best=null;long score=-1;
        // Prefer the maximum 16:9 supported resolution through 4K, otherwise largest supported size.
        for(Size sz:sizes){int w=sz.getWidth(),h=sz.getHeight();if(w>3840||h>2160||w<640||h<480)continue;
            if(!h264EncoderSupports(w,h))continue;
            long area=(long)w*h;long penalty=Math.abs(9L*w-16L*h);long sc=area*10000-penalty*30;
            if(sc>score){score=sc;best=sz;}}
        if(best==null)throw new IllegalStateException("Nema zajedničke rezolucije za kameru i H.264 video enkoder.");
        frameWidth=best.getWidth();frameHeight=best.getHeight();
        int sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)==null?0:characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int rotation=((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int degrees=new int[]{0,90,180,270}[rotation];
        int orientation=(sensor+(facing==CameraCharacteristics.LENS_FACING_FRONT?degrees:-degrees)+360)%360;
        int bitrate=compatibleH264Bitrate(frameWidth,frameHeight,
                frameWidth>=3000?48000000:(frameWidth>=1900?18000000:10000000));
        boolean mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        recorder=recorder("camera",frameWidth,frameHeight,bitrate,orientation,mic);
        manager.openCamera(id,new CameraDevice.StateCallback(){
            @Override public void onOpened(CameraDevice dev){
                if(!preparing||generation!=g){dev.close();return;}
                camera=dev;
                try{
                    Surface s=recorder.getSurface();
                    CaptureRequest.Builder request=dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    request.addTarget(s);request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
                    dev.createCaptureSession(Arrays.asList(s),new CameraCaptureSession.StateCallback(){
                        @Override public void onConfigured(CameraCaptureSession session){
                            if(!preparing||generation!=g){session.close();return;}
                            cameraSession=session;
                            try{session.setRepeatingRequest(request.build(),null,handler);recorder.start();active=true;preparing=false;startedAt=System.currentTimeMillis();}
                            catch(Exception e){setError("Kamera nije počela snimati",e);finish();}
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session){setError("Kamera nije uspjela podesiti sesiju",null);finish();}
                    },handler);
                }catch(Exception e){setError("Greška kamere",e);finish();}
            }
            @Override public void onDisconnected(CameraDevice dev){dev.close();finish();}
            @Override public void onError(CameraDevice dev,int err){dev.close();setError("Kamera je prijavila grešku "+err,null);finish();}
        },handler);
    }
    @SuppressWarnings("deprecation")
    private void beginScreen(Intent intent)throws Exception{
        int consentCode=intent.getIntExtra("consentCode",0);
        Intent consent=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("consent",Intent.class):intent.getParcelableExtra("consent");
        if(consent==null||consentCode!=android.app.Activity.RESULT_OK)throw new SecurityException("Screen capture consent missing");
        DisplayMetrics metrics=new DisplayMetrics();
        ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        frameWidth=(metrics.widthPixels/2)*2;frameHeight=(metrics.heightPixels/2)*2;
        // Keep the native display dimensions when the device's codec supports them.
        int max=Math.max(frameWidth,frameHeight);
        if(max>3840){double scale=3840.0/max;frameWidth=((int)(frameWidth*scale)/2)*2;frameHeight=((int)(frameHeight*scale)/2)*2;}
        int bitrate=frameWidth*frameHeight>4000000?35000000:18000000;
        recorder=recorder("screen",frameWidth,frameHeight,bitrate,0,false);
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection=manager.getMediaProjection(consentCode,consent);
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){handler.post(RecorderService.this::finish);}},handler);
        virtualDisplay=projection.createVirtualDisplay("Private capture",frameWidth,frameHeight,metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,handler);
        recorder.start();active=true;preparing=false;startedAt=System.currentTimeMillis();
    }
    /** Keep the UI/main looper responsive while MediaRecorder finalizes a large MP4.
     * The foreground service remains alive until encryption and index save finish.
     */
    private void finish(){
        if(finishing)return;
        finishing=true;generation++;
        handler.removeCallbacks(watchdog);
        final boolean wasRecording=active;
        active=false;preparing=false;
        final long when=startedAt;
        final String type=currentMode;
        final String usedLens=lens;
        final int w=frameWidth,h=frameHeight;
        final File pending=rawFile;rawFile=null;
        final MediaRecorder sessionRecorder=recorder;recorder=null;
        final CameraCaptureSession session=cameraSession;cameraSession=null;
        final CameraDevice device=camera;camera=null;
        final VirtualDisplay display=virtualDisplay;virtualDisplay=null;
        final MediaProjection previousProjection=projection;projection=null;
        try{if(session!=null)session.stopRepeating();}catch(Exception ignored){}
        io.execute(()->{
            boolean valid=wasRecording;
            try{
                // MediaRecorder.stop can block while flushing MP4. Never do this
                // on the main looper: it previously froze Stop and other buttons.
                if(sessionRecorder!=null){
                    try{if(wasRecording)sessionRecorder.stop();}
                    catch(RuntimeException e){valid=false;setError("Snimak nije moguće završiti; snimaj barem 5 sekundi",e);}
                    try{sessionRecorder.reset();sessionRecorder.release();}catch(Exception ignored){}
                }
                // Close camera/projection resources after finalization.
                handler.post(()->{
                    if(session!=null){try{session.close();}catch(Exception ignored){}}
                    if(device!=null){try{device.close();}catch(Exception ignored){}}
                    if(display!=null){try{display.release();}catch(Exception ignored){}}
                    if(previousProjection!=null){try{previousProjection.stop();}catch(Exception ignored){}}
                });
                final long sec=Math.max(1,(System.currentTimeMillis()-when)/1000);
                final long bytes=(pending!=null&&pending.exists())?pending.length():0;
                getSharedPreferences(PREFS,MODE_PRIVATE).edit()
                    .putLong("lastAttemptAt",System.currentTimeMillis())
                    .putLong("lastRawBytes",bytes).apply();
                if(pending!=null&&pending.exists()&&bytes>1024&&valid){
                    try{
                        MediaMetadataRetriever probe=new MediaMetadataRetriever();
                        try{
                            probe.setDataSource(pending.getAbsolutePath());
                            if("audio".equals(type)){
                                if(!"yes".equalsIgnoreCase(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))
                                    throw new IllegalStateException("M4A nema čitljiv audio zapis");
                            }else if(!"yes".equalsIgnoreCase(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))
                                throw new IllegalStateException("MP4 nema čitljiv video zapis");
                        }finally{probe.release();}
                        JSONObject record=new RecorderStore(this).encryptRecording(pending,type,usedLens,when,sec,w,h);
                        saveSuccess(record);
                        if(!pending.delete())Log.w(TAG,"Saved MP4 but raw copy could not be deleted");
                    }catch(Exception e){setError("Snimak nije spremljen u sef; izvorna datoteka je sačuvana",e);}
                }else if(getSharedPreferences(PREFS,MODE_PRIVATE).getString("lastError","").isEmpty()){
                    setError("Snimanje nije proizvelo ispravan zapis; privremeni fajl je sačuvan",null);
                }
            }finally{
                handler.post(()->{stopForegroundSafe();finishing=false;stopSelf();});
            }
        });
    }
    private void stopForegroundSafe(){if(foregoundShown){if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);foregoundShown=false;}}
    @Override public void onTaskRemoved(Intent rootIntent){
        // Swiping the task away counts as leaving the app, not as an immediate stop.
        appVisible(false);
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy(){
        // Android force-stop / process termination is not recoverable. Avoid promising otherwise.
        if(active||preparing)finish();
        handler.removeCallbacks(watchdog);instance=null;io.shutdown();super.onDestroy();
    }
}
