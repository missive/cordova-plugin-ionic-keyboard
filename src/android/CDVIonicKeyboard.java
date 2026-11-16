package io.ionic.keyboard;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;

// import additionally required classes for calculating screen height
import android.view.Display;
import android.graphics.Point;
import android.os.Build;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CDVIonicKeyboard extends CordovaPlugin {
    private OnGlobalLayoutListener list;
    private View rootView;
    private View mChildOfContent;
    private int usableHeightPrevious;
    private FrameLayout.LayoutParams frameLayoutParams;

    private static final int EASING_SAMPLES = 32;

    private View insetsView;
    private boolean resizeNone = false;
    private int imeBottom = 0;
    private int reportedHeight = 0;
    private final Set<WindowInsetsAnimationCompat> imeAnimations = new HashSet<>();

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        // Before Android 11 the system resizes the window itself, so the keyboard can't be kept out of it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                observeKeyboardFrame();
            }
        });
    }

    // The content view is the parent of Cordova's root layout, so its insets are what Cordova and the
    // web view see. In `none` resize mode the keyboard is taken out of them, so nothing shrinks the web view.
    private void observeKeyboardFrame() {
        insetsView = cordova.getActivity().findViewById(android.R.id.content);
        if (insetsView == null) {
            return;
        }

        WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(insetsView);
        if (rootInsets != null) {
            imeBottom = rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        }

        ViewCompat.setOnApplyWindowInsetsListener(insetsView, (view, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            if (bottom != imeBottom) {
                imeBottom = bottom;

                // Animated changes are reported when the animation starts, with its timing.
                if (imeAnimations.isEmpty()) {
                    view.post(() -> reportKeyboardFrame(0, null));
                }
            }

            if (!resizeNone) {
                return insets;
            }

            return new WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build();
        });

        ViewCompat.setWindowInsetsAnimationCallback(insetsView,
            new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override
                public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                    if (isImeAnimation(animation)) {
                        imeAnimations.add(animation);
                    }
                }

                @NonNull
                @Override
                public WindowInsetsAnimationCompat.BoundsCompat onStart(@NonNull WindowInsetsAnimationCompat animation, @NonNull WindowInsetsAnimationCompat.BoundsCompat bounds) {
                    // A gesture drives its own animation and has no duration, so it's reported when it ends.
                    if (isImeAnimation(animation) && animation.getDurationMillis() > 0) {
                        reportKeyboardFrame(animation.getDurationMillis() / 1000.0, animation.getInterpolator());
                    }
                    return bounds;
                }

                @NonNull
                @Override
                public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets, @NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                    return insets;
                }

                @Override
                public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                    if (imeAnimations.remove(animation)) {
                        reportKeyboardFrame(0, null);
                    }
                }
            });
    }

    private boolean isImeAnimation(WindowInsetsAnimationCompat animation) {
        return (animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0;
    }

    private int measureKeyboardOverlap() {
        View view = webView.getView();
        if (imeBottom == 0 || !view.isAttachedToWindow()) {
            return 0;
        }

        int[] location = new int[2];
        view.getLocationInWindow(location);
        int keyboardTop = view.getRootView().getHeight() - imeBottom;
        int overlap = location[1] + view.getHeight() - keyboardTop;
        float density = view.getResources().getDisplayMetrics().density;

        return Math.max(0, Math.round(overlap / density));
    }

    private void reportKeyboardFrame(double duration, Interpolator interpolator) {
        int height = measureKeyboardOverlap();
        if (height == reportedHeight) {
            return;
        }
        reportedHeight = height;

        String easing = "null";
        if (interpolator != null) {
            StringBuilder samples = new StringBuilder("[");
            for (int index = 0; index <= EASING_SAMPLES; index++) {
                if (index > 0) {
                    samples.append(",");
                }
                float progress = interpolator.getInterpolation(index / (float) EASING_SAMPLES);
                samples.append(String.format(Locale.ROOT, "%.4f", progress));
            }
            easing = samples.append("]").toString();
        }

        String js = String.format(Locale.ROOT,
            "window.Keyboard && Keyboard.fireOnFrameChanging(%d, %f, %s, %d);", height, duration, easing, System.currentTimeMillis());

        // Evaluated directly rather than through a plugin result, which is queued behind the bridge.
        webView.getEngine().evaluateJavascript(js, null);
    }

    private void setResizeMode(String mode) {
        resizeNone = "none".equals(mode);
        if (insetsView != null) {
            ViewCompat.requestApplyInsets(insetsView);
        }
    }

    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        if ("hide".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    //http://stackoverflow.com/a/7696791/1091751
                    InputMethodManager inputManager = (InputMethodManager) cordova.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    View v = cordova.getActivity().getCurrentFocus();

                    if (v == null) {
                        callbackContext.error("No current focus");
                    } else {
                        inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                        callbackContext.success(); // Thread-safe.
                    }
                }
            });
            return true;
        }
        if ("show".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    ((InputMethodManager) cordova.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE)).toggleSoftInput(0, InputMethodManager.HIDE_IMPLICIT_ONLY);
                    callbackContext.success(); // Thread-safe.
                }
            });
            return true;
        }
        if ("setResizeMode".equals(action)) {
            final String mode = args.getString(0);
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    setResizeMode(mode);
                    callbackContext.success();
                }
            });
            return true;
        }
        if ("init".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                	//calculate density-independent pixels (dp)
                    //http://developer.android.com/guide/practices/screens_support.html
                    DisplayMetrics dm = new DisplayMetrics();
                    cordova.getActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);
                    final float density = dm.density;

                    //http://stackoverflow.com/a/4737265/1091751 detect if keyboard is showing
                    FrameLayout content = (FrameLayout) cordova.getActivity().findViewById(android.R.id.content);
                    rootView = content.getRootView();

                    list = new OnGlobalLayoutListener() {
                        int previousHeightDiff = 0;
                        @Override
                        public void onGlobalLayout() {
                            boolean resize = preferences.getBoolean("resizeOnFullScreen", false);
                            if (resize) {
                                possiblyResizeChildOfContent();
                            }
                            Rect r = new Rect();
                            //r will be populated with the coordinates of your view that area still visible.
                            rootView.getWindowVisibleDisplayFrame(r);

                            PluginResult result;

                            // cache properties for later use
                            int rootViewHeight = rootView.getRootView().getHeight();
                            int resultBottom = r.bottom;

                            // calculate screen height differently for android versions >= 21: Lollipop 5.x, Marshmallow 6.x
                            //http://stackoverflow.com/a/29257533/3642890 beware of nexus 5
                            int screenHeight;

                            if (Build.VERSION.SDK_INT >= 21) {
                                Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
                                Point size = new Point();
                                display.getSize(size);
                                screenHeight = size.y;
                            } else {
                                screenHeight = rootViewHeight;
                            }

                            int heightDiff = screenHeight - resultBottom;

                            int pixelHeightDiff = (int)(heightDiff / density);
                            if (pixelHeightDiff > 100 && pixelHeightDiff != previousHeightDiff) { // if more than 100 pixels, its probably a keyboard...
                                String msg = "S" + Integer.toString(pixelHeightDiff);
                                result = new PluginResult(PluginResult.Status.OK, msg);
                                result.setKeepCallback(true);
                                callbackContext.sendPluginResult(result);
                            }
                            else if ( pixelHeightDiff != previousHeightDiff && ( previousHeightDiff - pixelHeightDiff ) > 100 ){
                            	String msg = "H";
                                result = new PluginResult(PluginResult.Status.OK, msg);
                                result.setKeepCallback(true);
                                callbackContext.sendPluginResult(result);
                            }
                            previousHeightDiff = pixelHeightDiff;
                        }

                        private void possiblyResizeChildOfContent() {
                            int usableHeightNow = computeUsableHeight();
                            if (usableHeightNow != usableHeightPrevious) {
                                frameLayoutParams.height = usableHeightNow;
                                mChildOfContent.requestLayout();
                                usableHeightPrevious = usableHeightNow;
                            }
                        }

                        private int computeUsableHeight() {
                            Rect r = new Rect();
                            mChildOfContent.getWindowVisibleDisplayFrame(r);
                            return r.bottom;
                        }
                    };

                    mChildOfContent = content.getChildAt(0);
                    rootView.getViewTreeObserver().addOnGlobalLayoutListener(list);
                    frameLayoutParams = (FrameLayout.LayoutParams) mChildOfContent.getLayoutParams();
                    PluginResult dataResult = new PluginResult(PluginResult.Status.OK);
                    dataResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(dataResult);
                }
            });
            return true;
        }
        return false;  // Returning false results in a "MethodNotFound" error.
    }

    @Override
    public void onDestroy() {
        if (rootView != null) {
            rootView.getViewTreeObserver().removeOnGlobalLayoutListener(list);
        }
        if (insetsView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(insetsView, null);
            ViewCompat.setWindowInsetsAnimationCallback(insetsView, null);
        }
    }

}
