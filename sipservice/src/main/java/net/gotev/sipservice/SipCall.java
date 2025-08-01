package net.gotev.sipservice;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;
import android.view.Surface;

import net.gotev.sipservice.Logger;

import org.pjsip.pjsua2.AudDevManager;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CallSetting;
import org.pjsip.pjsua2.CallVidSetStreamParam;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.Media;
import org.pjsip.pjsua2.MediaFmtChangedEvent;
import org.pjsip.pjsua2.OnCallMediaEventParam;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.OnStreamDestroyedParam;
import org.pjsip.pjsua2.RtcpStreamStat;
import org.pjsip.pjsua2.StreamInfo;
import org.pjsip.pjsua2.StreamStat;
import org.pjsip.pjsua2.VideoPreview;
import org.pjsip.pjsua2.VideoPreviewOpParam;
import org.pjsip.pjsua2.VideoWindow;
import org.pjsip.pjsua2.VideoWindowHandle;
import org.pjsip.pjsua2.pjmedia_dir;
import org.pjsip.pjsua2.pjmedia_event_type;
import org.pjsip.pjsua2.pjmedia_rtcp_fb_type;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_role_e;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsua2;
import org.pjsip.pjsua2.pjsua_call_flag;
import org.pjsip.pjsua2.pjsua_call_media_status;
import org.pjsip.pjsua2.pjsua_call_vid_strm_op;
import org.pjsip.pjsua2.pjsua_vid_req_keyframe_method;

/**
 * Wrapper around PJSUA2 Call object.
 * @author gotev (Aleksandar Gotev)
 */
@SuppressWarnings("unused")
public class SipCall extends Call {

    private static final String LOG_TAG = SipCall.class.getSimpleName();

    private final SipAccount account;
    private boolean localHold = false;
    private boolean localMute = false;
    private boolean localVideoMute = false;
    private long connectTimestamp = 0;
    private ToneGenerator toneGenerator;
    private boolean videoCall = false;
    private boolean videoConference = false;
    private boolean frontCamera = true;

    private VideoWindow mVideoWindow;
    private VideoPreview mVideoPreview;

    private StreamInfo streamInfo = null;
    private StreamStat streamStat = null;

    /**
     * Incoming call constructor.
     * @param account the account which own this call
     * @param callID the id of this call
     */
    public SipCall(SipAccount account, int callID) {
        super(account, callID);
        this.account = account;
        mVideoPreview = null;
        mVideoWindow = null;
    }

    /**
     * Outgoing call constructor.
     * @param account account which owns this call
     */
    public SipCall(SipAccount account) {
        super(account);
        this.account = account;
    }

