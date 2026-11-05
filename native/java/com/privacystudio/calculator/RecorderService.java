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

/** User-initiated foreground recorder. Stops 20 minutes after the last UI interaction. */
public class RecorderService extends Service {
    public static final String CAMERA="com.privacystudio.calculator.START_CAMERA";
    public static final String SCREEN="com.privacystudio.calculator.START_SCREEN";
    public static final String STOP="com.privacystudio.calculator.STOP";
    private static final String CHANNEL="camera_capture_active";
    private static final String TAG="VideoRecorder";
    private static final String PREFS="video_recorder_state_v4";
    private static final int NOTIFY_ID=2401;
    private static final long IDLE_MS=20L*60L*1000L;
    private static volatile RecorderService instance;
    private static volatile boolean active=false;
    private static volatile boolean preparing=false;
    private static volatile long startedAt=0;
    private static volatile String currentMode="camera";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile long lastInteraction=0;
    private MediaRecorder recorder;
    private CameraDevice camera;
    private CameraCaptureSession cameraSession;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private File rawFile;
    private String lens="back";
    private int frameWidth=0,frameHeight=0;
    private boolean finishing=false;
    private boolean foregoundShown=false;
    private int generation=0;
    private final Runnable watchdog=new Runnable(){@Override public void run(){
        if((active||preparing)&&lastInteraction>0 && SystemClock.elapsedRealtime()-lastInteraction>=IDLE_MS){
            Log.i(TAG,"Stop: 20 min inactivity");finish();
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
            File dir=new File(c.getFilesDir(),"capture_pending");
            File[] files=dir.listFiles((d,name)->name.endsWith(".tmp"));
            o.put("unprocessedFiles",files==null?0:files.length);
            if(instance!=null)o.put("idleSecondsLeft",Math.max(0,(IDLE_MS-(SystemClock.elapsedRealtime()-instance.lastInteraction))/1000));
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
    public static void userInteracted(){RecorderService s=instance;if(s!=null)s.lastInteraction=SystemClock.elapsedRealtime();}
    public static void appVisible(boolean visible){RecorderService s=instance;if(s!=null&&visible)s.lastInteraction=SystemClock.elapsedRealtime();}
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
        if(!CAMERA.equals(action)&&!SCREEN.equals(action))return START_NOT_STICKY;
        if(active||preparing||finishing)return START_NOT_STICKY;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("lastError","").apply();
        preparing=true;currentMode=CAMERA.equals(action)?"camera":"screen";
        startedAt=System.currentTimeMillis();lastInteraction=SystemClock.elapsedRealtime();
        lens=intent.getStringExtra("lens")==null?"back":intent.getStringExtra("lens");
        generation++;
        try{
            startVisibleNotification(SCREEN.equals(action));
            File pending=new File(getFilesDir(),"capture_pending");pending.mkdirs();
            rawFile=new File(pending,"capture_"+startedAt+".tmp");
            handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,5000);
            if(CAMERA.equals(action))beginCamera(generation);
            else beginScreen(intent);
        }catch(Exception e){setError("Pokretanje snimanja nije uspjelo",e);finish();}
        return START_NOT_STICKY;
    }
    private void makeChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"正在录制视频",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("正在录制视频");channel.setShowBadge(false);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private void startVisibleNotification(boolean screen){
        Intent launch=new Intent(this,MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification notification=builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("正在录制视频")
                .setContentText(screen?"正在录制屏幕":"正在录制视频")
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=29){
            int type=screen?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if(!screen && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFY_ID,notification,type);
        } else startForeground(NOTIFY_ID,notification);
        foregoundShown=true;
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
        rec.setOnErrorListener((mr,what,extra)->{Log.e(TAG,"MediaRecorder error "+what+" / "+extra);handler.post(this::finish);});
        return rec;
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
            long area=(long)w*h;long penalty=Math.abs(9L*w-16L*h);long sc=area*10000-penalty*30;
            if(sc>score){score=sc;best=sz;}}
        if(best==null)best=sizes[0];
        frameWidth=best.getWidth();frameHeight=best.getHeight();
        int sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)==null?0:characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int rotation=((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int degrees=new int[]{0,90,180,270}[rotation];
        int orientation=(sensor+(facing==CameraCharacteristics.LENS_FACING_FRONT?degrees:-degrees)+360)%360;
        int bitrate=(frameWidth>=3000?48000000:(frameWidth>=1900?18000000:10000000));
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
    private void finish(){
        if(finishing)return;
        finishing=true;generation++;
        handler.removeCallbacks(watchdog);
        boolean hadVideo=active;active=false;preparing=false;
        final long when=startedAt;
        final String type=currentMode;
        final String usedLens=lens;
        final int w=frameWidth,h=frameHeight;
        File pending=rawFile;rawFile=null;
        if(cameraSession!=null){try{cameraSession.stopRepeating();}catch(Exception ignored){}}
        // Finalize MP4 while camera/display surfaces are still available.
        if(recorder!=null){
            try{if(hadVideo)recorder.stop();}
            catch(RuntimeException e){hadVideo=false;setError("MP4 nije moguće završiti; pokušaj snimati barem 5 sekundi",e);}
            try{recorder.reset();recorder.release();}catch(Exception ignored){}recorder=null;
        }
        if(cameraSession!=null){try{cameraSession.close();}catch(Exception ignored){}cameraSession=null;}
        if(camera!=null){try{camera.close();}catch(Exception ignored){}camera=null;}
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        MediaProjection previousProjection=projection;projection=null;
        if(previousProjection!=null){try{previousProjection.stop();}catch(Exception ignored){}}
        final boolean valid=hadVideo;final long sec=Math.max(1,(System.currentTimeMillis()-when)/1000);
        if(pending!=null&&pending.exists()&&pending.length()>1024&&valid){
            io.execute(()->{
                try{
                    JSONObject record=new RecorderStore(this).encryptRecording(pending,type,usedLens,when,sec,w,h);
                    saveSuccess(record);
                    if(!pending.delete())Log.w(TAG,"Saved MP4 but raw file could not be deleted");
                }catch(Exception e){
                    // Do not destroy a valid raw MP4 when index/encryption fails.
                    setError("Šifriranje ili spremanje nije uspjelo; original je sačuvan za oporavak",e);
                }finally{handler.post(()->{stopForegroundSafe();finishing=false;stopSelf();});}
            });
        }else{
            if(getSharedPreferences(PREFS,MODE_PRIVATE).getString("lastError","").isEmpty())
                setError("Snimanje nije proizvelo ispravan MP4. Provjeri dozvole i probaj 10 sekundi.",null);
            // Keep any raw output for diagnosis / potential recovery.
            stopForegroundSafe();finishing=false;stopSelf();
        }
    }
    private void stopForegroundSafe(){if(foregoundShown){if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);foregoundShown=false;}}
    @Override public void onTaskRemoved(Intent rootIntent){
        // Task swipe does not stop a running foreground capture; app return resets the inactivity timer.
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy(){
        // Android force-stop / process termination is not recoverable. Avoid promising otherwise.
        if(active||preparing)finish();
        handler.removeCallbacks(watchdog);instance=null;io.shutdown();super.onDestroy();
    }
}
