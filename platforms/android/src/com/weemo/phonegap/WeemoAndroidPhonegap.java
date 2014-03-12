package com.weemo.phonegap;

import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import com.weemo.sdk.Weemo;
import com.weemo.sdk.WeemoCall;
import com.weemo.sdk.WeemoCall.CallStatus;
import com.weemo.sdk.WeemoEngine;
import com.weemo.sdk.WeemoEngine.UserType;
import com.weemo.sdk.event.WeemoEventListener;
import com.weemo.sdk.event.call.CallCreatedEvent;
import com.weemo.sdk.event.call.CallStatusChangedEvent;
import com.weemo.sdk.event.call.ReceivingVideoChangedEvent;
import com.weemo.sdk.event.global.AuthenticatedEvent;
import com.weemo.sdk.event.global.CanCreateCallChangedEvent;
import com.weemo.sdk.event.global.ConnectedEvent;
import com.weemo.sdk.event.global.StatusEvent;

public class WeemoAndroidPhonegap extends CordovaPlugin {

	private CallbackContext connectionCallback = null;
	private CallbackContext authenticationCallback = null;
	private CallbackContext callWindowCallback = null;
	
	private Map<String, CallbackContext> statusCallbacks = new HashMap<String, CallbackContext>();

	private AudioManager audioManager;
	
	@SuppressWarnings("serial")
	private static class DirectError extends Exception {
		int code;
		
		public DirectError(int code) {
			this.code = code;
		}
		
