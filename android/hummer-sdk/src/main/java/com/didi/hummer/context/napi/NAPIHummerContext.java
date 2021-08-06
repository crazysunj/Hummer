package com.didi.hummer.context.napi;

import android.support.annotation.NonNull;
import android.widget.Toast;

import com.didi.hummer.HummerSDK;
import com.didi.hummer.context.HummerContext;
import com.didi.hummer.core.debug.InvokeTracker;
import com.didi.hummer.core.debug.InvokerAnalyzerManager;
import com.didi.hummer.core.engine.base.ICallback;
import com.didi.hummer.core.engine.base.IRecycler;
import com.didi.hummer.core.engine.napi.NAPIContext;
import com.didi.hummer.core.engine.napi.jni.JSException;
import com.didi.hummer.core.exception.ExceptionCallback;
import com.didi.hummer.core.util.DebugUtil;
import com.didi.hummer.core.util.ExceptionUtil;
import com.didi.hummer.core.util.HMLog;
import com.didi.hummer.lifecycle.ILifeCycle;
import com.didi.hummer.render.component.view.Invoker;
import com.didi.hummer.render.style.HummerLayout;

import java.util.Arrays;

public class NAPIHummerContext extends HummerContext {

    private ICallback invoker = params -> {
        String className = (String) params[0];
        long objectID = ((Number) params[1]).longValue();
        String methodName = (String) params[2];
        Object[] realParams = Arrays.copyOfRange(params, 3, params.length);

        if (DebugUtil.isDebuggable()) {
            HMLog.d("HummerNative", String.format(
                    "[Java invoked][objectID=%d][className=%s][method=%s][params=%s]",
                    objectID, className, methodName, Arrays.toString(realParams)));
        }

        Invoker invoker = getInvoker(className);
        if (invoker == null) {
            HMLog.w("HummerNative", String.format("Invoker error: can't find this class [%s]", className));
            return null;
        }

        Object ret = null;
        try {
            // <for debug>
            InvokeTracker tracker = InvokerAnalyzerManager.getInstance().startTrack(mJsContext.getIdentify());
            if (tracker != null) {
                tracker.track(className, objectID, methodName, realParams);
            }

            // 执行具体的invoke方法，并得到Object类型的返回值
            ret = invoker.onInvoke(this, objectID, methodName, realParams);

            // <for debug>
            InvokerAnalyzerManager.getInstance().stopTrack(mJsContext.getIdentify(), tracker);
        } catch (Exception e) {
            String jsStack = ExceptionUtil.getJSErrorStack(mJsContext);
            ExceptionUtil.addStackTrace(e, new StackTraceElement("<<JS_Stack>>", "", "\n" + jsStack, -1));
            JSException.nativeException(mJsContext, e);
        }
        return ret;
    };

    private IRecycler recycler = objId -> {
        HMLog.v("HummerNative", "** onRecycle, objId = " + objId);
        Object obj = getObjectPool().remove(objId);
        if (obj instanceof ILifeCycle) {
            ((ILifeCycle) obj).onDestroy();
        }
    };

    private ExceptionCallback exceptionCallback = e -> {
        ExceptionUtil.addStackTrace(e, new StackTraceElement("<<Bundle>>", "", jsSourcePath, -1));
        HummerSDK.getException(namespace).onException(e);

        if (DebugUtil.isDebuggable()) {
            HMLog.e("HummerException", "Hummer Exception", e);
            Toast.makeText(HummerSDK.appContext, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    };

    public NAPIHummerContext(@NonNull HummerLayout container) {
        this(container, null);
    }

    public NAPIHummerContext(@NonNull HummerLayout container, String namespace) {
        super(container, namespace);
        mJsContext = NAPIContext.create();
        mJsContext.set("invoke", invoker);
        mJsContext.setRecycler(recycler);

        // 异常回调注册
        JSException.addJSContextExceptionCallback(mJsContext, exceptionCallback);

        // Debug模式下的视图树分析器初始化
        InvokerAnalyzerManager.getInstance().init(mJsContext.getIdentify());

        onCreate();
    }

    @Override
    public void releaseJSContext() {
        JSException.removeJSContextExceptionCallback(mJsContext);
        InvokerAnalyzerManager.getInstance().release(mJsContext.getIdentify());
        super.releaseJSContext();
    }
}