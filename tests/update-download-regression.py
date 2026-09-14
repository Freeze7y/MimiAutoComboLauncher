"""Production download/fallback verifier with deterministic HTTPS and Android doubles."""
from pathlib import Path
import subprocess,tempfile,importlib.util
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('publisher',root/'scripts/make-update.py');pub=importlib.util.module_from_spec(spec);spec.loader.exec_module(pub)
stubs={
'android/content/Context.java': '''package android.content;import android.content.pm.*;public class Context { public PackageManager pm=new PackageManager();public ApplicationInfo app=new ApplicationInfo();public String getPackageName(){return "app";}public PackageManager getPackageManager(){return pm;}public ApplicationInfo getApplicationInfo(){return app;} }''',
'android/content/pm/ApplicationInfo.java': 'package android.content.pm;public class ApplicationInfo {public String sourceDir;}',
'android/content/pm/PackageInfo.java': '''package android.content.pm;public class PackageInfo {public String packageName="app";public long code;public SigningInfo signingInfo=new SigningInfo();public long getLongVersionCode(){return code;}}''',
'android/content/pm/SigningInfo.java': '''package android.content.pm;public class SigningInfo {public String signature="official";public String[] getApkContentsSigners(){return new String[]{signature};}}''',
'android/content/pm/PackageManager.java': '''package android.content.pm;public class PackageManager {public static int GET_SIGNING_CERTIFICATES=1;public PackageInfo current=new PackageInfo(),next=new PackageInfo();public PackageManager(){current.code=12;next.code=13;}public PackageInfo getPackageInfo(String p,int f){return current;}public PackageInfo getPackageArchiveInfo(String p,int f){return next;}}''',
'org/json/JSONObject.java': '''package org.json;import java.util.*;public class JSONObject {public static JSONObject parsed;Map<String,Object> map=new HashMap<>();public JSONObject(){}public JSONObject(String s){map=parsed.map;}public JSONObject put(String k,Object v){map.put(k,v);return this;}public String getString(String k){return (String)map.get(k);}public long getLong(String k){return ((Number)map.get(k)).longValue();}public int getInt(String k){return (int)getLong(k);}public JSONObject getJSONObject(String k){return (JSONObject)map.get(k);}public JSONArray getJSONArray(String k){return (JSONArray)map.get(k);}}''',
'org/json/JSONArray.java': '''package org.json;import java.util.*;public class JSONArray {List<JSONObject> list=new ArrayList<>();public JSONArray put(JSONObject v){list.add(v);return this;}public int length(){return list.size();}public JSONObject getJSONObject(int i){return list.get(i);}}''',
'dev/local/nativemacrohelper/UpdateChecker.java':'package dev.local.nativemacrohelper;class UpdateChecker {static final String PROJECT="https://github.com/Freeze7y/MimiAutoComboLauncher";}',
'dev/local/nativemacrohelper/MacroController.java':'package dev.local.nativemacrohelper;import android.content.Context;class MacroController {static void log(Context c,String s){}}',
}
harness=r'''package dev.local.nativemacrohelper;
import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import org.json.*;import android.content.*;
public class DownloadTest {
 static Map<String,byte[]> network=new HashMap<>();static List<String> requests=new ArrayList<>();static int count;
 static String prefix=UpdateDownload.ROOT+"v1.5.0/";static byte[] old,next,delta;static Path dir;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);count++;}
 static JSONObject asset(String name,byte[] bytes)throws Exception {File f=dir.resolve("hash").toFile();Files.write(f.toPath(),bytes);return new JSONObject().put("url",prefix+name).put("size",bytes.length).put("sha256",DeltaPatch.hex(DeltaPatch.hash(f)));}
 static Context setup()throws Exception {
  network.clear();requests.clear();network.put(prefix+"update.json",new byte[]{123,125});network.put(prefix+"full.apk",next);network.put(prefix+"delta.mmd",delta);
  File base=dir.resolve("old.apk").toFile();Files.write(base.toPath(),old);Context c=new Context();c.app.sourceDir=base.toString();
  JSONObject patch=asset("delta.mmd",delta).put("baseSha256",DeltaPatch.hex(DeltaPatch.hash(base)));
  JSONObject.parsed=new JSONObject().put("format",1).put("version","1.5.0").put("versionCode",13).put("apk",asset("full.apk",next)).put("patches",new JSONArray().put(patch));return c;
 }
 static File prepare(Context c)throws Exception{return UpdateDownload.prepare(c,"v1.5.0",Files.createTempDirectory(dir,"case").toFile(),s->{});}
 static void fails(Context c,String why)throws Exception {boolean failed=false;try{prepare(c);}catch(IOException e){failed=true;}check(failed,why);}
 public static void main(String[] args)throws Exception {
  dir=Paths.get(args[0]);old=Files.readAllBytes(dir.resolve("base"));next=Files.readAllBytes(dir.resolve("target"));delta=Files.readAllBytes(dir.resolve("delta"));
  URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
   public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
   public int getResponseCode(){requests.add(url.toString());return network.containsKey(url.toString())?200:404;}
   public InputStream getInputStream(){return new ByteArrayInputStream(network.get(url.toString()));}
  };}}:null);
  Context c=setup();check(Arrays.equals(Files.readAllBytes(prepare(c).toPath()),next),"exact delta output");check(!requests.contains(prefix+"full.apk"),"delta saves full download");
  c=setup();network.put(prefix+"delta.mmd",new byte[]{0});prepare(c);check(requests.contains(prefix+"full.apk"),"corrupt patch falls back");
  c=setup();network.remove(prefix+"delta.mmd");prepare(c);check(requests.contains(prefix+"full.apk"),"missing patch falls back");
  c=setup();JSONObject.parsed.put("patches",new JSONArray());prepare(c);check(requests.contains(prefix+"full.apk"),"no matching patch falls back");
  c=setup();JSONObject.parsed.put("patches",new JSONArray());network.put(prefix+"full.apk",new byte[]{4});fails(c,"reject corrupt full download");
  c=setup();c.pm.next.signingInfo.signature="other";fails(c,"reject different signing identity");
  c=setup();c.pm.next.packageName="another";fails(c,"reject other package");
  c=setup();c.pm.next.code=12;fails(c,"reject older APK");
  c=setup();JSONObject.parsed.put("versionCode",12);fails(c,"reject rollback metadata");
  c=setup();JSONObject.parsed.put("version","9.0.0");fails(c,"reject mismatched release tag");
  c=setup();JSONObject.parsed.put("patches",new JSONArray());JSONObject.parsed.getJSONObject("apk").put("url","https://evil.example/full.apk");fails(c,"reject foreign asset URL");
  c=setup();Thread.currentThread().interrupt();fails(c,"cancel stops download");Thread.interrupted();check(!requests.contains(prefix+"full.apk"),"cancel must not fall back");
  System.out.println("PASS: "+count+" download/fallback/identity checks (HTTPS and Android doubles)");
 }
}'''
with tempfile.TemporaryDirectory(dir=root/'build',prefix='download-tests-') as temp:
 d=Path(temp);base=b'unchanged data'*1000;target=b'new header'+base+b'end';(d/'base').write_bytes(base);(d/'target').write_bytes(target);(d/'delta').write_bytes(pub.make_patch(base,target))
 for name,code in stubs.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code,encoding='utf-8')
 (d/'DownloadTest.java').write_text(harness,encoding='utf-8')
 sources=list(d.rglob('*.java'))+[root/'app/src/main/java/dev/local/nativemacrohelper'/f'{name}.java' for name in ['DeltaPatch','UpdateDownload']]
 subprocess.run(['javac','-encoding','UTF-8','-d',str(d),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',str(d),'dev.local.nativemacrohelper.DownloadTest',str(d)],check=True)
