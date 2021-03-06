package com.rdunlocked.watvcc;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.wifi.p2p.WifiP2pGroup;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.vstechlab.easyfonts.EasyFonts;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren"t displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 * <p>
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
public class DigitalFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.MONOSPACE, 200, true);

    /**
     * Update rate in milliseconds for interactive mode. Defaults to one second
     * because the watch face needs to update seconds in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<DigitalFace.Engine> mWeakReference;

        public EngineHandler(DigitalFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            DigitalFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private float mXOffset;
        private float mYOffset;
        private Paint mBackgroundPaint;
        private Paint mTextPaint;
        private Paint mHoursPaint;
        private Paint mMinsPaint;
        private float mXOffsetHours;
        private float mYOffsetHours;
        private float mXOffsetMins;
        private float mYOffsetMins;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;
        private boolean mAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalFace.this)
                    .setAcceptsTapEvents(true)
                    .build());

            mCalendar = Calendar.getInstance();

            Resources resources = DigitalFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);


            // Initializes background.
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.background));

            // Initializes Watch Face.
            mTextPaint = new Paint();
            mTextPaint.setTypeface(ResourcesCompat.getFont(getBaseContext(), R.font.kittens));
            mTextPaint.setAntiAlias(true);
            mTextPaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.matte_yellow));

            mHoursPaint = new Paint();
            mHoursPaint.setTypeface(ResourcesCompat.getFont(getBaseContext(), R.font.kittens));
            mHoursPaint.setAntiAlias(true);
            mHoursPaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.matte_blue));

            mMinsPaint = new Paint();
            mMinsPaint.setTypeface(ResourcesCompat.getFont(getBaseContext(), R.font.kittens));
            mMinsPaint.setAntiAlias(true);
            mMinsPaint.setColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.matte_red));

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren"t visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we"re visible (as well as
            // whether we"re in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            DigitalFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            DigitalFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalFace.this.getResources();
            boolean isRound = insets.isRound();
            // for singular text
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            //for Hours Digits
            mXOffsetHours = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_hours_round : R.dimen.digital_x_offset_hours);
            float hoursTSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_hours_round : R.dimen.digital_text_size_hours);

            //for Mins Digits
            mXOffsetMins = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_mins : R.dimen.digital_x_offset);
            float minsTSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_mins_round : R.dimen.digital_text_size_mins);

//            set size of texts
            mTextPaint.setTextSize(textSize-20);
            mHoursPaint.setTextSize(hoursTSize+60);
            mMinsPaint.setTextSize(minsTSize+60);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            mAmbient = inAmbientMode;
            if (mLowBitAmbient) {
                mTextPaint.setAntiAlias(!inAmbientMode);
                mTextPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            }

            // Whether the timer should be running depends on whether we"re visible (as well as
            // whether we"re in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), "ROHIT", Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
               // Toast.makeText(DigitalFace.this, "Screen off", Toast.LENGTH_SHORT).show();
                canvas.drawColor(Color.BLACK);
                mTextPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
                mHoursPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
                mMinsPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                mTextPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.matte_yellow));
                mHoursPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.matte_blue));
                mMinsPaint.setColor(
                        ContextCompat.getColor(getApplicationContext(), R.color.matte_red));
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text;
            String hoursGet = String.format("%02d",mCalendar.get(Calendar.HOUR));
            String minsGet = String.format("%02d",mCalendar.get(Calendar.MINUTE));



            canvas.drawText("It's", mXOffset +20, mYOffset+110, mTextPaint);
            canvas.drawText(hoursGet, mXOffsetHours+180 , mYOffsetHours + 220, mHoursPaint);
            canvas.drawText(minsGet, mXOffsetMins+180 , mYOffsetMins + 370, mMinsPaint);
           // canvas.drawText("rdunlocked18", mXOffsetMins+190 , mYOffsetMins + 370, mMinsPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn"t currently
         * or stops it if it shouldn"t be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we"re visible and in interactive mode.
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

        public int getBatteryPercentage(Context context) {

            if (Build.VERSION.SDK_INT >= 21) {

                BatteryManager bm = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            } else {

                IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, iFilter);

                int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
                int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;

                double batteryPct = level / (double) scale;

                return (int) (batteryPct * 100);
            }
        }

    }
}