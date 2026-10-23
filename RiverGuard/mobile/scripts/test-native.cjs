const {spawnSync}=require('node:child_process');
const path=require('node:path');
const windows=process.platform==='win32';
const result=spawnSync(windows?'gradlew.bat':'./gradlew',['--no-daemon',':app:testDebugUnitTest'],{cwd:path.resolve(__dirname,'../android'),stdio:'inherit',shell:windows});
if(result.error){console.error('Install JDK 21 and Android SDK 35 to run native Android tests:',result.error.message);}
process.exit(result.status??1);