    public int getCurrentState() {
        try {
            CallInfo info = getInfo();
            return info.getState();
        } catch (Exception exc) {
            Logger.error(getClass().getSimpleName(), "Error while getting call Info", exc);
            return pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED;
        }
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        try {
            CallInfo info = getInfo();
            Logger.debug(LOG_TAG, "Call State: " + info.getState() +
                    ", Role: " + info.getRole() +
                    ", LastReason: " + info.getLastReason() +
                    ", LastStatusCode: " + info.getLastStatusCode() +
                    ", RemoteUri: " + info.getRemoteUri() +
                    ", LocalContact: " + info.getLocalContact() +
                    ", RemoteContact: " + info.getRemoteContact() +
                    ", CallId: " + info.getCallIdString() +
                    ", LocalUri: " + info.getLocalUri());

            int callID = info.getId();
            int callState = info.getState();
            int callStatus = pjsip_status_code.PJSIP_SC_NULL;

            try {
                callStatus = info.getLastStatusCode();
                account.getService().setLastCallStatus(callStatus);
            } catch(Exception ex) {
                Logger.error(LOG_TAG, "Error while getting call status", ex);
            }

            if (callState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                checkAndStopLocalRingBackTone();
                stopVideoFeeds();
                account.removeCall(callID);
                if (connectTimestamp > 0 && streamInfo != null && streamStat != null) {
                    try {
                        sendCallStats(callID, info.getConnectDuration().getSec(), callStatus);
                    } catch (Exception ex) {
                        Logger.error(LOG_TAG, "Error while sending call stats", ex);
                        throw ex;
                    }
                }
            } else if (callState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                handleMedia(info);
                checkAndStopLocalRingBackTone();
                connectTimestamp = System.currentTimeMillis();
                if (videoCall) {
                    setVideoMute(false);
                }
            } else if (callState == pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
                int statusCode = info.getLastStatusCode();
                if (statusCode == pjsip_status_code.PJSIP_SC_RINGING && info.getRole() == pjsip_role_e.PJSIP_ROLE_UAC) {
                    checkAndStopLocalRingBackTone();
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80); // Use STREAM_VOICE_CALL, reduce volume to 80
                    toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
                } else if (statusCode == pjsip_status_code.PJSIP_SC_PROGRESS) {
                    checkAndStopLocalRingBackTone();
                }
            }

            account.getService().getBroadcastEmitter()
                    .callState(account.getData().getIdUri(), callID, callState, callStatus, connectTimestamp);

            if (callState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                account.getService().setLastCallStatus(0);
                delete();
            }
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "onCallState: error while getting call info", exc);
        }
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        CallInfo info;
        try {
            info = getInfo();
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "onCallMediaState: error while getting call info", exc);
            return;
        }

        for (int i = 0; i < info.getMedia().size(); i++) {
            Media media = getMedia(i);
            CallMediaInfo mediaInfo = info.getMedia().get(i);

            if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO
                    && media != null
                    && mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {

                handleAudioMedia(media);

            } else if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO
                    && mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                    && mediaInfo.getVideoIncomingWindowId() != pjsua2.INVALID_ID) {

                handleVideoMedia(mediaInfo);
            }
        }
    }

    @Override
    public void onCallMediaEvent(OnCallMediaEventParam prm) {
        int evType = prm.getEv().getType();
        switch (evType) {
            case pjmedia_event_type.PJMEDIA_EVENT_FMT_CHANGED:
                try {
                    CallInfo callInfo = getInfo();
                    CallMediaInfo mediaInfo = callInfo.getMedia().get((int)prm.getMedIdx());
                    if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO &&
                            mediaInfo.getDir() == pjmedia_dir.PJMEDIA_DIR_DECODING) {
                        MediaFmtChangedEvent fmtEvent = prm.getEv().getData().getFmtChanged();
                        Logger.info(LOG_TAG, "Notify new video size");
                        account.getService().getBroadcastEmitter().videoSize(
                                (int) fmtEvent.getNewWidth(),
                                (int) fmtEvent.getNewHeight()
                        );
                    }
                } catch (Exception ex) {
                    Logger.error(LOG_TAG, "Unable to get video dimensions", ex);
                }
                break;
            case pjmedia_event_type.PJMEDIA_EVENT_RX_RTCP_FB:
                Logger.debug(LOG_TAG, "Keyframe request received");
                if (prm.getEv().getData() != null &&
                        prm.getEv().getData().getRtcpFb().getFbType() == pjmedia_rtcp_fb_type.PJMEDIA_RTCP_FB_NACK &&
                        prm.getEv().getData().getRtcpFb().getIsParamLengthZero()
                ) {
                    Logger.info(LOG_TAG, "Sending new keyframe");
                    sendKeyFrame();
                }
        }
        super.onCallMediaEvent(prm);
    }

    @Override
    public void onStreamDestroyed(OnStreamDestroyedParam prm) {
        long idx = prm.getStreamIdx();
        try {
            CallInfo callInfo = getInfo();
            if (getInfo().getMedia().get((int)idx).getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
                streamInfo = getStreamInfo(idx);
                streamStat = getStreamStat(idx);
            }
        } catch (Exception ex) {
            Logger.error(LOG_TAG, "onStreamDestroyed: error while getting call stats", ex);
        }
        super.onStreamDestroyed(prm);
    }

    /**
     * Get the total duration of the call.
     * @return the duration in milliseconds or 0 if the call is not connected.
     */
    public long getConnectTimestamp() {
        return connectTimestamp;
    }

    public void acceptIncomingCall() {
        CallOpParam param = new CallOpParam();
        param.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
        setMediaParams(param);
        if (!videoCall) {
            CallSetting callSetting = param.getOpt();
            callSetting.setFlag(pjsua_call_flag.PJSUA_CALL_INCLUDE_DISABLED_MEDIA);
        }
        try {
            answer(param);
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Failed to accept incoming call", exc);
        }
    }

    public void sendBusyHereToIncomingCall() {
        CallOpParam param = new CallOpParam();
        param.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);

        try {
            answer(param);
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Failed to send busy here", exc);
        }
    }

    public void declineIncomingCall() {
        CallOpParam param = new CallOpParam();
        param.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);

        try {
            answer(param);
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Failed to decline incoming call", exc);
        }
    }

    public void hangUp() {
        CallOpParam param = new CallOpParam();
        param.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);

        try {
            hangup(param);
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Failed to hangUp call", exc);
        }
    }

    /**
     * Utility method to mute/unmute the device microphone during a call.
     * @param mute true to mute the microphone, false to un-mute it
     */
    public void setMute(boolean mute) {
        // return immediately if we are not changing the current state
        if (localMute == mute) return;

        CallInfo info;
        try {
            info = getInfo();
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "setMute: error while getting call info", exc);
            return;
        }

        for (int i = 0; i < info.getMedia().size(); i++) {
            Media media = getMedia(i);
            CallMediaInfo mediaInfo = info.getMedia().get(i);

            if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO
                    && media != null
                    && mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                AudioMedia audioMedia = AudioMedia.typecastFromMedia(media);

                // connect or disconnect the captured audio
                try {
                    AudDevManager mgr = account.getService().getAudDevManager();
                    if (mute) mgr.getCaptureDevMedia().stopTransmit(audioMedia);
                    else mgr.getCaptureDevMedia().startTransmit(audioMedia);
                    localMute = mute;
                    account.getService().getBroadcastEmitter().callMediaState(
                            account.getData().getIdUri(), getId(), MediaState.LOCAL_MUTE, localMute);
                } catch (Exception exc) {
                    Logger.error(LOG_TAG, "setMute: error while connecting audio media to sound device", exc);
                }
            }
        }
    }

    public boolean isLocalMute() {
        return localMute;
    }

    public void toggleMute() {
        setMute(!localMute);
    }

    /**
     * Utility method to transfer a call to a number in the same realm as the account to
     * which this call belongs to. If you want to transfer the call to a different realm, you
     * have to pass the full string in this format: sip:NUMBER@REALM. E.g. sip:200@mycompany.com
     * @param destination destination to which to transfer the call.
     * @throws Exception if an error occurs during the call transfer
     */
    public void transferTo(String destination) throws Exception {
        String transferString;

        if (destination.startsWith("sip:")) {
            transferString = "<" + destination + ">";
        } else {
            if ("*".equals(account.getData().getRealm())) {
                transferString = "<sip:" + destination + ">";
            } else {
                transferString = "<sip:" + destination + "@" + account.getData().getRealm() + ">";
            }
        }

        CallOpParam param = new CallOpParam();

        xfer(transferString, param);
    }

    public void setHold(boolean hold) {
        // return immediately if we are not changing the current state
        if (localHold == hold) return;

        CallOpParam param = new CallOpParam();

        try {
            if (hold) {
                Logger.debug(LOG_TAG, "holding call with ID " + getId());
                setHold(param);
            } else {
                // http://lists.pjsip.org/pipermail/pjsip_lists.pjsip.org/2015-March/018246.html
                Logger.debug(LOG_TAG, "un-holding call with ID " + getId());
                setMediaParams(param);
                CallSetting opt = param.getOpt();
                opt.setFlag(pjsua_call_flag.PJSUA_CALL_UNHOLD);
                reinvite(param);
            }
            localHold = hold;
            account.getService().getBroadcastEmitter().callMediaState(
                    account.getData().getIdUri(), getId(), MediaState.LOCAL_HOLD, localHold);
        } catch (Exception exc) {
            String operation = hold ? "hold" : "unhold";
            Logger.error(LOG_TAG, "Error while trying to " + operation + " call", exc);
        }
    }

    public void toggleHold() {
        setHold(!localHold);
    }

    public boolean isLocalHold() {
        return localHold;
    }

    // check if Local RingBack Tone has started, if so, stop it.
    private void checkAndStopLocalRingBackTone() {
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    // disable video programmatically
    @Override
    public void makeCall(String dst_uri, CallOpParam prm) throws java.lang.Exception {
        setMediaParams(prm);
        if (!videoCall) {
            CallSetting callSetting = prm.getOpt();
            callSetting.setFlag(pjsua_call_flag.PJSUA_CALL_INCLUDE_DISABLED_MEDIA);
        }
        super.makeCall(dst_uri, prm);
    }

    private void handleAudioMedia(Media media) {
        try {
            AudioMedia audioMedia = AudioMedia.typecastFromMedia(media);
            if (audioMedia == null) {
                Logger.error(LOG_TAG, "Audio media is null");
                return;
            }

            // Connect the call audio media to sound device
            Endpoint ep = Endpoint.instance();
            AudioMedia playback = ep.audDevManager().getPlaybackDevMedia();
            AudioMedia capture = ep.audDevManager().getCaptureDevMedia();

            audioMedia.startTransmit(playback);
            capture.startTransmit(audioMedia);

            // Adjust audio levels
            try {
                audioMedia.adjustTxLevel(1.0f); // Set transmit gain to normal
                audioMedia.adjustRxLevel(1.0f); // Set receive gain to normal
            } catch (Exception exc) {
                Logger.error(LOG_TAG, "Error while adjusting audio levels", exc);
            }

            Log.d(LOG_TAG, "Audio media connected successfully.");
        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Error while connecting audio media", exc);
        }
    }

    private void handleVideoMedia(CallMediaInfo mediaInfo) {
        if (mVideoWindow != null) {
            mVideoWindow.delete();
        }
        if (mVideoPreview != null) {
            mVideoPreview.delete();
        }
        if (!videoConference) {
            // Since 2.9 pjsip will not start capture device if autoTransmit is false
            // thus mediaInfo.getVideoCapDev() always returns -3 -> NULL
            // mVideoPreview = new VideoPreview(mediaInfo.getVideoCapDev());
            mVideoPreview = new VideoPreview(SipServiceConstants.FRONT_CAMERA_CAPTURE_DEVICE);
        }
        mVideoWindow = new VideoWindow(mediaInfo.getVideoIncomingWindowId());
    }

    public VideoWindow getVideoWindow() {
        return mVideoWindow;
    }

    public void setVideoWindow(VideoWindow mVideoWindow) {
        this.mVideoWindow = mVideoWindow;
    }

    public VideoPreview getVideoPreview() {
        return mVideoPreview;
    }

    public void setVideoPreview(VideoPreview mVideoPreview) {
        this.mVideoPreview = mVideoPreview;
    }

    private void stopVideoFeeds() {
        stopIncomingVideoFeed();
        stopPreviewVideoFeed();
    }

    public void setIncomingVideoFeed(Surface surface) {
        if (mVideoWindow != null) {
            VideoWindowHandle videoWindowHandle = new VideoWindowHandle();
            videoWindowHandle.getHandle().setWindow(surface);
            try {
                mVideoWindow.setWindow(videoWindowHandle);
                account.getService().getBroadcastEmitter().videoSize(
                        (int) mVideoWindow.getInfo().getSize().getW(),
                        (int) mVideoWindow.getInfo().getSize().getH());

                // start video again if not mute
                setVideoMute(localVideoMute);
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Unable to setup Incoming Video Feed", ex);
            }
        }
    }

    public void startPreviewVideoFeed(Surface surface) {
        if (mVideoPreview != null) {
            VideoWindowHandle videoWindowHandle = new VideoWindowHandle();
            videoWindowHandle.getHandle().setWindow(surface);
            VideoPreviewOpParam videoPreviewOpParam = new VideoPreviewOpParam();
            videoPreviewOpParam.setWindow(videoWindowHandle);
            try {
                mVideoPreview.start(videoPreviewOpParam);
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Unable to start Video Preview", ex);
            }
        }
    }

    public void stopIncomingVideoFeed() {
        VideoWindow videoWindow = getVideoWindow();
        if (videoWindow != null) {
            try {
                videoWindow.delete();
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Unable to stop remote video feed", ex);
            }
        }
    }

    public void stopPreviewVideoFeed() {
        VideoPreview videoPreview = getVideoPreview();
        if (videoPreview != null) {
            try {
                videoPreview.stop();
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Unable to stop preview video feed", ex);
            }
        }
    }

    public boolean isVideoCall() {
        return videoCall;
    }

    public boolean isVideoConference() {
        return videoConference;
    }

    public void setVideoParams(boolean videoCall, boolean videoConference) {
        this.videoCall = videoCall;
        this.videoConference = videoConference;
    }

    private void setMediaParams(CallOpParam param) {
        CallSetting callSetting = param.getOpt();
        callSetting.setAudioCount(1);
        callSetting.setVideoCount(videoCall ? 1 : 0);
        callSetting.setReqKeyframeMethod(pjsua_vid_req_keyframe_method.PJSUA_VID_REQ_KEYFRAME_RTCP_PLI);
    }

    public void setVideoMute(boolean videoMute) {
        try {
            vidSetStream(videoMute
                            ? pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_STOP_TRANSMIT
                            : pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_START_TRANSMIT,
                    new CallVidSetStreamParam());
            localVideoMute = videoMute;
            account.getService().getBroadcastEmitter().callMediaState(
                    account.getData().getIdUri(), getId(), MediaState.LOCAL_VIDEO_MUTE, localVideoMute);
        } catch(Exception ex) {
            Logger.error(LOG_TAG, "Error while toggling video transmission", ex);
        }
    }

    public boolean isLocalVideoMute() {
        return localVideoMute;
    }

    public boolean isFrontCamera() {
        return frontCamera;
    }

    public void setFrontCamera(boolean frontCamera) {
        this.frontCamera = frontCamera;
    }

    private void sendKeyFrame() {
        try {
            vidSetStream(pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_SEND_KEYFRAME, new CallVidSetStreamParam());
        } catch (Exception ex) {
            Logger.error(LOG_TAG, "Error sending keyframe", ex);
        }
    }

    private void sendCallStats(int callID, int duration, int callStatus) {
        String audioCodec = streamInfo.getCodecName().toLowerCase()+"_"+streamInfo.getCodecClockRate();

        RtcpStreamStat rxStat = streamStat.getRtcp().getRxStat();
        RtcpStreamStat txStat = streamStat.getRtcp().getTxStat();

        Jitter rxJitter = new Jitter(
                rxStat.getJitterUsec().getMax(),
                rxStat.getJitterUsec().getMean(),
                rxStat.getJitterUsec().getMin());

        Jitter txJitter = new Jitter(
                txStat.getJitterUsec().getMax(),
                txStat.getJitterUsec().getMean(),
                txStat.getJitterUsec().getMin());

        RtpStreamStats rx = new RtpStreamStats(
                (int)rxStat.getPkt(),
                (int)rxStat.getDiscard(),
                (int)rxStat.getLoss(),
                (int)rxStat.getReorder(),
                (int)rxStat.getDup(),
                rxJitter
        );

        RtpStreamStats tx = new RtpStreamStats(
                (int)txStat.getPkt(),
                (int)txStat.getDiscard(),
                (int)txStat.getLoss(),
                (int)txStat.getReorder(),
                (int)txStat.getDup(),
                txJitter
        );

        account.getService().getBroadcastEmitter().callStats(callID, duration, audioCodec, callStatus, rx, tx);
        streamInfo = null;
        streamStat = null;
    }

    private void handleMedia(CallInfo callInfo) {
        try {
            CallMediaInfoVector media = callInfo.getMedia();
            for (int i = 0; i < media.size(); i++) {
                CallMediaInfo mediaInfo = media.get(i);
                if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {

                    AudioMedia audioMedia = AudioMedia.typecastFromMedia(getMedia(i));
                    Endpoint ep = Endpoint.instance();
                    AudioMedia playback = ep.audDevManager().getPlaybackDevMedia();
                    AudioMedia capture = ep.audDevManager().getCaptureDevMedia();

                    audioMedia.startTransmit(playback);
                    capture.startTransmit(audioMedia);

                    // Adjust audio levels
                    try {
                        audioMedia.adjustTxLevel(1.0f); // Set transmit gain to normal
                        audioMedia.adjustRxLevel(1.0f); // Set receive gain to normal
                    } catch (Exception exc) {
                        Logger.error(LOG_TAG, "Error while adjusting audio levels", exc);
                    }

                    Log.d("SipCall", "Audio connected between app and network.");
                }
            }
        } catch (Exception e) {
            Log.e("SipCall", "Error handling media: " + e.getMessage(), e);
        }
    }

}
