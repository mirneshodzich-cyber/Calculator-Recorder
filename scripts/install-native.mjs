import {readFileSync,writeFileSync,cpSync,existsSync,mkdirSync} from 'node:fs';
import path from 'node:path';

const root=process.cwd();
const app=path.join(root,'android','app','src','main');
if(!existsSync(path.join(app,'AndroidManifest.xml'))){
 console.error('Prvo pokreni: npx cap add android');process.exit(1);
}
const dest=path.join(app,'java','com','privacystudio','calculator');
mkdirSync(dest,{recursive:true});
cpSync(path.join(root,'native','java','com','privacystudio','calculator'),dest,{recursive:true});
const resDest=path.join(app,'res','drawable');
mkdirSync(resDest,{recursive:true});
cpSync(path.join(root,'native','res','drawable'),resDest,{recursive:true});
const manifestPath=path.join(app,'AndroidManifest.xml');
let manifest=readFileSync(manifestPath,'utf8');
const permissions=[
 'android.permission.CAMERA',
 'android.permission.RECORD_AUDIO',
 'android.permission.FOREGROUND_SERVICE',
 'android.permission.FOREGROUND_SERVICE_CAMERA',
 'android.permission.FOREGROUND_SERVICE_MICROPHONE',
 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
 'android.permission.POST_NOTIFICATIONS'
];
for(const p of permissions){
 if(!manifest.includes(`android:name="${p}"`))manifest=manifest.replace('<application',`<uses-permission android:name="${p}" />\n    <application`);
}
if(!manifest.includes('android:name=".RecorderService"')){
 manifest=manifest.replace('</application>',`    <service android:name=".RecorderService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="camera|microphone|mediaProjection" />\n  </application>`);
}
// Keep the active recording notification visible. No notification stop action.
manifest=manifest.replace(/android:icon="[^"]+"/,'android:icon="@drawable/ic_calculator"');
if(!manifest.includes('android:usesCleartextTraffic=')){
 manifest=manifest.replace('<application','<application android:usesCleartextTraffic="false"');
}
writeFileSync(manifestPath,manifest);
console.log('Native RecorderPlugin + RecorderService installed, manifest updated.');
