package com.master.qpr1blur

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.SeekBar
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"

        // 仅作用于音量条/菜单面板本体的高斯模糊半径（像素）
        private const val PANEL_BLUR_RADIUS_PX = 90

        // 面板与静音按钮外圈的底色不透明度（0~255）：原版为 255（纯实心）
        private const val PANEL_BG_ALPHA = 160
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，启用面板本体与按钮毛玻璃引擎...")

        hookDialogs()
        hookPopupWindows()
        hookOverlayWindows(lpparam.classLoader)
        hookEmbeddedDropoutViews()
    }

    private fun hookDialogs() {
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    val className = dialog.javaClass.name
                    val window = dialog.window ?: return

                    if (isTargetComponent(className)) {
                        applyPanelOnlyBlur(window, PANEL_BLUR_RADIUS_PX)
                        window.decorView.let { decor ->
                            makeContainerTranslucent(decor, PANEL_BG_ALPHA, 0)
                        }
                        XposedBridge.log("$TAG 面板毛玻璃已注入 Dialog: $className")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG Dialog Hook 异常: ${t.message}")
                }
            }
        })
    }

    private fun hookPopupWindows() {
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handlePopupWindow(param.thisObject as? PopupWindow)
            }
        })
    }

    private fun handlePopupWindow(popup: PopupWindow?) {
        if (popup == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val contentView = popup.contentView ?: return
            val rootView = contentView.rootView ?: return
            makeContainerTranslucent(contentView, PANEL_BG_ALPHA, 0)

            val viewRootImpl = XposedHelpers.callMethod(rootView, "getViewRootImpl")
            if (viewRootImpl != null) {
                XposedHelpers.callMethod(viewRootImpl, "setBackgroundBlurRadius", PANEL_BLUR_RADIUS_PX)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG PopupWindow Hook 异常: ${t.message}")
        }
    }

    private fun hookOverlayWindows(classLoader: ClassLoader) {
        val wmgClass = XposedHelpers.findClassIfExists(
            "android.view.WindowManagerGlobal",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(wmgClass, "addView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (param.args.size < 2) return
                    val view = param.args[0] as? View ?: return
                    val params = param.args[1] as? WindowManager.LayoutParams ?: return

                    val title = params.title?.toString() ?: ""
                    val isTargetWindow = params.type == 2020 ||
                            params.type == 2017 ||
                            params.type == 2008 ||
                            isTargetComponent(title)

                    if (isTargetWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        params.format = PixelFormat.TRANSLUCENT
                        // 彻底清除全屏背景模糊与全屏变暗标志，确保桌面背景 100% 清晰
                        params.flags = params.flags and
                                WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and
                                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                        params.blurBehindRadius = 0

                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                try {
                                    val viewRootImpl = XposedHelpers.callMethod(v, "getViewRootImpl")
                                    if (viewRootImpl != null) {
                                        XposedHelpers.callMethod(
                                            viewRootImpl,
                                            "setBackgroundBlurRadius",
                                            PANEL_BLUR_RADIUS_PX
                                        )
                                    }
                                    makeContainerTranslucent(v, PANEL_BG_ALPHA, 0)
                                    // 延迟一帧再次处理异步加载的静音按钮背景
                                    v.post { makeContainerTranslucent(v, PANEL_BG_ALPHA, 0) }
                                    XposedBridge.log("$TAG 音量条本体及按钮毛玻璃已注入: $title")
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG 悬浮窗口模糊异常: ${e.message}")
                                }
                            }

                            override fun onViewDetachedFromWindow(v: View) {}
                        })
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG WindowManagerGlobal 异常: ${t.message}")
                }
            }
        })
    }

    private fun hookEmbeddedDropoutViews() {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val child = param.args.firstOrNull() as? View ?: return
                    val name = child.javaClass.name
                    if (name.contains("QSDetail", ignoreCase = true) ||
                        name.contains("Dropdown", ignoreCase = true) ||
                        name.contains("PopupMenu", ignoreCase = true)
                    ) {
                        makeContainerTranslucent(child, PANEL_BG_ALPHA, 0)
                    }
                } catch (_: Throwable) {
                }
            }
        })
    }

    private fun isTargetComponent(name: String): Boolean {
        return name.contains("Volume", ignoreCase = true) ||
                name.contains("SystemUIDialog", ignoreCase = true) ||
                name.contains("QS", ignoreCase = true) ||
                name.contains("Detail", ignoreCase = true) ||
                name.contains("Menu", ignoreCase = true) ||
                name.contains("Media", ignoreCase = true) ||
                name.contains("Output", ignoreCase = true) ||
                name.contains("Popup", ignoreCase = true)
    }

    private fun applyPanelOnlyBlur(window: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 0 }
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    /**
     * 递归穿透背景 Drawable（含 RippleDrawable 水波纹按钮底色）与 Tint 着色层，跳过音量滑块进度条
     */
    private fun makeContainerTranslucent(view: View, alpha: Int, depth: Int) {
        if (depth > 8 || view is SeekBar) return

        view.backgroundTintList?.let { tintList ->
            val defaultColor = tintList.defaultColor
            val translucentColor = Color.argb(
                alpha,
                Color.red(defaultColor),
                Color.green(defaultColor),
                Color.blue(defaultColor)
            )
            view.backgroundTintList = ColorStateList.valueOf(translucentColor)
        }

        view.background?.let { bg ->
            val mutated = bg.mutate()
            applyAlphaToDrawable(mutated, alpha)
            view.background = mutated
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                makeContainerTranslucent(view.getChildAt(i), alpha, depth + 1)
            }
        }
    }

    private fun applyAlphaToDrawable(drawable: Drawable, alpha: Int) {
        when (drawable) {
            is RippleDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    applyAlphaToDrawable(drawable.getDrawable(i), alpha)
                }
            }
            is GradientDrawable -> {
                drawable.alpha = alpha
                drawable.color?.let { csl ->
                    val c = csl.defaultColor
                    drawable.color = ColorStateList.valueOf(
                        Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    )
                }
            }
            is ColorDrawable -> {
                val c = drawable.color
                drawable.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            }
            else -> {
                drawable.alpha = alpha
            }
        }
    }
}
