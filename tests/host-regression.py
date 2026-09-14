"""Run production controller/receiver/service against recording Android API doubles.
This tests Java routing and side effects, NOT Android/HyperOS runtime behavior.
"""
from pathlib import Path
import subprocess
import time

root = Path(__file__).resolve().parents[1]
out = root / 'build' / ('host-tests-' + str(time.time_ns()))
src = out / 'src'
classes = out / 'classes'
classes.mkdir(parents=True)
stubs = {
'android/graphics/drawable/Icon.java': 'package android.graphics.drawable; import android.content.Context; public class Icon { public static Icon createWithResource(Context c,int r){ return new Icon(); } }',
'android/content/ComponentName.java': '''package android.content; public class ComponentName { public String pkg, cls; public ComponentName(String p,String c){pkg=p;cls=c;} }''',
'android/content/Intent.java': '''package android.content; import java.util.*; public class Intent { public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000; public ComponentName component; public String action; public int flags; public Map<String,Object> extras=new HashMap<>(); public Intent(){} public Intent(Context c,Class<?> k){component=new ComponentName("dev.local.nativemacrohelper",k.getName());} public Intent setComponent(ComponentName n){component=n;return this;} public Intent putExtra(String k,String v){extras.put(k,v);return this;} public Intent putExtra(String k,boolean v){extras.put(k,v);return this;} public String getStringExtra(String k){return (String)extras.get(k);} public Intent setAction(String a){action=a;return this;} public String getAction(){return action;} public Intent addFlags(int f){flags|=f;return this;} }''',
'android/content/SharedPreferences.java': '''package android.content; import java.util.*; public class SharedPreferences { public Map<String,String> data=new HashMap<>(); public String getString(String k,String d){return data.getOrDefault(k,d);} public Editor edit(){return new Editor();} public class Editor {public Editor putString(String k,String v){data.put(k,v);return this;} public void apply(){}} }''',
'android/content/Context.java': '''package android.content; import android.app.*; import android.content.pm.*; import java.util.*; public class Context { public final List<String> events=new ArrayList<>(); public final List<Intent> starts=new ArrayList<>(); public SharedPreferences prefs=new SharedPreferences(); public PackageManager pm=new PackageManager(); public NotificationManager nm=new NotificationManager(); public boolean denyVendor=false, denyHelper=false, missingVendorService=false, stopResult=true, denyStop=false; public SharedPreferences getSharedPreferences(String n,int m){return prefs;} public PackageManager getPackageManager(){return pm;} public <T>T getSystemService(Class<T> k){return k.cast(nm);} public ComponentName startForegroundService(Intent i){events.add("start:"+i.component.cls); starts.add(i); if((denyVendor && i.component.pkg.equals("com.xiaomi.macro")) || (denyHelper && i.component.cls.endsWith("MacroSessionService")))throw new SecurityException("denied"); if(missingVendorService && i.component.pkg.equals("com.xiaomi.macro"))return null; return i.component;} public void startActivity(Intent i){events.add("activity:"+i.component.pkg);} public boolean stopService(Intent i){events.add("stop:"+i.component.cls);if(denyStop && i.component.pkg.equals("com.xiaomi.macro"))throw new SecurityException("stop denied");return stopResult;} }''',
'android/content/BroadcastReceiver.java': '''package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
'android/content/pm/PackageManager.java': '''package android.content.pm; import android.content.*; public class PackageManager {public boolean missingLaunch=false; public ApplicationInfo getApplicationInfo(String p,int f)throws NameNotFoundException{if(p.equals("com.blackshark.macro"))throw new NameNotFoundException();return new ApplicationInfo();} public Intent getLaunchIntentForPackage(String p){if(missingLaunch)return null;return new Intent().setComponent(new ComponentName(p,"Main"));} public static class NameNotFoundException extends Exception{} }''',
'android/content/pm/ApplicationInfo.java': '''package android.content.pm; public class ApplicationInfo {public CharSequence loadLabel(PackageManager p){return "Game";}}''',
'android/content/pm/ServiceInfo.java': '''package android.content.pm; public class ServiceInfo {public static final int FOREGROUND_SERVICE_TYPE_SPECIAL_USE=1073741824;}''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock {public static long now=10000;public static long elapsedRealtime(){return now;}}''',
'android/os/Build.java': '''package android.os; public class Build {public static class VERSION {public static int SDK_INT=37;}}''',
'android/os/IBinder.java': '''package android.os; public interface IBinder {}''',
'android/app/Service.java': '''package android.app; import android.content.*;import android.os.*; public class Service extends Context {public static final int START_NOT_STICKY=2,STOP_FOREGROUND_REMOVE=1;public IBinder onBind(Intent i){return null;} public int onStartCommand(Intent i,int f,int id){return 0;} public void startForeground(int id,Notification n){events.add("foreground:legacy");nm.notify(id,n);} public void startForeground(int id,Notification n,int type){events.add("foreground:specialUse");nm.notify(id,n);} public void stopSelf(int id){events.add("stopSelf");}public void stopForeground(int f){events.add("stopForeground");nm.cancel(1);}public void onDestroy(){} }''',
'android/app/PendingIntent.java': '''package android.app; import android.content.*; public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public Intent intent;public String kind;public int flags;static PendingIntent make(Intent i,int f,String k){PendingIntent p=new PendingIntent();p.intent=i;p.kind=k;p.flags=f;return p;}public static PendingIntent getBroadcast(Context c,int id,Intent i,int f){return make(i,f,"broadcast");} public static PendingIntent getActivity(Context c,int id,Intent i,int f){return make(i,f,"activity");} }''',
'android/app/NotificationChannel.java': '''package android.app;public class NotificationChannel {public NotificationChannel(String id,String label,int level){}}''',
'android/app/NotificationManager.java': '''package android.app;public class NotificationManager {public static final int IMPORTANCE_LOW=2;public Notification last;public void createNotificationChannel(NotificationChannel c){}public void notify(int id,Notification n){last=n;}public void cancel(int id){last=null;}public boolean areNotificationsEnabled(){return true;}}''',
'android/app/Notification.java': '''package android.app; import android.content.*;import java.util.*;public class Notification {public PendingIntent content;public List<Action> actions=new ArrayList<>();public boolean ongoing;public static class Builder {Notification n=new Notification();public Builder(Context c,String channel){}public Builder setLargeIcon(android.graphics.drawable.Icon i){return this;}public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setContentIntent(PendingIntent p){n.content=p;return this;}public Builder setOnlyAlertOnce(boolean b){return this;}public Builder setOngoing(boolean b){n.ongoing=b;return this;}public Builder addAction(Action a){n.actions.add(a);return this;}public Notification build(){return n;}}public static class Action {public PendingIntent intent;public static class Builder {Action a=new Action();public Builder(Object icon,String label,PendingIntent p){a.intent=p;}public Action build(){return a;}}}}''',
'android/widget/Toast.java': '''package android.widget;import android.content.*;public class Toast{public static final int LENGTH_LONG=1;public static Toast makeText(Context c,String m,int d){return new Toast();}public void show(){}}''',
'dev/local/nativemacrohelper/R.java': '''package dev.local.nativemacrohelper;public class R{public static class drawable{public static int ic_notification=1,brand_image=2;}}''',
}
stubs['dev/local/nativemacrohelper/DiagnosticSnapshot.java'] = 'package dev.local.nativemacrohelper;import android.content.Context;class DiagnosticSnapshot {static String capture(Context c,String game){return "snapshot-at-"+android.os.SystemClock.elapsedRealtime();}}'
for name, code in stubs.items():
    path = src / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(code, encoding='utf-8')
prod = root / 'app/src/main/java/dev/local/nativemacrohelper'
for name in ('MacroController', 'MacroReceiver', 'MacroSessionService', 'ReleaseVersion', 'DiagnosticTrace', 'DiagnosticRun'):
    (src / ('dev/local/nativemacrohelper/' + name + '.java')).write_text((prod / (name + '.java')).read_text(encoding='utf-8'), encoding='utf-8')
harness = r'''
package dev.local.nativemacrohelper;
import android.content.*;import android.app.*;import android.os.*;
public class Regression {
 static int count; static String game="com.tencent.tmgp.sgame";
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);count++;}
 static void tick(){SystemClock.now+=2000;}
 static Intent command(String a){return new Intent().putExtra("game",game).putExtra("command",a);}
 static void noActivity(Context c){check(c.events.stream().noneMatch(s->s.startsWith("activity:")),"notification must never launch Activity: "+c.events);}
 public static void main(String[] args){
   Context c=new Context();Notification n=MacroController.notification(c,game);
   check(n.content.kind.equals("broadcast"),"content must be broadcast");
   check(n.ongoing,"session notification ongoing");
   for(Notification.Action a:n.actions){check(a.intent.kind.equals("broadcast"),"all buttons broadcast");check((a.intent.flags&PendingIntent.FLAG_IMMUTABLE)!=0,"immutable");}
   check(n.content.intent.component.cls.endsWith("MacroReceiver"),"receiver target");
   new MacroReceiver().onReceive(c,n.content.intent);
   noActivity(c);check(c.starts.size()==1,"one vendor request");
   check(c.starts.get(0).component.cls.equals("com.xiaomi.macro.MainService"),"correct vendor");
   check(game.equals(c.starts.get(0).extras.get("gamePackage")),"preserve game");
   check(Boolean.TRUE.equals(c.starts.get(0).extras.get("clickIcon")),"panel true");
   new MacroReceiver().onReceive(c,n.content.intent);check(c.starts.size()==2,"each explicit panel tap reaches vendor");
   tick();Context afterKill=new Context();afterKill.pm.missingLaunch=true;
   new MacroReceiver().onReceive(afterKill,n.content.intent);noActivity(afterKill);check(afterKill.starts.size()==1,"panel needs no launch intent or live activity");
   tick();Context stop=new Context();new MacroReceiver().onReceive(stop,n.actions.get(1).intent.intent);
   noActivity(stop);check(stop.events.contains("stop:com.xiaomi.macro.MainService"),"explicit vendor stop");check(stop.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"explicit helper stop");
   Context invalid=new Context();new MacroReceiver().onReceive(invalid,new Intent().setAction("launch").putExtra("game",game));new MacroReceiver().onReceive(invalid,null);check(invalid.events.isEmpty(),"receiver rejects launch/null");
   tick();MacroSessionService s=new MacroSessionService();int result=s.onStartCommand(command("launch"),0,1);
   check(result==Service.START_NOT_STICKY,"no automatic restart loop");
   check(s.events.get(0).equals("foreground:specialUse"),"promote before work on API37");
   check(s.events.get(1).equals("activity:"+game),"launch game, not helper");
   check(s.events.get(2).equals("start:com.xiaomi.macro.MainService"),"original order");
   check(Boolean.FALSE.equals(s.starts.get(0).extras.get("clickIcon")),"launch false");
   s.events.clear();s.onDestroy();check(s.events.stream().noneMatch(x->x.startsWith("stop:")),"destroy must not close macro");
   tick();Build.VERSION.SDK_INT=29;MacroSessionService old=new MacroSessionService();old.onStartCommand(command("panel"),0,2);noActivity(old);check(old.events.get(0).equals("foreground:legacy"),"API29 overload");
   MacroSessionService empty=new MacroSessionService();empty.onStartCommand(null,0,1);check(empty.events.size()==1&&empty.events.contains("stopSelf"),"null restart safe");
   tick();MacroSessionService denied=new MacroSessionService();denied.denyVendor=true;denied.onStartCommand(command("panel"),0,3);check(denied.events.contains("stopSelf"),"vendor failure stops own session");check(denied.prefs.getString("log","").contains("SecurityException"),"retain error");noActivity(denied);
   tick();MacroSessionService missing=new MacroSessionService();missing.missingVendorService=true;missing.onStartCommand(command("panel"),0,3);check(missing.events.contains("stopSelf"),"missing service stops own session");
   tick();Context request=new Context();MacroController.request(request,game,"launch");noActivity(request);check(request.starts.get(0).component.cls.endsWith("MacroSessionService"),"UI delegates helper");
   Context deniedHelper=new Context();deniedHelper.denyHelper=true;check(MacroController.request(deniedHelper,game,"launch").contains("失败"),"helper denial surfaced");
   Context bad=new Context();MacroController.request(bad,"", "launch");check(bad.events.isEmpty(),"invalid package safe");
   tick();Context retry=new Context();retry.denyVendor=true;
   MacroController.execute(retry,game,"panel");retry.denyVendor=false;
   check(MacroController.execute(retry,game,"panel").startsWith("已发送"),"failed request must not suppress immediate retry");
   tick();Context missingRetry=new Context();missingRetry.missingVendorService=true;
   MacroController.execute(missingRetry,game,"panel");missingRetry.missingVendorService=false;
   check(MacroController.execute(missingRetry,game,"panel").startsWith("已发送"),"null component must not suppress retry");
   MacroController.execute(missingRetry,game,"stop");
   check(MacroController.execute(missingRetry,game,"panel").startsWith("已发送"),"explicit stop clears debounce");
   Context alreadyStopped=new Context();alreadyStopped.stopResult=false;
   check(!MacroController.execute(alreadyStopped,null,"stop").contains("失败"),"absent service stop is idempotent");
   check(alreadyStopped.prefs.getString("log","").contains("stopService=false"),"retain raw stop result");
   check(alreadyStopped.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"cleanup without game name");
   Context stopDenied=new Context();stopDenied.denyStop=true;stopDenied.nm.notify(1,n);
   check(MacroController.execute(stopDenied,game,"stop").contains("失败"),"real stop denial is surfaced");
   check(stopDenied.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"vendor denial still cleans helper");
   check(stopDenied.nm.last==null,"vendor denial still clears notification");
   tick();Context quick=new Context();MacroController.execute(quick,game,"launch");
   MacroController.execute(quick,game,"panel");MacroController.execute(quick,game,"panel");
   check(quick.starts.size()==3,"launch followed by repeated panel taps all delivered");
   MacroController.execute(quick,game,"launch");
   check(quick.starts.size()==3,"panel does not reset launch double tap guard");
   Context session=new Context();session.prefs.edit().putString("lastGame",game).putString("lastProvider",MacroController.SHARK).apply();
   MacroController.execute(session,game,"panel");
   check(session.starts.get(0).component.pkg.equals(MacroController.SHARK),"panel follows existing session provider");
   tick();Context diagnostic=new Context();
   check(MacroController.execute(diagnostic,game,"prepare").startsWith("已发送"),"diagnostic initialization accepted");
   noActivity(diagnostic);
   check(Boolean.FALSE.equals(diagnostic.starts.get(0).extras.get("clickIcon")),"initialization test does not open panel");
   check(diagnostic.prefs.getString("log","").contains("caller=Context"),"record invocation context");
   Context diagnosticNull=new Context();diagnosticNull.missingVendorService=true;
   MacroController.execute(diagnosticNull,game,"prepare");
   check(diagnosticNull.prefs.getString("lastResult","").contains("空值"),"persist null result for advice");
   check(diagnosticNull.prefs.getString("log","").contains("结果"),"null result recorded");
   Context noLaunch=new Context();noLaunch.pm.missingLaunch=true;
   MacroController.execute(noLaunch,game,"launch");
   check(noLaunch.prefs.getString("log","").contains("找不到该应用的启动入口"),"missing launch reason recorded");
   check(noLaunch.starts.isEmpty(),"missing launcher does not initialize vendor");
   MacroController.execute(noLaunch,"bad", "panel");
   check(noLaunch.prefs.getString("lastResult","").contains("有效"),"invalid package result recorded");
   check(missing.prefs.getString("log","").contains("助手退出原因"),"failed session exit reason recorded");
   tick();Context diagCleanup=new Context();diagCleanup.missingVendorService=true;
   DiagnosticRun.run(diagCleanup,game);
   check(diagCleanup.events.contains("stop:com.xiaomi.macro.MainService"),"one tap diagnosis stops native even on null result");
   check(diagCleanup.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"diagnosis cleans own helper");
   check(diagCleanup.prefs.getString("diagnosticLatest","").contains("NATIVE_NULL"),"cleanup must not overwrite diagnostic conclusion");
   check(diagCleanup.prefs.getString("diagnosticFailure","").contains("NATIVE_START"),"failed step captured");
   check(diagCleanup.prefs.getString("diagnosticFailure","").contains("失败时状态"),"failure snapshot stored");
   check(diagCleanup.prefs.getString("lastResult","").contains("空值"),"retain diagnostic outcome after stop");
   String evidence=diagCleanup.prefs.getString("diagnosticFailure","");
   tick();diagCleanup.missingVendorService=false;DiagnosticRun.run(diagCleanup,game);
   check(diagCleanup.prefs.getString("diagnosticFailure","").equals(evidence),"later success preserves historical failure evidence");
   check(!DiagnosticTrace.active(),"trace cleared after operation");
   Context correlated=new Context();MacroController.request(correlated,game,"launch");
   String trace=correlated.starts.get(0).getStringExtra("traceId");
   check(trace!=null&&!trace.isEmpty(),"request carries correlation ID");
   MacroSessionService correlatedService=new MacroSessionService();correlatedService.prefs=correlated.prefs;
   tick();correlatedService.onStartCommand(correlated.starts.get(0),0,9);
   check(correlated.prefs.getString("diagnosticLatest","").contains(trace),"same ID in service result");
   check(correlated.prefs.getString("diagnosticLatest","").contains("HELPER_RECEIVED"),"compact step chain recorded");
   Context exceptionTrace=new Context();exceptionTrace.denyVendor=true;MacroController.execute(exceptionTrace,game,"panel");
   check(exceptionTrace.prefs.getString("diagnosticFailure","").contains("SECURITY_EXCEPTION"),"typed exception classification");
   check(exceptionTrace.prefs.getString("diagnosticFailure","").contains("MacroController.executeInternal"),"useful stack retained");
   check(exceptionTrace.prefs.getString("diagnosticFailure","").contains("NATIVE_START"),"exception tied to correct step");
   String first=exceptionTrace.prefs.getString("diagnosticLatest","");MacroController.execute(exceptionTrace,game,"panel");
   check(!first.equals(exceptionTrace.prefs.getString("diagnosticLatest","")),"unique IDs within same clock tick");
   DiagnosticTrace.clear(exceptionTrace);
   check(!DiagnosticTrace.report(exceptionTrace).contains("SecurityException"),"clear removes stored failure report");
   Context stopError=new Context();stopError.denyStop=true;DiagnosticRun.run(stopError,game);
   check(stopError.prefs.getString("diagnosticFailure","").contains("NATIVE_STOP"),"cleanup failure remains visible");
   check(stopError.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"cleanup failure still stops own helper");
   check(correlated.prefs.getString("diagnosticLatest","").contains("UI→服务等待"),"cross-intent queue delay retained");
   MacroController.log(correlated,"noisy-event-not-for-export");
   check(!DiagnosticTrace.report(correlated).contains("noisy-event-not-for-export"),"export omits routine timeline noise");
   MacroController.log(correlated,"更新准备失败：network-test");
   check(DiagnosticTrace.report(correlated).contains("network-test"),"export retains auxiliary failures");
   Context noGame=new Context();DiagnosticRun.run(noGame,"");
   check(noGame.starts.isEmpty(),"no-game diagnosis does not start vendor");
   check(noGame.events.contains("stop:dev.local.nativemacrohelper.MacroSessionService"),"no-game diagnosis still cleans session");
   check(ReleaseVersion.newer("v1.10.0","1.2.0"),"numeric minor version");
   check(!ReleaseVersion.newer("v1.2.0","1.2.0"),"same version");
   check(!ReleaseVersion.newer("v1.1.9","1.2.0"),"older release");
   check(ReleaseVersion.newer("2.0","1.99.99"),"major version");
   check(!ReleaseVersion.newer("1.2","1.2.0"),"missing patch");
   boolean invalidTag=false; try { ReleaseVersion.newer("v1.3.0-beta", "1.2.0"); } catch(IllegalArgumentException e){invalidTag=true;} check(invalidTag,"reject prerelease tags");
   System.out.println("PASS: "+count+" assertions against production controller/receiver/service (host API doubles only)");
 }
}
'''
(src / 'dev/local/nativemacrohelper/Regression.java').write_text(harness, encoding='utf-8')
sources = list(src.rglob('*.java'))
subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str,sources)], check=True)
subprocess.run(['java', '-cp', str(classes), 'dev.local.nativemacrohelper.Regression'], check=True)
