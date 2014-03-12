
// cat CallFragment.java.old | sed 's/@Nullable //g' | sed -E 's/R\.([a-z_]+)\.([a-z_]+)/getResources().getIdentifier("\2", "\1", getActivity().getPackageName())/g' > CallFragment.java
package com.weemo.phonegap;

import android.animation.ObjectAnimator;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.ToggleButton;

import com.weemo.sdk.Weemo;
import com.weemo.sdk.WeemoCall;
import com.weemo.sdk.WeemoCall.VideoProfile;
import com.weemo.sdk.WeemoCall.VideoSource;
import com.weemo.sdk.WeemoEngine;
import com.weemo.sdk.event.WeemoEventListener;
import com.weemo.sdk.event.call.ReceivingVideoChangedEvent;
import com.weemo.sdk.view.WeemoVideoInFrame;
import com.weemo.sdk.view.WeemoVideoOutPreviewFrame;

/*
 * This is the fragment that controls a call and display it's video views.
 * 
 * It uses the Weemo API to control everything that relates to the call, the video and the audio OUT.
 * 
 * Weemo does not exposes api to control audio IN.
 * This fragment uses Android's AudioManager to control everything that relates to audio IN.
 */
@SuppressWarnings("deprecation")
public class CallFragment extends Fragment {

	/*
	 * Factory (best practice for fragments)
	 */
	public static CallFragment newInstance(int callId, boolean locked) {
		CallFragment fragment = new CallFragment();
		Bundle args = new Bundle();
		args.putInt("callId", callId);
		args.putBoolean("locked", locked);
		fragment.setArguments(args);
		return fragment;
	}
	
	// Buttons
	private ImageView toggleIn;
	private ImageView muteOut;
	private ImageView video;
	private ImageView videoToggle;
	private ImageView hangup;
	private ToggleButton hdToggle;
	
	// The call
	private WeemoCall call;
	
	// This is the correction for the OrientationEventListener.
	// It allows portrait devices (like phones) and landscape devices (like tablets)
	// to have the same orientation result.
	private int correction;
	
	// Whether or not the speakerphone is on.
	// This is use to toggle
	private boolean isSpeakerphoneOn;

	// The audio manager used to control audio
	private AudioManager audioManager;

	// Used to rotate UI elements according to device orientation
	private OrientationEventListener oel;
	
	// Used to receive Intent.ACTION_HEADSET_PLUG, which is when the headset is (un)plugged
	private BroadcastReceiver br;
	
	// Both frames (declared in the XML) that will contain video OUT and IN
	private WeemoVideoOutPreviewFrame videoOutFrame;
	private WeemoVideoInFrame videoInFrame;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

		WeemoEngine weemo = Weemo.instance();
		// This check should has been done by the activity
		assert weemo != null;
		
		call = weemo.getCall(getArguments().getInt("callId"));
		
		// Register as event listener
		Weemo.eventBus().register(this);
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View root = inflater.inflate(getResources().getIdentifier("weemo_fragment_call", "layout", getActivity().getPackageName()), container, false);

		// Get the OUT frame from the inflated view and set the call to use it
		videoOutFrame = (WeemoVideoOutPreviewFrame) root.findViewById(getResources().getIdentifier("video_out", "id", getActivity().getPackageName()));
		videoOutFrame.setUseDeviceOrientation(getArguments().getBoolean("locked"));
		call.setVideoOut(videoOutFrame);

		// Get the IN frame from the inflated view and set the call to use it
		// We set its display to follow device orientation because we have blocked the device rotation
		videoInFrame = (WeemoVideoInFrame) root.findViewById(getResources().getIdentifier("video_in", "id", getActivity().getPackageName()));
		videoInFrame.setDisplayFollowDeviceOrientation(getArguments().getBoolean("locked"));
		call.setVideoIn(videoInFrame);

