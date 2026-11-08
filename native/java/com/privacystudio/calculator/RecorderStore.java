package com.privacystudio.calculator;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.security.KeyStore;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-256-GCM encrypted MP4 storage. No media scanner calls for vault files. */
public final class RecorderStore {
    // Every RecorderStore instance must serialize index updates on the same lock.
    private static final Object INDEX_LOCK = new Object();
    private static final String KEY_NAME = "private_video_vault_key_v1";
    private static final byte[] MAGIC = new byte[]{'S','V','R','1'};
    private final Context context;
    private final File vault;
    private final File previews;
    private final File index;
    public RecorderStore(Context c) {
        context=c.getApplicationContext();
        vault=new File(context.getFilesDir(),"encrypted_vault");
        previews=new File(context.getCacheDir(),"private_preview");
        index=new File(vault,"records.json");
        vault.mkdirs();previews.mkdirs();
    }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(KEY_NAME))return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_NAME,null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_NAME,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }
    private static void copy(InputStream in,OutputStream out)throws Exception {
        byte[] b=new byte[1024*256];int n;
        while((n=in.read(b))!=-1)out.write(b,0,n);
        out.flush();
    }
    private synchronized JSONArray readIndex()throws Exception {
        if(!index.exists())return new JSONArray();
        byte[] all;
        try(InputStream in=new FileInputStream(index)){
            all=new byte[(int)index.length()];int o=0,r;
            while(o<all.length&&(r=in.read(all,o,all.length-o))>0)o+=r;
            if(o!=all.length)throw new java.io.IOException("Nepotpuno pročitan indeks snimaka");
        }
        try{return new JSONArray(new String(all,java.nio.charset.StandardCharsets.UTF_8));}
        catch(JSONException e){
            // Preserve the damaged index as a separate file, then recover .svr entries.
            File backup=new File(vault,"records_damaged_"+System.currentTimeMillis()+".json");
            if(!index.renameTo(backup))throw new java.io.IOException("Oštećen indeks; ne mogu sačuvati njegovu rezervnu kopiju",e);
            android.util.Log.e("RecorderStore","Damaged metadata saved to "+backup.getName(),e);
            return new JSONArray();
        }
    }
    private synchronized void writeIndex(JSONArray arr)throws Exception {
        File tmp=new File(vault,"records.tmp");
        try(FileOutputStream os=new FileOutputStream(tmp)){
            os.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));os.getFD().sync();
        }
        if(!tmp.renameTo(index)){
            try(InputStream in=new FileInputStream(tmp);OutputStream out=new FileOutputStream(index)){copy(in,out);}
            tmp.delete();
        }
    }
    /** Safely expose encrypted .svr files even if a previous index write was interrupted. */
    private JSONArray repairIndex()throws Exception {
        synchronized(INDEX_LOCK){
            JSONArray found=readIndex();
            Set<String> ids=new HashSet<>();
            for(int i=0;i<found.length();i++) ids.add(found.getJSONObject(i).optString("id"));
            File[] encrypted=vault.listFiles((dir,name)->name.endsWith(".svr"));
            boolean changed=false;
            if(encrypted!=null) for(File file:encrypted){
                String id=file.getName().substring(0,file.getName().length()-4);
                if(ids.contains(id)) continue;
                JSONObject record=new JSONObject();
                record.put("id",id);record.put("name","RECOVERED_"+file.lastModified()+".mp4");
                record.put("type","recovered");record.put("lens","unknown");
                record.put("durationSec",0);record.put("createdAt",file.lastModified());
                record.put("width",0);record.put("height",0);
                record.put("sizeBytes",Math.max(0L,file.length()-32L));
                found.put(record);ids.add(id);changed=true;
            }
            if(changed)writeIndex(found);
            return found;
        }
    }
    public JSONArray list()throws Exception{return repairIndex();}
    public synchronized JSONObject find(String id)throws Exception {
        JSONArray records=repairIndex();
        for(int i=0;i<records.length();i++){
            JSONObject f=records.getJSONObject(i);
            if(f.optString("id").equals(id))return f;
        }
        throw new IllegalArgumentException("Nepoznat snimak.");
    }
    private File encryptedFile(String id){return new File(vault,id+".svr");}
    /** Write ciphertext first; only publish metadata after final GCM auth tag is saved. */
    public synchronized JSONObject encryptRecording(File raw,String type,String lens,long createdAt,long duration,int width,int height)throws Exception {
        String id=UUID.randomUUID().toString();
        File destination=encryptedFile(id);
        File temp=new File(vault,id+".incomplete");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore forbids caller-supplied IVs during ENCRYPT_MODE.
        // Let the provider generate a fresh IV, then persist it in the SVR1 header.
        cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV();
        if(iv==null||iv.length!=12)
            throw new java.security.GeneralSecurityException("AndroidKeyStore nije vratio 12-bajtni GCM IV");
        try(OutputStream outer=new BufferedOutputStream(new FileOutputStream(temp));InputStream in=new BufferedInputStream(new FileInputStream(raw))){
            outer.write(MAGIC);outer.write(iv);
            try(CipherOutputStream encrypted=new CipherOutputStream(outer,cipher)){copy(in,encrypted);}
        }catch(Exception e){temp.delete();throw e;}
        String name=("screen".equals(type)?"SCREEN_":"CAM_")+new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.ROOT).format(new java.util.Date(createdAt))+".mp4";
        JSONObject record=new JSONObject();record.put("id",id);record.put("name",name);record.put("type",type);record.put("lens",lens);record.put("durationSec",duration);record.put("createdAt",createdAt);record.put("width",width);record.put("height",height);record.put("sizeBytes",raw.length());
        synchronized(INDEX_LOCK){
            if(!temp.renameTo(destination)){temp.delete();throw new IllegalStateException("Nije moguće spremiti šifrirani zapis.");}
            JSONArray arr=readIndex();JSONArray next=new JSONArray();next.put(record);
            for(int i=0;i<arr.length();i++)next.put(arr.get(i));
            writeIndex(next);
        }
        return record;
    }
    private void decryptTo(String id,OutputStream out)throws Exception {
        find(id);File input=encryptedFile(id);
        if(!input.exists())throw new IllegalArgumentException("Šifrirani snimak ne postoji.");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(input)))){
            byte[] magic=new byte[4];in.readFully(magic);
            if(!java.util.Arrays.equals(magic,MAGIC))throw new IllegalArgumentException("Neispravan format datoteke.");
            byte[] iv=new byte[12];in.readFully(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            try(CipherInputStream plain=new CipherInputStream(in,cipher)){copy(plain,out);}
        }
    }
    public synchronized File preview(String id)throws Exception {
        purgePreviews();
        File file=new File(previews,"view_"+id+".mp4");
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){decryptTo(id,out);}
        catch(Exception e){file.delete();throw e;}
        return file;
    }
    public synchronized void purgePreviews(){File[] files=previews.listFiles();if(files!=null)for(File f:files)f.delete();}
    /** Stream the original decoded bytes into MediaStore; no re-encoding or transcoding. */
    public synchronized String export(String id)throws Exception {
        JSONObject r=find(id);String name=r.getString("name");
        if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException("Izvoz preko MediaStore zahtijeva Android 10+.");
        ContentValues meta=new ContentValues();meta.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        meta.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
        meta.put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Private Video Exports/");
        meta.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,meta);
        if(uri==null)throw new IllegalStateException("Nije moguće kreirati datoteku u galeriji.");
        try{
            try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IllegalStateException("Nema izlaznog toka za izvoz.");
                decryptTo(id,out);
            }
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            context.getContentResolver().update(uri,ready,null,null);
            return name;
        }catch(Exception e){context.getContentResolver().delete(uri,null,null);throw e;}
    }
    /** Recover finalized raw MP4 files left after previous encryption/index failures.
     * This is deliberately explicit, not invoked during every background poll. */
    public JSONObject recoverPending()throws Exception {
        File pending=new File(context.getFilesDir(),"capture_pending");
        File[] candidates=pending.listFiles((dir,name)->name.startsWith("capture_")&&name.endsWith(".tmp"));
        int recovered=0,invalid=0;
        if(candidates!=null)for(File file:candidates){
            if(!file.isFile()||file.length()<1024){invalid++;continue;}
            MediaMetadataRetriever reader=new MediaMetadataRetriever();
            try{
                reader.setDataSource(file.getAbsolutePath());
                String video=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
                if(!"yes".equalsIgnoreCase(video)){invalid++;continue;}
                String duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                long ms=duration==null?0:Long.parseLong(duration);
                int w=width==null?0:Integer.parseInt(width);
                int h=height==null?0:Integer.parseInt(height);
                long createdAt=file.lastModified();
                try {createdAt=Long.parseLong(file.getName().replace("capture_", "").replace(".tmp", ""));}
                catch(NumberFormatException ignored){}
                // Metadata isn't available for older orphan files; mark origin unknown.
                encryptRecording(file,"recovered","unknown",createdAt,Math.max(1,ms/1000),w,h);
                if(!file.delete())android.util.Log.w("RecorderStore","Could not delete recovered raw video");
                recovered++;
            }catch(Exception e){
                invalid++;android.util.Log.e("RecorderStore","Pending video recovery failed: "+file.getName(),e);
            }finally{reader.release();}
        }
        JSONObject result=new JSONObject();result.put("recovered",recovered);result.put("remaining",invalid);
        return result;
    }
    public JSONObject diagnostics()throws Exception {
        JSONObject out=new JSONObject();
        File pending=new File(context.getFilesDir(),"capture_pending");
        File[] raw=pending.listFiles((d,n)->n.startsWith("capture_")&&n.endsWith(".tmp"));
        File[] enc=vault.listFiles((d,n)->n.endsWith(".svr"));
        File[] incomplete=vault.listFiles((d,n)->n.endsWith(".incomplete"));
        long pendingBytes=0;
        if(raw!=null)for(File f:raw)pendingBytes+=f.length();
        out.put("pendingCount",raw==null?0:raw.length);
        out.put("pendingBytes",pendingBytes);
        out.put("encryptedCount",enc==null?0:enc.length);
        out.put("incompleteCount",incomplete==null?0:incomplete.length);
        out.put("indexedCount",repairIndex().length());
        out.put("usableBytes",context.getFilesDir().getUsableSpace());
        return out;
    }
    public synchronized void deleteAll()throws Exception {
        JSONArray all=readIndex();
        for(int i=0;i<all.length();i++){
            JSONObject r=all.getJSONObject(i);encryptedFile(r.getString("id")).delete();
        }
        writeIndex(new JSONArray());purgePreviews();
    }
}
