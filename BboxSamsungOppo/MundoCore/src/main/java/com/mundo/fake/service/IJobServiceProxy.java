package com.mundo.fake.service;

import android.app.job.JobInfo;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.fake.hook.BinderInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.utils.Slog;
import com.mundo.utils.UIDSpoofingHelper;

/**
 * IJobService Proxy to handle job scheduling in sandboxed environments
 * This prevents UID mismatch crashes when scheduling background jobs
 */
public class IJobServiceProxy extends BinderInvocationStub {
    private static final String TAG = "JobServiceStub";
    private static final String SERVICE_NAME = "jobscheduler";
    private static final int RESULT_FAILURE = 0;

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService(SERVICE_NAME);
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }
    
    private static abstract class BaseJobHandler extends MethodHook {

        /**
         * Android 14 (API 34) added a {@code String namespace} as the FIRST argument to
         * IJobScheduler.schedule(), enqueue(), and cancel(). From API 34 onwards:
         *   schedule(String namespace, in JobInfo job)   → args[0]=namespace, args[1]=JobInfo
         *   enqueue(String namespace, in JobInfo job, …) → args[0]=namespace, args[1]=JobInfo
         * Before API 34:
         *   schedule(in JobInfo job)                     → args[0]=JobInfo
         */
        protected static int getJobInfoIndex() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? 1 : 0;
        }

        protected Object processJobOperation(String operation, Object who, Method method, Object[] args) throws Throwable {
            if (!validateJobArgs(args)) {
                Slog.w(TAG, operation + ": Invalid arguments, returning failure");
                return RESULT_FAILURE;
            }
            int jobInfoIndex = getJobInfoIndex();
            JobInfo jobInfo = (JobInfo) args[jobInfoIndex];
            String packageName = jobInfo.getService().getPackageName();
            Slog.d(TAG, operation + ": Processing JobInfo for package: " + packageName);
            try {
                JobInfo proxyJobInfo = MundoCore.getBJobManager().schedule(jobInfo);
                if (proxyJobInfo != null) {
                    args[jobInfoIndex] = proxyJobInfo;
                    Slog.d(TAG, operation + ": Successfully created proxy JobInfo");
                    return method.invoke(who, args);
                }
            } catch (Exception e) {
                Slog.w(TAG, operation + ": BlackBox job manager failed, trying fallback", e);
            }
            return handleWithUIDSpoofing(operation, who, method, args, jobInfo);
        }

        protected boolean validateJobArgs(Object[] args) {
            int jobInfoIndex = getJobInfoIndex();
            if (args == null || args.length <= jobInfoIndex || args[jobInfoIndex] == null) {
                return false;
            }

            if (!(args[jobInfoIndex] instanceof JobInfo)) {
                Slog.w(TAG, "Argument at index " + jobInfoIndex + " is not JobInfo: "
                        + args[jobInfoIndex].getClass().getSimpleName());
                return false;
            }

            JobInfo jobInfo = (JobInfo) args[jobInfoIndex];
            return jobInfo.getService() != null;
        }

        protected Object handleWithUIDSpoofing(String operation, Object who, Method method, Object[] args, JobInfo jobInfo) throws Throwable {
            String targetPackage = jobInfo.getService().getPackageName();
            Slog.d(TAG, operation + ": Attempting UID spoofing for package: " + targetPackage);
            UIDSpoofingHelper.logUIDInfo("job_" + operation.toLowerCase(), targetPackage);
            if (UIDSpoofingHelper.needsUIDSpoofing("job_" + operation.toLowerCase(), targetPackage)) {
                Slog.d(TAG, operation + ": UID spoofing needed");
                return RESULT_FAILURE;
            }
            Slog.d(TAG, operation + ": No UID spoofing needed, proceeding normally");
            return method.invoke(who, args);
        }

        protected boolean isUIDValidationError(Exception e) {
            if (e.getCause() == null) return false;
            String message = e.getCause().getMessage();
            return message != null && message.contains("cannot schedule job");
        }
    }

    @ProxyMethod("schedule")
    public static class Schedule extends BaseJobHandler {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (!validateJobArgs(args)) {
                    return handleInvalidJobInfo("schedule", who, method, args);
                }
                return processJobOperation("Schedule", who, method, args);
            } catch (Exception e) {
                Slog.e(TAG, "Schedule: Error processing job", e);
                if (isUIDValidationError(e)) {
                    Slog.w(TAG, "UID validation failed for job scheduling");
                    return RESULT_FAILURE;
                }
                return executeFallback(who, method, args, "Schedule");
            }
        }

        private Object handleInvalidJobInfo(String operation, Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String workId = (String) args[0];
                Slog.d(TAG, operation + ": Handling WorkManager string ID: " + workId);
            }
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, operation + ": Failed to handle invalid JobInfo", e);
                return RESULT_FAILURE;
            }
        }
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                // API 34+: cancel(String namespace, int jobId) → args[0]=namespace, args[1]=jobId
                // Pre-API 34: cancel(int jobId) → args[0]=jobId
                int jobIdIndex = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? 1 : 0;
                if (args == null || args.length <= jobIdIndex || !(args[jobIdIndex] instanceof Integer)) {
                    Slog.w(TAG, "Cancel: Invalid arguments");
                    return method.invoke(who, args);
                }
                int jobId = (Integer) args[jobIdIndex];
                String processName = BActivityThread.getAppConfig().processName;
                int cancelledJobId = MundoCore.getBJobManager().cancel(processName, jobId);
                args[jobIdIndex] = cancelledJobId;
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "Cancel: Error canceling job", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                String processName = BActivityThread.getAppConfig().processName;
                MundoCore.getBJobManager().cancelAll(processName);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "CancelAll: Error canceling all jobs", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends BaseJobHandler {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (!validateJobArgs(args)) {
                    return handleInvalidJobInfo("enqueue", who, method, args);
                }
                return processJobOperation("Enqueue", who, method, args);
            } catch (Exception e) {
                Slog.e(TAG, "Enqueue: Error processing job", e);
                if (isUIDValidationError(e)) {
                    Slog.w(TAG, "UID validation failed for job enqueuing");
                    return RESULT_FAILURE;
                }
                return executeFallback(who, method, args, "Enqueue");
            }
        }

        private Object handleInvalidJobInfo(String operation, Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String workId = (String) args[0];
                Slog.d(TAG, operation + ": Handling WorkManager string ID: " + workId);
            }
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, operation + ": Failed to handle invalid JobInfo", e);
                return RESULT_FAILURE;
            }
        }
    }
    
    private static Object executeFallback(Object who, Method method, Object[] args, String operation) {
        try {
            return method.invoke(who, args);
        } catch (Exception fallbackException) {
            Slog.e(TAG, operation + ": Fallback also failed", fallbackException);
            return RESULT_FAILURE;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}