package me.app.fermatax.auto;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;
import static me.aap.fermata.BuildConfig.APPLICATION_ID;
import static me.app.fermatax.auto.LauncherActivity.INTENT_EXTRA_MODE;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.InstallSourceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.os.Binder;

/**
 * @author Andrey Pavlenko
 */
public class Xposed implements IXposedHookLoadPackage {
	private static final String AA_PKG = "com.google.android.projection.gearhead";
	private static final String GPLAY_PKG = "com.android.vending";
	private static final String EV_SRV_CON_FIELD = "__fermata_event_dispatcher_service__";
	private static final String PKG_MGR_CLASS = "android.content.pm.IPackageManager.Stub.Proxy";
	private static final String DISPATCHER_CLASS_NAME =
			"me.app.fermatax.auto.XposedEventDispatcherService";

	private volatile int molaUid = -1;

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
		var pkgName = lpparam.packageName;
		log("[Fermata] Hooking into package " + pkgName);

		if (AA_PKG.equals(pkgName) || APPLICATION_ID.equals(pkgName)) {
			if (VERSION.SDK_INT >= VERSION_CODES.R) {
				findAndHookMethod(InstallSourceInfo.class, "getInitiatingPackageName",
						new XC_MethodHook() {

							@Override
							protected void afterHookedMethod(MethodHookParam param) {
								param.setResult(GPLAY_PKG);
							}
						});
			} else {
				findAndHookMethod(PKG_MGR_CLASS, lpparam.classLoader, "getInstallerPackageName",
						String.class, new XC_MethodHook() {
							@Override
							protected void afterHookedMethod(MethodHookParam param) {
								param.setResult(GPLAY_PKG);
							}
						});
			}
			return;
		}

		if ("android".equals(pkgName)) {
			hookSystemServer(lpparam.classLoader);
			return;
		}

