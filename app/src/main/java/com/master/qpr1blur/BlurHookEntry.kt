package com.master.qpr1blur

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BlurHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QPR1BlurPort]"

        // 音量条/弹窗本体的高斯模糊半径（像素）：推荐 70 ~ 120
        private const val PANEL_BLUR_RADIUS_PX = 90

        // 音量条/弹窗本体的底色不透明度（0~255）：原版为 255（纯实心），推荐 150 ~ 185
        private const val PANEL_BG_ALPHA = 165
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        XposedBridge.log("$TAG 已注入 SystemUI，启用面板本体毛玻璃引擎...")

        hookDialogs(lpparam.classLoader)
        hookPopupWindows(lpparam.classLoader)
        hookVolumeOverlayWindows(lpparam.classLoader)
    }

    /**
     * 1. 拦截所有音量面板与快捷设置二级弹窗 Dialog（仅模糊面板本体，不模糊全屏背景）
     */
    private fun hookDialogs(classLoader: ClassLoader) {
        XposedBridge.hookAllMethods(Dialog::class.java, "onStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    val className = dialog.javaClass.name
                    val window = dialog.window ?: return

                    if (isTargetUiComponent(className)) {
                        applyPanelOnlyBlur(window, PANEL_BLUR_RADIUS_PX)
                        window.decorView.let { decor ->
                            makeContainerTranslucent(decor, PANEL_BG_ALPHA, 0)
                        }
                        XposedBridge.log("$TAG 面板本体毛玻璃已注入 Dialog: $className")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG Dialog Hook 异常: ${t.message}")
                }
            }
        })
    }

    /**
     * 2. 拦截快捷设置二级浮层常用的 PopupWindow
     */
    private fun hookPopupWindows(classLoader: ClassLoader) {
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
                XposedBridge.log("$TAG 面板本体毛玻璃已注入 PopupWindow")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG PopupWindow Hook 异常: ${t.message}")
        }
    }

    /**
     * 3. 拦截独立悬浮窗口形态的音量滑块胶囊（仅设置窗口自身轮廓内模糊）
     */
    private fun hookVolumeOverlayWindows(classLoader: ClassLoader) {
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
                    // 匹配音量条窗口类型 (2020 = TYPE_VOLUME_OVERLAY) 或标题含 Volume 的浮层
                    val isVolumeWindow = params.type == 2020 ||
                            title.contains("Volume", ignoreCase = true)

                    if (isVolumeWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // 必须设为半透明像素格式，否则面板无法透出底部模糊；不设置 blurBehindRadius 以免糊掉屏幕背景
                        params.format = PixelFormat.TRANSLUCENT

                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                try {
                                    // 仅对当前窗口自身轮廓区域启用硬件高斯模糊
                                    val viewRootImpl = XposedHelpers.callMethod(v, "getViewRootImpl")
                                    if (viewRootImpl != null) {
                                        XposedHelpers.callMethod(
                                            viewRootImpl,
                                            "setBackgroundBlurRadius",
                                            PANEL_BLUR_RADIUS_PX
                                        )
                                    }
                                    makeContainerTranslucent(v, PANEL_BG_ALPHA, 0)
                                    XposedBridge.log("$TAG 音量条本体毛玻璃已注入悬浮窗: $title")
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG 音量悬浮窗模糊异常: ${e.message}")
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

    private fun isTargetUiComponent(className: String): Boolean {
        return className.contains("Volume", ignoreCase = true) ||
                className.contains("SystemUIDialog", ignoreCase = true) ||
                className.contains("QS", ignoreCase = true) ||
                className.contains("Detail", ignoreCase = true)
    }

    /**
     * 仅对 Window 自身表面轮廓内进行背景采样模糊，保持窗口外部的全屏壁纸/应用完全清晰
     */
    private fun applyPanelOnlyBlur(window: Window, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setFormat(PixelFormat.TRANSLUCENT)
            // 清除任何可能存在的全屏背景变暗或全屏模糊标志
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    /**
     * 递归穿透背景 Drawable 与 Tint 着色层，将实心面板底色改为半透明毛玻璃底色
     */
    private fun makeContainerTranslucent(view: View, alpha: Int, depth: Int) {
        if (depth > 5) return

        // 1. 处理 View 自身的 backgroundTintList（现代 Material 3 胶囊常用 tint 覆盖颜色）
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

        // 2. 处理 Drawable 本身的不透明度
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
