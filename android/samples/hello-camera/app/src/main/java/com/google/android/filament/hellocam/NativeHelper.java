package com.google.android.filament.hellocam;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

import static android.opengl.EGL15.EGL_OPENGL_ES3_BIT;

public class NativeHelper {

    public static void init() {
        System.loadLibrary("native-lib");
    }

    static EGLContext createEGLContext() {
        EGLContext shareContext = EGL14.EGL_NO_CONTEXT;
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

        int[] minorMajor = null;
        EGL14.eglInitialize(display, minorMajor, 0, minorMajor, 0);
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = {0};
        int[] attribs = {EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE};
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0);

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        EGLContext context =
                EGL14.eglCreateContext(display, configs[0], shareContext, contextAttribs, 0);

        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };

        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("Error making GL context.");
        }

        return context;
        //return (EGLContext) nCreateEGLContext();
    }

    private static native Object nCreateEGLContext();
}
