package com.tokeninc.ftpservice.instance;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.content.ContextCompat;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;
import com.tokeninc.ftpservice.model.FTPDownloadModel;
import com.tokeninc.ftpservice.service.DownloadManager;
import com.tokeninc.ftpservice.support.SerializableManager;
import com.tokeninc.ftpservice.support.Utils;

import static com.tokeninc.ftpservice.instance.TokenFTPDownloader.FTP_FILE_NAME;

public class BackgroundFtpDownloadManager extends ListenableWorker {

    private ListenableFuture<Result> result;
    private DownloadManager downloadManager;

    public BackgroundFtpDownloadManager(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        result = CallbackToFutureAdapter.getFuture(completer -> {
            if(!Utils.isWifiConnected(getApplicationContext())){
                Log.e("TokenDownloader","Wifi not connected");
                completer.set(Result.retry());
            }
            else if(ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                Log.e("TokenDownloader","Permission not granted");
                completer.set(Result.retry());
            }
            else{
                FTPDownloadModel ftpDownloadModel = SerializableManager.
                        readSerializable(getApplicationContext(),FTP_FILE_NAME);
                if(ftpDownloadModel == null){
                    Log.e("TokenDownloader","ftpDownloadModel null");
                    completer.set(Result.retry());
                }
                else{
                    Log.e("TokenDownloader","Start Scheduled Downloader");
                    startScheduledDownloader(getApplicationContext(),ftpDownloadModel);
                    completer.set(Result.success());
                }

            }
            return completer;
        });
        return result;
    }

    private void startScheduledDownloader(@NonNull Context context,@NonNull FTPDownloadModel downloadModel){
        Intent startFTPIntent = new Intent(context, DownloadManager.class);
        startFTPIntent.setAction(Intent.ACTION_RUN);
        startFTPIntent.putExtra("model",downloadModel);
        if(Build.VERSION.SDK_INT >= 26){
            context.startForegroundService(startFTPIntent);
        }
        else{
            context.startService(startFTPIntent);
        }
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                DownloadManager.DownloadManagerBinder mLocalBinder =
                        (DownloadManager.DownloadManagerBinder) service;
                downloadManager = mLocalBinder.getInstance(downloadModel);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                downloadManager = null;
            }
        };
        context.bindService(startFTPIntent, connection, Context.BIND_AUTO_CREATE);
    }
}
