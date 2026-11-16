var argscheck = require('cordova/argscheck'),
    utils = require('cordova/utils'),
    exec = require('cordova/exec'),
    channel = require('cordova/channel');

var Keyboard = function () {};

Keyboard.fireOnShow = function (height) {
    Keyboard.isVisible = true;
    cordova.fireWindowEvent('keyboardDidShow', {
        'keyboardHeight': height
    });

    // To support the keyboardAttach directive listening events
    // inside Ionic's main bundle
    cordova.fireWindowEvent('native.keyboardshow', {
        'keyboardHeight': height
    });
};

Keyboard.fireOnHide = function () {
    Keyboard.isVisible = false;
    cordova.fireWindowEvent('keyboardDidHide');

    // To support the keyboardAttach directive listening events
    // inside Ionic's main bundle
    cordova.fireWindowEvent('native.keyboardhide');
};

Keyboard.fireOnHiding = function () {
    cordova.fireWindowEvent('keyboardWillHide');
};

Keyboard.fireOnShowing = function (height) {
    cordova.fireWindowEvent('keyboardWillShow', {
        'keyboardHeight': height
    });
};

Keyboard.fireOnChanging = function (height) {
    cordova.fireWindowEvent('keyboardHeightWillChange', {
        'keyboardHeight': height
    });
};

Keyboard.fireOnFrameChanging = function (height, duration, easing, startTime) {
    cordova.fireWindowEvent('keyboardFrameWillChange', {
        'keyboardHeight': height,
        'duration': duration,
        'easing': easing,
        'startTime': startTime
    });
};

Keyboard.hideFormAccessoryBar = Keyboard.hideKeyboardAccessoryBar = function (hide) {
    console.warn("Keyboard.hideKeyboardAccessoryBar() not supported in Android");
};

Keyboard.hide = function () {
    exec(null, null, "CDVIonicKeyboard", "hide", []);
};

Keyboard.show = function () {
    exec(null, null, "CDVIonicKeyboard", "show", []);
};

Keyboard.disableScroll = function (disable) {
    console.warn("Keyboard.disableScroll() not supported in Android");
};

Keyboard.setResizeMode = function (mode) {
    exec(null, null, "CDVIonicKeyboard", "setResizeMode", [mode]);
}

Keyboard.setKeyboardStyle = function(style) {
    console.warn("Keyboard.setKeyboardStyle() not supported in Android");
};

channel.onCordovaReady.subscribe(function () {
    exec(success, null, 'CDVIonicKeyboard', 'init', []);

    function success(msg) {
        var action = msg.charAt(0);
        if (action === 'S') {
            var keyboardHeight = parseInt(msg.substr(1));
            Keyboard.fireOnChanging(keyboardHeight);
            Keyboard.fireOnShowing(keyboardHeight);
            Keyboard.fireOnShow(keyboardHeight);

        } else if (action === 'H') {
            Keyboard.fireOnHiding();
            Keyboard.fireOnHide();
        }
    }
});


Keyboard.isVisible = false;
// Matches the native side, which only reports the keyboard's frame from Android 11.
Keyboard.supportsFrameEvents = Number((/Android (\d+)/.exec(navigator.userAgent) || [])[1]) >= 11;

module.exports = Keyboard;
