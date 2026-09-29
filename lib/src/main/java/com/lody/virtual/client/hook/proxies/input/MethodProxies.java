package com.lody.virtual.client.hook.proxies.input;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Method;

/**
 * @author Lody
 */

class MethodProxies {

    static class StartInput extends StartInputOrWindowGainedFocus {

        @Override
        public String getMethodName() {
            return "startInput";
        }
    }

    static class WindowGainedFocus extends StartInputOrWindowGainedFocus {

        @Override
        public String getMethodName() {
            return "windowGainedFocus";
        }


    }

    static class StartInputOrWindowGainedFocus extends MethodProxy {


        @Override
        public String getMethodName() {
            return "startInputOrWindowGainedFocus";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int editorInfoIndex = ArrayUtils.indexOfFirst(args, EditorInfo.class);
            if (editorInfoIndex != -1) {
                EditorInfo attribute = (EditorInfo) args[editorInfoIndex];
                if (shouldBypassOplusSecureKeyboard(attribute)) {
                    attribute.inputType &= ~InputType.TYPE_MASK_VARIATION;
                    attribute.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
                }
                if (attribute.packageName == null || attribute.packageName.length() == 0) {
                    attribute.packageName = getHostPkg();
                }
            }
            return method.invoke(who, args);
        }

        private boolean shouldBypassOplusSecureKeyboard(EditorInfo attribute) {
            return isPasswordInput(attribute.inputType);
        }

        private boolean isPasswordInput(int inputType) {
            int inputClass = inputType & InputType.TYPE_MASK_CLASS;
            int variation = inputType & InputType.TYPE_MASK_VARIATION;
            if (inputClass == InputType.TYPE_CLASS_TEXT) {
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            }
            return inputClass == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
    }
}