		public int getCode() {
			return code;
		}
	}

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);

		Weemo.ensureNativeLoad();

		Weemo.eventBus().register(this);
		
		audioManager = (AudioManager) cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
	}

	@Override
	public void onDestroy() {
		Weemo.disconnect();
		
		Weemo.eventBus().unregister(this);
		super.onDestroy();
	}

	@Override
	public boolean execute(String action, CordovaArgs args, CallbackContext callback) throws JSONException {
		try {
			if ("initialize".equals(action))
				initialize(callback, args.getString(0));
			else if ("authent".equals(action))
				authent(callback, args.getString(0), args.getInt(1));
			else if ("setDisplayName".equals(action))
				setDisplayName(callback, args.getString(0));
			else if ("getStatus".equals(action))
				getStatus(callback, args.getString(0));
			else if ("createCall".equals(action))
				createCall(callback, args.getString(0));
			else if ("disconnect".equals(action))
				disconnect(callback);
			else if ("muteOut".equals(action))
				muteOut(callback, args.getInt(0), args.getBoolean(1));
			else if ("resume".equals(action))
				resume(callback, args.getInt(0));
			else if ("hangup".equals(action))
				hangup(callback, args.getInt(0));
			else if ("displayCallWindow".equals(action))
				displayCallWindow(callback, args.getInt(0), args.getBoolean(1));
			else if ("setAudioRoute".equals(action))
				setAudioRoute(callback, args.getBoolean(0));
			else if ("getOSInfos".equals(action))
				getOSInfos(callback);
			else
				return false;
		}
		catch (DirectError e) {
			callback.error(e.getCode());
		}
		return true;
	}
	
	private void initialize(CallbackContext callback, String appId) {
		connectionCallback = callback;
		Weemo.initialize(appId, cordova.getActivity());
	}

	private WeemoEngine _getWeemo() throws DirectError {
		WeemoEngine weemo = Weemo.instance();
		if (weemo == null)
			throw new DirectError(-1);
		return weemo;
	}
	
	private void authent(CallbackContext callback, String token, int type) throws DirectError {
		WeemoEngine weemo = _getWeemo();
		authenticationCallback = callback;
		weemo.authenticate(token, type == 0 ? UserType.INTERNAL : UserType.EXTERNAL);
	}

	private void setDisplayName(CallbackContext callback, String displayName) throws DirectError {
		WeemoEngine weemo = _getWeemo();
		weemo.setDisplayName(displayName);
		callback.success();
	}

	private void getStatus(CallbackContext callback, String userID) throws DirectError {
		WeemoEngine weemo = _getWeemo();
		statusCallbacks.put(userID, callback);
		weemo.getStatus(userID);
	}

	private void createCall(CallbackContext callback, String userID) throws DirectError {
		WeemoEngine weemo = _getWeemo();
		weemo.createCall(userID);
		callback.success();
	}

	private void disconnect(CallbackContext callback) {
		Weemo.disconnect();
		callback.success();
	}

	private WeemoCall _getCall(int callID) throws DirectError {
		WeemoCall call = _getWeemo().getCall(callID);
		if (call == null)
			throw new DirectError(-2);
		return call;
	}
	
	private void muteOut(CallbackContext callback, int callID, boolean mute) throws DirectError {
		WeemoCall call = _getCall(callID);
		if (mute)
			call.audioMute();
		else
			call.audioUnMute();
		callback.success();
	}

	private void resume(CallbackContext callback, int callID) throws DirectError {
		WeemoCall call = _getCall(callID);
		call.resume();
		callback.success();
	}

	private void hangup(CallbackContext callback, int callID) throws DirectError {
		WeemoCall call = _getCall(callID);
		call.hangup();
		callback.success();
	}

	private void displayCallWindow(CallbackContext callback, final int callId, final boolean canComeBack) {
		final Activity activity = cordova.getActivity();
		callWindowCallback = callback;
		activity.runOnUiThread(new Runnable() {
			@Override public void run() {
				Intent intent = new Intent(cordova.getActivity(), CallActivity.class);
				intent.putExtra("canComeBack", canComeBack);
				intent.putExtra("callId", callId);
				cordova.startActivityForResult(WeemoAndroidPhonegap.this, intent, 2142);
			}
		});
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		Log.i("onActivityResult", "onActivityResult " + requestCode + " " + resultCode);
		super.onActivityResult(requestCode, resultCode, intent);
		if (requestCode == 2142 && callWindowCallback != null) {
			callWindowCallback.success();
			callWindowCallback = null;
		}
	}

	private void setAudioRoute(CallbackContext callback, boolean speakers) {
		audioManager.setSpeakerphoneOn(speakers);
		webView.sendJavascript("Weemo.internal.audioRouteChanged(" + (speakers ? "true" : "false") + ")");
		callback.success();
	}
	
	private void getOSInfos(CallbackContext callback) throws JSONException {
		JSONObject infos = new JSONObject();

		infos.put("OS", "Android");
		infos.put("version", Build.VERSION.RELEASE);

	    DisplayMetrics metrics = new DisplayMetrics();
		cordova.getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

		float density  = cordova.getActivity().getResources().getDisplayMetrics().density;
	    float dpWidth  = metrics.widthPixels / density;
	    infos.put("deviceType", dpWidth < 600 ? "phone" : (dpWidth < 720 ? "tablet-small" : "tablet-big"));
	    
	    infos.put("screenWidth", metrics.widthPixels);
	    infos.put("screenHeight", metrics.heightPixels);
	    
	    callback.success(infos);
	}

	@WeemoEventListener
	public void onConnected(ConnectedEvent e) {
		if (connectionCallback == null)
			return ;
		
		if (e.getError() == null)
			connectionCallback.success();
		else
			connectionCallback.error(e.getError().code());
		
		connectionCallback = null;
	}

	@WeemoEventListener
	public void onAuthenticated(AuthenticatedEvent e) {
		if (authenticationCallback == null)
			return ;
		
		if (e.getError() == null)
			authenticationCallback.success();
		else
			authenticationCallback.error(e.getError().code());
		
		authenticationCallback = null;
	}

	@WeemoEventListener
	public void onStatus(StatusEvent e) {
		CallbackContext callback = statusCallbacks.get(e.getUserID());
		if (callback == null)
			return ;
		
		callback.success(e.canBeCalled() ? 1 : 0);
		
		authenticationCallback = null;
	}

	@WeemoEventListener
	public void onCallCreated(CallCreatedEvent e) {
		webView.sendJavascript("Weemo.internal.callCreated(" + e.getCall().getCallId() + ", \'" + e.getCall().getContactDisplayName().replace("'", "\\'") + "\');");
	}

	@SuppressWarnings("deprecation")
	@WeemoEventListener
	public void onCallStatusChanged(CallStatusChangedEvent e) {
		webView.sendJavascript("Weemo.internal.callStatusChanged(" + e.getCall().getCallId() + ", " + e.getCallStatus().getCode() + ");");
		if (e.getCallStatus() == CallStatus.PROCEEDING) {
			boolean speakers = !audioManager.isWiredHeadsetOn();
			audioManager.setSpeakerphoneOn(speakers);
			webView.sendJavascript("Weemo.internal.audioRouteChanged(" + (speakers ? "true" : "false") + ");");
		}
	}

	@WeemoEventListener
	public void onCanCreateCallChanged(CanCreateCallChangedEvent e) {
		webView.sendJavascript("Weemo.internal.connectionChanged(" + (e.getError() != null ? e.getError().code() : 0) + ");");
	}

	@WeemoEventListener
	public void onReceivingVideoChanged(ReceivingVideoChangedEvent e) {
		Log.i("JAVASCRIPT", "Weemo.internal.videoInChanged(" + e.getCall().getCallId() + ", " + (e.isReceivingVideo() ? "true" : "false") + ");");
		webView.sendJavascript("Weemo.internal.videoInChanged(" + e.getCall().getCallId() + ", " + (e.isReceivingVideo() ? "true" : "false") + ");");
	}
}
