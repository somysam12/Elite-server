package com.mundo.core.system.am;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import black.android.app.job.BRJobInfo;
import com.mundo.MundoCore;
import com.mundo.core.system.BProcessManagerService;
import com.mundo.core.system.ISystemService;
import com.mundo.core.system.ProcessRecord;
import com.mundo.core.system.pm.BPackageManagerService;
import com.mundo.entity.JobRecord;
import com.mundo.proxy.ProxyManifest;
import com.mundo.utils.FileUtils;

/**
 * Created by @jagdish_vip on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService sService = new BJobManagerService();

    // process_jobId
    private final Map<String, JobRecord> mJobRecords = new HashMap<>();

    public static BJobManagerService get() {
        return sService;
    }

    @Override
    public JobInfo schedule(JobInfo info, int userId) throws RemoteException {
        ComponentName componentName = info.getService();
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ResolveInfo resolveInfo = BPackageManagerService.get().resolveService(intent, FileUtils.FileMode.MODE_IWUSR, null, userId);
        if (resolveInfo == null) {
            return info;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(serviceInfo.packageName, serviceInfo.processName, userId);
        if (processRecord == null) {
            processRecord = BProcessManagerService.get().
                    startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                throw new RuntimeException(
                        "Unable to create Process " + serviceInfo.processName);
            }
        }
        return scheduleJob(processRecord, info, serviceInfo);
    }

    @Override
    public JobRecord queryJobRecord(String processName, int jobId, int userId) throws RemoteException {
        return mJobRecords.get(formatKey(processName, jobId));
    }

    public JobInfo scheduleJob(ProcessRecord processRecord, JobInfo info, ServiceInfo serviceInfo) {
        JobRecord jobRecord = new JobRecord();
        jobRecord.mJobInfo = info;
        jobRecord.mServiceInfo = serviceInfo;

        mJobRecords.put(formatKey(processRecord.processName, info.getId()), jobRecord);
        ComponentName proxyComponent = new ComponentName(
                MundoCore.getHostPkg(),
                ProxyManifest.getProxyJobService(processRecord.bpid));
        try {
            BRJobInfo.get(info)._set_service(proxyComponent);
        } catch (Throwable ignored) { }
        // On API 36, hidden-API enforcement silently prevents the reflective write from
        // landing. Verify the mutation; if it failed, rebuild via the public Builder API.
        if (!proxyComponent.equals(info.getService())) {
            JobInfo rebuilt = rebuildJobInfo(info, proxyComponent);
            if (rebuilt != null) {
                mJobRecords.get(formatKey(processRecord.processName, info.getId())).mJobInfo = rebuilt;
                return rebuilt;
            }
        }
        return info;
    }

    private static JobInfo rebuildJobInfo(JobInfo src, ComponentName proxyComponent) {
        try {
            JobInfo.Builder b = new JobInfo.Builder(src.getId(), proxyComponent);
            b.setRequiresCharging(src.isRequireCharging());
            b.setRequiresDeviceIdle(src.isRequireDeviceIdle());
            b.setPersisted(src.isPersisted());
            b.setBackoffCriteria(src.getInitialBackoffMillis(), src.getBackoffPolicy());
            if (src.getExtras() != null) b.setExtras(src.getExtras());
            if (src.getTransientExtras() != null) b.setTransientExtras(src.getTransientExtras());
            if (src.isPeriodic()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    b.setPeriodic(src.getIntervalMillis(), src.getFlexMillis());
                } else {
                    b.setPeriodic(src.getIntervalMillis());
                }
            } else {
                if (src.getMinLatencyMillis() > 0) b.setMinimumLatency(src.getMinLatencyMillis());
                if (src.getMaxExecutionDelayMillis() > 0) b.setOverrideDeadline(src.getMaxExecutionDelayMillis());
            }
            int netType = src.getNetworkType();
            if (netType != JobInfo.NETWORK_TYPE_NONE) b.setRequiredNetworkType(netType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setRequiresBatteryNotLow(src.isRequireBatteryNotLow());
                b.setRequiresStorageNotLow(src.isRequireStorageNotLow());
            }
            return b.build();
        } catch (Throwable t) {
            Log.w("BJobManagerService", "rebuildJobInfo failed: " + t.getMessage());
            return null;
        }
    }

    @Override
    public void cancelAll(String processName, int userId) throws RemoteException {
        if (TextUtils.isEmpty(processName)) return;
        for (String key : mJobRecords.keySet()) {
            if (key.startsWith(processName + "_")) {
                JobRecord jobRecord = mJobRecords.get(key);
                // todo
            }
        }
    }

    @Override
    public int cancel(String processName, int jobId, int userId) throws RemoteException {
        return jobId;
    }

    private String formatKey(String processName, int jobId) {
        return processName + "_" + jobId;
    }

    @Override
    public void systemReady() {

    }
}
