/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;

import android.annotation.Nullable;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.widget.*;

import androidx.annotation.NonNull;

import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.spark.CurrentWeatherView;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.lockscreen.LockScreenWidgets;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.log.dagger.KeyguardClockLog;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ClockAnimations;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.plugins.log.LogLevel;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.shared.regionsampling.RegionSampler;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import com.android.systemui.clocks.ClockStyle;


import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch>
        implements Dumpable, TunerService.Tunable {
    private static final String TAG = "KeyguardClockSwitchController";

    private static final String LOCKSCREEN_WEATHER_ENABLED =
            "system:" + Settings.System.LOCKSCREEN_WEATHER_ENABLED;
    private static final String LOCKSCREEN_WEATHER_STYLE =
            "system:" + Settings.System.LOCKSCREEN_WEATHER_STYLE;
private static final String LOCKSCREEN_CLOCK_STYLE =
            "system:" + "clock_style";

    private static final String LOCKSCREEN_WIDGETS_ENABLED =
            "system:" + "lockscreen_widgets_enabled";


    private final StatusBarStateController mStatusBarStateController;
    private final ClockRegistry mClockRegistry;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final LockscreenSmartspaceController mSmartspaceController;
    private final SecureSettings mSecureSettings;
    private final DumpManager mDumpManager;
    private final ClockEventController mClockEventController;
    private final LogBuffer mLogBuffer;
    private final TunerService  mTunerService;
private final ContentResolver mCR;

    private FrameLayout mSmallClockFrame; // top aligned clock
    private FrameLayout mLargeClockFrame; // centered clock
    private View mCustomClock; // custom clock
    private View mCustomClockFrame; // custom clock frame



    @KeyguardClockSwitch.ClockSize
    private int mCurrentClockSize = SMALL;

    private int mKeyguardSmallClockTopMargin = 0;
    private int mKeyguardLargeClockTopMargin = 0;
    private final ClockRegistry.ClockChangeListener mClockChangedListener;

    private ViewGroup mStatusArea;

    // If the SMARTSPACE flag is set, keyguard_slice_view is replaced by the following views.
    private ViewGroup mDateWeatherView;
    private View mWeatherView;
    private View mSmartspaceView;

    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;

    private CurrentWeatherView mCurrentWeatherView;
    private boolean mShowWeather;
    private int mWeatherStyle;

private LockScreenWidgets mLsWidgets;
    private boolean mShowLockscreenWidgets;

    private boolean mOnlyClock = false;
    private final Executor mUiExecutor;
    private boolean mCanShowDoubleLineClock = true;
    private final ContentObserver mDoubleLineClockObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            updateDoubleLineClock();
        }
    };

