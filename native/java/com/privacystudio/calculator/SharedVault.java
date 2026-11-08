package com.privacystudio.calculator;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** User-selected SAF folder, not Android/data or the app's private storage. */
public final class SharedVault {
    private static final String PREFS="shared_vault_v1";
    private static final String KEY="tree_uri";
    private final Context context;
    private final Uri tree;
    private final Uri root;

    public static boolean configured(Context c){
        try { return new SharedVault(c).list().size()>=0; } catch(Exception e) {return false;}
    }
    public static String selectedUri(Context c){
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
    }
    public static JSONObject status(Context c)throws Exception {
        JSONObject j=new JSONObject();String url=selectedUri(c);
        boolean ready=false;int count=0;
        if(!url.isEmpty()){
            try {SharedVault s=new SharedVault(c);count=s.list().size();ready=true;}catch(Exception ignored){}
        }
        j.put("selected",ready);j.put("location",ready?Uri.parse(url).getLastPathSegment():"");
        j.put("encryptedFiles",count);j.put("requiresSelection",!ready);
        return j;
    }
    public static void choose(Context c,Uri newTree,int flags)throws Exception {
        if(newTree==null)throw new IOException("Folder nije odabran.");
        String previous=selectedUri(c);
        if(!previous.isEmpty()&&!previous.equals(newTree.toString())){
            // Never silently disconnect existing encrypted files by switching folders.
            try {
                SharedVault prior=new SharedVault(c);
                for(Item f:prior.list())if(f.name.endsWith(".cjn")||f.name.endsWith(".incomplete"))
                    throw new IOException("Trenutni folder sadrži šifrirane snimke. Ne mijenjaj ga dok ih ne premjestiš ili izvezeš.");
            }catch(IOException err){
                if(err.getMessage()!=null&&err.getMessage().startsWith("Trenutni folder"))throw err;
                // A revoked or removed old folder may be replaced; external files remain where they were.
            }
        }
        int allowed=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((allowed&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new IOException("Potrebna je dozvola za čitanje i pisanje u folder.");
        c.getContentResolver().takePersistableUriPermission(newTree,allowed);
        SharedVault trial=new SharedVault(c,newTree);
        trial.list();
        Uri test=trial.create(".recorder_write_test");
        try(OutputStream os=c.getContentResolver().openOutputStream(test,"w")){
            if(os==null)throw new IOException("Folder ne dozvoljava pisanje.");os.write(0);
        }finally{DocumentsContract.deleteDocument(c.getContentResolver(),test);}
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,newTree.toString()).commit();
    }
    private SharedVault(Context c,Uri u)throws Exception{
        context=c.getApplicationContext();tree=u;
        root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        boolean granted=false;
        for(UriPermission p:context.getContentResolver().getPersistedUriPermissions())
            if(p.getUri().equals(tree)&&p.isReadPermission()&&p.isWritePermission())granted=true;
        if(!granted)throw new IOException("Izgubljena je dozvola za odabrani folder. Ponovo ga odaberi.");
    }
    public SharedVault(Context c)throws Exception {
        this(c,getUri(c));
    }
    private static Uri getUri(Context c)throws IOException{
        String value=selectedUri(c);
        if(value.isEmpty())throw new IOException("U Postavkama prvo odaberi folder za šifrirane snimke.");
        return Uri.parse(value);
    }
    public static final class Item {
        public final String name;
        public final Uri uri;
        public final long size;
        Item(String n,Uri u,long s){name=n;uri=u;size=s;}
    }
    public List<Item> list()throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        List<Item> out=new ArrayList<>();
        String[] fields={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_SIZE,Document.COLUMN_MIME_TYPE};
        try(Cursor cursor=context.getContentResolver().query(children,fields,null,null,null)){
            if(cursor==null)throw new IOException("Ne mogu pristupiti odabranom folderu.");
            while(cursor.moveToNext()){
                if(Document.MIME_TYPE_DIR.equals(cursor.getString(3)))continue;
                String name=cursor.getString(1);if(name==null)continue;
                long size=cursor.isNull(2)?0:cursor.getLong(2);
                out.add(new Item(name,DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)),size));
            }
        }
        return out;
    }
    public Item find(String name)throws Exception{
        for(Item item:list())if(name.equals(item.name))return item;
        return null;
    }
    public Uri create(String name)throws Exception{
        if(find(name)!=null)throw new IOException("Datoteka već postoji: "+name);
        Uri created=DocumentsContract.createDocument(context.getContentResolver(),root,"application/octet-stream",name);
        if(created==null)throw new IOException("Nije moguće napraviti datoteku u odabranom folderu.");
        return created;
    }
    public InputStream input(Uri uri)throws Exception{
        InputStream result=context.getContentResolver().openInputStream(uri);
        if(result==null)throw new IOException("Ne mogu otvoriti šifrirani fajl za čitanje.");
        return result;
    }
    public OutputStream output(Uri uri)throws Exception{
        OutputStream result=context.getContentResolver().openOutputStream(uri,"w");
        if(result==null)throw new IOException("Ne mogu otvoriti šifrirani fajl za pisanje.");
        return result;
    }
    public void remove(Uri uri)throws Exception{
        if(!DocumentsContract.deleteDocument(context.getContentResolver(),uri))
            throw new IOException("Android nije obrisao datoteku iz odabranog foldera.");
    }
    public void remove(String name)throws Exception{
        Item item=find(name);if(item!=null)remove(item.uri);
    }
    public Uri publish(Uri temporary,String destination)throws Exception {
        if(find(destination)!=null)throw new IOException("Odredišna datoteka već postoji.");
        Uri renamed=null;
        try {renamed=DocumentsContract.renameDocument(context.getContentResolver(),temporary,destination);}catch(Exception ignored){}
        if(renamed!=null)return renamed;
        // Not all DocumentsProviders implement rename. Copy & authenticate before deleting temp.
        Uri copy=create(destination);
        try(InputStream in=input(temporary);OutputStream out=output(copy)){
            byte[] buf=new byte[256*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
        }catch(Exception e){try{remove(copy);}catch(Exception ignored){}throw e;}
        if(!digest(temporary).equals(digest(copy))){remove(copy);throw new IOException("Provjera kopije u vanjskom folderu nije uspjela.");}
        remove(temporary);
        return copy;
    }
    private String digest(Uri uri)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=input(uri)){
            byte[] block=new byte[256*1024];int n;while((n=in.read(block))!=-1)md.update(block,0,n);
        }
        return java.util.Arrays.toString(md.digest());
    }
    public void copyVerified(java.io.File from,String name)throws Exception{
        Item existing=find(name);
        if(existing!=null){
            if(!hashFile(from).equals(digest(existing.uri)))throw new IOException("Naziv postoji, ali podaci nisu isti: "+name);
            return;
        }
        Uri tmp=create(name+".incomplete");
        try(InputStream input=new java.io.FileInputStream(from);OutputStream output=output(tmp)){
            byte[] buf=new byte[256*1024];int n;while((n=input.read(buf))!=-1)output.write(buf,0,n);
        }catch(Exception e){try{remove(tmp);}catch(Exception ignored){}throw e;}
        if(!hashFile(from).equals(digest(tmp))){remove(tmp);throw new IOException("SHA-256 provjera migracije nije uspjela: "+name);}
        publish(tmp,name);
    }
    private static String hashFile(java.io.File from)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new java.io.FileInputStream(from)){
            byte[] b=new byte[256*1024];int n;while((n=input.read(b))!=-1)md.update(b,0,n);
        }
        return java.util.Arrays.toString(md.digest());
    }
}
