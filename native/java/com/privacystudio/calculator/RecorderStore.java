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
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.security.KeyStore;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.Locale;
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
    private static final byte[] MAGIC_V1 = new byte[]{'S','V','R','1'};
    // SVR2: independent authenticated blocks, fixed-size memory and cancellable reads.
    // Previously saved SVR1 recordings are still supported and never rewritten.
    private static final byte[] MAGIC_V2 = new byte[]{'S','V','R','2'};
    private static final int CHUNK_BYTES = 256 * 1024;
    private final Context context;
    private final File vault;
    private final File payloadVault;
    private final File previews;
    private final File index;
    public RecorderStore(Context c) {
        context=c.getApplicationContext();
        vault=new File(context.getFilesDir(),"encrypted_vault");
        // Separate encrypted files on app-specific external storage, never inside the APK.
        // This directory is removed on app uninstall; Android Keystore keys are too.
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new IllegalStateException("Vanjska privatna memorija nije dostupna; sef se neće premjestiti nesigurno.");
        payloadVault=new File(external,"SecureArchive");
        previews=new File(context.getCacheDir(),"private_preview");
        index=new File(vault,"records.json");
        vault.mkdirs();payloadVault.mkdirs();previews.mkdirs();
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
    /** Copy existing .svr encrypted blobs to external .cjn without changing AES bytes.
     *  The old file is retained until copy+fsync+length verification succeeds.
     *  IDs are stable so existing records and Android Keystore keys remain valid.
     */
    private void migrateLegacy() throws Exception {
        File[] old=vault.listFiles((dir,name)->name.endsWith(".svr"));
        if(old==null)return;
        for(File file:old){
            String id=file.getName().substring(0,file.getName().length()-4);
            if(!id.matches("[A-Za-z0-9_-]{1,96}"))continue;
            File dest=new File(payloadVault,"system_"+id+".cjn");
            if(!dest.exists()){
                File temp=new File(payloadVault,"system_"+id+".migrating");
                try(InputStream in=new FileInputStream(file);FileOutputStream out=new FileOutputStream(temp)){
                    copy(in,out);out.getFD().sync();
                }catch(Exception e){temp.delete();throw e;}
                if(temp.length()!=file.length()||!temp.renameTo(dest)){
                    temp.delete();throw new IOException("Migracija šifriranog fajla nije uspjela.");
                }
            }
            if(dest.length()!=file.length())throw new IOException("Neispravna dužina kopiranog sefa.");
            // If delete fails the duplicate is harmless; the external file is authoritative.
            if(!file.delete())android.util.Log.w("RecorderStore","Legacy encrypted copy retained: "+file.getName());
        }
    }
    /** Rebuild metadata when an index write was interrupted. Never rewrite AES content. */
    private JSONArray repairIndex()throws Exception {
        synchronized(INDEX_LOCK){
            migrateLegacy();
            JSONArray found=readIndex();
            Set<String> ids=new HashSet<>();
            for(int i=0;i<found.length();i++) ids.add(found.getJSONObject(i).optString("id"));
            File[] encrypted=payloadVault.listFiles((dir,name)->name.startsWith("system_")&&name.endsWith(".cjn"));
            boolean changed=false;
            if(encrypted!=null) for(File file:encrypted){
                String id=file.getName().substring(7,file.getName().length()-4);
                if(!id.matches("[A-Za-z0-9_-]{1,96}")||ids.contains(id))continue;
                JSONObject record=recoveredEntry(id,file.length(),file.lastModified());
                found.put(record);ids.add(id);changed=true;
            }
            if(SharedVault.configured(context)){
                SharedVault folder=new SharedVault(context);
                for(SharedVault.Item item:folder.list()){
                    if(!item.name.startsWith("system_")||!item.name.endsWith(".cjn"))continue;
                    String id=item.name.substring(7,item.name.length()-4);
                    if(!id.matches("[A-Za-z0-9_-]{1,96}")||ids.contains(id))continue;
                    found.put(recoveredEntry(id,item.size,System.currentTimeMillis()));ids.add(id);changed=true;
                }
            }
            if(changed)writeIndex(found);
            return found;
        }
    }
    private JSONObject recoveredEntry(String id,long length,long created)throws Exception {
        JSONObject record=new JSONObject();record.put("id",id);record.put("name","RECOVERED_"+id+".mp4");
        record.put("type","recovered");record.put("lens","unknown");
        record.put("durationSec",0);record.put("createdAt",created);
        record.put("width",0);record.put("height",0);record.put("sizeBytes",Math.max(0L,length-32L));
        return record;
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
    private File legacyEncryptedFile(String id){
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Neispravan ID snimka.");
        File modern=new File(payloadVault,"system_"+id+".cjn");
        if(modern.exists())return modern;
        File old=new File(vault,id+".svr");
        return old.exists()?old:modern;
    }
    private static final class Blob {
        final File file;final android.net.Uri uri;final SharedVault shared;final long size;
        Blob(File f){file=f;uri=null;shared=null;size=f.length();}
        Blob(SharedVault sf,SharedVault.Item item)throws Exception{
            file=null;uri=item.uri;shared=sf;
            if(item.size>0){size=item.size;}else{
                long measured=0;byte[] buf=new byte[256*1024];int n;
                try(InputStream in=sf.input(item.uri)){while((n=in.read(buf))!=-1)measured+=n;}
                size=measured;
            }
        }
        InputStream input()throws Exception{return file!=null?new FileInputStream(file):shared.input(uri);}
        void remove()throws Exception{
            if(file!=null){if(file.exists()&&!file.delete())throw new IOException("Ne mogu obrisati šifrirani fajl.");}
            else shared.remove(uri);
        }
    }
    private Blob encryptedBlob(String id)throws Exception {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Neispravan ID snimka.");
        if(SharedVault.configured(context)){
            SharedVault sv=new SharedVault(context);
            SharedVault.Item item=sv.find("system_"+id+".cjn");
            if(item!=null)return new Blob(sv,item);
        }
        File legacy=legacyEncryptedFile(id);
        if(legacy.exists())return new Blob(legacy);
        throw new IOException("Šifrirani snimak nedostaje. Provjeri odabrani folder u postavkama.");
    }
    private static final SecureRandom RANDOM=new SecureRandom();
    private String nextId()throws Exception{
        for(int i=0;i<20;i++){
            String id=Long.toString(10000000000L+Math.floorMod(RANDOM.nextLong(),89999999999L));
            if(!new File(payloadVault,"system_"+id+".cjn").exists() && (!SharedVault.configured(context) || new SharedVault(context).find("system_"+id+".cjn")==null))return id;
        }
        throw new IllegalStateException("Nije moguće generisati jedinstven naziv snimka.");
    }
    /** Write ciphertext first; only publish metadata after final GCM auth tag is saved. */
    public synchronized JSONObject encryptRecording(File raw,String type,String lens,long createdAt,long duration,int width,int height)throws Exception {
        String id=nextId();
        if(!SharedVault.configured(context))throw new IOException("U Postavkama prvo odaberi vanjski folder za snimke.");
        SharedVault selected=new SharedVault(context);
        String finalName="system_"+id+".cjn";
        android.net.Uri temporary=selected.create("system_"+id+".incomplete");
        // Encrypt directly to the user-selected folder; no app-private encrypted duplicate.
        try(DataOutputStream outer=new DataOutputStream(new BufferedOutputStream(selected.output(temporary)));
            InputStream in=new BufferedInputStream(new FileInputStream(raw))){
            outer.write(MAGIC_V2);
            final long originalSize=raw.length();
            outer.writeLong(originalSize);
            SecretKey secret=key();
            byte[] chunk=new byte[CHUNK_BYTES];
            int bytesRead;
            long blockNumber=0;
            while((bytesRead=readChunk(in,chunk))>0){
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,secret);
                byte[] iv=cipher.getIV();
                if(iv==null||iv.length!=12)throw new java.security.GeneralSecurityException("Neispravan Android Keystore IV");
                cipher.updateAAD(chunkAad(blockNumber,originalSize,bytesRead));
                byte[] encrypted=cipher.doFinal(chunk,0,bytesRead);
                outer.writeInt(bytesRead);
                outer.write(iv);
                outer.write(encrypted);
                blockNumber++;
            }
            outer.flush();
        }catch(Exception e){try{selected.remove(temporary);}catch(Exception ignored){}throw e;}
        String name=("audio".equals(type)?"VOICE_":("screen".equals(type)?"SCREEN_":"CAM_"))
            +new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.ROOT)
            .format(new java.util.Date(createdAt))+("audio".equals(type)?".m4a":".mp4");
        JSONObject record=new JSONObject();record.put("id",id);record.put("name",name);record.put("type",type);record.put("lens",lens);record.put("durationSec",duration);record.put("createdAt",createdAt);record.put("width",width);record.put("height",height);record.put("sizeBytes",raw.length());
        synchronized(INDEX_LOCK){
            selected.publish(temporary,finalName);
            JSONArray arr=readIndex();JSONArray next=new JSONArray();next.put(record);
            for(int i=0;i<arr.length();i++)next.put(arr.get(i));
            writeIndex(next);
        }
        return record;
    }
    /** Reports encrypted input bytes consumed; may throw IOException to cancel safely. */
    public interface TransferProgress {
        void onProgress(long processedBytes, long totalBytes) throws IOException;
    }
    private static int readChunk(InputStream input,byte[] buffer)throws IOException {
        int count=0;
        while(count<buffer.length){
            int n=input.read(buffer,count,buffer.length-count);
            if(n<0)break;
            if(n==0)continue;
            count+=n;
        }
        return count;
    }
    private static byte[] chunkAad(long index,long total,int length){
        java.nio.ByteBuffer aad=java.nio.ByteBuffer.allocate(8+8+4);
        return aad.putLong(index).putLong(total).putInt(length).array();
    }
    private static void report(TransferProgress progress,long done,long total)throws IOException {
        if(progress!=null)progress.onProgress(done,Math.max(1,total));
    }
    private void decryptTo(String id,OutputStream out,TransferProgress progress)throws Exception {
        find(id);
        Blob input=encryptedBlob(id);
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(input.input()))){
            byte[] magic=new byte[4];in.readFully(magic);
            if(java.util.Arrays.equals(magic,MAGIC_V2)){
                // 4-byte MAGIC + 8-byte plaintext length, then authenticated
                // chunks [length (4), IV (12), ciphertext + GCM tag (length+16)].
                final long originalSize=in.readLong();
                if(originalSize<1)throw new IOException("Neispravna izvorna dužina datoteke.");
                long total=Math.max(1L,input.size-12L);
                long processed=0,decoded=0,blockNumber=0;
                report(progress,0,total);
                SecretKey secret=key();
                byte[] head=new byte[3];
                while(true){
                    report(progress,processed,total);
                    int first=in.read();
                    if(first<0)break;
                    in.readFully(head);
                    int length=((first&255)<<24)|((head[0]&255)<<16)|((head[1]&255)<<8)|(head[2]&255);
                    if(length<1||length>CHUNK_BYTES)throw new IOException("Oštećen blok šifriranog snimka.");
                    byte[] iv=new byte[12];in.readFully(iv);
                    byte[] ciphertext=new byte[length+16];in.readFully(ciphertext);
                    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,iv));
                    cipher.updateAAD(chunkAad(blockNumber,originalSize,length));
                    byte[] clear=cipher.doFinal(ciphertext);
                    if(clear.length!=length)throw new IOException("Neispravna dužina dešifriranog bloka.");
                    report(progress,processed,total);
                    out.write(clear);
                    processed+=4L+12L+ciphertext.length;
                    decoded+=clear.length;
                    blockNumber++;
                    if(decoded>originalSize)throw new IOException("Višak podataka u šifriranom snimku.");
                    report(progress,processed,total);
                }
                if(processed!=total||decoded!=originalSize)
                    throw new IOException("Nepotpuna ili izmijenjena šifrirana datoteka.");
                report(progress,total,total);
            }else if(java.util.Arrays.equals(magic,MAGIC_V1)){
                // Legacy v1 format. Explicit small reads allow native progress and
                // cancellation even if the provider withholds output until GCM final.
                byte[] iv=new byte[12];in.readFully(iv);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
                long total=Math.max(1L,input.size-16L);
                long processed=0;
                byte[] block=new byte[64*1024];
                report(progress,0,total);
                int n;
                while((n=in.read(block))!=-1){
                    report(progress,processed,total);
                    byte[] plain=cipher.update(block,0,n);
                    if(plain!=null&&plain.length>0)out.write(plain);
                    processed+=n;
                    report(progress,processed,total);
                }
                report(progress,processed,total);
                byte[] finalBytes=cipher.doFinal();
                if(finalBytes!=null&&finalBytes.length>0)out.write(finalBytes);
                report(progress,total,total);
            }else throw new IllegalArgumentException("Nepoznat format šifriranog snimka.");
            out.flush();
        }
    }
    public synchronized File preview(String id)throws Exception {return preview(id,null);}
    public synchronized File preview(String id,TransferProgress progress)throws Exception {
        purgePreviews();
        boolean audio="audio".equals(find(id).optString("type"));
        File file=new File(previews,"view_"+id+(audio?".m4a":".mp4"));
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){decryptTo(id,out,progress);}
        catch(Exception e){file.delete();throw e;}
        if(file.length()<1024L){file.delete();throw new java.io.IOException("Dešifrirani video je prazan.");}
        return file;
    }
    public synchronized void purgePreviews(){File[] files=previews.listFiles();if(files!=null)for(File f:files)f.delete();}
    /** Stream the original decoded bytes into MediaStore; no re-encoding or transcoding. */
    public synchronized String export(String id)throws Exception {return export(id,null);}
    public synchronized String export(String id,TransferProgress progress)throws Exception {
        JSONObject r=find(id);String name=r.getString("name");
        if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException("Izvoz preko MediaStore zahtijeva Android 10+.");
        boolean audio="audio".equals(r.optString("type"));
        ContentValues meta=new ContentValues();meta.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        meta.put(MediaStore.MediaColumns.MIME_TYPE,audio?"audio/mp4":"video/mp4");
        meta.put(MediaStore.MediaColumns.RELATIVE_PATH,audio?"Music/Calculator Exports/":"Movies/Calculator Exports/");
        meta.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(audio?MediaStore.Audio.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI,meta);
        if(uri==null)throw new IllegalStateException("Nije moguće kreirati datoteku u galeriji.");
        try{
            try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IllegalStateException("Nema izlaznog toka za izvoz.");
                decryptTo(id,out,progress);
            }
            if(progress!=null)progress.onProgress(Math.max(1L,encryptedBlob(id).size-16L),Math.max(1L,encryptedBlob(id).size-16L));
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            int published=context.getContentResolver().update(uri,ready,null,null);
            if(published<1)throw new java.io.IOException("Galerija nije potvrdila objavu MP4 snimka.");
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
                String audio=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
                if(!"yes".equalsIgnoreCase(video)&&!"yes".equalsIgnoreCase(audio)){invalid++;continue;}
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
                encryptRecording(file,"yes".equalsIgnoreCase(video)?"recovered":"audio","unknown",createdAt,Math.max(1,ms/1000),w,h);
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
        File[] enc=payloadVault.listFiles((d,n)->n.endsWith(".cjn"));
        File[] incomplete=payloadVault.listFiles((d,n)->n.endsWith(".incomplete")||n.endsWith(".migrating"));
        long pendingBytes=0;
        if(raw!=null)for(File f:raw)pendingBytes+=f.length();
        out.put("pendingCount",raw==null?0:raw.length);
        out.put("pendingBytes",pendingBytes);
        int sharedCount=0;
        if(SharedVault.configured(context))for(SharedVault.Item item:new SharedVault(context).list())if(item.name.endsWith(".cjn"))sharedCount++;
        out.put("encryptedCount",(enc==null?0:enc.length)+sharedCount);
        out.put("incompleteCount",incomplete==null?0:incomplete.length);
        out.put("indexedCount",repairIndex().length());
        out.put("usableBytes",payloadVault.getUsableSpace());
        out.put("storagePath",SharedVault.configured(context)?"Korisnički odabrani folder: "+SharedVault.selectedUri(context):payloadVault.getAbsolutePath());
        out.put("externalSelected",SharedVault.configured(context));
        out.put("legacyAppFiles",enc==null?0:enc.length);
        return out;
    }
    /** Delete exactly one encrypted recording after its index entry is removed.
     * Exported gallery copies, if any, are intentionally unaffected.
     */
    public void deleteOne(String id)throws Exception {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Neispravan ID snimka.");
        synchronized(INDEX_LOCK){
            JSONArray records=repairIndex();
            JSONArray next=new JSONArray();
            boolean exists=false;
            for(int i=0;i<records.length();i++){
                JSONObject record=records.getJSONObject(i);
                if(id.equals(record.optString("id")))exists=true;
                else next.put(record);
            }
            if(!exists)throw new IllegalArgumentException("Snimak ne postoji u sefu.");
            // Keep the encrypted file in place until metadata is durably saved.
            // If deletion fails, repairIndex will safely rediscover the file.
            writeIndex(next);
            encryptedBlob(id).remove();
            // A failed migration may have left a duplicate at the legacy path.
            File legacy=new File(payloadVault,"system_"+id+".cjn");
            if(legacy.exists()&&!legacy.delete())throw new IOException("Ne mogu obrisati preostalu staru .cjn kopiju.");
            File older=new File(vault,id+".svr");
            if(older.exists()&&!older.delete())throw new IOException("Ne mogu obrisati preostalu .svr kopiju.");
            for(String ext:new String[]{".mp4",".m4a"}){
                File cached=new File(previews,"view_"+id+ext);
                if(cached.exists()&&!cached.delete())
                    android.util.Log.w("RecorderStore","Could not remove expired preview for deleted record");
            }
        }
    }
    /** Explicit migration after SAF folder selection; never delete source until SHA-256 verified. */
    public JSONObject migrateShared()throws Exception {
        if(!SharedVault.configured(context))throw new IOException("Prvo odaberi folder u Postavkama.");
        synchronized(INDEX_LOCK){
            migrateLegacy();
            SharedVault target=new SharedVault(context);
            File[] local=payloadVault.listFiles((dir,name)->name.startsWith("system_")&&name.endsWith(".cjn"));
            int moved=0;long released=0;
            if(local!=null)for(File file:local){
                target.copyVerified(file,file.getName());
                long length=file.length();
                if(!file.delete())throw new IOException("Kopija je sigurna, ali stari fajl nije uklonjen: "+file.getName());
                moved++;released+=length;
            }
            JSONObject summary=new JSONObject();summary.put("moved",moved);summary.put("releasedBytes",released);
            return summary;
        }
    }
    public synchronized void deleteAll()throws Exception {
        JSONArray all=repairIndex();
        for(int i=0;i<all.length();i++){
            JSONObject r=all.getJSONObject(i);
            String id=r.getString("id");
            encryptedBlob(id).remove();
            File legacy=new File(payloadVault,"system_"+id+".cjn");
            if(legacy.exists()&&!legacy.delete())throw new IOException("Ne mogu obrisati staru kopiju: "+legacy.getName());
            File older=new File(vault,id+".svr");
            if(older.exists()&&!older.delete())throw new IOException("Ne mogu obrisati stari SVR: "+older.getName());
        }
        writeIndex(new JSONArray());purgePreviews();
    }
}
