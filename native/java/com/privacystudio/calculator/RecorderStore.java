package com.privacystudio.calculator;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
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
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-256-GCM encrypted MP4 storage. No media scanner calls for vault files. */
public final class RecorderStore {
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
        try(InputStream in=new FileInputStream(index)){
            byte[] all=new byte[(int)index.length()];int o=0,r;
            while(o<all.length&&(r=in.read(all,o,all.length-o))>0)o+=r;
            return new JSONArray(new String(all,0,o,java.nio.charset.StandardCharsets.UTF_8));
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
    public synchronized JSONArray list()throws Exception{return readIndex();}
    public synchronized JSONObject find(String id)throws Exception {
        JSONArray records=readIndex();
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
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        try(OutputStream outer=new BufferedOutputStream(new FileOutputStream(temp));InputStream in=new BufferedInputStream(new FileInputStream(raw))){
            outer.write(MAGIC);outer.write(iv);
            try(CipherOutputStream encrypted=new CipherOutputStream(outer,cipher)){copy(in,encrypted);}
        }catch(Exception e){temp.delete();throw e;}
        if(!temp.renameTo(destination)){temp.delete();throw new IllegalStateException("Nije moguće spremiti šifrirani zapis.");}
        String name=("screen".equals(type)?"SCREEN_":"CAM_")+new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.ROOT).format(new java.util.Date(createdAt))+".mp4";
        JSONObject record=new JSONObject();record.put("id",id);record.put("name",name);record.put("type",type);record.put("lens",lens);record.put("durationSec",duration);record.put("createdAt",createdAt);record.put("width",width);record.put("height",height);record.put("sizeBytes",raw.length());
        JSONArray arr=readIndex();JSONArray next=new JSONArray();next.put(record);
        for(int i=0;i<arr.length();i++)next.put(arr.get(i));
        writeIndex(next);
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
    public synchronized void deleteAll()throws Exception {
        JSONArray all=readIndex();
        for(int i=0;i<all.length();i++){
            JSONObject r=all.getJSONObject(i);encryptedFile(r.getString("id")).delete();
        }
        writeIndex(new JSONArray());purgePreviews();
    }
}