private boolean mEnableCustomClock = false;
    private int mClockStyle;
    private final ContentObserver mCustomClockObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            updateCustomClock();
        }
    };
    private final ContentObserver mShowWeatherObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            setWeatherVisibility();
        }
    };

    private final KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener
            mKeyguardUnlockAnimationListener =
            new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                @Override
                public void onUnlockAnimationFinished() {
                    // For performance reasons, reset this once the unlock animation ends.
                    setClipChildrenForUnlock(true);
                }
            };

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            ClockRegistry clockRegistry,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            LockscreenSmartspaceController smartspaceController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SecureSettings secureSettings,
            ContentResolver cr,
            @Main Executor uiExecutor,
            DumpManager dumpManager,
            ClockEventController clockEventController,
            @KeyguardClockLog LogBuffer logBuffer) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mClockRegistry = clockRegistry;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mSmartspaceController = smartspaceController;
        mSecureSettings = secureSettings;
        mCR = cr;
        mUiExecutor = uiExecutor;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mDumpManager = dumpManager;
        mClockEventController = clockEventController;
        mLogBuffer = logBuffer;
        mView.setLogBuffer(mLogBuffer);

        mClockChangedListener = new ClockRegistry.ClockChangeListener() {
            @Override
            public void onCurrentClockChanged() {
                setClock(mClockRegistry.createCurrentClock());
            }
            @Override
            public void onAvailableClocksChanged() { }
        };

        mTunerService = Dependency.get(TunerService.class);
    }

    /**
     * Mostly used for alternate displays, limit the information shown
     */
    public void setOnlyClock(boolean onlyClock) {
        mOnlyClock = onlyClock;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    protected void onInit() {
        mKeyguardSliceViewController.init();

        mSmallClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);
        mCurrentWeatherView = mView.findViewById(R.id.weather_container);
        mCustomClock = mView.findViewById(R.id.clock_ls);
            mCustomClockFrame = mView.findViewById(R.id.clock_frame);
            mLsWidgets = (LockScreenWidgets) mView.findViewById(R.id.keyguard_widgets);


        mDumpManager.unregisterDumpable(getClass().toString()); // unregister previous clocks
        mDumpManager.registerDumpable(getClass().toString(), this);

        mTunerService.addTunable(this, LOCKSCREEN_WEATHER_ENABLED);
        mTunerService.addTunable(this, LOCKSCREEN_WEATHER_STYLE);
    }

    @Override
    protected void onViewAttached() {
        mClockRegistry.registerClockChangeListener(mClockChangedListener);
        setClock(mClockRegistry.createCurrentClock());
        mClockEventController.registerListeners(mView);
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin);

        updateWeatherView();

        if (mOnlyClock) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            ksv.setVisibility(View.GONE);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            nic.setVisibility(View.GONE);
            return;
        }
        updateAodIcons();

        mStatusArea = mView.findViewById(R.id.keyguard_status_area);

        if (mSmartspaceController.isEnabled() && !mShowWeather) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            int viewIndex = mStatusArea.indexOfChild(ksv);
            ksv.setVisibility(View.GONE);

            // TODO(b/261757708): add content observer for the Settings toggle and add/remove
            //  weather according to the Settings.
            if (mSmartspaceController.isDateWeatherDecoupled()) {
                addDateWeatherView(viewIndex);
                viewIndex += 1;
            }

            addSmartspaceView(viewIndex);
        }

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
                false, /* notifyForDescendants */
                mDoubleLineClockObserver,
                UserHandle.USER_ALL
        );

mSecureSettings.registerContentObserverForUser(
                Settings.Secure.CLOCK_LS,
                false, /* notifyForDescendants */
                mCustomClockObserver,
                UserHandle.USER_ALL
        );

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED,
                false, /* notifyForDescendants */
                mShowWeatherObserver,
                UserHandle.USER_ALL
        );

