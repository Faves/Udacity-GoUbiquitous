/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 *
 * thanks to :
 * - http://swarmnyc.com/whiteboard/building-android-wear-watch-face-with-live-weather-data-3/
 * - http://swarmnyc.com/whiteboard/how-to-design-and-develop-an-android-watch-face-app-wearables-overview/
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWatchFace";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for active mode (non-ambient).
     */
    private static final long ACTIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener, CapabilityApi.CapabilityListener {


        private static final int MSG_UPDATE_TIME = 0;

        private static final String
                WEATHER_CAPABILITY_CAPABILITY_NAME = "sunshine_app";

        /* Handler to update the time periodically in interactive mode. */
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        Log.v(TAG, "updating time");
                        invalidate();
                        if (shouldUpdateTimeHandlerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    ACTIVE_INTERVAL_MS - (timeMs % ACTIVE_INTERVAL_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                            requireWeatherInfo();
                        }
                        break;
                }
            }
        };

        /**
         * Handles time zone and locale changes.
         */
        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        private boolean mRegisteredReceiver = false;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mSecondPaint;
        private Paint mAmPmPaint;
        private Paint mColonPaint;
        private Paint mDatePaint;
        private Paint mSeparatorPaint;
        private Paint mTemperatureMinPaint;
        private Paint mTemperatureMaxPaint;

        private Paint mArtPaint;
        private Bitmap mArtBitmap;

        private float mColonWidth;

        private Calendar mCalendar;

        private float mYOffset;

        private String mAmString;
        private String mPmString;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        /*
         * Google API Client used to make Google Fit requests for step data.
         */
        private GoogleApiClient mGoogleApiClient;

        private long mWeatherInfoReceivedTime;
        private String mTemperatureMin;
        private String mTemperatureMax;
        private int mWeatherId;
        private long mRequireInterval;
        private long mWeatherInfoRequiredTime;
        private String mTranscriptionNodeId;
        private float mYTimeOriginOffset;
        private float mYDateOriginOffset;
        private float mYTemperatureOriginOffset;
        private float mYSecondsOriginOffset;
        private float mSeparatorWidth;
        private float mSeparatorVertMargin;
        private float mYTimeDescent;
        private float mYSecondsDescent;
        private int mBackgroundColor;
        private float mYTemperatureDescent;
        private float mArtHeightWidth;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(TAG, "onCreate");

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API) // tell Google API that we want to use Warable API
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = getResources();

            mYOffset = resources.getDimension(R.dimen.sun_y_offset);
            mAmString = resources.getString(R.string.fit_am);
            mPmString = resources.getString(R.string.fit_pm);

            mBackgroundColor = resources.getColor(R.color.primary_dark);
            int whiteColor = Color.WHITE;
            int blackColor = Color.BLACK;
            int greyColor = resources.getColor(R.color.primary_light);

            mHourPaint = createTextPaint(whiteColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(whiteColor);
            mSecondPaint = createTextPaint(greyColor);
            mAmPmPaint = createTextPaint(greyColor);
            mColonPaint = createTextPaint(greyColor);
            mDatePaint = createTextPaint(greyColor);
            mSeparatorPaint = createSeparatorPaint(whiteColor);
            mTemperatureMaxPaint = createTextPaint(whiteColor, BOLD_TYPEFACE);
            mTemperatureMinPaint = createTextPaint(greyColor);

            mArtPaint = createBitmapPaint(blackColor);
            mArtBitmap = null;

            mCalendar = Calendar.getInstance();

            mRequireInterval = resources.getInteger(R.integer.weather_default_require_interval);
            mWeatherInfoRequiredTime = System.currentTimeMillis() - (DateUtils.SECOND_IN_MILLIS * 58);
            mGoogleApiClient.connect();
        }

        private Paint createTextPaint(int color) {
            return createTextPaint(color, NORMAL_TYPEFACE);
        }
        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }
        private Paint createSeparatorPaint(int color) {
            Paint paint = new Paint();
            paint.setColor(color);
            return paint;
        }
        private Paint createBitmapPaint(int color) {
            Paint paint = new Paint();
            paint.setColor(color);
            return paint;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "onVisibilityChanged: " + visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.sun_text_size_round : R.dimen.sun_text_size);

            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mSecondPaint.setTextSize(resources.getDimension(R.dimen.sun_seconds_text_size));
            mAmPmPaint.setTextSize(resources.getDimension(R.dimen.sun_am_pm_size));
            mColonPaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.sun_date_text_size));
            mTemperatureMaxPaint.setTextSize(resources.getDimension(R.dimen.sun_temperature_text_size));
            mTemperatureMinPaint.setTextSize(resources.getDimension(R.dimen.sun_temperature_text_size));

            mYOffset = resources.getDimension(isRound
                    ? R.dimen.sun_y_offset_round : R.dimen.sun_y_offset);

            mColonWidth = mColonPaint.measureText(Consts.COLON_STRING);
            mYTimeOriginOffset = -mHourPaint.ascent();
            mYTimeDescent = mHourPaint.descent();
            mYSecondsOriginOffset = -mSecondPaint.ascent();
            mYSecondsDescent = mSecondPaint.descent();
            mYDateOriginOffset = -mDatePaint.ascent();
            mYTemperatureOriginOffset = -mTemperatureMaxPaint.ascent();
            mYTemperatureDescent = mTemperatureMaxPaint.descent();
            mArtHeightWidth = resources.getDimension(R.dimen.sun_art_height_width);

            mSeparatorWidth = resources.getDimension(R.dimen.sun_separ_width);
            mSeparatorVertMargin = resources.getDimension(R.dimen.sun_separ_vert_margin);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                    + ", low-bit ambient = " + mLowBitAmbient);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());

            invalidate();
            requireWeatherInfo();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mSecondPaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mTemperatureMaxPaint.setAntiAlias(antiAlias);
                mTemperatureMinPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }
        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }
        private String getDateString(Calendar calendar) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("ccc MMM d yyyy", Locale.getDefault());
            return simpleDateFormat.format(calendar.getTime());
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            final long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            final boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);
            final float xOffsetCenter = bounds.centerX();

            // Draw the background.
            if (!isInAmbientMode()) {
                canvas.drawColor(mBackgroundColor);
            }
            else {
                canvas.drawColor(Color.BLACK);
            }

            // Draw the time
            //- get data
            // -> hour
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            // -> minutes
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            // -> seconds (and am-pm)
            String secondsString = formatTwoDigitNumber(mCalendar.get(Calendar.SECOND));

            //- calculate positions
            float widthHours = mHourPaint.measureText(hourString);
            float widthMinutes = mMinutePaint.measureText(minuteString);
            float widthSeconds = mSecondPaint.measureText(secondsString);
            float widthTime = widthHours + mColonWidth + widthMinutes;// + mColonWidth + widthSeconds;
            float xOffsetHours = xOffsetCenter - (widthTime / 2f);
            float xOffsetColon1 = xOffsetHours + widthHours;
            float xOffsetMinutes = xOffsetColon1 + mColonWidth;
            float xOffsetColon2 = xOffsetMinutes + widthMinutes;    //not show
            float xOffsetSeconds = xOffsetColon2;// + mColonWidth;
            float y = mYOffset;
            float yOffsetHours = y + mYTimeOriginOffset;
            float yOffsetSeconds = y + mYSecondsOriginOffset + mYTimeDescent - mYSecondsDescent;

            //- draw the hours
            canvas.drawText(hourString,
                    xOffsetHours, yOffsetHours, mHourPaint);

            //- draw first colon
            canvas.drawText(Consts.COLON_STRING,
                    xOffsetColon1, yOffsetHours, mColonPaint);

            //- draw the minutes
            canvas.drawText(minuteString,
                    xOffsetMinutes, yOffsetHours, mMinutePaint);

            // In interactive mode, draw a second colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!isInAmbientMode()) {
                canvas.drawText(secondsString,
                        xOffsetSeconds, yOffsetSeconds, mSecondPaint);
            } else if (!is24Hour) {
                canvas.drawText(getAmPmString(mCalendar.get(Calendar.AM_PM)),
                        xOffsetSeconds, yOffsetSeconds, mAmPmPaint);
            }

            // Draw the date
            //- get data
            String date = getDateString(mCalendar);
            //- calculate positions
            float xOffsetDate = xOffsetCenter - (mDatePaint.measureText(date) / 2);
            y += mHourPaint.getTextSize();
            //- draw the date
            canvas.drawText(date,
                    xOffsetDate, y + mYDateOriginOffset, mDatePaint);

            // Draw the separator
            //- calculate positions
            float xOffsetSeparator = xOffsetCenter - (mSeparatorWidth / 2);
            y += mDatePaint.getTextSize() + mSeparatorVertMargin;
            //- draw the separator
            canvas.drawLine(xOffsetSeparator, y,
                    xOffsetSeparator + mSeparatorWidth, y,
                    mSeparatorPaint);


            // Only render steps if there is no peek card, so they do not bleed into each other
            // in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                if (mTemperatureMin != null && mTemperatureMax != null) {
                    //- calculate positions
                    float widthTemperatureMax = mTemperatureMaxPaint.measureText(mTemperatureMax);
                    float widthTemperatureMin = mTemperatureMinPaint.measureText(mTemperatureMin);
                    float widthTemperatureWithArt = mArtHeightWidth + mColonWidth + widthTemperatureMax + mColonWidth + widthTemperatureMin;
                    float xOffsetArt = xOffsetCenter - (widthTemperatureWithArt / 2f);
                    float xOffsetTemperatureMax = xOffsetArt + mArtHeightWidth + mColonWidth;
                    float xOffsetTemperatureMin = xOffsetTemperatureMax + widthTemperatureMax + mColonWidth;
                    y += mSeparatorVertMargin;

                    //- draw the art if weather condition exists
                    int weatherArtResourceId = Utility.getArtResourceForWeatherCondition(mWeatherId);
                    if (weatherArtResourceId != -1) {
                        mArtBitmap = BitmapFactory.decodeResource(getResources(), weatherArtResourceId);
                        mArtBitmap = Bitmap.createScaledBitmap(mArtBitmap,
                                (int) mArtHeightWidth,
                                (int) mArtHeightWidth, true);
                        canvas.drawBitmap(
                                mArtBitmap,
                                xOffsetArt, y + (mYTemperatureDescent / 2), mArtPaint);
                    }

                    //- draw the temperature Max
                    canvas.drawText(mTemperatureMax,
                            xOffsetTemperatureMax, y + mYTemperatureOriginOffset, mTemperatureMaxPaint);
                    //- draw the temperature Min
                    canvas.drawText(mTemperatureMin,
                            xOffsetTemperatureMin, y + mYTemperatureOriginOffset, mTemperatureMinPaint);
                }

            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            Log.d(TAG, "updateTimer");

            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldUpdateTimeHandlerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldUpdateTimeHandlerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        protected void requireWeatherInfo() {
            Log.d(TAG, "requireWeatherInfo()");

            if (!mGoogleApiClient.isConnected())
                return;

            long timeMs = System.currentTimeMillis();

            // The weather info is still up to date.
            if ((timeMs - mWeatherInfoReceivedTime) <= mRequireInterval)
                return;

            // Try once in a min.
            if ((timeMs - mWeatherInfoRequiredTime) <= DateUtils.MINUTE_IN_MILLIS)
                return;

            mWeatherInfoRequiredTime = timeMs;
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(Consts.PATH_WEATHER_REQUIRE);
            putDataMapRequest.getDataMap().putLong(Consts.KEY_WEATHER_REQUEST_TIME, timeMs);
            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataMapRequest.asPutDataRequest())
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            Log.d(TAG, "requireWeatherInfo: " + dataItemResult.getStatus() + ", " + dataItemResult.getDataItem().getUri());
                        }
                    });
            /*Wearable.MessageApi.sendMessage(mGoogleApiClient, "", Consts.PATH_WEATHER_REQUIRE, null)
                    .setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                        @Override
                        public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                            Log.d(TAG, "SendRequireMessage:" + sendMessageResult.getStatus());
                        }
                    });*/
        }

        protected void fetchData(DataMap dataMap) {
            if (dataMap.containsKey(Consts.KEY_WEATHER_UPDATE_TIME)) {
                mWeatherInfoReceivedTime = dataMap.getLong(Consts.KEY_WEATHER_UPDATE_TIME);
            }

            if (dataMap.containsKey(Consts.KEY_WEATHER_TEMPERATURE_MIN)) {
                mTemperatureMin = dataMap.getString(Consts.KEY_WEATHER_TEMPERATURE_MIN);
            }
            if (dataMap.containsKey(Consts.KEY_WEATHER_TEMPERATURE_MAX)) {
                mTemperatureMax = dataMap.getString(Consts.KEY_WEATHER_TEMPERATURE_MAX);
            }

            if (dataMap.containsKey(Consts.KEY_WEATHER_WEATHER_ID)) {
                mWeatherId = dataMap.getInt(Consts.KEY_WEATHER_WEATHER_ID);
            }

            invalidate();
        }

        protected void getData() {
            Log.d(TAG, "Start getting Data");
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetLocalNodeResult>() {
                @Override
                public void onResult(NodeApi.GetLocalNodeResult getLocalNodeResult) {
                    Uri uri = new Uri.Builder()
                            .scheme("wear")
                            .path(Consts.PATH_WEATHER_INFO)
                            .authority(getLocalNodeResult.getNode().getId())
                            .build();

                    getData(uri);
                }
            });
        }
        protected void getData(Uri uri) {

            Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                    .setResultCallback(
                            new ResultCallback<DataApi.DataItemResult>() {
                                @Override
                                public void onResult(DataApi.DataItemResult dataItemResult) {
                                    Log.d(TAG, "Finish Data: " + dataItemResult.getStatus());
                                    if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                        fetchData(DataMapItem.fromDataItem(dataItemResult.getDataItem()).getDataMap());
                                    }
                                }
                            }
                    );
        }


        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "mGoogleApiAndFitCallbacks.onConnected: " + connectionHint);
            getData();

            Wearable.DataApi.addListener(mGoogleApiClient, this);
            setupVoiceTranscription();

            requireWeatherInfo();
        }

        @Override
        public void onConnectionSuspended(int cause) {
            //Log.d(TAG, "mGoogleApiAndFitCallbacks.onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            //Log.d(TAG, "mGoogleApiAndFitCallbacks.onConnectionFailed: " + result);
        }


        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (int i = 0; i < dataEvents.getCount(); i++) {
                DataEvent event = dataEvents.get(i);
                DataMap dataMap = DataMap.fromByteArray(event.getDataItem().getData());
                Log.d(TAG, "onDataChanged: " + dataMap);

                fetchData(dataMap);
            }
        }

        private void setupVoiceTranscription() {
            //listen for state modification of connected nodes
            Wearable.CapabilityApi.addCapabilityListener(
                    mGoogleApiClient, this, WEATHER_CAPABILITY_CAPABILITY_NAME);

            Wearable.CapabilityApi.getCapability(
                    mGoogleApiClient, WEATHER_CAPABILITY_CAPABILITY_NAME,
                    CapabilityApi.FILTER_REACHABLE).setResultCallback(
                    new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                        @Override
                        public void onResult(CapabilityApi.GetCapabilityResult result) {
                            if (!result.getStatus().isSuccess()) {
                                Log.e(TAG, "setupConfirmationHandlerNode() Failed to get capabilities, "
                                        + "status: " + result.getStatus().getStatusMessage());
                                return;
                            }
                            updateTranscriptionCapability(result.getCapability());
                        }
                    });
        }

        @Override
        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
            updateTranscriptionCapability(capabilityInfo);
        }
        private void updateTranscriptionCapability(CapabilityInfo capabilityInfo) {
            if (capabilityInfo == null) {
                return;
            }

            Set<Node> connectedNodes = capabilityInfo.getNodes();
            if (connectedNodes.isEmpty()) {
                mTranscriptionNodeId = null;
            } else {
                mTranscriptionNodeId = pickBestNodeId(connectedNodes);
            }

            Log.e(TAG, "updateTranscriptionCapability: " + mTranscriptionNodeId);
        }
        private String pickBestNodeId(Set<Node> nodes) {
            String bestNodeId = null;
            // Find a nearby node or pick one arbitrarily
            for (Node node : nodes) {
                if (node.isNearby()) {
                    return node.getId();
                }
                bestNodeId = node.getId();
            }
            return bestNodeId;
        }
    }
}
