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
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemAsset;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    public static final String KEY_TEMPERATURE_HIGH = "KEY_TEMPERATURE_HIGH";
    public static final String KEY_TEMPERATURE_LOW = "KEY_TEMPERATURE_LOW";
    public static final String KEY_WEATHER_ICON = "KEY_WEATHER_ICON";
    public static final String KEY_TIMESTAMP = "KEY_TIMESTAMP";

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    public static final String TIME_FORMAT = "%d:%02d";
    public static final String DATE_FORMAT = "EE, MMM dd yyyy";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mHighTemperaturePaint;
        Paint mLowTemperaturePaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;
        float mLineHeight;
        float mTempLineHeight;
        Bitmap mWeatherIcon;
        String mHighTemperature;
        String mLowTemperature;

        GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mTempLineHeight = resources.getDimension(R.dimen.digital_temperature_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(getColor(R.color.background));

            mTimePaint = createTextPaint(getColor(R.color.digital_text));
            mDatePaint = createTextPaint(getColor(R.color.digital_text));

            mHighTemperaturePaint = createTextPaint(getColor(R.color.digital_text),BOLD_TYPEFACE);
            mLowTemperaturePaint = createTextPaint(getColor(R.color.digital_text));

            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            return createTextPaint(textColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeFace) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeFace);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float textSize = resources.getDimension(isRound ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHighTemperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));
            mLowTemperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));
            //mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime.setToNow();
            String timeText = String.format(TIME_FORMAT, mTime.hour, mTime.minute);

            canvas.drawText(timeText, bounds.centerX() - (mTimePaint.measureText(timeText) / 2), mYOffset, mTimePaint);

            String dateText = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(new Date());
            canvas.drawText(dateText, bounds.centerX() - (mDatePaint.measureText(dateText) / 2), mYOffset + mLineHeight, mDatePaint);
            if (mHighTemperature!=null && mLowTemperature!=null){
                float wM = 0;
                float hM = mHighTemperaturePaint.measureText(mHighTemperature);
                float lM = mLowTemperaturePaint.measureText(mLowTemperature);
                if (mWeatherIcon!=null && !isInAmbientMode()) {
                    Bitmap b = Bitmap.createScaledBitmap(mWeatherIcon, (int) (mWeatherIcon.getWidth() * 0.25f), (int) (mWeatherIcon.getHeight() * 0.25f), true);
                    wM = b.getWidth();
                    canvas.drawBitmap(b,bounds.centerX() - (wM + hM + lM)/2,mYOffset + mTempLineHeight,null);
                }
                canvas.drawText(mHighTemperature,  bounds.centerX() - (wM + hM + lM)/2 + wM, mYOffset + mLineHeight + mTempLineHeight, mHighTemperaturePaint);
                canvas.drawText(mLowTemperature, bounds.centerX() - (wM + hM + lM) / 2 + +wM + hM, mYOffset + mLineHeight + mTempLineHeight, mLowTemperaturePaint);
            }


        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        protected void processDataItem(DataItem dataItem){
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
            Asset iconAsset = dataMapItem.getDataMap().getAsset(KEY_WEATHER_ICON);

            if (iconAsset != null) {
                Wearable.DataApi.getFdForAsset(mGoogleApiClient, iconAsset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                    @Override
                    public void onResult(DataApi.GetFdForAssetResult getFdForAssetResult) {
                        InputStream assetInputStream = getFdForAssetResult.getInputStream();
                        if (assetInputStream == null) {
                            Log.w(TAG, "Requested an unknown Asset.");
                        }else {
                            mWeatherIcon = BitmapFactory.decodeStream(assetInputStream);
                        }
                    }
                });
            }

            DataMap dataMap = dataMapItem.getDataMap();
            if (dataMap.containsKey(KEY_TEMPERATURE_HIGH)) {
                mHighTemperature = " "+dataMap.getString(KEY_TEMPERATURE_HIGH);
            }
            if (dataMap.containsKey(KEY_TEMPERATURE_LOW)) {
                mLowTemperature = " "+ dataMap.getString(KEY_TEMPERATURE_LOW);
            }
            for(String key:dataItem.getAssets().keySet()){
                DataItemAsset a = dataItem.getAssets().get(key);
                Log.i(TAG,a.getDataItemKey());
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.i(TAG, "onDataChanged");
            for (DataEvent event:dataEvents) {
                processDataItem(event.getDataItem());
            }
            invalidate();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.i(TAG, "onConnected: " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(DataItemBuffer dataItems) {
                    for(DataItem dataItem:dataItems){
                        processDataItem(dataItem);
                    }
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i(TAG, "onConnectionSuspended: " + i);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.i(TAG, "onConnectionFailed: " + connectionResult);
        }

    }
}