		// This will call setOrientation each time the device orientation have changed
		// This allows us to display the control ui buttons in the correct orientation
		oel = new OrientationEventListener(getActivity(), SensorManager.SENSOR_DELAY_NORMAL) {
			int lastOrientation = -1;
			@Override public void onOrientationChanged(int orientation) {
				if (orientation > 45 && orientation <= 135)
					orientation = 270;
				else if (orientation > 135 && orientation <= 225)
					orientation = 180;
				else if (orientation > 225 && orientation <= 315)
					orientation = 90;
				else if (orientation > 315 || orientation <= 45)
					orientation = 0;
				orientation = (orientation + 360 - correction) % 360;
				if (lastOrientation != orientation) {
					setOrientation(orientation);
					lastOrientation = orientation;
				}
			}
		};
		
		// Simple brodcast receiver that will call setSpeakerphoneOn when receiving an intent
		// It will be registered for Intent.ACTION_HEADSET_PLUG intents
		br = new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());
			}
		};
		
		// Button that toggles audio route
		toggleIn = (ImageView) root.findViewById(getResources().getIdentifier("toggle_in", "id", getActivity().getPackageName()));
		toggleIn.setOnClickListener(new OnClickListener() {
			@Override public void onClick(View v) {
				setSpeakerphoneOn(!isSpeakerphoneOn);
			}
		});

		// Button that toggles audio OUT mute
		muteOut = (ImageView) root.findViewById(getResources().getIdentifier("mute_out", "id", getActivity().getPackageName()));
		muteOut.setOnClickListener(new OnClickListener() {
			boolean mute = false;
			@Override public void onClick(View v) {
				mute = !mute;
				if (mute) {
					call.audioMute();
					muteOut.setImageResource(getResources().getIdentifier("weemo_mic_muted", "drawable", getActivity().getPackageName()));
				}
				else {
					call.audioUnMute();
					muteOut.setImageResource(getResources().getIdentifier("weemo_mic_on", "drawable", getActivity().getPackageName()));
				}
			}
		});

		// Button that toggles sending video
		// Note that we also toggle the videoOutFrame visibility
		video = (ImageView) root.findViewById(getResources().getIdentifier("video", "id", getActivity().getPackageName()));
		video.setOnClickListener(new OnClickListener() {
			boolean video = true;
			@Override public void onClick(View v) {
				video = !video;
				if (video) {
					videoOutFrame.setVisibility(View.VISIBLE);
					call.videoStart();
					videoToggle.setVisibility(View.VISIBLE);
				}
				else {
					videoOutFrame.setVisibility(View.GONE);
					call.videoStop();
					videoToggle.setVisibility(View.GONE);
				}
			}
		});

		// Button that toggles sending video source
		videoToggle = (ImageView) root.findViewById(getResources().getIdentifier("video_toggle", "id", getActivity().getPackageName()));
		videoToggle.setOnClickListener(new OnClickListener() {
			boolean front = true;
			@Override public void onClick(View v) {
				front = !front;
				call.setVideoSource(front ? VideoSource.FRONT : VideoSource.BACK);
			}
		});

		// Button that hangs up the call
		hangup = (ImageView) root.findViewById(getResources().getIdentifier("hangup", "id", getActivity().getPackageName()));
		hangup.setOnClickListener(new OnClickListener() {
			@Override public void onClick(View v) {
				call.hangup();
			}
		});

		// Button that toggles if we want to receive HD video or not
		hdToggle = (ToggleButton) root.findViewById(getResources().getIdentifier("hd_toggle", "id", getActivity().getPackageName()));
		hdToggle.setOnClickListener(new OnClickListener() {
			@Override public void onClick(View v) {
				boolean hd = hdToggle.isChecked();
				call.setInVideoProfile(hd ? VideoProfile.HD : VideoProfile.SD);
			}
		});
		
		// By default, we set the audio IN route according to isWiredHeadsetOn
		setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());

		// Get the correction for the OrientationEventListener
		switch (getActivity().getWindowManager().getDefaultDisplay().getRotation()) {
			case Surface.ROTATION_0:   correction = 0;   break ;
			case Surface.ROTATION_90:  correction = 90;  break ;
			case Surface.ROTATION_180: correction = 180; break ;
			case Surface.ROTATION_270: correction = 270; break ;
		}

		// Sets the camera preview dimensions according to whether or not the remote contact has started his video
		setVideoOutFrameDimensions(call.isReceivingVideo());
		
		return root;
	}
	
	@Override
	public void onDestroy() {
		// Unregister as event listener
		Weemo.eventBus().unregister(this);

		super.onDestroy();
	}

	/*
	 * Uses AudioManager to route audio to Speakerphone or not
	 */
	private void setSpeakerphoneOn(boolean on) {
		audioManager.setSpeakerphoneOn(on);
		toggleIn.setImageResource(getResources().getIdentifier(on ? "weemo_volume_on" : "weemo_volume_muted", "drawable", getActivity().getPackageName()));
		isSpeakerphoneOn = on;
	}

	/*
	 * Sets the dimension preview dimensions according to whether or not we are receiving video.
	 * If we are, we need to have a small preview.
	 * If we are not, the preview needs to fill the space.
	 */
	private void setVideoOutFrameDimensions(boolean isReceivingVideo) {
		DisplayMetrics metrics = new DisplayMetrics();
		getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

		LayoutParams params = videoOutFrame.getLayoutParams();
		if (isReceivingVideo) {
			params.width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32 * 4, metrics);
			params.height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32 * 4, metrics);
		}
		else {
			params.width = LayoutParams.MATCH_PARENT;
			params.height = LayoutParams.MATCH_PARENT;
		}
		
		videoOutFrame.setLayoutParams(params);
	}

	/*
	 * Animate the property of an object.
	 * may first add or remove 360 degrees to the property
	 * to ensure that the property will rotate in the right direction
	 */
	private void animate(View view, String property, float current, int angle) {
		if (angle - current > 180)
			ObjectAnimator.ofFloat(view, property, current + 360).setDuration(0).start();
		else if (current - angle > 180)
			ObjectAnimator.ofFloat(view, property, current - 360).setDuration(0).start();
		
		ObjectAnimator.ofFloat(view, property, angle).start();
	}

	// Sets orientation of all UI elements
	// This is called by the OrientationEventListener
	private void setOrientation(int orientation) {
		animate(toggleIn,    "rotation", toggleIn.getRotation(),    orientation);
		animate(muteOut,     "rotation", muteOut.getRotation(),     orientation);
		animate(video,       "rotation", video.getRotation(),       orientation);
		animate(videoToggle, "rotation", videoToggle.getRotation(), orientation);
		animate(hangup,      "rotation", hangup.getRotation(),      orientation);
		animate(hdToggle,    "rotation", hdToggle.getRotation(),    orientation);
	}
	
	@Override
	public void onStart() {
		super.onStart();

		// Start listening for orientation changes
		if (oel.canDetectOrientation())
			oel.enable();
		
		// Register the BrodcastReceiver to detect headset connection change
		IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
		filter.setPriority(0);
		getActivity().registerReceiver(br, filter);
	}

	@Override
	public void onStop() {
		// We do not need to listen for orientation change while we are in the background
		// Beside, not stoping this will generate a leak when the fragment is destroyed
		oel.disable();

		// Same as the line above
		getActivity().unregisterReceiver(br);

		super.onStop();
	}
	
	/*
	 * This listener catches ReceivingVideoChangedEvent
	 * 1. It is annotated with @WeemoEventListener
	 * 2. It takes one argument which type is ReceivingVideoChangedEvent
	 * 3. It's fragment object has been registered with Weemo.getEventBus().register(this) in onCreate()
	 */
	@WeemoEventListener
	public void onReceivingVideoChanged(ReceivingVideoChangedEvent e) {
		// First, we check that this event concerns the call we are monitoring
		if (e.getCall().getCallId() != call.getCallId())
			return ;
		
		// Sets the camera preview dimensions according to whether or not the remote contact has started his video
		setVideoOutFrameDimensions(e.isReceivingVideo());
	}
	
}