mCR.registerContentObserver(
                Settings.System.getUriFor("clock_style"),
                false,
                mCustomClockObserver
        );

        updateDoubleLineClock();
        setWeatherVisibility();

        mKeyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);
    }

    int getNotificationIconAreaHeight() {
        return mNotificationIconAreaController.getHeight();
    }

    @Override
    protected void onViewDetached() {
        mTunerService.removeTunable(this);
        mClockRegistry.unregisterClockChangeListener(mClockChangedListener);
        mClockEventController.unregisterListeners();
        setClock(null);

        mSecureSettings.unregisterContentObserver(mDoubleLineClockObserver);
        mSecureSettings.unregisterContentObserver(mCustomClockObserver);

        mKeyguardUnlockAnimationController.removeKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCKSCREEN_WEATHER_ENABLED:
                mShowWeather =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateWeatherView();
                break;
            case LOCKSCREEN_WEATHER_STYLE:
                mWeatherStyle =
                        TunerService.parseInteger(newValue, 0);
                updateWeatherView();
                break;
            default:
                break;
        }
    }

    public void updateWeatherView() {
        mUiExecutor.execute(() -> {
            if (mCurrentWeatherView != null) {
                if (mShowWeather && mWeatherStyle > 0 && !mOnlyClock) {
                    mCurrentWeatherView.enableUpdates();
                    mCurrentWeatherView.setVisibility(View.VISIBLE);
                } else {
                    mCurrentWeatherView.disableUpdates();
                    mCurrentWeatherView.setVisibility(View.GONE);
                }
            }
        });
    }

    void onLocaleListChanged() {
          updateCustomClock();
        if (mSmartspaceController.isEnabled() && !mShowWeather) {
            if (mSmartspaceController.isDateWeatherDecoupled()) {
                mDateWeatherView.removeView(mWeatherView);
                int index = mStatusArea.indexOfChild(mDateWeatherView);
                if (index >= 0) {
                    mStatusArea.removeView(mDateWeatherView);
                    addDateWeatherView(index);
                }
            }
            int index = mStatusArea.indexOfChild(mSmartspaceView);
            if (index >= 0) {
                mStatusArea.removeView(mSmartspaceView);
                addSmartspaceView(index);
            }
        }
    }

    private void addDateWeatherView(int index) {
        mDateWeatherView = (ViewGroup) mSmartspaceController.buildAndConnectDateView(mView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        mStatusArea.addView(mDateWeatherView, index, lp);
        int startPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        int endPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_end);
        mDateWeatherView.setPaddingRelative(startPadding, 0, endPadding, 0);

        addWeatherView();
    }

    private void addWeatherView() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT);
        mWeatherView = mSmartspaceController.buildAndConnectWeatherView(mView);
        // Place weather right after the date, before the extras
        final int index = mDateWeatherView.getChildCount() == 0 ? 0 : 1;
        mDateWeatherView.addView(mWeatherView, index, lp);
        mWeatherView.setPaddingRelative(0, 0, 4, 0);
    }

    private void addSmartspaceView(int index) {
        mSmartspaceView = mSmartspaceController.buildAndConnectView(mView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        mStatusArea.addView(mSmartspaceView, index, lp);
        int startPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        int endPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_end);
        mSmartspaceView.setPaddingRelative(startPadding, 0, endPadding, 0);

        mKeyguardUnlockAnimationController.setLockscreenSmartspace(mSmartspaceView);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin);
        mView.updateClockTargetRegions();
    }


    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@KeyguardClockSwitch.ClockSize int clockSize, boolean animate) {
        if (!mCanShowDoubleLineClock && clockSize == KeyguardClockSwitch.LARGE
           || mEnableCustomClock && clockSize == KeyguardClockSwitch.LARGE) {
            return;
        }

        mCurrentClockSize = clockSize;

        ClockController clock = getClock();
        boolean appeared = mView.switchToClock(clockSize, animate);
        if (clock != null && animate && appeared && clockSize == LARGE) {
            clock.getAnimations().enter();
        }
        mClockEventController.updateAll();
    }

    /**
     * Animates the clock view between folded and unfolded states
     */
    public void animateFoldToAod(float foldFraction) {
        ClockController clock = getClock();
        if (clock != null) {
            clock.getAnimations().fold(foldFraction);
        }
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        mLogBuffer.log(TAG, LogLevel.INFO, "refresh");
        if (mSmartspaceController != null) {
            mSmartspaceController.requestSmartspaceUpdate();
        }
        ClockController clock = getClock();
        if (clock != null) {
            clock.getSmallClock().getEvents().onTimeTick();
            clock.getLargeClock().getEvents().onTimeTick();
        }
        if (mEnableCustomClock && mCustomClock != null) {
        	((ClockStyle) mCustomClock).onTimeChanged();
        }
    }

    /**
     * Update position of the view, with optional animation. Move the slice view and the clock
     * slightly towards the center in order to prevent burn-in. Y positioning occurs at the
     * view parent level. The large clock view will scale instead of using x position offsets, to
     * keep the clock centered.
     */
    void updatePosition(int x, float scale, AnimationProperties props, boolean animate) {
        x = getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -x : x;

        PropertyAnimator.setProperty(mSmallClockFrame, AnimatableProperty.TRANSLATION_X,
                x, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_X,
                scale, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_Y,
                scale, props, animate);

        if (mStatusArea != null) {
            PropertyAnimator.setProperty(mStatusArea, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);
        }
    }

    /**
     * Get y-bottom position of the currently visible clock on the keyguard.
     * We can't directly getBottom() because clock changes positions in AOD for burn-in
     */
    int getClockBottom(int statusBarHeaderHeight) {
        ClockController clock = getClock();
        if (clock == null) {
            return 0;
        }

        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
            // This gets the expected clock bottom if mLargeClockFrame had a top margin, but it's
            // top margin only contributed to height and didn't move the top of the view (as this
            // was the computation previously). As we no longer have a margin, we add this back
            // into the computation manually.
            int frameHeight = mLargeClockFrame.getHeight();
            int clockHeight = clock.getLargeClock().getView().getHeight();
            if (!mEnableCustomClock) {
                return frameHeight / 2 + clockHeight / 2 + mKeyguardLargeClockTopMargin / -2;
            } else {
            	return 0;
            }
            
        } else {
            int clockHeight = clock.getSmallClock().getView().getHeight();
            if (!mEnableCustomClock) {
                return clockHeight + statusBarHeaderHeight + mKeyguardSmallClockTopMargin;
            } else {
            	return 0;
            }
        }
    }


    /**
     * Get the height of the currently visible clock on the keyguard.
     */
    int getClockHeight() {
        ClockController clock = getClock();
        if (clock == null) {
            return 0;
        }

        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
        	if (!mEnableCustomClock) {
                return clock.getLargeClock().getView().getHeight();
            } else {
            	return 0;
            }
        } else {
            if (!mEnableCustomClock) {
                return clock.getSmallClock().getView().getHeight();
            } else {
            	return 0;
            }
        }
    }


    boolean isClockTopAligned() {
        // Returns false except certain edge cases, see comment in getClockBottom
        // TODO(b/261755021): Cleanup Large Frame Visibility
        return mLargeClockFrame.getVisibility() != View.VISIBLE;
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
        mNotificationIconAreaController.setupAodIcons(nic);
    }

    private void setClock(ClockController clock) {
        if (clock != null && mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.INFO, "New Clock");
        }

        mClockEventController.setClock(clock);
        mView.setClock(clock, mStatusBarStateController.getState());
    }

    @Nullable
    private ClockController getClock() {
        return mClockEventController.getClock();
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    private void updateDoubleLineClock() {
        updateCustomClock();
        mCanShowDoubleLineClock = mSecureSettings.getIntForUser(
            Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1,
                UserHandle.USER_CURRENT) != 0;

        if (!mCanShowDoubleLineClock || mEnableCustomClock) {
            mUiExecutor.execute(() -> displayClock(KeyguardClockSwitch.SMALL, /* animate */ true));
        }
    }

private void updateCustomClock() {
        int clockStyle = Settings.System.getInt(getContext().getContentResolver(), "clock_style", 0);
        mEnableCustomClock = clockStyle != 0;
        ViewGroup.LayoutParams params = mSmallClockFrame.getLayoutParams();
        ViewGroup.LayoutParams params2 = mLargeClockFrame.getLayoutParams();
        RelativeLayout.LayoutParams params4 = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    	params4.addRule(RelativeLayout.BELOW, mEnableCustomClock ? R.id.clock_ls : R.id.lockscreen_clock_view);
    	if (mStatusArea != null) {
    	    mStatusArea.setLayoutParams(params4);
    	}

        if (mEnableCustomClock) {
            params.width = 0;
            params.height = 0;
            mSmallClockFrame.setLayoutParams(params);
            params2.width = 0;
            params2.height = 0;
            mLargeClockFrame.setLayoutParams(params2);
            mCustomClockFrame.setVisibility(View.VISIBLE);
        } else {
        	params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mSmallClockFrame.setLayoutParams(params);
            params2.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params2.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mLargeClockFrame.setLayoutParams(params2);
        	mCustomClockFrame.setVisibility(View.GONE);
        }
    }


    private void setWeatherVisibility() {
        if (mWeatherView != null) {
            mUiExecutor.execute(
                    () -> mWeatherView.setVisibility(
                        mSmartspaceController.isWeatherEnabled() && !mShowWeather ? View.VISIBLE : View.GONE));
        }
    }

    /**
     * Sets the clipChildren property on relevant views, to allow the smartspace to draw out of
     * bounds during the unlock transition.
     */
    private void setClipChildrenForUnlock(boolean clip) {
        if (mStatusArea != null) {
            mStatusArea.setClipChildren(clip);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("currentClockSizeLarge=" + (mCurrentClockSize == LARGE));
        pw.println("mCanShowDoubleLineClock=" + mCanShowDoubleLineClock);
        mView.dump(pw, args);
        ClockController clock = getClock();
        if (clock != null) {
            clock.dump(pw);
        }
        final RegionSampler regionSampler = mClockEventController.getRegionSampler();
        if (regionSampler != null) {
            regionSampler.dump(pw);
        }
    }

    /** Gets the animations for the current clock. */
    @Nullable
    public ClockAnimations getClockAnimations() {
        ClockController clock = getClock();
        return clock == null ? null : clock.getAnimations();
    }
}