		findAndHookMethod(Activity.class, "onStart", new XC_MethodHook() {

			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				var a = (Activity) param.thisObject;
				debug("onStart: ", a);
				register(a);
				handleIntent(a, a.getIntent());
			}
		});
		findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {

			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				var a = (Activity) param.thisObject;
				debug("onResume: ", a);
				register(a);
			}
		});
		findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {

			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				var a = param.thisObject;
				debug("onPause: ", a);
				var con = (EventServiceConnection) getAdditionalInstanceField(a, EV_SRV_CON_FIELD);
				if (con != null) con.unregister();
			}
		});
		findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {

			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				var a = param.thisObject;
				debug("onDestroy: ", a);
				var con = (EventServiceConnection) removeAdditionalInstanceField(a, EV_SRV_CON_FIELD);
				if (con != null) con.disconnect();
			}
		});
		findAndHookMethod(Activity.class, "onConfigurationChanged", Configuration.class,
				new XC_MethodHook() {

					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						var a = param.thisObject;
						debug("onConfigurationChanged: ", a);
						var con = (EventServiceConnection) getAdditionalInstanceField(a, EV_SRV_CON_FIELD);
						if ((con != null) && (con.mirroringMode != 0)) con.configureActivity();
					}
				});
		findAndHookMethod(Activity.class, "onNewIntent", Intent.class, new XC_MethodHook() {

			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				var intent = (Intent) param.args[0];
				if (!intent.hasExtra(INTENT_EXTRA_MODE)) return;
				var a = (Activity) param.thisObject;
				debug("onNewIntent: ", a);
				handleIntent(a, intent);
			}
		});
	}

	// system_server (android): Torque yontemi icin cerceve kancalari.
	// Mola'ya guvenilir ekran olusturma izni verir ve ikincil ekrandaki uygulamalarin
	// kilit ekraninda calismasini saglar. Kapsam (scope) "android" olmali.
	private void hookSystemServer(ClassLoader cl) {
		// 1) DisplayManagerService.checkCallingPermission -> Mola/root/shell icin ADD_TRUSTED_DISPLAY ver.
		try {
			Class<?> dms = XposedHelpers.findClass("com.android.server.display.DisplayManagerService", cl);
			int n = XposedBridge.hookAllMethods(dms, "checkCallingPermission", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) {
					if ((param.args == null) || (param.args.length < 1)) return;
					Object a0 = param.args[0];
					if (!(a0 instanceof String)) return;
					String perm = (String) a0;
					if (!perm.equals("android.permission.ADD_TRUSTED_DISPLAY")
							&& !perm.equals("android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")) return;
					int uid = Binder.getCallingUid();
					if (isMolaUid(uid) || (uid == 0) || (uid == 2000)) param.setResult(true);
				}
			}).size();
			log("[Fermata] checkCallingPermission hooks=" + n);
		} catch (Throwable t) {
			log("[Fermata] checkCallingPermission hook failed: " + t);
		}

		// 2) VirtualDisplayAdapter.isNeverBlank -> trusted + own_group ekran kilitte kararmasin.
		try {
			Class<?> vda = XposedHelpers.findClass("com.android.server.display.VirtualDisplayAdapter", cl);
			XposedBridge.hookAllMethods(vda, "isNeverBlank", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) {
					if ((param.args == null) || (param.args.length < 1)) return;
					Object a0 = param.args[0];
					if (!(a0 instanceof Integer)) return;
					int f = (Integer) a0;
					// TRUSTED(1024) + OWN_DISPLAY_GROUP(2048), AUTO_MIRROR(16) degil.
					if (((f & 1024) != 0) && ((f & 2048) != 0) && ((f & 16) == 0)) param.setResult(true);
				}
			});
		} catch (Throwable t) {
			log("[Fermata] isNeverBlank hook failed: " + t);
		}

		// 3) ActivityRecord: ikincil ekrandaki (displayId != 0) uygulamalar kilitte gorunsun,
		//    relaunch olmasin.
		try {
			final Class<?> ar = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl);
			XC_MethodHook showHook = new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					if (Boolean.TRUE.equals(param.getResult())) return;
					Object rec = pickActivityRecord(param, ar);
					if ((rec != null) && (displayIdOf(rec) > 0)) param.setResult(Boolean.TRUE);
				}
			};
			int s = XposedBridge.hookAllMethods(ar, "canShowWhenLocked", showHook).size();
			s += XposedBridge.hookAllMethods(ar, "canShowWhenLockedInner", showHook).size();
			XposedBridge.hookAllMethods(ar, "shouldRelaunchLocked", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					if (Boolean.FALSE.equals(param.getResult())) return;
					Object rec = pickActivityRecord(param, ar);
					if (rec == null) rec = param.thisObject;
					if ((rec != null) && ar.isInstance(rec) && (displayIdOf(rec) > 0)) {
						param.setResult(Boolean.FALSE);
					}
				}
			});
			log("[Fermata] canShowWhenLocked hooks=" + s);
		} catch (Throwable t) {
			log("[Fermata] lock-screen hook failed: " + t);
		}

		log("[Fermata] system_server hooks installed");
	}

	private boolean isMolaUid(int uid) {
		if (uid < 10000) return false;
		if (molaUid >= 0) return uid == molaUid;
		try {
			Object pm = XposedHelpers.callStaticMethod(Class.forName("android.app.AppGlobals"), "getPackageManager");
			Object name = XposedHelpers.callMethod(pm, "getNameForUid", uid);
			if (APPLICATION_ID.equals(name)) {
				molaUid = uid;
				return true;
			}
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	private static Object pickActivityRecord(XC_MethodHook.MethodHookParam param, Class<?> ar) {
		if ((param.thisObject != null) && ar.isInstance(param.thisObject)) return param.thisObject;
		if (param.args != null) {
			for (Object a : param.args) if ((a != null) && ar.isInstance(a)) return a;
		}
		return null;
	}

	private static int displayIdOf(Object activityRecord) {
		try {
			Object r = XposedHelpers.callMethod(activityRecord, "getDisplayId");
			if (r instanceof Integer) return (Integer) r;
		} catch (Throwable ignored) {
		}
		return -1;
	}

	private static void register(Activity a) {
		var con = (EventServiceConnection) getAdditionalInstanceField(a, EV_SRV_CON_FIELD);
		if (con == null) {
			con = new EventServiceConnection(a);
			setAdditionalInstanceField(a, EV_SRV_CON_FIELD, con);
		}
		con.register();
	}

	private static void handleIntent(Activity a, Intent intent) {
		if (intent == null) return;
		var con = (EventServiceConnection) getAdditionalInstanceField(a, EV_SRV_CON_FIELD);
		if (con != null) {
			con.mirroringMode = intent.getIntExtra(INTENT_EXTRA_MODE, 0);
			con.configureActivity();
		}
	}

	private static void debug(Object... msg) {
		Object message;
		if (msg.length == 1) {
			message = msg[0];
		} else {
			var sb = new StringBuilder("[Fermata] ");
			for (var m : msg) sb.append(m);
			message = sb;
		}
		log(String.valueOf(message));
	}

	private static class EventServiceConnection extends Handler implements ServiceConnection {
		private static final int FULLSCREEN_FLAGS =
				SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_LOW_PROFILE |
						SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE |
						SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
		private static final int SCREEN_ON_FLAGS =
				FLAG_KEEP_SCREEN_ON | FLAG_TURN_SCREEN_ON | FLAG_DISMISS_KEYGUARD | FLAG_SHOW_WHEN_LOCKED;
		private final int[] loc = new int[2];
		private final Messenger replyTo = new Messenger(this);
		private final Activity activity;
		private Messenger messenger;
		private int registrationKey;
		private int mirroringMode;
		private boolean connecting;
		private boolean registered;

		EventServiceConnection(Activity activity) {
			super(Looper.getMainLooper());
			this.activity = activity;
			if (VERSION.SDK_INT >= VERSION_CODES.Q) {
				activity.setInheritShowWhenLocked(true);
			}
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			debug("Connected: ", activity);
			connecting = false;
			messenger = new Messenger(service);
			if (registered) register();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			debug("Disconnected: ", activity);
			connecting = false;
			messenger = null;
			restore();
		}

		@Override
		public void onBindingDied(ComponentName name) {
			debug("Connection died: ", activity);
			connecting = false;
			messenger = null;
			restore();
			connect();
		}

		@Override
		public void handleMessage(@NonNull Message msg) {
			switch (msg.what) {
				case XposedEventDispatcherService.MSG_MIRROR_MODE -> {
					mirroringMode = msg.arg1;
					configureActivity();
				}
				case XposedEventDispatcherService.MSG_MOTION_EVENT -> {
					MotionEvent e;
					if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
						e = msg.getData().getParcelable("e", MotionEvent.class);
					} else {
						e = msg.getData().getParcelable("e");
					}
					if (e == null) return;
					activity.getWindow().getDecorView().getLocationOnScreen(loc);
					e.setLocation(e.getX() - loc[0], e.getY() - loc[1]);
					activity.dispatchTouchEvent(e);
				}
				case XposedEventDispatcherService.MSG_BACK_EVENT -> activity.onBackPressed();
			}
		}

		void connect() {
			if (connecting) {
				debug("Waiting for connection: ", activity);
				return;
			}
			if (messenger != null) {
				debug("Already connected: ", activity);
				return;
			}
			try {
				connecting = true;
				debug("Connecting: ", activity);
				var i = new Intent();
				i.setComponent(new ComponentName(APPLICATION_ID, DISPATCHER_CLASS_NAME));
				if (!activity.bindService(i, this, 0)) {
					debug("Unable to establish connection: ", activity);
					connecting = false;
					activity.unbindService(this);
				}
			} catch (Exception err) {
				connecting = false;
				debug("Connection attempt failed: ", activity);
			}
		}

		void disconnect() {
			try {
				debug("Disconnecting: ", activity);
				restore();
				activity.unbindService(this);
			} catch (Exception ignore) {}
		}

		void register() {
			if (isWindowMode()) return;
			registered = true;
			if (messenger == null) {
				connect();
				return;
			}
			try {
				debug("Registering: ", activity);
				registrationKey = Long.hashCode(SystemClock.uptimeMillis()) ^ hashCode();
				var msg =
						Message.obtain(null, XposedEventDispatcherService.MSG_REGISTER, registrationKey, 0);
				msg.replyTo = this.replyTo;
				messenger.send(msg);
			} catch (RemoteException err) {
				debug(err, "Failed to register: ", activity);
			}
		}

		void unregister() {
			registered = false;
			restore();
			if (messenger == null) return;
			try {
				debug("Unregistering: ", activity);
				var msg =
						Message.obtain(null, XposedEventDispatcherService.MSG_UNREGISTER, registrationKey, 0);
				messenger.send(msg);
			} catch (RemoteException err) {
				debug(err, "Failed to unregister: ", activity);
			}
		}

		private void restore() {
			mirroringMode = 0;
			configureActivity();
		}

		void configureActivity() {
			if (isWindowMode()) {
				unregister();
				return;
			}
			var w = activity.getWindow();
			var decor = w.getDecorView();

			if (mirroringMode == 0) {
				debug("Disabling mirroring mode: ", activity);
				w.clearFlags(SCREEN_ON_FLAGS);
				decor.setSystemUiVisibility(SYSTEM_UI_FLAG_VISIBLE);
				activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
			} else {
				debug("Enabling mirroring mode: ", activity);
				w.addFlags(SCREEN_ON_FLAGS);
				decor.setSystemUiVisibility(FULLSCREEN_FLAGS);
				activity.setRequestedOrientation(
						(mirroringMode == 1) ? SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
								SCREEN_ORIENTATION_SENSOR_PORTRAIT);
			}
		}

		private boolean isWindowMode() {
			return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) &&
					(activity.isInMultiWindowMode() || activity.isInPictureInPictureMode());
		}
	}
}
