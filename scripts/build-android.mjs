import {existsSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
const run=(cmd,args)=>{const r=spawnSync(cmd,args,{stdio:'inherit',shell:process.platform==='win32'});if(r.status!==0)process.exit(r.status??1)};
if(!existsSync('node_modules'))run('npm',['install']);
if(!existsSync('android/app/src/main/AndroidManifest.xml'))run('npx',['cap','add','android']);
run('node',['scripts/install-native.mjs']);
run('npx',['cap','sync','android']);
process.chdir('android');
run(process.platform==='win32'?'gradlew.bat':'./gradlew',['assembleDebug']);
