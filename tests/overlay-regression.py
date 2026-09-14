"""Production overlay probe against API doubles; no device permission claims."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
files = {
"android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public int uid=12345; }",
"android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager { public boolean missing; public ApplicationInfo getApplicationInfo(String p,int f) throws NameNotFoundException { if(missing) throw new NameNotFoundException(); return new ApplicationInfo(); } public static class NameNotFoundException extends Exception {} }",
"android/content/Context.java": "package android.content; public class Context { public android.content.pm.PackageManager pm=new android.content.pm.PackageManager(); public android.app.AppOpsManager ops=new android.app.AppOpsManager(); public android.content.pm.PackageManager getPackageManager(){return pm;} public <T> T getSystemService(Class<T> t){return t.cast(ops);} }",
"android/app/AppOpsManager.java": "package android.app; public class AppOpsManager { public static final int MODE_ALLOWED=0, MODE_IGNORED=1; public static final String OPSTR_SYSTEM_ALERT_WINDOW=\"android:system_alert_window\"; public int mode; public boolean reject; public int uid; public String pkg; public int unsafeCheckOpNoThrow(String o,int u,String p){uid=u;pkg=p;if(reject)throw new SecurityException();return mode;} }",
"dev/local/nativemacrohelper/OverlayTest.java": """package dev.local.nativemacrohelper;
import android.content.Context;
public class OverlayTest {
 static int count;
 static void expect(Context c, NativeOverlayPermission.State s) {if(NativeOverlayPermission.check(c,"com.xiaomi.macro")!=s)throw new AssertionError(s);count++;}
 public static void main(String[] args) {
  Context c=new Context();
  expect(c,NativeOverlayPermission.State.ALLOWED);
  if(c.ops.uid!=12345 || !c.ops.pkg.equals("com.xiaomi.macro"))throw new AssertionError("wrong target");count++;
  c.ops.mode=1;expect(c,NativeOverlayPermission.State.DENIED);
  for(int m:new int[]{2,3,4,99}){c.ops.mode=m;expect(c,NativeOverlayPermission.State.UNKNOWN);}
  c.ops.reject=true;expect(c,NativeOverlayPermission.State.UNKNOWN);
  c.ops=null;expect(c,NativeOverlayPermission.State.UNKNOWN);
  c.pm.missing=true;expect(c,NativeOverlayPermission.State.MISSING);
  if(NativeOverlayPermission.check(c,"")!=NativeOverlayPermission.State.MISSING)throw new AssertionError();count++;
  System.out.println("PASS: "+count+" overlay assertions (host doubles)");
 }
}"""
}
with tempfile.TemporaryDirectory(dir=root/'build',prefix='overlay-tests-') as temp:
 d=Path(temp)
 for name,code in files.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code,encoding='utf-8')
 sources=[str(p) for p in d.rglob('*.java')]
 sources.append(str(root/'app/src/main/java/dev/local/nativemacrohelper/NativeOverlayPermission.java'))
 subprocess.run(['javac','-encoding','UTF-8','-d',str(d),*sources],check=True)
 subprocess.run(['java','-cp',str(d),'dev.local.nativemacrohelper.OverlayTest'],check=True)
