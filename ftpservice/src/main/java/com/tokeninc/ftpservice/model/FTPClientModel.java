package com.tokeninc.ftpservice.model;

import org.apache.commons.net.ftp.FTPClient;

public class FTPClientModel {
    private FTPClient ftpClient;
    private String downloadUrl;

    public FTPClient getFtpClient() {
        return ftpClient;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public FTPClientModel(FTPClient ftpClient,String downloadUrl){
        this.ftpClient = ftpClient;
        this.downloadUrl = downloadUrl;
    }
}
