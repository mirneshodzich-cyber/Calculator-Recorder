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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.UUID;

@CapacitorPlugin(name="SecureRecorder",permissions={
        @Permission(alias="capture",strings={Manifest.permission.CAMERA}),
        @Permission(alias="audio",strings={Manifest.permission.RECORD_AUDIO}),
        @Permission(alias="notifications",strings={Manifest.permission.POST_NOTIFICATIONS})
})
public class SecureRecorderPlugin extends Plugin {
    private final ExecutorService background=Executors.newSingleThreadExecutor();
    // A transfer never queues behind metadata recovery/other maintenance work.
    private final ExecutorService transferWorker=Executors.newSingleThreadExecutor();
    // Metadata polling must not queue behind a large decrypt/export.
    private final ExecutorService metadata=Executors.newSingleThreadExecutor();
    private final AtomicReference<TransferJob> runningTransfer=new AtomicReference<>(null);

    private static final class TransferJob implements RecorderStore.TransferProgress {
        final String token=UUID.randomUUID().toString();
        final String id;
        final String kind;
        final AtomicBoolean cancelRequested=new AtomicBoolean(false);
        volatile long bytes=0;
        volatile long total=0;
        volatile String phase="Priprema...";
        volatile Thread workerThread;
        volatile long lastProgressAt=System.currentTimeMillis();
        TransferJob(String kind,String id){this.kind=kind;this.id=id;}
        void checkCancelled()throws InterruptedIOException {
            if(cancelRequested.get()||Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Operacija je prekinuta.");
        }
        void requestCancel(){
            cancelRequested.set(true);
            phase="Prekidam obradu i uklanjam nepotpunu kopiju...";
            Thread worker=workerThread;
            if(worker!=null)worker.interrupt();
        }
        @Override public void onProgress(long current,long size)throws IOException {
            checkCancelled();
            bytes=Math.max(0L,current);
            total=Math.max(1L,size);
            if(!cancelRequested.get())phase="Dešifriranje i provjera video zapisa...";
            lastProgressAt=System.currentTimeMillis();
        }
        JSObject status() throws org.json.JSONException {
            JSObject out=new JSObject();
            out.put("active",true);
            out.put("token",token);
            out.put("kind",kind);
            out.put("id",id);
            out.put("bytes",bytes);
            out.put("total",total);
            // Do not advertise 100% until AES-GCM authentication and publishing finish.
            int percent=total>0?(int)Math.min(99L,(bytes*100L)/total):0;
            out.put("percent",percent);
            out.put("phase",phase);
            out.put("lastProgressAt",lastProgressAt);
            out.put("cancelRequested",cancelRequested.get());
            return out;
        }
    }
    private TransferJob reserveTransfer(PluginCall call,String kind,String id){
        TransferJob job=new TransferJob(kind,id);
        if(!runningTransfer.compareAndSet(null,job)){
            call.reject("Drugi video se već otvara ili izvozi. Pričekaj završetak ili prekini trenutnu operaciju.");
            return null;
        }
        return job;
    }
    @PluginMethod public void transferStatus(PluginCall call){
        try {
            TransferJob job=runningTransfer.get();
            if(job==null){JSObject out=new JSObject();out.put("active",false);call.resolve(out);}
            else call.resolve(job.status());
        }catch(org.json.JSONException e){call.reject("Ne mogu očitati napredak: "+e.getMessage());}
    }
    @PluginMethod public void cancelTransfer(PluginCall call){
        TransferJob job=runningTransfer.get();
        JSObject out=new JSObject();
        if(job==null){out.put("cancelRequested",false);out.put("active",false);}
        else {job.requestCancel();out.put("cancelRequested",true);out.put("active",true);}
        call.resolve(out);
    }
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
        metadata.execute(()->{try{JSObject out=new JSObject();out.put("files",new RecorderStore(getContext()).list());call.resolve(out);}catch(Exception e){call.reject("Ne mogu učitati sef: "+e.getMessage());}});
    }
    @PluginMethod public void preview(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID.");return;}
        background.execute(()->{try{
            File file=new RecorderStore(getContext()).preview(id);
            JSObject out=new JSObject();out.put("path","file://"+file.getAbsolutePath());call.resolve(out);
        }catch(Exception e){call.reject("Ne mogu dešifrirati snimak: "+e.getMessage());}});
    }
    /** Decrypt in a worker; only open native player after verified, cancellable preparation. */
    @PluginMethod public void play(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID snimka.");return;}
        TransferJob job=reserveTransfer(call,"play",id);
        if(job==null)return;
        transferWorker.execute(()->{
            job.workerThread=Thread.currentThread();
            File f=null;
            try{
                job.checkCancelled();
                job.phase="Pripremam privatni pregled...";
                f=new RecorderStore(getContext()).preview(id,job);
                job.checkCancelled();
                job.phase="Otvaram video-player...";
                Activity host=getActivity();
                if(host==null||host.isFinishing())throw new IOException("Aplikacija mora biti otvorena za reprodukciju.");
                final File ready=f;
                host.runOnUiThread(()->{
                    try{
                        job.checkCancelled();
                        Intent view=new Intent(host,PreviewActivity.class);
                        view.putExtra("previewPath",ready.getAbsolutePath());
                        host.startActivity(view);
                        call.resolve();
                    }catch(Exception e){ready.delete();call.reject("Pregled nije uspio: "+e.getMessage());}
                    finally{runningTransfer.compareAndSet(job,null);}
                });
            }catch(Exception e){
                if(f!=null)f.delete();
                call.reject(job.cancelRequested.get()?"Otvaranje snimka je prekinuto.":"Privatni pregled nije uspio: "+e.getMessage());
                runningTransfer.compareAndSet(job,null);
            }
        });
    }
    @PluginMethod public void purgePreview(PluginCall call){background.execute(()->{new RecorderStore(getContext()).purgePreviews();call.resolve();});}
    @PluginMethod public void export(PluginCall call){
        String id=call.getString("id");if(id==null){call.reject("Nedostaje ID.");return;}
        TransferJob job=reserveTransfer(call,"export",id);
        if(job==null)return;
        transferWorker.execute(()->{
            job.workerThread=Thread.currentThread();
            try{
                job.checkCancelled();
                job.phase="Pripremam izvoz u galeriju...";
                String name=new RecorderStore(getContext()).export(id,job);
                JSObject out=new JSObject();out.put("name",name);call.resolve(out);
            }catch(Exception e){
                call.reject(job.cancelRequested.get()?"Izvoz je prekinut; nepotpuna kopija je uklonjena.":"Izvoz nije uspio: "+e.getMessage());
            }finally{runningTransfer.compareAndSet(job,null);}
        });
    }
    @PluginMethod public void recover(PluginCall call){
        if(runningTransfer.get()!=null){call.reject("Pričekaj završetak izvoza/pregleda.");return;}
        if(RecorderService.busy()){call.reject("Pričekaj dok snimanje i spremanje ne završe.");return;}
        background.execute(()->{
            try{call.resolve(JSObject.fromJSONObject(new RecorderStore(getContext()).recoverPending()));}
            catch(Exception e){call.reject("Obnova snimaka nije uspjela: "+e.getMessage());}
        });
    }
    @PluginMethod public void diagnostics(PluginCall call){
        metadata.execute(()->{
            try{
                JSObject out=JSObject.fromJSONObject(new RecorderStore(getContext()).diagnostics());
                out.put("lastError",RecorderService.status(getContext()).optString("lastError",""));
                out.put("lastRawBytes",RecorderService.status(getContext()).optLong("lastRawBytes",0));
                call.resolve(out);
            }catch(Exception e){call.reject("Dijagnostika sefa nije uspjela: "+e.getMessage());}
        });
    }
    @PluginMethod public void deleteOne(PluginCall call){
        String id=call.getString("id");
        if(id==null){call.reject("Nedostaje ID snimka.");return;}
        if(runningTransfer.get()!=null){call.reject("Prvo završi ili prekini aktivni pregled/izvoz.");return;}
        if(RecorderService.busy()){call.reject("Sačekaj da snimanje i spremanje završe.");return;}
        background.execute(()->{
            try{new RecorderStore(getContext()).deleteOne(id);call.resolve();}
            catch(Exception e){call.reject("Brisanje nije uspjelo: "+e.getMessage());}
        });
    }
    @PluginMethod public void deleteAll(PluginCall call){
        if(runningTransfer.get()!=null){call.reject("Ne možeš brisati snimke tokom izvoza ili pregleda.");return;}
        if(RecorderService.busy()){call.reject("Zaustavi snimanje prije brisanja.");return;}
        background.execute(()->{try{new RecorderStore(getContext()).deleteAll();call.resolve();}catch(Exception e){call.reject(e.getMessage());}});
    }
    @Override protected void handleOnDestroy(){
        TransferJob job=runningTransfer.get();if(job!=null)job.requestCancel();
        transferWorker.shutdown();background.shutdown();metadata.shutdown();super.handleOnDestroy();
    }
}
