package com.privacystudio.calculator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.media.projection.MediaProjectionManager;
import android.os.Build;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name="SecureRecorder",permissions={
        @Permission(alias="capture",strings={Manifest.permission.CAMERA}),
        @Permission(alias="audio",strings={Manifest.permission.RECORD_AUDIO}),
        @Permission(alias="notifications",strings={Manifest.permission.POST_NOTIFICATIONS})
})
public class SecureRecorderPlugin extends Plugin {
    private final ExecutorService background=Executors.newSingleThreadExecutor();
    @PluginMethod public void status(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(RecorderService.status(getContext())));
        } catch (org.json.JSONException e) {
            call.reject("Ne mogu očitati status snimanja: " + e.getMessage());
        }
    }
    @PluginMethod public void touch(PluginCall call){RecorderService.userInteracted();call.resolve();}

    @PluginMethod public void startCamera(PluginCall call){
        if(RecorderService.busy()){call.reject("Snimanje je već aktivno.");return;}
        if(getPermissionState("capture")!=PermissionState.GRANTED){requestPermissionForAlias("capture",call,"capturePermissionResult");return;}
        requestOptionalMicThenCamera(call);
    }
    @PermissionCallback private void capturePermissionResult(PluginCall call){
        if(getPermissionState("capture")!=PermissionState.GRANTED){call.reject("Potrebna je dozvola za kameru. Mikrofon je opcionalan.");return;}
        requestOptionalMicThenCamera(call);
    }
    private void requestOptionalMicThenCamera(PluginCall call){
        if(getPermissionState("audio")!=PermissionState.GRANTED){
            // If the user denies microphone, video can still record without audio.
            requestPermissionForAlias("audio",call,"audioPermissionResult");return;
        }
        requestNotificationThenCamera(call);
    }
    @PermissionCallback private void audioPermissionResult(PluginCall call){
        requestNotificationThenCamera(call);
    }
    private void requestNotificationThenCamera(PluginCall call){
        if(Build.VERSION.SDK_INT>=33&&getPermissionState("notifications")!=PermissionState.GRANTED){requestPermissionForAlias("notifications",call,"notificationCameraResult");return;}
        launchCamera(call);
    }
    @PermissionCallback private void notificationCameraResult(PluginCall call){
        if(getPermissionState("notifications")!=PermissionState.GRANTED){call.reject("Dopusti obavijest o aktivnom snimanju.");return;}
        launchCamera(call);
    }
    private void launchCamera(PluginCall call){
        String lens=call.getString("lens","back");
        if(!lens.equals("front")&&!lens.equals("back")){call.reject("Nepoznata kamera.");return;}
        Intent i=new Intent(getContext(),RecorderService.class).setAction(RecorderService.CAMERA).putExtra("lens",lens);
        try{if(Build.VERSION.SDK_INT>=26)getContext().startForegroundService(i);else getContext().startService(i);call.resolve();}
        catch(Exception e){call.reject("Ne mogu pokrenuti kameru: "+e.getMessage());}
    }
    @PluginMethod public void startScreen(PluginCall call){
        if(RecorderService.busy()){call.reject("Snimanje je već aktivno.");return;}
        if(Build.VERSION.SDK_INT>=33&&getPermissionState("notifications")!=PermissionState.GRANTED){requestPermissionForAlias("notifications",call,"notificationScreenResult");return;}
        requestProjection(call);
    }
    @PermissionCallback private void notificationScreenResult(PluginCall call){
        if(getPermissionState("notifications")!=PermissionState.GRANTED){call.reject("Dopusti obavijest o aktivnom snimanju.");return;}
        requestProjection(call);
    }
    private void requestProjection(PluginCall call){
        try{
            MediaProjectionManager mp=(MediaProjectionManager)getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(call,mp.createScreenCaptureIntent(),"onScreenConsent");
        }catch(Exception e){call.reject("Ne mogu tražiti dozvolu za ekran: "+e.getMessage());}
    }
    @ActivityCallback private void onScreenConsent(PluginCall call,ActivityResult result){
        if(result.getResultCode()!=Activity.RESULT_OK||result.getData()==null){call.reject("Nije odobreno snimanje ekrana.");return;}
        Intent i=new Intent(getContext(),RecorderService.class).setAction(RecorderService.SCREEN);
        i.putExtra("consentCode",result.getResultCode());i.putExtra("consent",result.getData());
        try{if(Build.VERSION.SDK_INT>=26)getContext().startForegroundService(i);else getContext().startService(i);call.resolve();}
        catch(Exception e){call.reject("Ne mogu pokrenuti snimanje ekrana: "+e.getMessage());}
    }
    @PluginMethod public void stop(PluginCall call){
        if(!RecorderService.busy()){call.resolve();return;}
        Intent stop=new Intent(getContext(),RecorderService.class).setAction(RecorderService.STOP);
        try{getContext().startService(stop);call.resolve();}catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void list(PluginCall call){
        background.execute(()->{try{JSObject out=new JSObject();out.put("files",new RecorderStore(getContext()).list());call.resolve(out);}catch(Exception e){call.reject("Ne mogu učitati sef: "+e.getMessage());}});
    }
    @PluginMethod public void preview(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID.");return;}
        background.execute(()->{try{
            File file=new RecorderStore(getContext()).preview(id);
            JSObject out=new JSObject();out.put("path","file://"+file.getAbsolutePath());call.resolve(out);
        }catch(Exception e){call.reject("Ne mogu dešifrirati snimak: "+e.getMessage());}});
    }
    /** Native player avoids WebView file:// and localhost URI restrictions on newer Android. */
    @PluginMethod public void play(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID snimka.");return;}
        background.execute(()->{
            try{
                File f=new RecorderStore(getContext()).preview(id);
                Activity host=getActivity();
                if(host==null||host.isFinishing()){
                    f.delete();call.reject("Aplikacija mora biti otvorena za reprodukciju.");return;
                }
                host.runOnUiThread(()->{
                    try{
                        Intent view=new Intent(host,PreviewActivity.class);
                        view.putExtra("previewPath",f.getAbsolutePath());
                        host.startActivity(view);
                        call.resolve();
                    }catch(Exception e){f.delete();call.reject("Ne mogu otvoriti video-player: "+e.getMessage());}
                });
            }catch(Exception e){call.reject("Privatni pregled nije uspio: "+e.getMessage());}
        });
    }
    @PluginMethod public void purgePreview(PluginCall call){background.execute(()->{new RecorderStore(getContext()).purgePreviews();call.resolve();});}
    @PluginMethod public void export(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID.");return;}
        background.execute(()->{try{
            String name=new RecorderStore(getContext()).export(id);
            JSObject out=new JSObject();out.put("name",name);call.resolve(out);
        }catch(Exception e){call.reject("Izvoz nije uspio: "+e.getMessage());}});
    }
    @PluginMethod public void recover(PluginCall call){
        if(RecorderService.busy()){call.reject("Pričekaj dok snimanje i spremanje ne završe.");return;}
        background.execute(()->{
            try{call.resolve(JSObject.fromJSONObject(new RecorderStore(getContext()).recoverPending()));}
            catch(Exception e){call.reject("Obnova snimaka nije uspjela: "+e.getMessage());}
        });
    }
    @PluginMethod public void diagnostics(PluginCall call){
        background.execute(()->{
            try{
                JSObject out=JSObject.fromJSONObject(new RecorderStore(getContext()).diagnostics());
                out.put("lastError",RecorderService.status(getContext()).optString("lastError",""));
                out.put("lastRawBytes",RecorderService.status(getContext()).optLong("lastRawBytes",0));
                call.resolve(out);
            }catch(Exception e){call.reject("Dijagnostika sefa nije uspjela: "+e.getMessage());}
        });
    }
    @PluginMethod public void deleteAll(PluginCall call){
        if(RecorderService.busy()){call.reject("Zaustavi snimanje prije brisanja.");return;}
        background.execute(()->{try{new RecorderStore(getContext()).deleteAll();call.resolve();}catch(Exception e){call.reject(e.getMessage());}});
    }
    @Override protected void handleOnDestroy(){background.shutdown();super.handleOnDestroy();}
}
