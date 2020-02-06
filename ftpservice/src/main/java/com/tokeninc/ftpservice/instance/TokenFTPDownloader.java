package com.tokeninc.ftpservice.instance;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.tokeninc.ftpservice.model.FTPDownloadModel;
import com.tokeninc.ftpservice.service.DownloadManager;
import com.tokeninc.ftpservice.support.SerializableManager;
import com.tokeninc.ftpservice.support.Utils;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class TokenFTPDownloader {

    private DownloadManager downloadManager;
    public static String FTP_FILE_NAME = "token_ftp_downloader.class";
    public static String FILE_SAVE_ARRAY = "token_downloaded_file_list.array";
    private String ftpJobName = "ftp_downloader";

    private static TokenFTPDownloader instance;

    public static TokenFTPDownloader getInstance() {
        if(instance == null){
            instance = new TokenFTPDownloader();
        }
        return instance;
    }

    public void startFtpDownloadManager(@NonNull WeakReference<? extends AppCompatActivity> weakReference,
                                        @NonNull FTPDownloadModel ftpDownloadModel, boolean checkWifiCondition){
        if(!Utils.isWifiConnected(weakReference.get()) && checkWifiCondition){
            Log.e("TokenDownloader","Wifi not active");
            return;
        }
        Intent startFTPIntent = new Intent(weakReference.get(), DownloadManager.class);
        startFTPIntent.setAction(Intent.ACTION_GET_CONTENT);
        startFTPIntent.putExtra("model",ftpDownloadModel);
        weakReference.get().startService(startFTPIntent);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                DownloadManager.DownloadManagerBinder mLocalBinder =
                        (DownloadManager.DownloadManagerBinder) service;
                downloadManager = mLocalBinder.getInstance(ftpDownloadModel);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                downloadManager = null;
            }
        };
        weakReference.get().bindService(startFTPIntent, connection, Context.BIND_AUTO_CREATE);
    }



    public void startFTPScheduler(@NonNull Context context,@NonNull FTPDownloadModel ftpDownloadModel) {
        SerializableManager.saveSerializable(context,ftpDownloadModel,FTP_FILE_NAME);
        Constraints constraints = new Constraints.Builder()
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build();
        PeriodicWorkRequest downloadWork = new PeriodicWorkRequest.Builder(BackgroundFtpDownloadManager.class,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints).build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ftpJobName,
                ExistingPeriodicWorkPolicy.KEEP, downloadWork);
    }


    public void removeFTPScheduler(@NonNull Context context){
        SerializableManager.removeSerializable(context,FTP_FILE_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(ftpJobName);
    }
}
